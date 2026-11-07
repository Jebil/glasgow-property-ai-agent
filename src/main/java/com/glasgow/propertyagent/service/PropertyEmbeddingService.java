package com.glasgow.propertyagent.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.repository.IndexedPropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PropertyEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PropertyEmbeddingService.class);

    private static final Pattern PHOTO_LOG_PATTERN = Pattern.compile(
            "^(Photo|Floorplan)\\s+\\[(\\d+/\\d+)\\]\\s*\\(([^)]+)\\):\\s*(.*)$",
            Pattern.MULTILINE
    );

    private final JdbcTemplate jdbcTemplate;
    private final IndexedPropertyRepository propertyRepository;
    private final OllamaApiClient ollamaClient;
    private final String embeddingModel;

    // SIMP-04: Bounded Caffeine cache for batch criteria vector evaluation (rule -> (listingId -> match))
    private final Cache<String, Map<String, PropertyEmbeddingMatch>> criteriaBatchCache = Caffeine.newBuilder()
            .maximumSize(250)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public record SemanticSearchResult(
            String listingId,
            String title,
            String address,
            int priceGbp,
            int bedrooms,
            String thumbnailUrl,
            String rightmoveUrl,
            String chunkType,
            String chunkText,
            String imageRef,
            double similarityScore,
            String nearestStationName,
            int estimatedDoorToCentralMins
    ) {}

    public record PropertyEmbeddingMatch(
            double similarity,
            String chunkType,
            String chunkText,
            String imageRef
    ) {}

    public record EmbeddingStats(
            long totalProperties,
            long embeddedProperties,
            long totalChunks,
            String embeddingModel,
            boolean modelOnline,
            boolean embeddingInProgress,
            int batchProcessed,
            int batchTotal
    ) {}

    private final AtomicBoolean embeddingInProgress = new AtomicBoolean(false);
    private final AtomicInteger batchProcessed = new AtomicInteger(0);
    private final AtomicInteger batchTotal = new AtomicInteger(0);

    public record ChunkRecord(
            String chunkType,
            String chunkText,
            String imageRef
    ) {}

    public PropertyEmbeddingService(
            JdbcTemplate jdbcTemplate,
            IndexedPropertyRepository propertyRepository,
            ObjectMapper objectMapper,
            String ollamaBaseUrl,
            String embeddingModel) {
        this(jdbcTemplate, propertyRepository, new OllamaApiClient(objectMapper, ollamaBaseUrl), embeddingModel);
    }

    @Autowired
    public PropertyEmbeddingService(
            JdbcTemplate jdbcTemplate,
            IndexedPropertyRepository propertyRepository,
            OllamaApiClient ollamaClient,
            @Value("${property-agent.ollama.embedding-model:nomic-embed-text}") String embeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.propertyRepository = propertyRepository;
        this.ollamaClient = ollamaClient;
        this.embeddingModel = embeddingModel;
    }

    public boolean isEmbeddingInProgress() {
        return embeddingInProgress.get();
    }

    public boolean isModelOnline() {
        return ollamaClient.isModelInstalled(embeddingModel);
    }

    public float[] generateQueryEmbedding(String query) {
        if (query == null || query.isBlank()) return null;
        String prefixed = query.startsWith("search_query:") ? query : "search_query: " + query.trim();
        return generateEmbedding(prefixed);
    }

    /**
     * N11 / IMP-06: Evaluates a criteria rule using hybrid pgvector cosine similarity + PostgreSQL
     * full-text lexical ranking (ts_rank_cd + websearch_to_tsquery) across ALL properties in ONE single SQL query,
     * caching the map in Caffeine so /rank with custom rules runs in <20ms.
     */
    public Map<String, PropertyEmbeddingMatch> findBestMatchesForCriteriaBatch(String rule) {
        if (rule == null || rule.isBlank() || jdbcTemplate == null) return Map.of();
        return criteriaBatchCache.get(rule, q -> {
            float[] queryVec = generateQueryEmbedding(q);
            if (queryVec == null) return Map.of();
            String vecStr = vectorToString(queryVec);

            String sql = """
                SELECT DISTINCT ON (sub.listing_id) sub.listing_id, sub.chunk_type, sub.chunk_text, sub.image_ref, sub.similarity
                FROM (
                    SELECT pe.listing_id, pe.chunk_type, pe.chunk_text, pe.image_ref,
                           LEAST(1.0, (1 - (pe.embedding <=> ?::vector))
                               + 0.08 * LEAST(1.0, ts_rank_cd(to_tsvector('english', pe.chunk_text), websearch_to_tsquery('english', ?)) * 2.5)) AS similarity
                    FROM property_embeddings pe
                ) sub
                ORDER BY sub.listing_id, sub.similarity DESC
            """;
            try {
                Map<String, PropertyEmbeddingMatch> map = new HashMap<>();
                jdbcTemplate.query(sql, rs -> {
                    String lid = rs.getString("listing_id");
                    map.put(lid, new PropertyEmbeddingMatch(
                        rs.getDouble("similarity"),
                        rs.getString("chunk_type"),
                        rs.getString("chunk_text"),
                        rs.getString("image_ref")
                    ));
                }, vecStr, q);
                return map;
            } catch (Exception e) {
                log.warn("Batch criteria query failed for '{}': {}", q, e.getMessage());
                return Map.of();
            }
        });
    }

    public void clearCriteriaBatchCache() {
        criteriaBatchCache.invalidateAll();
    }

    public float[] generateEmbedding(String text) {
        if (text == null || text.isBlank()) return null;
        List<float[]> res = generateBatchEmbeddings(List.of(text));
        return (res != null && !res.isEmpty()) ? res.get(0) : null;
    }

    public List<float[]> generateBatchEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<String> sanitized = texts.stream()
                .map(t -> t.replaceAll("[\\r\\n]+", " ").trim())
                .filter(t -> !t.isBlank())
                .map(t -> t.length() > 2000 ? t.substring(0, 2000) : t)
                .toList();
        if (sanitized.isEmpty()) return List.of();
        return ollamaClient.embedBatch(embeddingModel, sanitized);
    }

    public int embedProperty(IndexedPropertyEntity entity) {
        if (entity == null || entity.getListingId() == null || jdbcTemplate == null) return 0;
        String listingId = entity.getListingId();

        List<ChunkRecord> chunks = extractPropertyChunks(entity);
        if (chunks.isEmpty()) {
            log.warn("[{}] embedProperty found 0 chunks to embed for listing '{}'", listingId, entity.getTitle());
            return 0;
        }

        List<String> chunkTexts = chunks.stream()
                .map(c -> c.chunkText().startsWith("search_document:") ? c.chunkText() : "search_document: " + c.chunkText())
                .toList();
        List<float[]> embeddings = generateBatchEmbeddings(chunkTexts);

        if (embeddings.size() != chunks.size()) {
            log.warn("[{}] Generated {} embeddings for {} chunks - embedding count mismatch",
                    listingId, embeddings.size(), chunks.size());
            return 0;
        }

        try {
            jdbcTemplate.update("DELETE FROM property_embeddings WHERE listing_id = ?", listingId);

            String sql = "INSERT INTO property_embeddings (listing_id, chunk_type, chunk_text, image_ref, embedding) VALUES (?, ?, ?, ?, ?::vector)";
            List<Object[]> batchArgs = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkRecord chunk = chunks.get(i);
                float[] vec = embeddings.get(i);
                String vecStr = vectorToString(vec);
                batchArgs.add(new Object[]{listingId, chunk.chunkType(), chunk.chunkText(), chunk.imageRef(), vecStr});
            }

            jdbcTemplate.batchUpdate(sql, batchArgs);
            clearCriteriaBatchCache();
            log.info("[{}] Successfully indexed {} vector chunks into pgvector", listingId, chunks.size());
            return chunks.size();
        } catch (Exception e) {
            log.error("[{}] Error inserting embeddings into PostgreSQL: {}", listingId, e.getMessage(), e);
            return 0;
        }
    }

    public List<ChunkRecord> extractPropertyChunks(IndexedPropertyEntity entity) {
        List<ChunkRecord> list = new ArrayList<>();

        StringBuilder overview = new StringBuilder();
        overview.append(entity.getTitle() != null ? entity.getTitle() : "Property").append(". ");
        overview.append("Located at: ").append(entity.getAddress()).append(". ");
        overview.append("Bedrooms: ").append(entity.getBedrooms()).append(", Type: ").append(entity.getPropertyType()).append(". ");
        overview.append("Price: £").append(entity.getPriceGbp()).append(" (").append(entity.getPriceQualifier() != null ? entity.getPriceQualifier() : "").append("). ");
        if (entity.getNearestStationName() != null && !entity.getNearestStationName().isBlank()) {
            overview.append("Nearest station: ").append(entity.getNearestStationName())
                    .append(" (~").append(entity.getEstimatedDoorToCentralMins()).append(" mins to Glasgow Central). ");
        }
        list.add(new ChunkRecord("OVERVIEW", overview.toString(), null));

        String llmLog = entity.getLlmResponseLog();
        if (llmLog != null && !llmLog.isBlank()) {
            Matcher m = PHOTO_LOG_PATTERN.matcher(llmLog);
            while (m.find()) {
                String type = m.group(1).toUpperCase();
                String filename = m.group(3).trim();
                String note = m.group(4).trim();

                if (!note.isBlank() && note.length() > 15
                        && !note.toLowerCase().startsWith("inspected property photo:")
                        && !note.toLowerCase().contains("without detailed visual notes")) {
                    String prefix = type.equals("PHOTO") ? "Visual Photo Inspection: " : "Architectural Floorplan Inspection: ";
                    list.add(new ChunkRecord(type, prefix + note, filename));
                }
            }
        }

        String rawDesc = entity.getRawDescription();
        if (rawDesc != null && !rawDesc.isBlank()) {
            String[] paragraphs = rawDesc.split("(\\r?\\n){2,}");
            for (String p : paragraphs) {
                String cleaned = p.replaceAll("[\\r\\n]+", " ").trim();
                if (cleaned.length() > 40 && !cleaned.toLowerCase().contains("epc") && !cleaned.toLowerCase().contains("council tax band")) {
                    if (cleaned.length() > 600) cleaned = cleaned.substring(0, 600);
                    list.add(new ChunkRecord("DESCRIPTION", cleaned, null));
                }
            }
        }

        Set<String> seen = new HashSet<>();
        List<ChunkRecord> deduped = new ArrayList<>();
        for (ChunkRecord c : list) {
            String norm = c.chunkText().toLowerCase().trim();
            if (seen.add(norm)) {
                deduped.add(c);
            }
        }

        return deduped;
    }

    public PropertyEmbeddingMatch findBestMatchForProperty(String listingId, String query, float[] precomputedQueryVec) {
        if (jdbcTemplate == null) return null;
        float[] queryVec = precomputedQueryVec != null ? precomputedQueryVec : generateQueryEmbedding(query);
        if (queryVec == null) return null;

        String vecStr = vectorToString(queryVec);
        String lexicalQuery = (query == null) ? "" : query.trim();
        String sql = """
            SELECT sub.chunk_type, sub.chunk_text, sub.image_ref, sub.similarity
            FROM (
                SELECT chunk_type, chunk_text, image_ref,
                       LEAST(1.0, (1 - (embedding <=> ?::vector))
                           + 0.08 * LEAST(1.0, ts_rank_cd(to_tsvector('english', chunk_text), websearch_to_tsquery('english', ?)) * 2.5)) AS similarity
                FROM property_embeddings
                WHERE listing_id = ?
            ) sub
            ORDER BY sub.similarity DESC
            LIMIT 1
        """;

        try {
            return jdbcTemplate.query(sql, rs -> {
                if (rs.next()) {
                    return new PropertyEmbeddingMatch(
                            rs.getDouble("similarity"),
                            rs.getString("chunk_type"),
                            rs.getString("chunk_text"),
                            rs.getString("image_ref")
                    );
                }
                return null;
            }, vecStr, lexicalQuery, listingId);
        } catch (Exception e) {
            log.warn("[{}] Error finding best match for property: {}", listingId, e.getMessage());
            return null;
        }
    }

    public List<SemanticSearchResult> semanticSearch(String query, double minSimilarity, int limit) {
        if (jdbcTemplate == null) return List.of();
        float[] queryVec = generateQueryEmbedding(query);
        if (queryVec == null) return List.of();

        String vecStr = vectorToString(queryVec);
        String lexicalQuery = query == null ? "" : query.trim();
        int maxResults = Math.min(Math.max(1, limit), 50);

        String sql = """
            SELECT sub.listing_id, sub.chunk_type, sub.chunk_text, sub.image_ref, sub.similarity,
                   ip.title, ip.address, ip.price_gbp, ip.bedrooms, ip.thumbnail_url, ip.rightmove_url,
                   ip.nearest_station_name, ip.estimated_door_to_central_mins
            FROM (
                SELECT pe.listing_id, pe.chunk_type, pe.chunk_text, pe.image_ref,
                       LEAST(1.0, (1 - (pe.embedding <=> ?::vector))
                           + 0.08 * LEAST(1.0, ts_rank_cd(to_tsvector('english', chunk_text), websearch_to_tsquery('english', ?)) * 2.5)) AS similarity
                FROM property_embeddings pe
            ) sub
            JOIN indexed_properties ip ON sub.listing_id = ip.listing_id
            WHERE sub.similarity >= ?
            ORDER BY sub.similarity DESC
            LIMIT ?
        """;

        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> new SemanticSearchResult(
                    rs.getString("listing_id"),
                    rs.getString("title"),
                    rs.getString("address"),
                    rs.getInt("price_gbp"),
                    rs.getInt("bedrooms"),
                    rs.getString("thumbnail_url"),
                    rs.getString("rightmove_url"),
                    rs.getString("chunk_type"),
                    rs.getString("chunk_text"),
                    rs.getString("image_ref"),
                    rs.getDouble("similarity"),
                    rs.getString("nearest_station_name"),
                    rs.getInt("estimated_door_to_central_mins")
            ), vecStr, lexicalQuery, minSimilarity, maxResults);
        } catch (Exception e) {
            log.error("Semantic search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public int embedAllProperties(boolean forceReembed) {
        if (jdbcTemplate == null || propertyRepository == null) return 0;
        if (!embeddingInProgress.compareAndSet(false, true)) {
            log.info("Batch embedding already in progress; ignoring duplicate trigger.");
            return 0;
        }
        try {
            Set<String> alreadyEmbedded = forceReembed
                ? Set.of()
                : new HashSet<>(jdbcTemplate.queryForList("SELECT DISTINCT listing_id FROM property_embeddings", String.class));
            List<IndexedPropertyEntity> all = propertyRepository.findAll();
            batchTotal.set(all.size());
            batchProcessed.set(0);
            int count = 0;
            for (IndexedPropertyEntity entity : all) {
                try {
                    if (!forceReembed && alreadyEmbedded.contains(entity.getListingId())) {
                        continue;
                    }
                    int chunks = embedProperty(entity);
                    if (chunks > 0) count++;
                } finally {
                    batchProcessed.incrementAndGet();
                }
            }
            clearCriteriaBatchCache();
            log.info("Batch embedding complete: processed {} properties", count);
            return count;
        } finally {
            embeddingInProgress.set(false);
        }
    }

    public EmbeddingStats getEmbeddingStats() {
        long totalProps = 0;
        long embeddedProps = 0;
        long totalChunks = 0;
        try {
            if (propertyRepository != null) {
                totalProps = propertyRepository.count();
            }
            if (jdbcTemplate != null) {
                Long emb = jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT listing_id) FROM property_embeddings",
                        Long.class
                );
                Long chunks = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM property_embeddings",
                        Long.class
                );
                embeddedProps = emb != null ? emb : 0;
                totalChunks = chunks != null ? chunks : 0;
            }
        } catch (Exception e) {
            log.debug("Embedding stats query fallback: {}", e.getMessage());
        }
        return new EmbeddingStats(
                totalProps,
                embeddedProps,
                totalChunks,
                embeddingModel,
                isModelOnline(),
                embeddingInProgress.get(),
                batchProcessed.get(),
                batchTotal.get()
        );
    }

    private String vectorToString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
