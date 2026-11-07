package com.glasgow.propertyagent.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.BuyerWorkflowState;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import com.glasgow.propertyagent.model.RankedPropertyResponse;
import com.glasgow.propertyagent.model.SearchFilterRequest;
import com.glasgow.propertyagent.model.StationProfile;
import com.glasgow.propertyagent.repository.IndexedPropertyRepository;
import com.glasgow.propertyagent.service.BuyerWorkflowService;
import com.glasgow.propertyagent.service.GlasgowSchoolAndAreaService;
import com.glasgow.propertyagent.service.HomeReportAnalyzerService;
import com.glasgow.propertyagent.service.OllamaAgentService;
import com.glasgow.propertyagent.service.PropertyEmbeddingService;
import com.glasgow.propertyagent.service.PropertyMetadataCache;
import com.glasgow.propertyagent.service.RightmoveScraperService;
import com.glasgow.propertyagent.service.StationCommuteService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/properties")
public class PropertyAgentController {

    private static final Logger log = LoggerFactory.getLogger(PropertyAgentController.class);

    /**
     * REF-04: Declarative RowMapper replacing 60+ lines of manual ResultSet extraction,
     * with safe primitive defaults and PostgreSQL TIMESTAMPTZ -> java.time.Instant conversion.
     */
    public static final RowMapper<IndexedPropertyEntity> CANDIDATE_ROW_MAPPER = createCandidateRowMapper();

    private static RowMapper<IndexedPropertyEntity> createCandidateRowMapper() {
        BeanPropertyRowMapper<IndexedPropertyEntity> mapper = new BeanPropertyRowMapper<>(IndexedPropertyEntity.class) {
            @Override
            protected Object getColumnValue(ResultSet rs, int index, PropertyDescriptor pd) throws SQLException {
                if (pd.getPropertyType() == Instant.class) {
                    Timestamp ts = rs.getTimestamp(index);
                    return ts != null ? ts.toInstant() : null;
                }
                return super.getColumnValue(rs, index, pd);
            }
        };
        mapper.setPrimitivesDefaultedForNullValue(true);
        return mapper;
    }

    private final IndexedPropertyRepository repository;
    private final StationCommuteService stationService;
    private final OllamaAgentService ollamaAgent;
    private final RightmoveScraperService scraperService;
    private final PropertyEmbeddingService embeddingService;
    private final PropertyMetadataCache metadataCache;
    private final BuyerWorkflowService buyerWorkflowService;
    private final HomeReportAnalyzerService homeReportAnalyzerService;
    private final GlasgowSchoolAndAreaService schoolAndAreaService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    // SIMP-04: Bounded Caffeine cache for on-demand commute research dossiers
    private final Cache<String, OllamaAgentService.CommuteResearchReport> commuteReportCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofHours(2))
        .build();

    // REF-05: Event-driven SSE subscriber pool (zero idle background timer polling)
    private final Set<SseEmitter> sseEmitters = ConcurrentHashMap.newKeySet();

    // Cached dynamic search suggestions across database
    private volatile List<String> cachedSuggestions = null;

    public PropertyAgentController(
            IndexedPropertyRepository repository,
            StationCommuteService stationService,
            OllamaAgentService ollamaAgent,
            RightmoveScraperService scraperService,
            PropertyEmbeddingService embeddingService,
            PropertyMetadataCache metadataCache,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this(
            repository, stationService, ollamaAgent, scraperService, embeddingService,
            metadataCache, new BuyerWorkflowService(), new HomeReportAnalyzerService(),
            new GlasgowSchoolAndAreaService(objectMapper), objectMapper, jdbcTemplate
        );
    }

    @Autowired
    public PropertyAgentController(
            IndexedPropertyRepository repository,
            StationCommuteService stationService,
            OllamaAgentService ollamaAgent,
            RightmoveScraperService scraperService,
            PropertyEmbeddingService embeddingService,
            PropertyMetadataCache metadataCache,
            BuyerWorkflowService buyerWorkflowService,
            HomeReportAnalyzerService homeReportAnalyzerService,
            GlasgowSchoolAndAreaService schoolAndAreaService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.stationService = stationService;
        this.ollamaAgent = ollamaAgent;
        this.scraperService = scraperService;
        this.embeddingService = embeddingService;
        this.metadataCache = metadataCache;
        this.buyerWorkflowService = buyerWorkflowService != null ? buyerWorkflowService : new BuyerWorkflowService();
        this.homeReportAnalyzerService = homeReportAnalyzerService != null ? homeReportAnalyzerService : new HomeReportAnalyzerService();
        this.schoolAndAreaService = schoolAndAreaService != null ? schoolAndAreaService : new GlasgowSchoolAndAreaService(objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = jdbcTemplate != null ? JdbcClient.create(jdbcTemplate) : null;
        if (this.scraperService != null) {
            this.scraperService.subscribeStatusUpdates(this::broadcastStatusSnapshot);
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void runStartupStationAndBusUpgrade() {
        Thread.ofVirtual().name("startup-station-bus-upgrade").start(() -> {
            try {
                List<IndexedPropertyEntity> all = repository.findAll();
                int upgraded = 0;
                for (IndexedPropertyEntity e : all) {
                    if (e.getLatitude() != 0.0 && e.getLongitude() != 0.0) {
                        boolean needsSave = false;
                        StationCommuteService.NearestStationMatch match =
                            stationService.resolveNearestStation(e.getLatitude(), e.getLongitude(), e.getAddress());
                        StationProfile st = match.station();
                        if (!Objects.equals(e.getNearestStationName(), st.stationName())
                                || Math.abs(e.getDistanceToStationMiles() - match.distanceToStationMiles()) > 0.05) {
                            e.setNearestStationName(st.stationName());
                            e.setDistanceToStationMiles(match.distanceToStationMiles());
                            e.setFastestTrainMins(st.fastestTrainToGlasgowCentralMins());
                            e.setEstimatedDoorToCentralMins(stationService.calculateDoorToCentralMins(match.distanceToStationMiles(), st));
                            e.setStationFreeParking(st.freeParkAndRide());
                            e.setStationAccessibilityCategory(st.accessibilityCategory());
                            needsSave = true;
                        }
                        if (e.getNearestBusStopName() == null || e.getNearestBusStopName().isBlank()) {
                            stationService.resolveAndPopulateBusInfo(e);
                            needsSave = true;
                        }
                        if (needsSave) {
                            repository.save(e);
                            upgraded++;
                        }
                    }
                }
                if (upgraded > 0) {
                    log.info("One-off startup station & bus upgrade completed for {} existing DB entities.", upgraded);
                }
            } catch (Exception ex) {
                log.warn("Startup station/bus upgrade encountered non-fatal error: {}", ex.getMessage());
            }
        });
    }

    @GetMapping("/stations")
    public Collection<StationProfile> getStations() {
        return stationService.getAllStations();
    }

    /**
     * FEAT-02: Serves all ScotRail stations (with coordinates & corridor groups), the 15-stop SPT Subway loop,
     * Greater Glasgow school/area hubs, and resolved coordinates for Partner A & Partner B target hubs.
     */
    @GetMapping("/transit-map-data")
    public Map<String, Object> getTransitMapData(
            @RequestParam(required = false) String center,
            @RequestParam(required = false) String secondCenter) {
        double[] primaryCoords = stationService.resolveCenterCoordinates(
            (center == null || center.isBlank()) ? "Glasgow Central" : center
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("centerCoords", primaryCoords);
        if (secondCenter != null && !secondCenter.isBlank()) {
            payload.put("secondCenterCoords", stationService.resolveCenterCoordinates(secondCenter));
        }
        payload.put("scotrailStations", stationService.getScotRailStationConfigs());
        payload.put("subwayHubs", stationService.getSubwayHubs());
        payload.put("schoolAreas", schoolAndAreaService.getAllAreaHubs());
        return payload;
    }

    /**
     * FEAT-05: Summary counts of shortlisted, hidden/dismissed, and Home-Report-inspected properties.
     */
    @GetMapping("/workflow-summary")
    public BuyerWorkflowService.WorkflowSummary getWorkflowSummary() {
        return buyerWorkflowService.getSummary();
    }

    /**
     * FEAT-05: Update shortlist star, hide/dismiss toggle, viewing status, personal notes, or manual HR override.
     */
    @PutMapping("/{listingId}/workflow")
    public BuyerWorkflowState updatePropertyWorkflow(
            @PathVariable String listingId,
            @RequestBody BuyerWorkflowService.WorkflowUpdateRequest req) {
        return buyerWorkflowService.updateWorkflow(listingId, req);
    }

    public record HomeReportTextRequest(String rawText) {}

    /**
     * FEAT-06: Upload a Scottish Home Report PDF (or text file) to extract Market Valuation, m², £/m²,
     * Council Tax Band, EPC Band, and Single Survey Category 2/3 repair warnings.
     */
    @PostMapping(value = "/{listingId}/home-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadHomeReportPdf(
            @PathVariable String listingId,
            @RequestParam("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Please select a Home Report PDF or text file."));
            }
            Integer askingHint = repository != null
                ? repository.findById(listingId).map(IndexedPropertyEntity::getPriceGbp).orElse(null)
                : null;
            HomeReportAnalyzerService.HomeReportAnalysis analysis =
                homeReportAnalyzerService.analyzePdfBytes(file.getBytes(), askingHint);
            BuyerWorkflowState updatedWorkflow = buyerWorkflowService.saveHomeReportAnalysis(listingId, analysis);
            return ResponseEntity.ok(Map.of(
                "listingId", listingId,
                "analysis", analysis,
                "workflow", updatedWorkflow,
                "summaryCounts", buyerWorkflowService.getSummary()
            ));
        } catch (IllegalArgumentException badReq) {
            return ResponseEntity.badRequest().body(Map.of("error", badReq.getMessage()));
        } catch (Exception e) {
            log.warn("Home Report upload parse error for {}: {}", listingId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "Failed to parse Home Report: " + e.getMessage()));
        }
    }

    /**
     * FEAT-06: Paste Single Survey / Home Report text directly for instant valuation & Category 2/3 repair analysis.
     */
    @PostMapping(value = "/{listingId}/home-report-text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyzeHomeReportText(
            @PathVariable String listingId,
            @RequestBody HomeReportTextRequest req) {
        try {
            if (req == null || req.rawText() == null || req.rawText().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Paste Single Survey / Home Report text to analyze."));
            }
            Integer askingHint = repository != null
                ? repository.findById(listingId).map(IndexedPropertyEntity::getPriceGbp).orElse(null)
                : null;
            HomeReportAnalyzerService.HomeReportAnalysis analysis =
                homeReportAnalyzerService.analyzeHomeReportText(req.rawText(), askingHint);
            BuyerWorkflowState updatedWorkflow = buyerWorkflowService.saveHomeReportAnalysis(listingId, analysis);
            return ResponseEntity.ok(Map.of(
                "listingId", listingId,
                "analysis", analysis,
                "workflow", updatedWorkflow,
                "summaryCounts", buyerWorkflowService.getSummary()
            ));
        } catch (IllegalArgumentException badReq) {
            return ResponseEntity.badRequest().body(Map.of("error", badReq.getMessage()));
        } catch (Exception e) {
            log.warn("Home Report text analysis error for {}: {}", listingId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "Failed to analyze Home Report text: " + e.getMessage()));
        }
    }

    @GetMapping("/llm-status")
    public OllamaAgentService.LlmStatusReport getLlmStatus() {
        return ollamaAgent.getLlmStatus(true);
    }

    @GetMapping("/scrape-status")
    public RightmoveScraperService.ScrapeJobStatus getScrapeStatus() {
        return scraperService.getScrapeJobStatus();
    }

    /**
     * REF-05: Pure event-driven Server-Sent Events (SSE) stream for live scraping & LLM reanalysis progress.
     */
    @GetMapping(value = "/scrape-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamScrapeEvents() {
        SseEmitter emitter = new SseEmitter(1_800_000L); // 30 minutes timeout
        sseEmitters.add(emitter);
        emitter.onCompletion(() -> sseEmitters.remove(emitter));
        emitter.onTimeout(() -> {
            sseEmitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> sseEmitters.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                .name("status")
                .data(scraperService.getScrapeJobStatus(), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            sseEmitters.remove(emitter);
        }
        return emitter;
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeoutException() {
        // Client automatically reconnects via EventSource if needed.
    }

    public void broadcastStatusSnapshot(RightmoveScraperService.ScrapeJobStatus status) {
        if (sseEmitters.isEmpty() || status == null) return;
        for (SseEmitter emitter : sseEmitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("status")
                    .data(status, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                emitter.complete();
                sseEmitters.remove(emitter);
            }
        }
    }

    @PostMapping("/reanalyze-llm")
    public ResponseEntity<RightmoveScraperService.ScrapeJobStatus> reanalyzeWithLlm(
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "false") boolean onlyFailed) {
        this.cachedSuggestions = null;
        RightmoveScraperService.ScrapeJobStatus status = scraperService.startBackgroundReanalyzeLlm(limit, onlyFailed);
        HttpStatus code = status.accepted() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(code).body(status);
    }

    public record CommuteResearchRequest(
        String targetLocation,
        List<String> transportModes
    ) {}

    @GetMapping("/{listingId}")
    public ResponseEntity<IndexedPropertyEntity> getPropertyDetail(@PathVariable String listingId) {
        return repository.findById(listingId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{listingId}/commute-research")
    public OllamaAgentService.CommuteResearchReport researchPropertyCommute(
            @PathVariable String listingId,
            @RequestBody(required = false) CommuteResearchRequest req) {
        IndexedPropertyEntity entity = repository.findById(listingId)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                HttpStatus.NOT_FOUND, "Property not found: " + listingId));
        String target = (req != null && req.targetLocation() != null && !req.targetLocation().isBlank())
            ? req.targetLocation()
            : "Glasgow Central";
        List<String> modes = (req != null && req.transportModes() != null && !req.transportModes().isEmpty())
            ? req.transportModes()
            : List.of("Train", "Subway", "Bus");

        String cacheKey = listingId + ":" + target + ":" + String.join(",", modes);
        return commuteReportCache.get(cacheKey, k -> {
            String dossier = stationService.buildGroundedCommuteDossier(entity, target, modes);
            return ollamaAgent.researchCommuteWithLlm(listingId, target, dossier);
        });
    }

    @GetMapping("/logs")
    public Map<String, Object> getRecentLogs(@RequestParam(defaultValue = "180") int lines) {
        Path logFile = Path.of("logs", "property-agent.log");
        int targetLines = Math.min(500, Math.max(10, lines));
        List<String> tail = readTailLines(logFile, targetLines);
        return Map.of(
            "logFilePath", logFile.toAbsolutePath().toString(),
            "totalIndexedCount", repository.count(),
            "lines", tail
        );
    }

    private static List<String> readTailLines(Path path, int maxLines) {
        if (!Files.exists(path)) return List.of("Log file ./logs/property-agent.log not created yet.");
        File file = path.toFile();
        long fileLength = file.length();
        if (fileLength == 0) return List.of();

        long seekStart = Math.max(0, fileLength - 131072L);
        int readLen = (int) (fileLength - seekStart);
        byte[] buffer = new byte[readLen];

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(seekStart);
            raf.readFully(buffer);
        } catch (Exception e) {
            return List.of("Error reading log tail: " + e.getMessage());
        }

        String chunk = new String(buffer, StandardCharsets.UTF_8);
        String[] split = chunk.split("\r?\n");
        List<String> result = new ArrayList<>();
        int start = (seekStart > 0 && split.length > 1) ? 1 : 0;
        for (int i = start; i < split.length; i++) {
            result.add(split[i]);
        }
        if (result.size() > maxLines) {
            result = result.subList(result.size() - maxLines, result.size());
        }
        return result;
    }

    @GetMapping("/suggestions")
    public List<String> getDynamicSuggestions() {
        if (cachedSuggestions != null) {
            return cachedSuggestions;
        }

        Set<String> baseSuggestions = new LinkedHashSet<>(List.of(
            "Public transport (Train/Subway/Bus) to target <= 25 mins",
            "High frequency public transport (>= 6 services/hr)",
            "Top-tier secondary school catchment (East Ren / Bearsden / Jordanhill / Lenzie)",
            "High-amenity neighbourhood (SIMD decile >= 8)",
            "Nearest station has Free Park & Ride",
            "Station has Category A step-free lift access",
            "Downstairs WC present on ground floor",
            "Master bedroom has attached en-suite",
            "Living room direct access & sightline to garden",
            "Plot allows outward rear kitchen extension into garden",
            "Enclosed child-safe flat rear lawn",
            "South or South-West facing garden",
            "Dedicated utility room",
            "Dedicated home office / study",
            "Driveway for 2+ cars",
            "Quiet cul-de-sac location",
            "Walk-in condition (no major DIY)",
            "Built-in / fitted wardrobes in bedrooms",
            "Under-stairs / integrated storage cupboard",
            "Pantry / larder storage",
            "Floored attic / eaves storage",
            "Garden space for trampoline / kids play area",
            "Open plan kitchen with breakfast bar / island"
        ));

        Map<String, Integer> highlightFreq = new HashMap<>();
        for (String metaJson : repository.findAllMetadataJson()) {
            ExtractedPropertyMetadata meta = null;
            try {
                meta = objectMapper.readValue(metaJson, ExtractedPropertyMetadata.class);
            } catch (Exception ignored) {}
            if (meta != null && meta.extractedHighlights() != null) {
                for (String h : meta.extractedHighlights()) {
                    if (h == null || h.isBlank() || h.length() < 10) continue;
                    String lower = h.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("verified by") || lower.contains("sq ft") || lower.contains("floor area") || lower.contains("floorplan")) {
                        continue;
                    }
                    highlightFreq.merge(h.trim(), 1, Integer::sum);
                }
            }
        }

        highlightFreq.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(35)
            .forEach(e -> baseSuggestions.add(e.getKey()));

        List<String> result = new ArrayList<>(baseSuggestions);
        this.cachedSuggestions = result;
        return result;
    }

    @PostMapping("/cancel-job")
    public ResponseEntity<Map<String, Object>> cancelJob() {
        scraperService.cancelJob();
        return ResponseEntity.ok(Map.of("message", "Background job cancellation requested."));
    }

    @PostMapping("/scrape")
    public ResponseEntity<RightmoveScraperService.ScrapeJobStatus> triggerScrape(@Valid @RequestBody SearchFilterRequest request) {
        this.cachedSuggestions = null;
        String provider = request.portalProvider() != null && !request.portalProvider().isBlank()
            ? request.portalProvider().trim().toLowerCase(Locale.ROOT)
            : "rightmove";
        RightmoveScraperService.ScrapeJobStatus status = scraperService.startBackgroundScrape(
            request.centerLocation(),
            request.radiusMiles(),
            provider,
            request.propertyTypes(),
            request.maxPrice(),
            request.minBedrooms()
        );
        HttpStatus code = status.accepted() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(code).body(status);
    }

    @DeleteMapping("/clear")
    @Transactional
    public ResponseEntity<Map<String, Object>> clearAllProperties(@RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "Pass ?confirm=true to clear database."
            ));
        }
        if (embeddingService.isEmbeddingInProgress()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "deletedCount", 0L,
                "totalIndexedCount", repository.count(),
                "status", "Cannot clear database while vector embedding batch is running. Wait for it to finish."
            ));
        }
        if (!scraperService.tryAcquireMaintenanceLock()) {
            RightmoveScraperService.ScrapeJobStatus active = scraperService.getScrapeJobStatus();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "deletedCount", 0L,
                "totalIndexedCount", active.totalIndexedCount(),
                "status", "Cannot clear database while background fetch/indexing is running (" + active.targetCenter() + ", " + active.targetRadiusMiles() + " mi). Wait for it to finish."
            ));
        }
        try {
            long countBefore = repository.count();
            try {
                jdbcTemplate.update("DELETE FROM property_embeddings");
            } catch (Exception e) {
                log.debug("Skipping property_embeddings delete: {}", e.getMessage());
            }
            repository.deleteAllInBatch();
            metadataCache.clear();
            commuteReportCache.invalidateAll();
            cachedSuggestions = null;
            embeddingService.clearCriteriaBatchCache();
            scraperService.clearAllDownloadedMedia();
            scraperService.resetStateOnDatabaseClear();

            String msg = "Cleared " + countBefore + " properties and local downloaded images from PostgreSQL & disk (shortlist & Home Report notes preserved by listing ID).";
            log.info(msg);
            return ResponseEntity.ok(Map.of(
                "deletedCount", countBefore,
                "totalIndexedCount", 0L,
                "status", msg
            ));
        } finally {
            scraperService.releaseMaintenanceLock();
        }
    }

    @PostMapping("/rank")
    public List<RankedPropertyResponse> filterAndRank(@Valid @RequestBody SearchFilterRequest request) {
        List<String> allowedTypesLower = (request.propertyTypes() == null || request.propertyTypes().isEmpty())
            ? List.of()
            : request.propertyTypes().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();

        int criteriaCount = request.dynamicCriteria() == null ? 0 : request.dynamicCriteria().size();
        List<String> ruleSummaries = request.dynamicCriteria() == null
            ? List.of()
            : request.dynamicCriteria().stream().map(c -> c.rule() + " [" + c.priority() + "]").toList();

        int minFreq = Math.max(0, request.minServicesPerHour());
        String workflowTab = request.workflowFilter() != null ? request.workflowFilter().trim().toUpperCase(Locale.ROOT) : "ALL";
        boolean isDedicatedWorkflowTab = "SHORTLISTED".equals(workflowTab) || "HIDDEN".equals(workflowTab);
        boolean showAllInDb = Boolean.TRUE.equals(request.bypassHardFilters()) || isDedicatedWorkflowTab;

        boolean hasSecondCommuter = request.secondCenterLocation() != null && !request.secondCenterLocation().isBlank();
        List<String> effectiveSecondModes = (request.secondTransportModes() != null && !request.secondTransportModes().isEmpty())
            ? request.secondTransportModes()
            : request.transportModes();
        int maxSecondMins = request.maxSecondCommuteMins() != null && request.maxSecondCommuteMins() > 0
            ? request.maxSecondCommuteMins()
            : 45;
        int maxUpfrontCash = request.maxUpfrontCashGbp() != null ? request.maxUpfrontCashGbp() : 0;

        long totalInDb = repository.count();
        List<IndexedPropertyEntity> candidates = queryCandidatesWithoutLlmLog(
            showAllInDb, allowedTypesLower, request.maxPrice(), request.minBedrooms()
        );

        // FEAT-05: Single batch query for buyer workflow & Home Report state across all candidates (zero N+1)
        List<String> candidateIds = candidates.stream().map(IndexedPropertyEntity::getListingId).toList();
        Map<String, BuyerWorkflowState> workflowMap = buyerWorkflowService.loadStatesForListings(candidateIds);

        List<RankedPropertyResponse> ranked = candidates.stream()
            .filter(p -> {
                BuyerWorkflowState wf = workflowMap.getOrDefault(p.getListingId(), BuyerWorkflowState.empty());
                return switch (workflowTab) {
                    case "SHORTLISTED" -> wf.shortlisted() && !wf.hidden();
                    case "HIDDEN" -> wf.hidden();
                    default -> !wf.hidden();
                };
            })
            .filter(p -> isDedicatedWorkflowTab || allowedTypesLower.isEmpty() || (p.getPropertyType() != null && allowedTypesLower.contains(p.getPropertyType().toLowerCase(Locale.ROOT))))
            .filter(p -> showAllInDb || stationService.calculateDistanceFromCenterMiles(request.centerLocation(), p) <= request.radiusMiles())
            .filter(p -> showAllInDb || p.getPriceGbp() <= request.maxPrice())
            .filter(p -> showAllInDb || p.getBedrooms() >= request.minBedrooms())
            .filter(p -> showAllInDb || !request.requireFreeStationParking() || p.isStationFreeParking() || stationService.hasNearbyFreeParkingStation(p, 3.0))
            .filter(p -> showAllInDb || !request.requireStepFreeCategoryA() || "A".equalsIgnoreCase(p.getStationAccessibilityCategory()) || stationService.hasNearbyCategoryAStation(p, 3.0))
            .map(entity -> {
                ExtractedPropertyMetadata strictMeta = metadataCache.computeIfAbsent(entity.getListingId(), id -> {
                    ExtractedPropertyMetadata heuristic = ollamaAgent.heuristicExtract(entity.getRawDescription());
                    try {
                        if (entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()) {
                            ExtractedPropertyMetadata stored = objectMapper.readValue(entity.getMetadataJson(), ExtractedPropertyMetadata.class);
                            boolean isLlmVerified = "VISION_LLM".equalsIgnoreCase(entity.getMetadataSource())
                                || "TEXT_LLM".equalsIgnoreCase(entity.getMetadataSource())
                                || (entity.getMetadataSource() == null && stored.extractedHighlights() != null
                                    && stored.extractedHighlights().stream().anyMatch(h -> h.startsWith("Verified by Ollama")));
                            if (isLlmVerified) {
                                return stored;
                            } else if (stored.extractedHighlights() != null && !stored.extractedHighlights().isEmpty()) {
                                Set<String> mergedHighlights = new LinkedHashSet<>(heuristic.extractedHighlights());
                                mergedHighlights.addAll(stored.extractedHighlights());
                                return new ExtractedPropertyMetadata(
                                    heuristic.hasDownstairsWc(),
                                    heuristic.hasMasterEnsuite(),
                                    heuristic.totalBathrooms(),
                                    heuristic.livingRoomLocation(),
                                    heuristic.directGardenAccessFromLiving(),
                                    heuristic.gardenSightlineFromSofa(),
                                    heuristic.kitchenLayout(),
                                    heuristic.kitchenExtensionPotentialToRear(),
                                    heuristic.hasUtilityRoom(),
                                    heuristic.hasDedicatedStudyOrOffice(),
                                    heuristic.garageType(),
                                    heuristic.gardenEnclosedForKids(),
                                    heuristic.gardenOrientation(),
                                    heuristic.drivewayCarCapacity(),
                                    heuristic.interiorCondition(),
                                    heuristic.epcRating(),
                                    heuristic.heatingType(),
                                    new ArrayList<>(mergedHighlights)
                                );
                            }
                        }
                    } catch (Exception ignored) {}
                    return heuristic;
                });

                StationProfile defaultStation = stationService.resolveStation(entity.getNearestStationName());
                StationCommuteService.TransitEvaluation transit = stationService.evaluateBestPublicTransport(
                    entity, defaultStation, request.centerLocation(), request.transportModes()
                );
                StationCommuteService.TransitEvaluation secondTransit = hasSecondCommuter
                    ? stationService.evaluateBestPublicTransport(entity, defaultStation, request.secondCenterLocation(), effectiveSecondModes)
                    : null;
                StationProfile effectiveStation = transit.bestStation() != null ? transit.bestStation() : defaultStation;
                BuyerWorkflowState wf = workflowMap.getOrDefault(entity.getListingId(), BuyerWorkflowState.empty());
                double[] resolvedCoords = stationService.resolvePropertyCoordinates(entity);

                return ollamaAgent.evaluateAndScoreProperty(
                    entity, strictMeta, effectiveStation, transit, secondTransit, wf, resolvedCoords, request
                );
            })
            .filter(r -> showAllInDb || r.doorToTargetMins() <= request.maxDoorToCentralCommuteMins())
            .filter(r -> showAllInDb || r.servicesPerHourPeak() >= minFreq)
            .filter(r -> showAllInDb || !hasSecondCommuter || r.secondCommute() == null || r.secondCommute().doorToTargetMins() <= maxSecondMins)
            .filter(r -> showAllInDb || maxUpfrontCash <= 0 || r.costBreakdown() == null || r.costBreakdown().totalUpfrontCashGbp() <= maxUpfrontCash)
            .sorted(Comparator.comparingInt(RankedPropertyResponse::overallScore).reversed()
                .thenComparingInt(RankedPropertyResponse::doorToTargetMins)
                .thenComparing(Comparator.comparingInt(RankedPropertyResponse::servicesPerHourPeak).reversed())
                .thenComparingInt(RankedPropertyResponse::priceGbp))
            .collect(Collectors.toList());

        String topSummary = ranked.stream()
            .limit(3)
            .map(r -> r.address() + " (" + r.overallScore() + "/100 via " + r.bestTransitMode() + " " + r.doorToTargetMins() + "m)")
            .collect(Collectors.joining(", "));

        log.info("Re-rank executed: tab={}, target='{}'{}, modes={}, minFreq={}/hr, {} active criteria {}{} -> {}/{} DB properties displayed. Top 3: [{}]",
            workflowTab,
            request.centerLocation(),
            hasSecondCommuter ? " + PartnerB='" + request.secondCenterLocation() + "'" : "",
            request.transportModes(),
            minFreq,
            criteriaCount,
            ruleSummaries,
            showAllInDb ? " (Bypassing hard cutoffs)" : "",
            ranked.size(),
            totalInDb,
            topSummary);

        return ranked;
    }

    /**
     * IMP-01 & REF-04: Lightweight projection query excluding bulky llm_response_log (20-40KB/row),
     * executed via Spring JdbcClient + declarative CANDIDATE_ROW_MAPPER.
     */
    private List<IndexedPropertyEntity> queryCandidatesWithoutLlmLog(
            boolean showAllInDb,
            List<String> allowedTypesLower,
            int maxPrice,
            int minBedrooms) {
        StringBuilder sql = new StringBuilder("""
            SELECT listing_id, title, address, property_type, price_gbp, previous_price_gbp,
                   price_qualifier, price_changed_at, last_seen_at, bedrooms,
                   distance_from_glasgow_central_miles, latitude, longitude,
                   rightmove_url, thumbnail_url, floorplan_url, raw_description,
                   nearest_station_name, distance_to_station_miles, fastest_train_mins,
                   estimated_door_to_central_mins, station_free_parking, station_accessibility_category,
                   nearest_bus_stop_name, distance_to_bus_stop_miles, walk_to_bus_stop_mins,
                   bus_frequency_peak_per_hour, bus_route_refs, bus_primary_operator, bus_osm_verified,
                   has_downstairs_wc, has_master_ensuite, direct_garden_access_from_living,
                   metadata_json, all_images_json, all_floorplans_json, downloaded_image_count,
                   metadata_source, metadata_model, analyzed_at, indexed_at, '' AS llm_response_log
            FROM indexed_properties
            WHERE 1 = 1
        """);
        List<Object> params = new ArrayList<>();
        if (!showAllInDb && !allowedTypesLower.isEmpty() && allowedTypesLower.size() < 5) {
            String placeholders = allowedTypesLower.stream().map(t -> "?").collect(Collectors.joining(", "));
            sql.append(" AND LOWER(property_type) IN (").append(placeholders).append(")");
            params.addAll(allowedTypesLower);
        }
        if (!showAllInDb) {
            if (maxPrice > 0) {
                sql.append(" AND price_gbp <= ?");
                params.add(maxPrice);
            }
            if (minBedrooms > 1) {
                sql.append(" AND bedrooms >= ?");
                params.add(minBedrooms);
            }
        }

        return jdbcClient.sql(sql.toString())
            .params(params)
            .query(CANDIDATE_ROW_MAPPER)
            .list();
    }

    @GetMapping("/semantic-search")
    public ResponseEntity<?> semanticSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "0.50") double minSimilarity,
            @RequestParam(defaultValue = "20") int limit) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query parameter 'q' is required"));
        }
        List<PropertyEmbeddingService.SemanticSearchResult> results = embeddingService.semanticSearch(q, minSimilarity, limit);
        return ResponseEntity.ok(Map.of(
                "query", q,
                "minSimilarity", minSimilarity,
                "count", results.size(),
                "results", results
        ));
    }

    @GetMapping("/embedding-stats")
    public PropertyEmbeddingService.EmbeddingStats getEmbeddingStats() {
        return embeddingService.getEmbeddingStats();
    }

    @PostMapping("/embed-all")
    public ResponseEntity<?> triggerEmbedAll(@RequestParam(defaultValue = "false") boolean force) {
        if (scraperService.isJobRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "BUSY",
                    "message", "Wait for active scrape/re-analysis job to complete before syncing embeddings."
            ));
        }
        if (embeddingService.isEmbeddingInProgress()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "ALREADY_RUNNING",
                    "message", "Vector embedding batch process is already running."
            ));
        }
        Thread.ofVirtual().name("bg-property-embedder").start(() -> {
            try {
                embeddingService.embedAllProperties(force);
            } catch (Exception e) {
                log.error("Batch embedding task error: {}", e.getMessage(), e);
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "status", "STARTED",
                "message", "Vector embedding batch process started in background."
        ));
    }
}
