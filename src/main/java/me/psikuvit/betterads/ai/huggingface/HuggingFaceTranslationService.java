package me.psikuvit.betterads.ai.huggingface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.ai.TranslationService;
import me.psikuvit.betterads.storage.StorageService;
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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls the HuggingFace Space at https://huggingface.co/spaces/3amizoubir/translator
 * ("DubVidImag" - EN->FR dubbing, a gr.Interface with api_name="dub_video")
 * via the same 3-step Gradio HTTP flow as {@link HuggingFaceModerationService}:
 * multipart upload -> submit a call (get an event_id) -> read the SSE event
 * stream until the "complete" event, which carries a Video FileData payload.
 *
 * The Space only dubs English audio into French, so any other target locale
 * is rejected outright rather than silently mistranslating or passing the
 * source through unchanged.
 *
 * The dubbed video only exists on HuggingFace's file server, but downstream
 * code (AdController) resolves AdVersion.storageKey as a key in *our* S3
 * bucket, so the result is downloaded and re-uploaded under a derived key.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "huggingface")
public class HuggingFaceTranslationService implements TranslationService {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String SUPPORTED_TARGET_LOCALE = "fr";
    private static final String FEATURE_NAME = "translation";

    private final StorageService storageService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HuggingFaceTranslationService(StorageService storageService,
            @Value("${app.ai.huggingface.translation-space-url}") String spaceUrl) {
        log.info("[huggingface] HuggingFaceTranslationService initialized with spaceUrl={}", spaceUrl);
        this.storageService = storageService;
        HttpClient httpClient = HttpClient.create().responseTimeout(TIMEOUT);
        this.webClient = WebClient.builder()
                .baseUrl(spaceUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * This is a real, billable AI call (unlike the mock/local dev providers),
     * so versions produced by it carry a feature tag that {@code BillingService}
     * surcharges on top of the base per-view rate.
     */
    @Override
    public String featureName() {
        return FEATURE_NAME;
    }

    @Override
    public String translate(String storageKey, String targetLocale) {
        if (targetLocale == null || !SUPPORTED_TARGET_LOCALE.equalsIgnoreCase(targetLocale.trim())) {
            log.warn("[huggingface] rejected unsupported targetLocale='{}' for key={} (only 'fr' is supported)", targetLocale, storageKey);
            throw new IllegalArgumentException(
                    "HuggingFace dubbing only supports English -> French translation (targetLocale must be 'fr'), got: " + targetLocale);
        }

        long start = System.currentTimeMillis();
        log.info("[huggingface] translate() called for key={}, targetLocale={}", storageKey, targetLocale);
        try {
            String filename = storageKey.substring(storageKey.lastIndexOf('/') + 1);
            InputStreamResource file = storageService.downloadObject(storageKey);

            String uploadedPath = upload(file, filename);
            String eventId = submit(uploadedPath, filename);
            JsonNode dubbedFile = awaitResult(eventId);

            String newKey = downloadAndStore(dubbedFile, storageKey);
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("[huggingface] translate() produced key={} from source key={} in {}ms", newKey, storageKey, elapsedMs);
            return newKey;
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - start;
            log.warn("[huggingface] translation call failed for key={}, targetLocale={} after {}ms: {}",
                    storageKey, targetLocale, elapsedMs, e.getMessage(), e);
            throw new RuntimeException("HuggingFace translation failed for " + storageKey + " -> " + targetLocale, e);
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
        log.debug("[huggingface] submitting call to /gradio_api/call/dub_video for uploadedPath={}", uploadedPath);
        Map<String, Object> fileData = Map.of(
                "path", uploadedPath,
                "meta", Map.of("_type", "gradio.FileData"),
                "orig_name", filename
        );
        // dub_video expects a VideoData input: { video: FileData, subtitles: null }
        Map<String, Object> videoData = Map.of("video", fileData);
        Map<String, Object> body = Map.of("data", List.of(videoData));

        String response = webClient.post()
                .uri("/gradio_api/call/dub_video")
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

    /**
     * Returns the raw FileData JSON object for the dubbed video (the
     * dub_video endpoint's single output), not just a text decision.
     */
    private JsonNode awaitResult(String eventId) throws Exception {
        long start = System.currentTimeMillis();
        log.debug("[huggingface] awaiting SSE 'complete' event for eventId={}", eventId);

        // Walk the SSE stream event-by-event. Log non-terminal events (heartbeat,
        // progress) for debugging and return as soon as we see "complete" or "error".
        List<ServerSentEvent<String>> terminal = webClient.get()
                .uri("/gradio_api/call/dub_video/{eventId}", eventId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnNext(sse -> {
                    String evt = sse.event();
                    if (!"complete".equals(evt) && !"error".equals(evt)) {
                        log.trace("[huggingface] event={} for eventId={}", evt, eventId);
                    }
                })
                .filter(sse -> "complete".equals(sse.event()) || "error".equals(sse.event()))
                .take(1)
                .collectList()
                .block(TIMEOUT);

        if (terminal == null || terminal.isEmpty()) {
            log.warn("[huggingface] no terminal event received for eventId={} after {}ms",
                    eventId, System.currentTimeMillis() - start);
            throw new IllegalStateException("No complete/error event received for event_id=" + eventId);
        }

        ServerSentEvent<String> event = terminal.getFirst();
        if ("error".equals(event.event())) {
            log.warn("[huggingface] Space returned error for eventId={}: {}", eventId, event.data());
            throw new IllegalStateException("HuggingFace Space error for event_id=" + eventId + ": " + event.data());
        }

        JsonNode result = objectMapper.readTree(event.data());
        // The response is a VideoData: { "video": FileData, "subtitles": null }
        // or a flat array [FileData]. Handle both.
        JsonNode fileData;
        if (result.isArray()) {
            fileData = result.get(0);
        } else if (result.has("video")) {
            fileData = result.get("video");
        } else {
            fileData = result;
        }
        if (fileData == null || fileData.isNull()) {
            throw new IllegalStateException("Complete event carried no output file for event_id=" + eventId);
        }
        log.debug("[huggingface] dubbed file received for eventId={} after {}ms",
                eventId, System.currentTimeMillis() - start);
        return fileData;
    }

    private String downloadAndStore(JsonNode fileData, String sourceKey) {
        JsonNode urlNode = fileData.get("url");
        byte[] bytes = (urlNode != null && !urlNode.isNull())
                ? downloadBytes(URI.create(urlNode.asText()))
                : downloadBytes(fileData.get("path").asText());

        String newKey = derivedKey(sourceKey);
        storageService.uploadObject(newKey, bytes, "video/mp4");
        return newKey;
    }

    private byte[] downloadBytes(URI absoluteUrl) {
        return webClient.get().uri(absoluteUrl).retrieve().bodyToMono(byte[].class).block(TIMEOUT);
    }

    private byte[] downloadBytes(String relativePath) {
        return webClient.get().uri("/gradio_api/file={path}", relativePath).retrieve().bodyToMono(byte[].class).block(TIMEOUT);
    }

    private String derivedKey(String sourceKey) {
        int dot = sourceKey.lastIndexOf('.');
        String base = dot == -1 ? sourceKey : sourceKey.substring(0, dot);
        String ext = dot == -1 ? "" : sourceKey.substring(dot);
        return base + "_fr" + ext;
    }
}
