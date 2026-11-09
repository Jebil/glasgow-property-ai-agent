package com.glasgow.propertyagent.service.ai;

import com.glasgow.propertyagent.service.scraping.RightmoveScraperService;
import com.glasgow.propertyagent.service.app.ReadOnlySqlExecutor;
import com.glasgow.propertyagent.service.app.BuyerWorkflowService;

import com.glasgow.propertyagent.model.BuyerWorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI chat assistant for custom property search, dynamic database querying,
 * system log checking, buyer workflow automation, and background scrape control.
 */
@Service
public class ChatAssistantService {

    private static final Logger log = LoggerFactory.getLogger(ChatAssistantService.class);

    static final int MAX_TOOL_ROUNDS = 5;
    static final int MAX_HISTORY_MESSAGES = 12;
    static final Set<String> VALID_PROPERTY_TYPES = Set.of("Detached", "Semi-Detached", "Terraced", "Bungalow", "Flat");
    static final Set<String> VALID_TRANSPORT_MODES = Set.of("Train", "Subway", "Bus");
    static final Set<String> VALID_PRIORITIES = Set.of("MUST_HAVE", "HIGH", "MEDIUM", "NICE_TO_HAVE");
    static final Set<String> VALID_VIEWING_STATUSES = Set.of("NONE", "BOOKED", "VISITED", "OFFER_PLACED");

    public record ChatMessage(String role, String content) {}

    public record ChatRequest(
        List<ChatMessage> messages,
        Map<String, Object> filters,
        List<Map<String, Object>> topResults
    ) {}

    public record ChatResponse(
        String reply,
        List<Map<String, Object>> actions,
        List<String> toolTrace,
        String thinking,
        String model
    ) {
        public ChatResponse(String reply, List<Map<String, Object>> actions, List<String> toolTrace, String model) {
            this(reply, actions, toolTrace, "", model);
        }
    }

    private final OllamaApiClient ollama;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;
    private final PropertyEmbeddingService embeddingService;
    private final ReadOnlySqlExecutor sqlExecutor;
    private final WebSearchClient webClient;
    private final RightmoveScraperService scraperService;
    private final BuyerWorkflowService buyerWorkflowService;
    private final String chatModel;

    public ChatAssistantService(
            OllamaApiClient ollama,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            PropertyEmbeddingService embeddingService,
            ReadOnlySqlExecutor sqlExecutor,
            WebSearchClient webClient,
            String chatModel) {
        this(ollama, objectMapper, jdbcTemplate, embeddingService, sqlExecutor, webClient, null, null, chatModel);
    }

    @Autowired
    public ChatAssistantService(
            OllamaApiClient ollama,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            PropertyEmbeddingService embeddingService,
            ReadOnlySqlExecutor sqlExecutor,
            WebSearchClient webClient,
            @Autowired(required = false) RightmoveScraperService scraperService,
            @Autowired(required = false) BuyerWorkflowService buyerWorkflowService,
            @Value("${property-agent.ollama.chat-model:${property-agent.ollama.ranking-model:gemma4:e4b}}") String chatModel) {
        this.ollama = ollama;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.jdbcClient = jdbcTemplate != null ? JdbcClient.create(jdbcTemplate) : null;
        this.embeddingService = embeddingService;
        this.sqlExecutor = sqlExecutor;
        this.webClient = webClient;
        this.scraperService = scraperService;
        this.buyerWorkflowService = buyerWorkflowService;
        this.chatModel = chatModel;
    }

    // ------------------------------------------------------------------ chat loop

    public ChatResponse chat(ChatRequest request) {
        List<Map<String, Object>> actions = new ArrayList<>();
        List<String> trace = new ArrayList<>();

        List<Map<String, Object>> convo = new ArrayList<>();
        convo.add(Map.of("role", "system", "content", buildSystemPrompt(request)));
        List<ChatMessage> history = request.messages() == null ? List.of() : request.messages();
        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (ChatMessage m : history.subList(from, history.size())) {
            if (m == null || m.content() == null || m.content().isBlank()) continue;
            String role = "assistant".equalsIgnoreCase(m.role()) ? "assistant" : "user";
            convo.add(Map.of("role", role, "content", m.content()));
        }
        if (convo.size() == 1) {
            return new ChatResponse("Ask me anything about the properties, your current filters, buying in Scotland, or managing scrapes & logs.",
                actions, trace, chatModel);
        }

        boolean useTools = true;
        String notice = "";
        try {
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                JsonNode resp;
                try {
                    resp = ollama.postChatJson(buildOllamaBody(convo, useTools));
                } catch (Exception e) {
                    if (useTools && looksLikeToolsUnsupported(e)) {
                        log.warn("Model '{}' rejected tools; continuing as plain chat: {}", chatModel, e.getMessage());
                        useTools = false;
                        notice = "(This model build does not support tool calling, so I can't run app tools right now.)\n\n";
                        resp = ollama.postChatJson(buildOllamaBody(convo, false));
                    } else {
                        throw e;
                    }
                }

                JsonNode msg = resp.path("message");
                String rawThinking = msg.path("thinking").isTextual() ? msg.path("thinking").asString("")
                    : msg.path("reasoning_content").isTextual() ? msg.path("reasoning_content").asString("") : "";
                JsonNode toolCalls = msg.path("tool_calls");
                if (!useTools || !toolCalls.isArray() || toolCalls.size() == 0) {
                    String reply = msg.path("content").asString("").trim();
                    String thinking = rawThinking;
                    if (reply.contains("<think>") && reply.contains("</think>")) {
                        int start = reply.indexOf("<think>");
                        int end = reply.indexOf("</think>");
                        if (end > start) {
                            String extracted = reply.substring(start + 7, end).trim();
                            if (thinking.isBlank()) thinking = extracted;
                            reply = (reply.substring(0, start) + reply.substring(end + 8)).trim();
                        }
                    }
                    if (reply.isEmpty()) reply = "I couldn't produce an answer — please try rephrasing.";
                    return new ChatResponse(notice + reply, actions, trace, thinking, chatModel);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> assistantMsg = objectMapper.convertValue(msg, Map.class);
                convo.add(assistantMsg);

                for (JsonNode call : toolCalls) {
                    String name = call.path("function").path("name").asString("");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = call.path("function").path("arguments").isObject()
                        ? objectMapper.convertValue(call.path("function").path("arguments"), Map.class)
                        : new LinkedHashMap<>();
                    trace.add(name + " " + safeJson(args));
                    String result = executeTool(name, args, actions);
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_name", name);
                    toolMsg.put("content", result);
                    convo.add(toolMsg);
                }
            }
            return new ChatResponse(notice + "I used several tools but couldn't finish — please narrow the question.",
                actions, trace, chatModel);
        } catch (Exception e) {
            log.warn("Chat assistant failed: {}", e.getMessage());
            return new ChatResponse("The local AI model is unavailable right now (" + e.getMessage()
                + "). Check that Ollama is running.", actions, trace, chatModel);
        }
    }

    private Map<String, Object> buildOllamaBody(List<Map<String, Object>> convo, boolean withTools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("messages", convo);
        body.put("stream", false);
        body.put("think", false);
        body.put("keep_alive", "30m");
        body.put("options", Map.of("num_ctx", 8192, "temperature", 0.3));
        if (withTools) body.put("tools", toolDefinitions());
        return body;
    }

    static boolean looksLikeToolsUnsupported(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("does not support tools") || (m.contains("tools") && m.contains("400"));
    }

    public String buildSystemPrompt(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            You are the AI assistant inside the 'Glasgow Property AI Agent', a local app that ranks houses for sale \
            around Greater Glasgow for a family buyer. Today is %s.
            
            RULES & CAPABILITIES:
            1. Property data comes ONLY from the local database via run_sql / search_properties / get_property / get_property_evidence. Never invent listings, prices, or addresses.
            2. When the user asks WHY or HOW a feature was marked (e.g. "how did you mark it as offstreet parking when property description doesn't say that", "where is the downstairs WC", "how do you know the garden is south facing"):
               - Call get_property_evidence for that listing_id.
               - Inspect metadataSource ('VISION_LLM', 'TEXT_LLM', or 'HEURISTIC'), metadataModel, extractedFeatures, and visionAndReasoningTranscript.
               - Explain clearly whether the Vision LLM (gemma) analyzed the listing photos and floorplan images directly, whether it was identified via floorplan layout or heuristic regex patterns, or whether it was found in the portal's key features.
            3. Full Application Access:
               - To launch a portal scrape for newly listed homes, call trigger_fetch.
               - To check running scrape progress, call get_job_status. To cancel it, call cancel_job.
               - To check recent system logs or error traces, call get_logs.
               - To shortlist, hide, add private buyer notes, or record viewing status, call update_buyer_workflow.
               - To pop open a property's detail/floorplan modal in the user's browser, call open_property_in_ui.
               - To clear or reset the active chat session history in the UI, call clear_session.
               - To change search criteria or filter cutoffs in the UI, call apply_filters.
            4. For external facts (mortgage interest rates, Scottish LBTT/ADS tax rules, current news) call web_search%s, then cite the source URL.
            5. Be concise (a short paragraph or bullet points). Format prices in GBP (£).
            """.formatted(
                LocalDate.now(),
                webClient != null && webClient.isEnabled() ? " (and fetch_webpage for details)" : " (currently disabled)"
            ));

        if (sqlExecutor != null && sqlExecutor.isAvailable()) {
            sb.append("""

                DATABASE ACCESS: you can query the whole PostgreSQL database read-only with run_sql (one SELECT per call). \
                Prefer run_sql for counts, averages, price drops (previous_price_gbp > price_gbp), comparisons and any custom \
                filter; use search_properties for fuzzy text/semantic matching. Write valid PostgreSQL, always include LIMIT, \
                select only the columns you need (avoid the 'embedding', 'llm_response_log' and raw description columns unless \
                essential), and base answers strictly on returned rows. If a query errors, read the error and retry.
                SCHEMA (table(column type, ...)  -- row count):
                """);
            sb.append(sqlExecutor.describeSchema());
        }

        sb.append("\nCURRENT UI FILTERS (what the user currently has applied):\n");
        sb.append(truncate(safeJson(request.filters() == null ? Map.of() : request.filters()), 3500)).append('\n');

        List<Map<String, Object>> top = request.topResults() == null ? List.of() : request.topResults();
        if (!top.isEmpty()) {
            sb.append("\nTOP RANKED RESULTS CURRENTLY SHOWN (best first):\n");
            int n = 0;
            for (Map<String, Object> r : top) {
                if (n++ >= 10) break;
                sb.append("- ").append(truncate(safeJson(r), 400)).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ tools

    public List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(tool("search_properties",
            "Search the LOCAL database of indexed properties for sale. Combine free-text (semantic) with numeric filters.",
            Map.of(
                "query", prop("string", "Free-text, e.g. 'south facing garden near a good primary school'"),
                "max_price", prop("integer", "Maximum price in GBP"),
                "min_price", prop("integer", "Minimum price in GBP"),
                "min_bedrooms", prop("integer", "Minimum bedrooms"),
                "property_types", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Any of Detached, Semi-Detached, Terraced, Bungalow, Flat"),
                "area", prop("string", "Text that must appear in the address, e.g. 'Bearsden'"),
                "limit", prop("integer", "Max results (default 8, max 15)")
            ), List.of()));

        tools.add(tool("get_property",
            "Get full details (description, station, commute, metadata) for one listing by listing_id.",
            Map.of("listing_id", prop("string", "Listing id from search_properties")),
            List.of("listing_id")));

        tools.add(tool("get_property_evidence",
            "Retrieve the full AI vision transcript, visual notes on each photo and floorplan, heuristic reasoning, and verification badge for a property. "
                + "Use this when the user asks WHY or HOW a feature was marked (e.g. 'how did you mark it as offstreet parking when property description doesn't say that', 'where is the downstairs WC', 'why is it south facing').",
            Map.of("listing_id", prop("string", "Listing ID from search_properties or get_property")),
            List.of("listing_id")));

        tools.add(tool("get_logs",
            "Read recent application log lines (from ./logs/property-agent.log). Useful to check errors, scrape events, transit lookups, or background worker activity.",
            Map.of(
                "lines", prop("integer", "Number of lines to return (default 80, max 250)"),
                "filter", prop("string", "Case-insensitive substring filter, e.g. 'error', 'scrape', 'ollama', 'vision', 'parking'")
            ), List.of()));

        Map<String, Object> filterProps = new LinkedHashMap<>();
        filterProps.put("maxPrice", prop("integer", "Max price GBP"));
        filterProps.put("minBedrooms", prop("integer", "Minimum bedrooms"));
        filterProps.put("radiusMiles", prop("number", "Search radius in miles from centerLocation"));
        filterProps.put("centerLocation", prop("string", "Commute target, e.g. 'Glasgow Central'"));
        filterProps.put("maxDoorToCentralCommuteMins", prop("integer", "Max door-to-target commute minutes"));
        filterProps.put("minServicesPerHour", prop("integer", "Minimum peak services per hour"));
        filterProps.put("requireFreeStationParking", prop("boolean", "Require free station Park & Ride"));
        filterProps.put("requireStepFreeCategoryA", prop("boolean", "Require step-free Category A station"));
        filterProps.put("propertyTypes", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Replace selected types (Detached, Semi-Detached, Terraced, Bungalow, Flat)"));
        filterProps.put("transportModes", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Any of Train, Subway, Bus"));
        filterProps.put("addCriteria", Map.of("type", "array", "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "rule", prop("string", "Ranking rule text, e.g. 'South facing garden'"),
                    "priority", prop("string", "MUST_HAVE, HIGH, MEDIUM or NICE_TO_HAVE")),
                "required", List.of("rule")),
            "description", "Ranking criteria to add"));
        filterProps.put("clearCriteria", prop("boolean", "Remove all existing ranking criteria first"));
        tools.add(tool("apply_filters",
            "Change the filters/criteria shown in the app UI and re-run the ranking. Include only the fields to change.",
            filterProps, List.of()));

        if (sqlExecutor != null && sqlExecutor.isAvailable()) {
            tools.add(tool("run_sql",
                "Run ONE read-only PostgreSQL SELECT (or WITH ... SELECT) against the full app database using the schema "
                    + "given in the system prompt. Use for aggregates, comparisons, price drops, custom filters and analytics. "
                    + "Always add LIMIT. Returns columns and rows (max 100). If it errors, fix the SQL and retry.",
                Map.of("sql", prop("string", "A single SELECT statement, no trailing semicolon")),
                List.of("sql")));
        }

        if (scraperService != null) {
            tools.add(tool("trigger_fetch",
                "Trigger a background scrape/fetch for new property listings from Rightmove, OnTheMarket, or both portals. Results are saved into PostgreSQL.",
                Map.of(
                    "center_location", prop("string", "Target center, e.g. 'Glasgow Central' (default: 'Glasgow Central')"),
                    "radius_miles", prop("number", "Search radius in miles (default: 15.0)"),
                    "portal_provider", prop("string", "'all', 'rightmove', or 'onthemarket' (default: 'all')"),
                    "property_types", Map.of("type", "array", "items", Map.of("type", "string"), "description", "List of types (Detached, Semi-Detached, Bungalow)"),
                    "max_price", prop("integer", "Max price in GBP (0 = no cap)"),
                    "min_bedrooms", prop("integer", "Minimum bedrooms (0 = any)")
                ), List.of()));

            tools.add(tool("get_job_status",
                "Check the status of the currently running or last completed background scrape/indexing job.",
                Map.of(), List.of()));

            tools.add(tool("cancel_job",
                "Cancel the currently running background scrape job.",
                Map.of(), List.of()));
        }

        if (buyerWorkflowService != null) {
            tools.add(tool("update_buyer_workflow",
                "Update buyer state for a property: shortlist, hide, set viewing status ('NONE', 'BOOKED', 'VISITED', 'OFFER_PLACED'), save private notes, or update home report valuation / floor area / council tax band.",
                Map.of(
                    "listing_id", prop("string", "Target property listing_id (required)"),
                    "shortlisted", prop("boolean", "True to shortlist, false to remove from shortlist"),
                    "hidden", prop("boolean", "True to hide listing from search results"),
                    "viewing_status", prop("string", "Viewing status: 'NONE', 'BOOKED', 'VISITED', or 'OFFER_PLACED'"),
                    "notes", prop("string", "Private buyer notes on this listing"),
                    "home_report_valuation_gbp", prop("integer", "Home Report valuation in GBP"),
                    "floor_area_sqm", prop("number", "Floor area in square meters"),
                    "council_tax_band", prop("string", "Council tax band (A-H)")
                ), List.of("listing_id")));
        }

        tools.add(tool("open_property_in_ui",
            "Instruct the user's web browser to immediately open the property detail & floorplan modal for a specific listing.",
            Map.of("listing_id", prop("string", "Listing ID")),
            List.of("listing_id")));

        tools.add(tool("clear_session",
            "Clear or reset the current AI chat session and conversation history in the user interface. Use when the user asks to clear session, reset chat, start over, or forget previous conversation.",
            Map.of(), List.of()));

        if (webClient != null && webClient.isEnabled()) {
            tools.add(tool("web_search",
                "Search the internet (UK results) for current facts such as interest rates, LBTT/ADS rules, schools, area info.",
                Map.of("query", prop("string", "Search query")), List.of("query")));
            tools.add(tool("fetch_webpage",
                "Fetch and read the text of a public web page (use a URL returned by web_search).",
                Map.of("url", prop("string", "http(s) URL")), List.of("url")));
        }
        return tools;
    }

    public String executeTool(String name, Map<String, Object> args, List<Map<String, Object>> actions) {
        try {
            return switch (name == null ? "" : name) {
                case "search_properties" -> toolSearchProperties(args);
                case "get_property" -> toolGetProperty(args);
                case "get_property_evidence" -> toolGetPropertyEvidence(args);
                case "get_logs" -> toolGetLogs(args);
                case "apply_filters" -> toolApplyFilters(args, actions);
                case "run_sql" -> toolRunSql(args);
                case "trigger_fetch" -> toolTriggerFetch(args, actions);
                case "get_job_status" -> toolGetJobStatus(args);
                case "cancel_job" -> toolCancelJob(args);
                case "update_buyer_workflow" -> toolUpdateBuyerWorkflow(args, actions);
                case "open_property_in_ui" -> toolOpenPropertyInUi(args, actions);
                case "clear_session" -> toolClearSession(args, actions);
                case "web_search" -> toolWebSearch(args);
                case "fetch_webpage" -> toolFetchWebpage(args);
                default -> "{\"error\":\"Unknown tool: " + name + "\"}";
            };
        } catch (Exception e) {
            log.warn("Chat tool '{}' failed: {}", name, e.getMessage());
            return "{\"error\":" + safeJson(String.valueOf(e.getMessage())) + "}";
        }
    }

    private String toolSearchProperties(Map<String, Object> args) {
        if (jdbcClient == null) return "{\"error\":\"Database unavailable\"}";
        String query = str(args.get("query"));
        int limit = Math.min(15, Math.max(1, intOr(args.get("limit"), 8)));

        List<String> semanticIds = new ArrayList<>();
        if (!query.isBlank() && embeddingService != null) {
            try {
                Set<String> ids = new LinkedHashSet<>();
                for (PropertyEmbeddingService.SemanticSearchResult r : embeddingService.semanticSearch(query, 0.45, 40)) {
                    ids.add(r.listingId());
                }
                semanticIds.addAll(ids);
            } catch (Exception e) {
                log.debug("Semantic search unavailable for chat: {}", e.getMessage());
            }
        }

        StringBuilder sql = new StringBuilder("""
            SELECT listing_id, title, address, property_type, price_gbp, bedrooms,
                   nearest_station_name, estimated_door_to_central_mins, rightmove_url
            FROM indexed_properties WHERE 1 = 1
            """);
        List<Object> params = new ArrayList<>();

        if (!semanticIds.isEmpty()) {
            sql.append(" AND listing_id IN (").append(String.join(",", semanticIds.stream().map(x -> "?").toList())).append(")");
            params.addAll(semanticIds);
        } else if (!query.isBlank()) {
            int kw = 0;
            for (String w : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (w.length() < 3 || kw >= 4) continue;
                sql.append(" AND (LOWER(title) LIKE ? OR LOWER(address) LIKE ? OR LOWER(raw_description) LIKE ?)");
                String like = "%" + w + "%";
                params.add(like); params.add(like); params.add(like);
                kw++;
            }
        }
        int maxPrice = intOr(args.get("max_price"), 0);
        if (maxPrice > 0) { sql.append(" AND price_gbp <= ?"); params.add(maxPrice); }
        int minPrice = intOr(args.get("min_price"), 0);
        if (minPrice > 0) { sql.append(" AND price_gbp >= ?"); params.add(minPrice); }
        int minBeds = intOr(args.get("min_bedrooms"), 0);
        if (minBeds > 0) { sql.append(" AND bedrooms >= ?"); params.add(minBeds); }
        List<String> types = stringList(args.get("property_types")).stream()
            .filter(VALID_PROPERTY_TYPES::contains).map(t -> t.toLowerCase(Locale.ROOT)).toList();
        if (!types.isEmpty()) {
            sql.append(" AND LOWER(property_type) IN (").append(String.join(",", types.stream().map(x -> "?").toList())).append(")");
            params.addAll(types);
        }
        String area = str(args.get("area"));
        if (!area.isBlank()) { sql.append(" AND LOWER(address) LIKE ?"); params.add("%" + area.toLowerCase(Locale.ROOT) + "%"); }
        if (semanticIds.isEmpty()) sql.append(" ORDER BY price_gbp ASC");

        List<Map<String, Object>> rows = jdbcClient.sql(sql.toString()).params(params).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("listingId", rs.getString("listing_id"));
            m.put("address", rs.getString("address"));
            m.put("type", rs.getString("property_type"));
            m.put("priceGbp", rs.getInt("price_gbp"));
            m.put("bedrooms", rs.getInt("bedrooms"));
            m.put("nearestStation", rs.getString("nearest_station_name"));
            m.put("doorToCentralMins", rs.getInt("estimated_door_to_central_mins"));
            m.put("url", rs.getString("rightmove_url"));
            return m;
        }).list();

        if (!semanticIds.isEmpty()) {
            rows = new ArrayList<>(rows);
            rows.sort((a, b) -> Integer.compare(semanticIds.indexOf((String) a.get("listingId")), semanticIds.indexOf((String) b.get("listingId"))));
        }
        List<Map<String, Object>> limited = rows.size() > limit ? rows.subList(0, limit) : rows;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalMatches", rows.size());
        out.put("properties", limited);
        return safeJson(out);
    }

    private String toolGetProperty(Map<String, Object> args) {
        if (jdbcClient == null) return "{\"error\":\"Database unavailable\"}";
        String id = str(args.get("listing_id"));
        if (id.isBlank()) return "{\"error\":\"listing_id is required\"}";
        List<Map<String, Object>> rows = jdbcClient.sql("""
            SELECT listing_id, title, address, property_type, price_gbp, bedrooms, price_qualifier,
                   nearest_station_name, distance_to_station_miles, estimated_door_to_central_mins,
                   station_free_parking, raw_description, metadata_json, rightmove_url
            FROM indexed_properties WHERE listing_id = ?
            """).param(id).query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("listingId", rs.getString("listing_id"));
                m.put("title", rs.getString("title"));
                m.put("address", rs.getString("address"));
                m.put("type", rs.getString("property_type"));
                m.put("priceGbp", rs.getInt("price_gbp"));
                m.put("priceQualifier", rs.getString("price_qualifier"));
                m.put("bedrooms", rs.getInt("bedrooms"));
                m.put("nearestStation", rs.getString("nearest_station_name"));
                m.put("distanceToStationMiles", rs.getDouble("distance_to_station_miles"));
                m.put("doorToCentralMins", rs.getInt("estimated_door_to_central_mins"));
                m.put("freeStationParking", rs.getBoolean("station_free_parking"));
                m.put("description", truncate(String.valueOf(rs.getString("raw_description")), 1500));
                m.put("extractedMetadata", truncate(String.valueOf(rs.getString("metadata_json")), 1200));
                m.put("url", rs.getString("rightmove_url"));
                return m;
            }).list();
        return rows.isEmpty() ? "{\"error\":\"No property with that listing_id\"}" : safeJson(rows.get(0));
    }

    private String toolGetPropertyEvidence(Map<String, Object> args) {
        if (jdbcClient == null) return "{\"error\":\"Database unavailable\"}";
        String id = str(args.get("listing_id"));
        if (id.isBlank()) return "{\"error\":\"listing_id is required\"}";
        List<Map<String, Object>> rows = jdbcClient.sql("""
            SELECT listing_id, title, address, property_type, price_gbp,
                   metadata_source, metadata_model, analyzed_at,
                   metadata_json, llm_response_log, raw_description
            FROM indexed_properties WHERE listing_id = ?
            """).param(id).query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("listingId", rs.getString("listing_id"));
                m.put("title", rs.getString("title"));
                m.put("address", rs.getString("address"));
                m.put("propertyType", rs.getString("property_type"));
                m.put("priceGbp", rs.getInt("price_gbp"));
                m.put("metadataSource", rs.getString("metadata_source")); // "VISION_LLM", "TEXT_LLM", "HEURISTIC"
                m.put("metadataModel", rs.getString("metadata_model"));
                m.put("analyzedAt", rs.getString("analyzed_at"));
                String metaRaw = rs.getString("metadata_json");
                try {
                    if (metaRaw != null && !metaRaw.isBlank()) {
                        m.put("extractedFeatures", objectMapper.readValue(metaRaw, Map.class));
                    }
                } catch (Exception e) {
                    m.put("extractedFeaturesRaw", metaRaw);
                }
                String llmLog = rs.getString("llm_response_log");
                m.put("visionAndReasoningTranscript", truncate(llmLog != null && !llmLog.isBlank() ? llmLog : "No LLM transcript recorded (heuristic extraction used).", 4000));
                m.put("descriptionExcerpt", truncate(String.valueOf(rs.getString("raw_description")), 1000));
                return m;
            }).list();
        return rows.isEmpty() ? "{\"error\":\"No property with listing_id " + id + "\"}" : safeJson(rows.get(0));
    }

    private String toolGetLogs(Map<String, Object> args) {
        int lines = Math.min(250, Math.max(10, intOr(args.get("lines"), 80)));
        String filter = str(args.get("filter"));
        Path path = Path.of("logs", "property-agent.log");
        if (!Files.exists(path)) {
            return "{\"status\":\"No log file found at ./logs/property-agent.log yet.\"}";
        }
        try {
            File file = path.toFile();
            long fileLength = file.length();
            if (fileLength == 0) return "{\"lines\":[]}";
            long seekStart = Math.max(0, fileLength - 262144L); // last 256KB
            int readLen = (int) (fileLength - seekStart);
            byte[] buffer = new byte[readLen];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(seekStart);
                raf.readFully(buffer);
            }
            String content = new String(buffer, StandardCharsets.UTF_8);
            String[] rawLines = content.split("\r?\n");
            List<String> matched = new ArrayList<>();
            String fLower = filter.toLowerCase(Locale.ROOT);
            for (String line : rawLines) {
                if (filter.isBlank() || line.toLowerCase(Locale.ROOT).contains(fLower)) {
                    matched.add(line);
                }
            }
            int start = Math.max(0, matched.size() - lines);
            List<String> tail = matched.subList(start, matched.size());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lineCount", tail.size());
            out.put("lines", tail);
            if (!filter.isBlank()) out.put("filter", filter);
            return safeJson(out);
        } catch (Exception e) {
            return "{\"error\":" + safeJson("Error reading log tail: " + e.getMessage()) + "}";
        }
    }

    private String toolTriggerFetch(Map<String, Object> args, List<Map<String, Object>> actions) {
        if (scraperService == null) return "{\"error\":\"Scraper service unavailable in this environment\"}";
        String center = str(args.get("center_location"));
        if (center.isBlank()) center = "Glasgow Central";
        double radius = doubleOr(args.get("radius_miles"), 15.0);
        String provider = str(args.get("portal_provider"));
        if (provider.isBlank()) provider = str(args.get("provider"));
        if (provider.isBlank()) provider = "all";
        List<String> types = stringList(args.get("property_types"));
        int maxPrice = intOr(args.get("max_price"), 0);
        int minBeds = intOr(args.get("min_bedrooms"), 0);

        RightmoveScraperService.ScrapeJobStatus status = scraperService.startBackgroundScrape(
            center, radius, provider, types.isEmpty() ? null : types, maxPrice, minBeds
        );
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "fetch_started");
        action.put("centerLocation", center);
        action.put("radiusMiles", radius);
        actions.add(action);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", status.accepted());
        out.put("statusMessage", status.statusMessage());
        out.put("jobType", status.jobType());
        out.put("targetCenter", status.targetCenter());
        out.put("targetRadiusMiles", status.targetRadiusMiles());
        return safeJson(out);
    }

    private String toolGetJobStatus(Map<String, Object> args) {
        if (scraperService == null) return "{\"error\":\"Scraper service unavailable in this environment\"}";
        return safeJson(scraperService.getScrapeJobStatus());
    }

    private String toolCancelJob(Map<String, Object> args) {
        if (scraperService == null) return "{\"error\":\"Scraper service unavailable in this environment\"}";
        scraperService.cancelJob();
        return "{\"status\":\"Cancellation requested for active background scrape job.\"}";
    }

    private String toolUpdateBuyerWorkflow(Map<String, Object> args, List<Map<String, Object>> actions) {
        if (buyerWorkflowService == null) return "{\"error\":\"Buyer workflow service unavailable in this environment\"}";
        String listingId = str(args.get("listing_id"));
        if (listingId.isBlank()) return "{\"error\":\"listing_id is required\"}";
        Boolean shortlisted = args.get("shortlisted") instanceof Boolean b ? b : null;
        Boolean hidden = args.get("hidden") instanceof Boolean b ? b : null;
        String rawVs = str(args.get("viewing_status")).toUpperCase(Locale.ROOT);
        String viewingStatus = VALID_VIEWING_STATUSES.contains(rawVs) ? rawVs : null;
        String notes = str(args.get("notes"));
        if (notes.isBlank()) notes = str(args.get("buyer_notes"));
        Integer hrValuation = args.get("home_report_valuation_gbp") instanceof Number n ? n.intValue() : null;
        Double floorArea = args.get("floor_area_sqm") instanceof Number n ? n.doubleValue() : null;
        String councilTax = str(args.get("council_tax_band"));

        BuyerWorkflowService.WorkflowUpdateRequest req = new BuyerWorkflowService.WorkflowUpdateRequest(
            shortlisted,
            hidden,
            viewingStatus,
            notes.isBlank() ? null : notes,
            hrValuation,
            floorArea,
            councilTax.isBlank() ? null : councilTax
        );
        BuyerWorkflowState updated = buyerWorkflowService.updateWorkflow(listingId, req);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "refresh_workflow");
        action.put("listingId", listingId);
        actions.add(action);

        return safeJson(Map.of("status", "updated", "listingId", listingId, "workflow", updated));
    }

    private String toolOpenPropertyInUi(Map<String, Object> args, List<Map<String, Object>> actions) {
        String listingId = str(args.get("listing_id"));
        if (listingId.isBlank()) return "{\"error\":\"listing_id is required\"}";
        actions.add(Map.of("type", "open_property", "listingId", listingId));
        return "{\"status\":\"queued\",\"note\":\"UI will open modal for listing " + listingId + "\"}";
    }

    private String toolClearSession(Map<String, Object> args, List<Map<String, Object>> actions) {
        actions.add(Map.of("type", "clear_session"));
        return "{\"status\":\"cleared\",\"note\":\"Active AI chat session and conversation history cleared.\"}";
    }

    private String toolApplyFilters(Map<String, Object> args, List<Map<String, Object>> actions) {
        Map<String, Object> filters = new LinkedHashMap<>();
        putInt(filters, args, "maxPrice", 0, 10_000_000);
        putInt(filters, args, "minBedrooms", 0, 15);
        putInt(filters, args, "maxDoorToCentralCommuteMins", 5, 240);
        putInt(filters, args, "minServicesPerHour", 0, 60);
        if (args.get("radiusMiles") instanceof Number n) {
            double r = n.doubleValue();
            if (r >= 0.5 && r <= 50.0) filters.put("radiusMiles", r);
        }
        String center = str(args.get("centerLocation"));
        if (!center.isBlank()) filters.put("centerLocation", center);
        if (args.get("requireFreeStationParking") instanceof Boolean b) filters.put("requireFreeStationParking", b);
        if (args.get("requireStepFreeCategoryA") instanceof Boolean b) filters.put("requireStepFreeCategoryA", b);
        List<String> types = stringList(args.get("propertyTypes")).stream().filter(VALID_PROPERTY_TYPES::contains).toList();
        if (!types.isEmpty()) filters.put("propertyTypes", types);
        List<String> modes = stringList(args.get("transportModes")).stream().filter(VALID_TRANSPORT_MODES::contains).toList();
        if (!modes.isEmpty()) filters.put("transportModes", modes);

        List<Map<String, String>> add = new ArrayList<>();
        if (args.get("addCriteria") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String rule = str(m.get("rule"));
                    String pr = str(m.get("priority")).toUpperCase(Locale.ROOT);
                    if (!rule.isBlank()) add.add(Map.of("rule", truncate(rule, 200), "priority", VALID_PRIORITIES.contains(pr) ? pr : "HIGH"));
                }
            }
        }
        boolean clear = Boolean.TRUE.equals(args.get("clearCriteria"));

        if (filters.isEmpty() && add.isEmpty() && !clear) return "{\"error\":\"No valid filter changes supplied\"}";

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "apply_filters");
        action.put("filters", filters);
        action.put("addCriteria", add);
        action.put("clearCriteria", clear);
        actions.add(action);
        return "{\"status\":\"queued\",\"note\":\"The UI will apply these changes and re-rank now.\",\"applied\":" + safeJson(action) + "}";
    }

    private String toolRunSql(Map<String, Object> args) throws Exception {
        if (sqlExecutor == null || !sqlExecutor.isAvailable()) return "{\"error\":\"Database unavailable\"}";
        String sql = str(args.get("sql"));
        try {
            ReadOnlySqlExecutor.QueryResult r = sqlExecutor.execute(sql);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("columns", r.columns());
            out.put("rows", r.rows());
            out.put("rowCount", r.rows().size());
            if (r.truncated()) out.put("note", "Result truncated at the row cap; add filters/LIMIT or aggregate.");
            return truncate(safeJson(out), 12000);
        } catch (IllegalArgumentException rejected) {
            return "{\"error\":" + safeJson("Query rejected: " + rejected.getMessage()) + "}";
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "{\"error\":" + safeJson("SQL error: " + truncate(msg.replaceAll("\\s+", " "), 400)) + "}";
        }
    }

    private String toolWebSearch(Map<String, Object> args) throws Exception {
        if (webClient == null || !webClient.isEnabled()) return "{\"error\":\"Web search is disabled\"}";
        String q = str(args.get("query"));
        if (q.isBlank()) return "{\"error\":\"query is required\"}";
        List<WebSearchClient.WebResult> results = webClient.search(q, 5);
        return safeJson(Map.of("query", q, "results", results));
    }

    private String toolFetchWebpage(Map<String, Object> args) throws Exception {
        if (webClient == null || !webClient.isEnabled()) return "{\"error\":\"Web access is disabled\"}";
        String url = str(args.get("url"));
        if (url.isBlank()) return "{\"error\":\"url is required\"}";
        return safeJson(Map.of("url", url, "text", webClient.fetchPageText(url)));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> tool(String name, String description, Map<String, Object> props, List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", required);
        return Map.of("type", "function", "function", Map.of("name", name, "description", description, "parameters", params));
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static void putInt(Map<String, Object> out, Map<String, Object> args, String key, int min, int max) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            int i = n.intValue();
            if (i >= min && i <= max) out.put(key, i);
        } else if (v instanceof String s && s.matches("\\d{1,9}")) {
            int i = Integer.parseInt(s);
            if (i >= min && i <= max) out.put(key, i);
        }
    }

    static int intOr(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.replaceAll("[^0-9-]", "")); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    static double doubleOr(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.replaceAll("[^0-9.-]", "")); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                String s = str(o);
                if (s.isEmpty()) continue;
                for (String canon : VALID_PROPERTY_TYPES) if (canon.equalsIgnoreCase(s)) s = canon;
                for (String canon : VALID_TRANSPORT_MODES) if (canon.equalsIgnoreCase(s)) s = canon;
                out.add(s);
            }
        }
        return out;
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String safeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
