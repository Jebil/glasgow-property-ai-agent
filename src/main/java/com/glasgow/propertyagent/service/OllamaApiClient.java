package com.glasgow.propertyagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REF-02 / SIMP-03: Shared, Virtual-Thread-friendly Ollama HTTP & Process Client.
 * Replaces duplicated RestClient + SimpleClientHttpRequestFactory instances across
 * OllamaAgentService and PropertyEmbeddingService, using a lazy JdkClientHttpRequestFactory
 * so unit tests in restricted sandboxes never open NIO selector pipes on construction.
 */
@Component
public class OllamaApiClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaApiClient.class);

    private final ObjectMapper objectMapper;
    private final String ollamaBaseUrl;
    private final AtomicLong lastOllamaAutoStartEpochMs = new AtomicLong(0);
    private volatile RestClient restClient;

    @Autowired
    public OllamaApiClient(
            ObjectMapper objectMapper,
            @Value("${property-agent.ollama.base-url:http://127.0.0.1:11434}") String ollamaBaseUrl) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.ollamaBaseUrl = (ollamaBaseUrl == null || ollamaBaseUrl.isBlank())
            ? "http://127.0.0.1:11434"
            : ollamaBaseUrl;
    }

    public String getBaseUrl() {
        return ollamaBaseUrl;
    }

    private RestClient getRestClient() {
        RestClient client = this.restClient;
        if (client == null) {
            synchronized (this) {
                client = this.restClient;
                if (client == null) {
                    HttpClient jdkClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .build();
                    JdkClientHttpRequestFactory reqFactory = new JdkClientHttpRequestFactory(jdkClient);
                    reqFactory.setReadTimeout(Duration.ofSeconds(180));
                    client = RestClient.builder()
                        .baseUrl(ollamaBaseUrl)
                        .requestFactory(reqFactory)
                        .build();
                    this.restClient = client;
                }
            }
        }
        return client;
    }

    public List<String> fetchInstalledModels() {
        try {
            String raw = getRestClient().get()
                .uri("/api/tags")
                .retrieve()
                .body(String.class);
            if (raw == null || raw.isBlank()) return null;
            JsonNode root = objectMapper.readTree(raw);
            List<String> names = new ArrayList<>();
            JsonNode arr = root.path("models");
            if (arr.isArray()) {
                for (JsonNode m : arr) {
                    String name = m.path("name").asString("");
                    if (!name.isBlank()) names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isModelInstalled(String modelPrefix) {
        if (modelPrefix == null || modelPrefix.isBlank()) return false;
        List<String> models = fetchInstalledModels();
        if (models == null) return false;
        String lower = modelPrefix.toLowerCase();
        return models.stream().anyMatch(m -> m.toLowerCase().contains(lower));
    }

    public String postChatRaw(Map<String, Object> requestBody) {
        return getRestClient().post()
            .uri("/api/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(String.class);
    }

    public JsonNode postChatJson(Map<String, Object> requestBody) {
        String raw = postChatRaw(requestBody);
        return objectMapper.readTree(raw);
    }

    public List<float[]> embedBatch(String embeddingModel, List<String> sanitizedInputs) {
        if (sanitizedInputs == null || sanitizedInputs.isEmpty()) return List.of();
        try {
            Map<String, Object> req = Map.of(
                "model", embeddingModel,
                "input", sanitizedInputs,
                "keep_alive", "30m"
            );
            String responseBody = getRestClient().post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(String.class);

            if (responseBody == null || responseBody.isBlank()) return List.of();
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingsNode = root.path("embeddings");
            if (!embeddingsNode.isArray()) return List.of();

            List<float[]> results = new ArrayList<>();
            for (JsonNode arr : embeddingsNode) {
                if (arr.isArray()) {
                    float[] vec = new float[arr.size()];
                    for (int i = 0; i < arr.size(); i++) {
                        vec[i] = (float) arr.get(i).asDouble();
                    }
                    results.add(vec);
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("Failed generating embeddings via Ollama ({}: {}): {}", embeddingModel, ollamaBaseUrl, e.getMessage());
            return List.of();
        }
    }

    public void warmupModel(String model) {
        warmupModel(model, false);
    }

    public void warmupModel(String model, boolean warmVisionProjector) {
        if (warmVisionProjector) {
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(img, "png", baos);
                String tinyPngB64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
                Map<String, Object> visionWarmReq = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", "ok", "images", List.of(tinyPngB64))),
                    "stream", false,
                    "think", false,
                    "keep_alive", "30m",
                    "options", Map.of("num_predict", 1, "num_ctx", 4096)
                );
                postChatRaw(visionWarmReq);
                return;
            } catch (Exception e) {
                log.debug("Vision projector warmup fell back to text warmup for '{}': {}", model, e.getMessage());
            }
        }
        Map<String, Object> req = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", "hi")),
            "stream", false,
            "think", false,
            "keep_alive", "30m",
            "options", Map.of("num_predict", 1, "num_ctx", 4096)
        );
        postChatRaw(req);
    }

    public void tryAutoStartLocalOllama() {
        long now = System.currentTimeMillis();
        // N16: Rate limit auto-start to at most once per 60 seconds
        long prev = lastOllamaAutoStartEpochMs.get();
        if (now - prev < 60_000L) {
            return;
        }
        if (!lastOllamaAutoStartEpochMs.compareAndSet(prev, now)) {
            return;
        }
        try {
            File localExe = new File(System.getProperty("user.home"), "AppData\\Local\\Programs\\Ollama\\ollama.exe");
            if (localExe.exists()) {
                log.info("Found local Ollama executable at {}; starting 'ollama serve' in background...", localExe.getAbsolutePath());
                new ProcessBuilder(localExe.getAbsolutePath(), "serve")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            log.warn("Could not auto-start local Ollama daemon: {}", e.getMessage());
        }
    }
}
