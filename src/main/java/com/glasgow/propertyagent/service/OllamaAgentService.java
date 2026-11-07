package com.glasgow.propertyagent.service;

import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.BuyerWorkflowState;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.model.RankedPropertyResponse;
import com.glasgow.propertyagent.model.SearchFilterRequest;
import com.glasgow.propertyagent.model.StationProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REF-02: Focused Multimodal Local AI Extraction & Commuter Research Orchestrator.
 * Delegates HTTP/process transport to OllamaApiClient, image scaling/WebP conversion to
 * VisionImagePreprocessor, regex fallback to HeuristicMetadataExtractor, and deterministic
 * Stage 2 CPU scoring to PropertyScoringEngine (zero circular @Lazy dependencies).
 */
@Service
public class OllamaAgentService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAgentService.class);
    private static final Pattern BATCH_LINE_PATTERN = Pattern.compile(
        "^(?:\\[(\\d+)\\]|(\\d+)[.:]|Image\\s+(\\d+)[.:])\\s*(.*)$",
        Pattern.CASE_INSENSITIVE
    );

    private final ObjectMapper objectMapper;
    private final OllamaApiClient ollamaClient;
    private final HeuristicMetadataExtractor heuristicExtractor;
    private final PropertyScoringEngine scoringEngine;
    private final String visionModel;
    private final String rankingModel;
    private final int floorplanMaxDimension;
    private final int photoMaxDimension;
    private final int photoBatchSize;

    public record LlmStatusReport(
        boolean ollamaOnline,
        boolean visionModelAvailable,
        String configuredVisionModel,
        String configuredRankingModel,
        List<String> installedModels,
        String modeDescription,
        String setupHint
    ) {}

    public record LlmExtractionResult(
        ExtractedPropertyMetadata metadata,
        String fullLlmResponseLog,
        String metadataSource, // "VISION_LLM", "TEXT_LLM", "HEURISTIC"
        String metadataModel
    ) {
        public LlmExtractionResult(ExtractedPropertyMetadata metadata, String fullLlmResponseLog) {
            this(metadata, fullLlmResponseLog, "VISION_LLM", "gemma4:e4b");
        }
    }

    public record CommuteResearchReport(
        String listingId,
        String targetLocation,
        String modelUsed,
        boolean ollamaGenerated,
        String llmThinking,
        String llmCommuteBreakdown,
        String groundedDossier
    ) {}

    @FunctionalInterface
    public interface ExtractionProgressListener {
        void onProgress(int currentImage, int totalImages, String stepMessage);
    }

    // Convenience constructors for standalone unit tests
    public OllamaAgentService(
            ObjectMapper objectMapper,
            PropertyEmbeddingService embeddingService,
            String ollamaBaseUrl,
            String visionModel,
            String rankingModel) {
        this(objectMapper, embeddingService, ollamaBaseUrl, visionModel, rankingModel, 1280, 768, 3);
    }

    public OllamaAgentService(
            ObjectMapper objectMapper,
            PropertyEmbeddingService embeddingService,
            String ollamaBaseUrl,
            String visionModel,
            String rankingModel,
            int floorplanMaxDimension,
            int photoMaxDimension,
            int photoBatchSize) {
        this(
            objectMapper,
            new OllamaApiClient(objectMapper, ollamaBaseUrl),
            new HeuristicMetadataExtractor(),
            new PropertyScoringEngine(objectMapper, embeddingService),
            visionModel,
            rankingModel,
            floorplanMaxDimension,
            photoMaxDimension,
            photoBatchSize
        );
    }

    @Autowired
    public OllamaAgentService(
            ObjectMapper objectMapper,
            OllamaApiClient ollamaClient,
            HeuristicMetadataExtractor heuristicExtractor,
            PropertyScoringEngine scoringEngine,
            @Value("${property-agent.ollama.vision-model:gemma4:e4b}") String visionModel,
            @Value("${property-agent.ollama.ranking-model:gemma4:e4b}") String rankingModel,
            @Value("${property-agent.ollama.floorplan-max-dimension:1280}") int floorplanMaxDimension,
            @Value("${property-agent.ollama.photo-max-dimension:768}") int photoMaxDimension,
            @Value("${property-agent.ollama.photo-batch-size:3}") int photoBatchSize) {
        this.objectMapper = objectMapper;
        this.ollamaClient = ollamaClient;
        this.heuristicExtractor = heuristicExtractor;
        this.scoringEngine = scoringEngine;
        this.visionModel = visionModel;
        this.rankingModel = rankingModel;
        this.floorplanMaxDimension = Math.max(640, floorplanMaxDimension);
        this.photoMaxDimension = Math.max(512, photoMaxDimension);
        this.photoBatchSize = Math.max(1, photoBatchSize);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupModelOnStartup() {
        Thread.ofVirtual().name("ollama-model-warmup").start(() -> {
            try {
                LlmStatusReport status = getLlmStatus(false);
                if (status.ollamaOnline() && !status.installedModels().isEmpty()) {
                    String model = status.configuredVisionModel();
                    log.info("P7: Pre-pinning and warming up local model '{}' (visionProjector={}, num_ctx=4096, keep_alive=30m)...",
                        model, status.visionModelAvailable());
                    ollamaClient.warmupModel(model, status.visionModelAvailable());
                    log.info("P7: Local model '{}' successfully pre-pinned in memory.", model);

                    status.installedModels().stream()
                        .filter(m -> m.toLowerCase().contains("nomic-embed") || m.toLowerCase().contains("embed"))
                        .findFirst()
                        .ifPresent(embedModel -> {
                            ollamaClient.embedBatch(embedModel, List.of("warmup"));
                            log.info("P7: Embedding model '{}' pre-pinned with keep_alive=30m.", embedModel);
                        });
                }
            } catch (Exception e) {
                log.debug("P7: Model warmup non-fatal: {}", e.getMessage());
            }
        });
    }

    public LlmStatusReport getLlmStatus(boolean autoStartIfOffline) {
        List<String> models = ollamaClient.fetchInstalledModels();
        if (models == null && autoStartIfOffline) {
            ollamaClient.tryAutoStartLocalOllama();
            models = ollamaClient.fetchInstalledModels();
        }

        if (models == null) {
            return new LlmStatusReport(
                false,
                false,
                visionModel,
                rankingModel,
                List.of(),
                "Heuristic Fallback Active (Ollama daemon offline at " + ollamaClient.getBaseUrl() + ")",
                "Run 'ollama serve' and 'ollama pull gemma4:e4b' to enable multimodal floorplan & photo vision."
            );
        }

        String matchedVision = findBestVisionModel(models);
        String matchedText = findBestTextModel(models);

        if (matchedVision != null) {
            return new LlmStatusReport(
                true,
                true,
                matchedVision,
                matchedText != null ? matchedText : matchedVision,
                models,
                "Ollama Multimodal Vision Online (" + matchedVision + " analyzing floorplans + photos)",
                "Ready — Vision LLM is active."
            );
        } else if (matchedText != null) {
            return new LlmStatusReport(
                true,
                false,
                visionModel,
                matchedText,
                models,
                "Ollama Text LLM Online (" + matchedText + ") — No Vision model pulled yet",
                "Run 'ollama pull gemma4:e4b' in terminal to enable floorplan & photo image inspection."
            );
        } else {
            return new LlmStatusReport(
                true,
                false,
                visionModel,
                rankingModel,
                models,
                "Ollama Online (0 models pulled) — Using Heuristic Parser until a model is pulled",
                "Run 'ollama pull gemma4:e4b' in terminal to download the local vision model."
            );
        }
    }

    private String findBestVisionModel(List<String> installed) {
        if (installed == null || installed.isEmpty()) return null;
        for (String m : installed) {
            String lower = m.toLowerCase();
            if (lower.contains("embed") || lower.contains("nomic") || lower.contains("bge")) {
                continue;
            }
            if (lower.contains(visionModel.toLowerCase()) || lower.contains("vision") || lower.contains("llava")
                    || lower.contains("gemma4") || lower.contains("minicpm-v")
                    || lower.contains("qwen2.5vl") || lower.contains("qwen2-vl") || lower.contains("pixtral") || lower.contains("moondream")) {
                return m;
            }
        }
        return null;
    }

    private String findBestTextModel(List<String> installed) {
        if (installed == null || installed.isEmpty()) return null;
        for (String m : installed) {
            String lower = m.toLowerCase();
            if (lower.contains("embed") || lower.contains("nomic") || lower.contains("bge") || lower.contains("minilm")) {
                continue;
            }
            if (lower.contains(rankingModel.toLowerCase().split(":")[0])) {
                return m;
            }
        }
        for (String m : installed) {
            String lower = m.toLowerCase();
            if (!lower.contains("embed") && !lower.contains("nomic") && !lower.contains("bge")) {
                return m;
            }
        }
        return installed.get(0);
    }

    public CommuteResearchReport researchCommuteWithLlm(
            String listingId,
            String targetLocation,
            String groundedDossier) {

        String targetLabel = (targetLocation == null || targetLocation.isBlank()) ? "Glasgow Central" : targetLocation.trim();
        LlmStatusReport status = getLlmStatus(true);
        if (!status.ollamaOnline() || status.installedModels().isEmpty()) {
            log.info("[{}] Ollama offline during commute research to '{}' — returning grounded transit dossier.", listingId, targetLabel);
            return new CommuteResearchReport(
                listingId,
                targetLabel,
                "Grounded Transit Engine (Ollama Offline)",
                false,
                "",
                "Local Ollama LLM is currently offline. Showing verified ScotRail, SPT Subway & OpenStreetMap/NaPTAN transit dossier below:\n\n" + groundedDossier,
                groundedDossier
            );
        }

        String modelToUse = status.visionModelAvailable()
            ? status.configuredVisionModel()
            : findBestTextModel(status.installedModels());

        String prompt = """
            You are a Greater Glasgow public transport & family commuter analyst.
            Analyze the commute from this property to "%s" using ONLY the verified ScotRail, SPT Subway, OpenStreetMap/NaPTAN bus, and listing facts below.
            STRICT RULE: Never invent train times, station distances, or bus route numbers — quote ONLY the exact numbers from the Verified Transit Dossier.
            Keep your internal thinking concise and focused on comparing the trade-offs, then write a practical 4-bullet breakdown (plain text with bullet headings, max 180 words total):
            • Primary Fastest Commute: Best option, exact station/stop distance (walk vs drive), in-vehicle mins, total door-to-target mins, and peak frequency.
            • Alternative Station / Park & Ride Comparison: Compare the #1 closest station against #2/#3 nearby ScotRail or Subway Park & Ride hubs (especially if a slightly further station has faster door-to-target time, free parking spaces, or Category A lifts).
            • Bus & Disruption Backup: Nearest NaPTAN/OSM bus stop, walk time, route numbers/operator, and Subway backup.
            • Family, Pram & Estate-Agent Notes: Step-free ORR category (A vs B1/B2), parking practicality, and any local transport clues from the listing text.

            VERIFIED TRANSIT DOSSIER:
            %s
            """.formatted(targetLabel, groundedDossier);

        try {
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("model", modelToUse);
            reqBody.put("stream", false);
            reqBody.put("think", true);
            reqBody.put("keep_alive", "30m");
            reqBody.put("options", Map.of("temperature", 0.15, "num_predict", 2048, "num_ctx", 8192));
            reqBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            log.info("[{}] Running local LLM ('{}', think=true) grounded commute research -> target='{}'...", listingId, modelToUse, targetLabel);

            JsonNode root = ollamaClient.postChatJson(reqBody);
            JsonNode msgNode = root.path("message");
            String thinking = msgNode.path("thinking").asString("").trim();
            String answer = msgNode.path("content").asString("").trim();
            if (answer.isBlank()) {
                answer = thinking;
            }
            answer = answer.replaceAll("(?s)<think>.*?</think>", "").trim();
            if (answer.isBlank()) {
                throw new IllegalStateException("Empty commute breakdown returned by Ollama");
            }

            if (!thinking.isBlank()) {
                log.info("[{}] LLM Commute Thinking ('{}' -> '{}') -> Thinking: {}",
                    listingId, modelToUse, targetLabel, thinking.replaceAll("\\s+", " "));
            }
            log.info("[{}] LLM Commute Breakdown ('{}' -> '{}') -> LLM Response: {}",
                listingId, modelToUse, targetLabel, answer.replaceAll("\\s+", " "));

            return new CommuteResearchReport(
                listingId,
                targetLabel,
                modelToUse + " (Thinking)",
                true,
                thinking,
                answer,
                groundedDossier
            );
        } catch (Exception e) {
            log.warn("[{}] LLM Commute Breakdown failed ({}) — falling back to grounded dossier.", listingId, e.getMessage());
            return new CommuteResearchReport(
                listingId,
                targetLabel,
                modelToUse + " (Fallback)",
                false,
                "",
                "Grounded ScotRail / SPT Subway / NaPTAN Commute Dossier:\n\n" + groundedDossier,
                groundedDossier
            );
        }
    }

    public ExtractedPropertyMetadata extractMetadataFromDownloadedMedia(
            String listingId,
            String description,
            PropertyMediaService.DownloadedMediaBundle media) {
        return extractMetadataWithTranscript(listingId, description, media).metadata();
    }

    public LlmExtractionResult extractMetadataWithTranscript(
            String listingId,
            String description,
            PropertyMediaService.DownloadedMediaBundle media) {
        return extractMetadataWithTranscript(listingId, description, media, (c, t, m) -> {});
    }

    public LlmExtractionResult extractMetadataWithTranscript(
            String listingId,
            String description,
            PropertyMediaService.DownloadedMediaBundle media,
            ExtractionProgressListener progressListener) {

        ExtractionProgressListener listener = progressListener != null ? progressListener : (c, t, m) -> {};
        LlmStatusReport status = getLlmStatus(false);
        if (!status.ollamaOnline() || status.installedModels().isEmpty()) {
            log.info("[{}] Downloaded {} images locally ({} floorplans, {} photos). Ollama unavailable/0 models ({}); using heuristic parser.",
                listingId, media.totalDownloadedFiles(), media.floorplanFiles().size(), media.photoFiles().size(), status.modeDescription());
            listener.onProgress(media.totalDownloadedFiles(), media.totalDownloadedFiles(), "Ollama offline — running local heuristic metadata parser...");
            ExtractedPropertyMetadata fallback = heuristicExtractor.extract(description);
            return new LlmExtractionResult(fallback, "Ollama offline / 0 models installed — used heuristic fallback parser.", "HEURISTIC", "heuristic-offline");
        }

        if (status.visionModelAvailable()) {
            String activeVlm = status.configuredVisionModel();
            List<String> visualNotes = new ArrayList<>();

            List<Path> informativePhotos = VisionImagePreprocessor.filterInformativePhotos(media.photoFiles(), 0);
            int totalFiles = media.floorplanFiles().size() + informativePhotos.size();
            int currentIdx = 0;

            log.info("[{}] Starting Vision analysis across {} selected files ({} floorplans + {} filtered photos, skipped {} non-informative photos) with '{}'...",
                listingId, totalFiles, media.floorplanFiles().size(), informativePhotos.size(),
                Math.max(0, media.photoFiles().size() - informativePhotos.size()), activeVlm);

            for (Path fpFile : media.floorplanFiles()) {
                currentIdx++;
                listener.onProgress(currentIdx, totalFiles,
                    "Analyzing floorplan " + currentIdx + "/" + totalFiles + " (" + fpFile.getFileName() + ") with " + activeVlm + "...");
                String note = inspectSingleLocalImage(
                    activeVlm,
                    fpFile,
                    true,
                    "Inspect this UK residential floorplan and output ONLY 5 concise bullet points (max 220 words total). " +
                    "STRICT RULE: Output NO report title, NO date/client header, NO executive summary, and NO step-by-step imperial-to-metric multiplication math — quote printed room dimensions directly.\n" +
                    "- Rooms & Dimensions (All Floors): List each room per floor with its printed dimensions and total floor area if printed.\n" +
                    "- Essential Furniture Capacity: Assess capacity for essential furniture per room (e.g. Master fits King bed + dual nightstands + wardrobe; Bedroom 2 fits Double bed + desk; Bedroom 3 fits Single/bunk only; Lounge fits 3-piece suite).\n" +
                    "- Storage & Utility: Note fitted wardrobes ('W'), cupboards ('Cup'd'/'Store'), utility room, and garage (Integral/Attached/Detached).\n" +
                    "- Family Layout & Garden Flow: Ground-floor WC, master en-suite, lounge location (Front/Rear/Through), direct rear garden doors, and kitchen/diner flow.\n" +
                    "- Extension Verdict: One sentence on rear outward extension feasibility."
                );
                String singleLineNote = (note == null || note.isBlank()) ? "(empty response)" : note.replaceAll("\\s+", " ").trim();
                if (note != null && !note.isBlank()) {
                    visualNotes.add("Floorplan [" + currentIdx + "/" + totalFiles + "] (" + fpFile.getFileName() + "): " + singleLineNote);
                }
                log.info("[{}] Analyzed floorplan {}/{} ({}) -> LLM Response: {}",
                    listingId, currentIdx, totalFiles, fpFile.getFileName(), singleLineNote);
            }

            int batchSize = this.photoBatchSize;
            for (int i = 0; i < informativePhotos.size(); i += batchSize) {
                int end = Math.min(i + batchSize, informativePhotos.size());
                List<Path> batch = informativePhotos.subList(i, end);
                int batchStartIdx = currentIdx;
                currentIdx += batch.size();

                listener.onProgress(currentIdx, totalFiles,
                    "Batch analyzing photos " + (batchStartIdx + 1) + "-" + currentIdx + "/" + totalFiles + " with " + activeVlm + "...");

                List<String> batchNotes = inspectBatchLocalImages(activeVlm, batch, batchStartIdx, totalFiles);
                for (String bn : batchNotes) {
                    visualNotes.add(bn);
                    log.info("[{}] {}", listingId, bn);
                }
            }

            String combinedEvidence = description + "\n\n--- VISUAL INSPECTION NOTES FROM ALL " + visualNotes.size() + " DOWNLOADED IMAGES ---\n"
                + String.join("\n", visualNotes);

            listener.onProgress(totalFiles, totalFiles,
                "Synthesizing architectural JSON (" + activeVlm + ", think=false) from all " + totalFiles + " images...");

            return synthesizeStructuredMetadata(
                listingId,
                activeVlm,
                combinedEvidence,
                visualNotes,
                "Verified by Ollama Vision (" + visualNotes.size() + "/" + totalFiles + " downloaded images & floorplans analyzed)"
            );
        }

        String textModel = findBestTextModel(status.installedModels());
        listener.onProgress(media.totalDownloadedFiles(), media.totalDownloadedFiles(),
            "Synthesizing metadata JSON via text LLM (" + textModel + ")...");
        log.info("[{}] Downloaded {} images locally; running text LLM '{}' (pull gemma4:e4b to inspect images)...",
            listingId, media.totalDownloadedFiles(), textModel);
        return synthesizeStructuredMetadata(
            listingId,
            textModel,
            description,
            List.of(),
            "Verified by Ollama Text LLM (" + textModel + "; " + media.totalDownloadedFiles() + " images downloaded)"
        );
    }

    private String inspectSingleLocalImage(String visionModelName, Path imageFile, boolean isFloorplan, String prompt) {
        try {
            byte[] bytes = isFloorplan
                ? VisionImagePreprocessor.prepareFloorplanBytesForVision(imageFile, this.floorplanMaxDimension)
                : VisionImagePreprocessor.prepareImageBytesForVision(imageFile, this.photoMaxDimension, 0.82f);
            if (bytes == null || bytes.length < 500) return null;
            String b64 = Base64.getEncoder().encodeToString(bytes);

            Map<String, Object> userMsg = Map.of(
                "role", "user",
                "content", prompt,
                "images", List.of(b64)
            );
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("model", visionModelName);
            reqBody.put("stream", false);
            reqBody.put("think", false);
            reqBody.put("keep_alive", "30m");
            reqBody.put("options", Map.of("temperature", 0.2, "num_predict", isFloorplan ? 450 : 200, "num_ctx", 4096));
            reqBody.put("messages", List.of(userMsg));

            JsonNode root = ollamaClient.postChatJson(reqBody);
            JsonNode msgNode = root.path("message");
            String text = msgNode.path("content").asString("").trim();
            if (text.isBlank()) {
                text = msgNode.path("thinking").asString("").trim();
            }
            return text.replaceAll("(?s)<think>.*?</think>", "").trim();
        } catch (Exception e) {
            log.warn("Vision pass failed on {}: {}", imageFile.getFileName(), e.getMessage());
            return null;
        }
    }

    private List<String> inspectBatchLocalImages(String visionModelName, List<Path> batch, int startIndex, int totalFiles) {
        if (batch.isEmpty()) return List.of();
        if (batch.size() == 1) {
            String note = inspectSingleLocalImage(
                visionModelName,
                batch.get(0),
                false,
                "Act as an expert UK residential building surveyor inspecting this property photo. Provide a concise 1-2 sentence assessment:\n" +
                "- Essential Furniture Fit: Assess realistic room capacity for essential furniture (e.g. Master fits King bed + bedside tables; Double fits Double bed + wardrobe; Box fits Single bed or desk only; Living fits 3-seater sofa + dining suite).\n" +
                "- Permanent Fixtures & Upgrades: Kitchen cabinetry/worktop quality (handleless/quartz vs dated laminate), bathroom suite modernity (walk-in shower/contemporary sanitaryware vs dated bath/tiling), fitted wardrobes/storage cupboards, and exterior driveway/fencing."
            );
            return (note == null || note.isBlank())
                ? List.of()
                : List.of("Photo [" + (startIndex + 1) + "/" + totalFiles + "] (" + batch.get(0).getFileName() + "): " + note.replaceAll("\\s+", " ").trim());
        }

        try {
            List<String> b64List = new ArrayList<>();
            List<Path> validPaths = new ArrayList<>();
            List<Integer> validOriginalIndices = new ArrayList<>();
            for (int idx = 0; idx < batch.size(); idx++) {
                Path img = batch.get(idx);
                byte[] bytes = VisionImagePreprocessor.prepareImageBytesForVision(img, this.photoMaxDimension, 0.82f);
                if (bytes != null && bytes.length >= 500) {
                    b64List.add(Base64.getEncoder().encodeToString(bytes));
                    validPaths.add(img);
                    validOriginalIndices.add(idx);
                }
            }
            if (b64List.isEmpty()) return List.of();

            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("You are an expert UK residential building surveyor inspecting ").append(b64List.size()).append(" sequential photos from a UK property listing.\n")
                .append("CRITICAL RULE: Disregard all loose temporary seller decor (artwork, rugs, loose staging items). Assess the space for essential furniture fit, permanent fixtures, and structural condition.\n")
                .append("For each numbered photo [1] to [").append(b64List.size()).append("], write a concise 1-2 sentence surveyor assessment:\n")
                .append("- Space & Essential Furniture Fit: Identify the room. Gauge whether it comfortably fits essential furniture (e.g. King/Double bed with side clearance vs pushed against radiator/door; desk/wardrobe space; full dining/sofa setup) and detect any wide-angle lens distortion.\n")
                .append("- Permanent Fixtures & Upgrades: Detail kitchen cabinetry/worktops (solid/quartz vs laminate), bathroom suite modernity (contemporary walk-in shower/tiling vs dated fittings), fitted wardrobes, or exterior driveway surface/boundary fence security.\n")
                .append("If a photo is purely an EPC energy chart or agency logo, reply 'SKIP'.\n")
                .append("Format strictly as:\n");
            for (int i = 1; i <= b64List.size(); i++) {
                promptBuilder.append("[").append(i).append("] <1-2 concise sentences on furniture fit, permanent fixtures & condition>\n");
            }

            Map<String, Object> userMsg = Map.of(
                "role", "user",
                "content", promptBuilder.toString(),
                "images", b64List
            );
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("model", visionModelName);
            reqBody.put("stream", false);
            reqBody.put("think", false);
            reqBody.put("keep_alive", "30m");
            reqBody.put("options", Map.of("temperature", 0.2, "num_predict", 450, "num_ctx", 4096));
            reqBody.put("messages", List.of(userMsg));

            JsonNode root = ollamaClient.postChatJson(reqBody);
            JsonNode msgNode = root.path("message");
            String text = msgNode.path("content").asString("").trim();
            if (text.isBlank()) {
                text = msgNode.path("thinking").asString("").trim();
            }
            text = text.replaceAll("(?s)<think>.*?</think>", "").trim();

            List<String> results = new ArrayList<>();
            Map<Integer, String> parsed = new LinkedHashMap<>();
            Map<Integer, StringBuilder> accumulator = new LinkedHashMap<>();
            int currentTarget = -1;
            for (String rawLine : text.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isBlank()) continue;
                Matcher m = BATCH_LINE_PATTERN.matcher(line);
                if (m.find()) {
                    String numStr = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
                    try {
                        int num = Integer.parseInt(numStr);
                        if (num >= 1 && num <= validPaths.size()) {
                            currentTarget = num;
                            accumulator.computeIfAbsent(currentTarget, k -> new StringBuilder())
                                .append(m.group(4).trim());
                            continue;
                        }
                    } catch (Exception ignored) {}
                }
                if (currentTarget > 0) {
                    StringBuilder sb = accumulator.get(currentTarget);
                    if (sb != null) {
                        if (sb.length() > 0 && !sb.toString().endsWith(" ")) sb.append(" ");
                        sb.append(line);
                    }
                }
            }
            for (Map.Entry<Integer, StringBuilder> entry : accumulator.entrySet()) {
                parsed.put(entry.getKey(), entry.getValue().toString().trim());
            }

            for (int i = 0; i < validPaths.size(); i++) {
                Path p = validPaths.get(i);
                int photoNum = startIndex + validOriginalIndices.get(i) + 1;
                String note = parsed.get(i + 1);
                if (note != null && (note.equalsIgnoreCase("SKIP") || note.toLowerCase().startsWith("skip"))) {
                    continue;
                }
                if (note == null || note.isBlank()) {
                    if (i == 0 && !text.isBlank() && parsed.isEmpty()) {
                        note = text.replaceAll("\\s+", " ").trim();
                    } else {
                        note = "Inspected property photo: " + p.getFileName();
                    }
                }
                results.add("Photo [" + photoNum + "/" + totalFiles + "] (" + p.getFileName() + "): " + note.replaceAll("\\s+", " ").trim());
            }
            return results;
        } catch (Exception e) {
            log.warn("Batch vision pass failed: {}; falling back to single inspection", e.getMessage());
            List<String> fallbacks = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                Path p = batch.get(i);
                int photoNum = startIndex + i + 1;
                String note = inspectSingleLocalImage(
                    visionModelName,
                    p,
                    false,
                    "Act as an expert UK chartered property reviewer inspecting this photo. Provide a critical, expressive 2-3 sentence assessment:\n" +
                    "- Room Scale & Proportions: Identify the room/space and gauge realistic room dimensions using visual anchors (e.g. bed clearance on both sides, ceiling height vs sloped attic eaves, sofa scale; flag if wide-angle lens exaggerates room depth).\n" +
                    "- Storage & Built-in Features: Specifically note any fitted/built-in wardrobes (sliding/mirrored), under-stairs cupboards, pantry towers, or utility storage.\n" +
                    "- Materials, Condition & Outdoor: Detail cabinetry/worktops/flooring quality and walk-in decor condition. For exteriors, evaluate garden plot size, boundary fence security, and driveway parking capacity."
                );
                if (note != null && !note.isBlank()) {
                    fallbacks.add("Photo [" + photoNum + "/" + totalFiles + "] (" + p.getFileName() + "): " + note.replaceAll("\\s+", " ").trim());
                }
            }
            return fallbacks;
        }
    }

    // Pass-through helpers preserving static/instance API compatibility
    public static List<Path> filterInformativePhotos(List<Path> photos, int maxCap) {
        return VisionImagePreprocessor.filterInformativePhotos(photos, maxCap);
    }

    public static byte[] prepareFloorplanBytesForVision(Path imageFile, int maxDimension) {
        return VisionImagePreprocessor.prepareFloorplanBytesForVision(imageFile, maxDimension);
    }

    public static byte[] prepareImageBytesForVision(Path imageFile, int maxDimension, float jpegQuality) {
        return VisionImagePreprocessor.prepareImageBytesForVision(imageFile, maxDimension, jpegQuality);
    }

    public RankedPropertyResponse evaluateAndScoreProperty(
            IndexedPropertyEntity entity,
            ExtractedPropertyMetadata metadata,
            StationProfile station,
            StationCommuteService.TransitEvaluation transit,
            SearchFilterRequest filterRequest) {
        return scoringEngine.evaluateAndScoreProperty(entity, metadata, station, transit, filterRequest);
    }

    public RankedPropertyResponse evaluateAndScoreProperty(
            IndexedPropertyEntity entity,
            ExtractedPropertyMetadata metadata,
            StationProfile station,
            StationCommuteService.TransitEvaluation transit,
            StationCommuteService.TransitEvaluation secondTransit,
            BuyerWorkflowState workflowState,
            double[] resolvedCoords,
            SearchFilterRequest filterRequest) {
        return scoringEngine.evaluateAndScoreProperty(
            entity, metadata, station, transit, secondTransit, workflowState, resolvedCoords, filterRequest
        );
    }

    public ExtractedPropertyMetadata heuristicExtract(String description) {
        return heuristicExtractor.extract(description);
    }

    private LlmExtractionResult synthesizeStructuredMetadata(
            String listingId,
            String modelToUse,
            String fullEvidenceText,
            List<String> visualNotes,
            String verificationBadge) {

        String prompt = """
            You are a UK residential architect and chartered building surveyor.
            Using BOTH the surveyor inspection notes from all downloaded floorplans/photos and the listing description below,
            extract structured JSON metadata.
            CRITICAL EVALUATION GUIDELINES:
            - Focus on permanent fixtures, capital improvements, structural layouts, and raw usable floor space. Disregard loose seller staging decor (artwork, rugs, loose staging items).
            - In 'extractedHighlights', prioritize:
              • Room Sizing & Essential Furniture Capacity (e.g. 'Master bedroom fits King bed + bedside tables (14m²)', 'True double bedrooms with wardrobe clearance', 'Lounge comfortably fits 3-piece suite', 'Box room / nursery / home office', 'Total floor area approx 1,100 sq ft')
              • Permanent Fixture Quality & Upgrades (e.g. 'Contemporary fitted kitchen with quartz worktops', 'Modern walk-in shower room', 'Fully tiled luxury bathroom suite', 'New gas central heating radiators')
              • Built-in Storage (e.g. 'Integrated wardrobes in bedrooms', 'Under-stairs storage cupboard', 'Dedicated utility room', 'Walk-in pantry')
              • Grounds & Exterior Layout (e.g. 'Enclosed child-safe garden', 'Monoblock driveway for 2+ cars', 'Direct French doors to rear patio')
            Return ONLY valid JSON matching these fields:
            {
              "hasDownstairsWc": boolean,
              "hasMasterEnsuite": boolean,
              "totalBathrooms": integer,
              "livingRoomLocation": "Rear" | "Front" | "Through-Lounge",
              "directGardenAccessFromLiving": boolean,
              "gardenSightlineFromSofa": "Direct (Watch kids from sofa)" | "Partial via Kitchen/Diner" | "None",
              "kitchenLayout": string,
              "kitchenExtensionPotentialToRear": boolean,
              "hasUtilityRoom": boolean,
              "hasDedicatedStudyOrOffice": boolean,
              "garageType": "Integral" | "Detached" | "None",
              "gardenEnclosedForKids": boolean,
              "gardenOrientation": "South" | "South-West" | "West" | "East" | "North" | "Unknown",
              "drivewayCarCapacity": integer,
              "interiorCondition": "Walk-in / Modern" | "Good / Minor Cosmetic" | "Needs Renovation",
              "epcRating": string,
              "heatingType": string,
              "extractedHighlights": [string]
            }

            Property Evidence:
            """ + fullEvidenceText;

        String rawResponse = null;
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", modelToUse);
            requestBody.put("stream", false);
            requestBody.put("think", false);
            requestBody.put("format", metadataJsonSchema());
            requestBody.put("keep_alive", "30m");
            requestBody.put("options", Map.of("temperature", 0.1, "num_predict", 800, "num_ctx", 4096));
            requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            rawResponse = ollamaClient.postChatRaw(requestBody);
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode msgNode = root.path("message");
            String thinking = msgNode.path("thinking").asString("").trim();
            String content = msgNode.path("content").asString("").trim();
            String cleanJson = extractCleanJsonObject(content);
            if (cleanJson.isBlank()) {
                throw new IllegalStateException("Ollama returned empty JSON content (done_reason=" + root.path("done_reason").asString("") + ")");
            }

            if (!thinking.isBlank()) {
                log.info("[{}] Ollama Architectural Synthesis Thinking via '{}' -> Thinking: {}",
                    listingId, modelToUse, thinking.replaceAll("\\s+", " ").trim());
            }
            String compactJsonForLog = cleanJson.replaceAll("\\s+", " ").trim();
            log.info("[{}] Ollama JSON synthesis succeeded via '{}' ({}) -> LLM JSON Response: {}",
                listingId, modelToUse, verificationBadge, compactJsonForLog);

            ExtractedPropertyMetadata extracted = objectMapper.readValue(cleanJson, ExtractedPropertyMetadata.class);
            List<String> highlights = new ArrayList<>(extracted.extractedHighlights() != null ? extracted.extractedHighlights() : List.of());
            highlights.add(verificationBadge);
            ExtractedPropertyMetadata finalMeta = new ExtractedPropertyMetadata(
                extracted.hasDownstairsWc(),
                extracted.hasMasterEnsuite(),
                extracted.totalBathrooms(),
                extracted.livingRoomLocation(),
                extracted.directGardenAccessFromLiving(),
                extracted.gardenSightlineFromSofa(),
                extracted.kitchenLayout(),
                extracted.kitchenExtensionPotentialToRear(),
                extracted.hasUtilityRoom(),
                extracted.hasDedicatedStudyOrOffice(),
                extracted.garageType(),
                extracted.gardenEnclosedForKids(),
                extracted.gardenOrientation(),
                extracted.drivewayCarCapacity(),
                extracted.interiorCondition(),
                extracted.epcRating(),
                extracted.heatingType(),
                highlights
            );

            StringBuilder transcript = new StringBuilder();
            if (visualNotes != null && !visualNotes.isEmpty()) {
                transcript.append("=== PER-IMAGE VISION LLM RESPONSES (").append(modelToUse).append(", think=false) ===\n");
                for (String vn : visualNotes) {
                    transcript.append(vn).append("\n");
                }
                transcript.append("\n");
            }
            if (!thinking.isBlank()) {
                transcript.append("=== ARCHITECTURAL REASONING / THINKING (").append(modelToUse).append(", think=true) ===\n");
                transcript.append(thinking).append("\n\n");
            }
            transcript.append("=== FINAL SYNTHESIZED LLM JSON (").append(modelToUse).append(") ===\n");
            transcript.append(cleanJson);

            String src = (visualNotes != null && !visualNotes.isEmpty()) ? "VISION_LLM" : "TEXT_LLM";
            return new LlmExtractionResult(finalMeta, transcript.toString(), src, modelToUse);
        } catch (Exception e) {
            String rawPreview = (rawResponse == null) ? "(no HTTP response)" : rawResponse.replaceAll("\\s+", " ").trim();
            log.warn("[{}] Ollama JSON synthesis needed fallback ({}). Raw LLM response: {}",
                listingId, e.getMessage(), rawPreview);
            ExtractedPropertyMetadata fallback = heuristicExtractor.extract(fullEvidenceText);
            List<String> merged = new ArrayList<>(fallback.extractedHighlights());
            merged.add("Local Heuristic Parser (LLM Synthesis Fallback)");
            ExtractedPropertyMetadata fallbackMeta = new ExtractedPropertyMetadata(
                fallback.hasDownstairsWc(),
                fallback.hasMasterEnsuite(),
                fallback.totalBathrooms(),
                fallback.livingRoomLocation(),
                fallback.directGardenAccessFromLiving(),
                fallback.gardenSightlineFromSofa(),
                fallback.kitchenLayout(),
                fallback.kitchenExtensionPotentialToRear(),
                fallback.hasUtilityRoom(),
                fallback.hasDedicatedStudyOrOffice(),
                fallback.garageType(),
                fallback.gardenEnclosedForKids(),
                fallback.gardenOrientation(),
                fallback.drivewayCarCapacity(),
                fallback.interiorCondition(),
                fallback.epcRating(),
                fallback.heatingType(),
                merged
            );
            StringBuilder transcript = new StringBuilder();
            if (visualNotes != null && !visualNotes.isEmpty()) {
                transcript.append("=== PER-IMAGE VISION LLM RESPONSES (").append(modelToUse).append(") ===\n");
                for (String vn : visualNotes) {
                    transcript.append(vn).append("\n");
                }
                transcript.append("\n");
            }
            transcript.append("=== JSON SYNTHESIS FALLBACK ===\n").append(e.getMessage()).append("\nRaw Output: ").append(rawPreview);
            return new LlmExtractionResult(fallbackMeta, transcript.toString(), "HEURISTIC", "heuristic-fallback");
        }
    }

    private static Map<String, Object> metadataJsonSchema() {
        Map<String, Object> bool = Map.of("type", "boolean");
        Map<String, Object> integer = Map.of("type", "integer");
        Map<String, Object> str = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hasDownstairsWc", bool);
        props.put("hasMasterEnsuite", bool);
        props.put("totalBathrooms", integer);
        props.put("livingRoomLocation", Map.of("type", "string", "enum", List.of("Rear", "Front", "Through-Lounge")));
        props.put("directGardenAccessFromLiving", bool);
        props.put("gardenSightlineFromSofa", Map.of("type", "string", "enum",
            List.of("Direct (Watch kids from sofa)", "Partial via Kitchen/Diner", "None")));
        props.put("kitchenLayout", str);
        props.put("kitchenExtensionPotentialToRear", bool);
        props.put("hasUtilityRoom", bool);
        props.put("hasDedicatedStudyOrOffice", bool);
        props.put("garageType", Map.of("type", "string", "enum", List.of("Integral", "Detached", "Attached", "Converted", "None")));
        props.put("gardenEnclosedForKids", bool);
        props.put("gardenOrientation", Map.of("type", "string", "enum",
            List.of("South", "South-West", "West", "East", "North", "Unknown")));
        props.put("drivewayCarCapacity", integer);
        props.put("interiorCondition", Map.of("type", "string", "enum",
            List.of("Walk-in / Modern", "Good / Minor Cosmetic", "Needs Renovation")));
        props.put("epcRating", str);
        props.put("heatingType", str);
        props.put("extractedHighlights", Map.of("type", "array", "items", str));
        return Map.of("type", "object", "properties", props, "required", new ArrayList<>(props.keySet()));
    }

    private String extractCleanJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
        int firstBrace = cleaned.indexOf('{');
        if (firstBrace < 0) return cleaned;

        int lastBrace = cleaned.lastIndexOf('}');
        if (lastBrace > firstBrace) {
            String candidate = cleaned.substring(firstBrace, lastBrace + 1);
            if (!hasUnclosedQuotes(candidate)) {
                return candidate;
            }
        }
        return repairTruncatedJson(cleaned.substring(firstBrace));
    }

    private boolean hasUnclosedQuotes(String s) {
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            }
        }
        return inString;
    }

    private String repairTruncatedJson(String json) {
        if (json == null || json.isBlank()) return json;
        StringBuilder sb = new StringBuilder(json.trim());
        boolean inString = false;
        boolean escape = false;
        int openBraces = 0;
        int openBrackets = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') openBraces++;
                else if (c == '}') openBraces = Math.max(0, openBraces - 1);
                else if (c == '[') openBrackets++;
                else if (c == ']') openBrackets = Math.max(0, openBrackets - 1);
            }
        }
        if (inString) {
            sb.append('"');
        } else {
            int lastNonWs = sb.length() - 1;
            while (lastNonWs >= 0 && Character.isWhitespace(sb.charAt(lastNonWs))) {
                lastNonWs--;
            }
            if (lastNonWs >= 0 && sb.charAt(lastNonWs) == ',') {
                sb.delete(lastNonWs, sb.length());
            }
        }
        while (openBrackets > 0) {
            sb.append(']');
            openBrackets--;
        }
        while (openBraces > 0) {
            sb.append('}');
            openBraces--;
        }
        return sb.toString();
    }
}
