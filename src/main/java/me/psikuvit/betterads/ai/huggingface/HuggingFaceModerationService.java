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
 * Calls the HuggingFace Space at https://huggingface.co/spaces/3amizoubir/ad-moderation
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
        this.storageService = storageService;
        HttpClient httpClient = HttpClient.create().responseTimeout(TIMEOUT);
        this.webClient = WebClient.builder()
                .baseUrl(spaceUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public ValidationResult moderate(String storageKey) {
        try {
            String filename = storageKey.substring(storageKey.lastIndexOf('/') + 1);
            InputStreamResource file = storageService.downloadObject(storageKey);

            String uploadedPath = upload(file, filename);
            String eventId = submit(uploadedPath, filename);
            String decision = awaitDecision(eventId);

            return switch (decision) {
                case "accepted" -> ValidationResult.APPROVED;
                case "refused" -> ValidationResult.REJECTED;
                default -> {
                    log.warn("Unexpected decision '{}' from HuggingFace moderation for key={}", decision, storageKey);
                    yield ValidationResult.FLAGGED;
                }
            };
        } catch (Exception e) {
            // A technical failure isn't a moderation signal - flag for human
            // review rather than silently auto-approving or auto-rejecting.
            log.warn("HuggingFace moderation call failed for key={}: {}", storageKey, e.getMessage(), e);
            return ValidationResult.FLAGGED;
        }
    }

    private String upload(InputStreamResource file, String filename) throws Exception {
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
            throw new IllegalStateException("Upload response contained no file path: " + response);
        }
        return paths[0];
    }

    private String submit(String uploadedPath, String filename) throws Exception {
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
            throw new IllegalStateException("Submit response contained no event_id: " + response);
        }
        return eventId.asText();
    }

    private String awaitDecision(String eventId) throws Exception {
        ServerSentEvent<String> complete = webClient.get()
                .uri("/gradio_api/call/moderate/{eventId}", eventId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .filter(sse -> "complete".equals(sse.event()))
                .next()
                .block(TIMEOUT);

        if (complete == null || complete.data() == null) {
            throw new IllegalStateException("No complete event received for event_id=" + eventId);
        }
        JsonNode result = objectMapper.readTree(complete.data());
        return result.get(0).asText("").trim().toLowerCase();
    }
}
