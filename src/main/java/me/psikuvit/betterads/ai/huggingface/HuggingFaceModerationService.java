package me.psikuvit.betterads.ai.huggingface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.ModerationService;
import me.psikuvit.betterads.storage.StorageService;
import me.psikuvit.betterads.validation.dto.ValidationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls the HuggingFace Space at <a href="https://huggingface.co/spaces/3amizoubir/ad-moderation">...</a>
 * (a gr.Interface with api_name="moderate") via Gradio's HTTP API, which is a
 * 3-step flow: multipart upload -> submit a call (get an event_id) -> read
 * the SSE event stream for that event_id until the "complete" event.
 *
 * The Space runs on ZeroGPU (@spaces.GPU(duration=120)) with no streaming
 * progress support of its own - all 5 detector agents run sequentially and
 * only the final decision is returned, so the generous timeout here is
 * covering cold start + queueing + up to 120s of actual inference, not a
 * slow network call.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "huggingface")
public class HuggingFaceModerationService implements ModerationService {

    private static final Duration TIMEOUT = Duration.ofMinutes(4);

    private final StorageService storageService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HuggingFaceModerationService(StorageService storageService,
            @Value("${app.ai.huggingface.space-url}") String spaceUrl) {
        log.info("[huggingface] HuggingFaceModerationService initialized with spaceUrl={}", spaceUrl);
        this.storageService = storageService;
        HttpClient httpClient = HttpClient.create().responseTimeout(TIMEOUT);
        this.webClient = WebClient.builder()
                .baseUrl(spaceUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public ValidationResult moderate(String storageKey) {
        long start = System.currentTimeMillis();
        log.info("[huggingface] moderate() called for key={}", storageKey);
        try {
            String filename = storageKey.substring(storageKey.lastIndexOf('/') + 1);
            InputStreamResource file = storageService.downloadObject(storageKey);

            String uploadedPath = upload(file, filename);
            String eventId = submit(uploadedPath, filename);
            String decision = awaitDecision(eventId);

            long elapsedMs = System.currentTimeMillis() - start;
            ValidationResult result = switch (decision) {
                case "accepted" -> ValidationResult.APPROVED;
                case "refused" -> ValidationResult.REJECTED;
                default -> {
                    log.warn("[huggingface] Unexpected decision '{}' from HuggingFace moderation for key={}", decision, storageKey);
                    yield ValidationResult.FLAGGED;
                }
            };
            log.info("[huggingface] moderate() result={} (raw decision='{}') for key={} in {}ms", result, decision, storageKey, elapsedMs);
            return result;
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - start;
            // A technical failure isn't a moderation signal - flag for human
            // review rather than silently auto-approving or auto-rejecting.
            log.warn("[huggingface] moderation call failed for key={} after {}ms: {}", storageKey, elapsedMs, e.getMessage(), e);
            return ValidationResult.FLAGGED;
        }
    }

    private String upload(InputStreamResource file, String filename) throws Exception {
        log.debug("[huggingface] uploading file '{}' to /gradio_api/upload", filename);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("files", file).filename(filename);

        String response = webClient.post()
                .uri("/gradio_api/upload")
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);
        String[] paths = objectMapper.readValue(response, String[].class);
        if (paths.length == 0) {
            log.warn("[huggingface] upload response contained no file path for filename={}: {}", filename, response);
            throw new IllegalStateException("Upload response contained no file path: " + response);
        }
        log.debug("[huggingface] uploaded '{}' -> path={}", filename, paths[0]);
        return paths[0];
    }

    private String submit(String uploadedPath, String filename) throws Exception {
        log.debug("[huggingface] submitting call to /gradio_api/call/moderate for uploadedPath={}", uploadedPath);
        Map<String, Object> fileData = Map.of(
                "path", uploadedPath,
                "meta", Map.of("_type", "gradio.FileData"),
                "orig_name", filename
        );
        Map<String, Object> body = Map.of("data", List.of(fileData, ""));

        String response = webClient.post()
                .uri("/gradio_api/call/moderate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);
        JsonNode json = objectMapper.readTree(response);
        JsonNode eventId = json.get("event_id");
        if (eventId == null) {
            log.warn("[huggingface] submit response contained no event_id for uploadedPath={}: {}", uploadedPath, response);
            throw new IllegalStateException("Submit response contained no event_id: " + response);
        }
        log.debug("[huggingface] submitted, eventId={}", eventId.asText());
        return eventId.asText();
    }

    private String awaitDecision(String eventId) throws Exception {
        long start = System.currentTimeMillis();
        log.debug("[huggingface] awaiting SSE 'complete' event for eventId={}", eventId);
        ServerSentEvent<String> complete = webClient.get()
                .uri("/gradio_api/call/moderate/{eventId}", eventId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .filter(sse -> "complete".equals(sse.event()))
                .next()
                .block(TIMEOUT);

        if (complete == null || complete.data() == null) {
            log.warn("[huggingface] no complete event received for eventId={} after {}ms", eventId, System.currentTimeMillis() - start);
            throw new IllegalStateException("No complete event received for event_id=" + eventId);
        }
        JsonNode result = objectMapper.readTree(complete.data());
        String decision = result.get(0).asText("").trim().toLowerCase();
        log.debug("[huggingface] decision='{}' for eventId={} after {}ms", decision, eventId, System.currentTimeMillis() - start);
        return decision;
    }
}
