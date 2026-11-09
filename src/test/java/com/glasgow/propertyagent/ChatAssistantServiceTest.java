package com.glasgow.propertyagent;

import com.glasgow.propertyagent.service.scraping.ScrapeJobStateTracker;
import com.glasgow.propertyagent.service.scraping.RightmoveScraperService;
import com.glasgow.propertyagent.service.ai.WebSearchClient;
import com.glasgow.propertyagent.service.ai.ChatAssistantService;
import com.glasgow.propertyagent.service.app.ReadOnlySqlExecutor;
import com.glasgow.propertyagent.service.ai.OllamaApiClient;
import com.glasgow.propertyagent.service.app.BuyerWorkflowService;

import com.glasgow.propertyagent.model.BuyerWorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.File;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ChatAssistantServiceTest {

    private final ObjectMapper om = new ObjectMapper();

    /** Scripted fake Ollama: returns queued responses (or throws) and records every request body. */
    static class FakeOllama extends OllamaApiClient {
        final ObjectMapper om;
        final List<Object> script = new ArrayList<>();
        final List<Map<String, Object>> requests = new ArrayList<>();

        FakeOllama(ObjectMapper om) {
            super(om, "http://127.0.0.1:1");
            this.om = om;
        }

        @Override
        public JsonNode postChatJson(Map<String, Object> body) {
            requests.add(body);
            Object next = script.remove(0);
            if (next instanceof RuntimeException e) throw e;
            return om.readTree((String) next);
        }
    }

    /** Fake scraper double that records requests and avoids network / background threads. */
    static class FakeScraperService extends RightmoveScraperService {
        String lastCenter;
        double lastRadius;
        String lastProvider;
        List<String> lastPropertyTypes;
        int lastMaxPrice;
        int lastMinBedrooms;
        int callCount = 0;
        int cancelCount = 0;
        boolean accepted = true;

        FakeScraperService() {
            super(null, new ScrapeJobStateTracker(), null, null, null, null);
        }

        @Override
        public ScrapeJobStatus startBackgroundScrape(
                String centerLocation,
                double radiusMiles,
                String provider,
                List<String> propertyTypes,
                int maxPrice,
                int minBedrooms) {
            this.callCount++;
            this.lastCenter = centerLocation;
            this.lastRadius = radiusMiles;
            this.lastProvider = provider;
            this.lastPropertyTypes = propertyTypes;
            this.lastMaxPrice = maxPrice;
            this.lastMinBedrooms = minBedrooms;
            return new ScrapeJobStatus(
                accepted, accepted, "SCRAPE", "RUNNING", centerLocation, radiusMiles,
                "", 0, 0, 0, 0, 0, "", "", "", 0, 0, 0, 0, 0, 0, 100L,
                "", "", 0, accepted ? "Job started" : "Background job is already running",
                centerLocation, radiusMiles, 0, 0, List.of()
            );
        }

        @Override
        public ScrapeJobStatus getScrapeJobStatus() {
            return new ScrapeJobStatus(
                false, false, "IDLE", "FINISHED", "Glasgow Central", 15.0,
                "", 0, 0, 0, 0, 0, "", "", "", 0, 0, 0, 0, 0, 0, 100L,
                "", "", 0, "Idle", "Glasgow Central", 15.0, 0, 0, List.of()
            );
        }

        @Override
        public void cancelJob() {
            this.cancelCount++;
        }
    }

    /** JDK Dynamic Proxy based mock of JdbcTemplate to avoid ByteBuddy named pipe issues on Java 25. */
    static JdbcTemplate fakeJdbcTemplate(Map<String, Object> row) {
        Connection conn = (Connection) Proxy.newProxyInstance(
            ChatAssistantServiceTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, methodArgs) -> {
                if ("prepareStatement".equals(method.getName())) {
                    return fakePreparedStatement(row);
                }
                if ("getMetaData".equals(method.getName())) {
                    return Proxy.newProxyInstance(
                        ChatAssistantServiceTest.class.getClassLoader(),
                        new Class<?>[]{DatabaseMetaData.class},
                        (p, m, a) -> null);
                }
                return null;
            }
        );
        DataSource ds = (DataSource) Proxy.newProxyInstance(
            ChatAssistantServiceTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, methodArgs) -> {
                if ("getConnection".equals(method.getName())) {
                    return conn;
                }
                return null;
            }
        );
        return new JdbcTemplate(ds);
    }

    private static PreparedStatement fakePreparedStatement(Map<String, Object> row) {
        return (PreparedStatement) Proxy.newProxyInstance(
            ChatAssistantServiceTest.class.getClassLoader(),
            new Class<?>[]{PreparedStatement.class},
            (proxy, method, methodArgs) -> {
                if ("executeQuery".equals(method.getName())) {
                    return fakeResultSet(row);
                }
                if ("unwrap".equals(method.getName()) && methodArgs != null && methodArgs.length == 1) {
                    Class<?> target = (Class<?>) methodArgs[0];
                    if (target.isInstance(proxy)) return proxy;
                }
                return null;
            }
        );
    }

    private static ResultSet fakeResultSet(Map<String, Object> row) {
        AtomicBoolean consumed = new AtomicBoolean(false);
        return (ResultSet) Proxy.newProxyInstance(
            ChatAssistantServiceTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class},
            (proxy, method, methodArgs) -> {
                String name = method.getName();
                if ("next".equals(name)) {
                    return consumed.compareAndSet(false, true);
                }
                if ("getString".equals(name) && methodArgs != null && methodArgs.length == 1) {
                    Object val = row.get(methodArgs[0]);
                    return val != null ? String.valueOf(val) : null;
                }
                if ("getInt".equals(name) && methodArgs != null && methodArgs.length == 1) {
                    Object val = row.get(methodArgs[0]);
                    return val instanceof Number n ? n.intValue() : 0;
                }
                if ("getObject".equals(name) && methodArgs != null && methodArgs.length == 1) {
                    return row.get(methodArgs[0]);
                }
                if ("getMetaData".equals(name)) {
                    return Proxy.newProxyInstance(
                        ChatAssistantServiceTest.class.getClassLoader(),
                        new Class<?>[]{ResultSetMetaData.class},
                        (p, m, a) -> 0);
                }
                return null;
            }
        );
    }

    private ChatAssistantService service(FakeOllama fake, boolean webEnabled) {
        return new ChatAssistantService(fake, om, (JdbcTemplate) null, null, null, new WebSearchClient(webEnabled, 5000), "gemma4:e4b");
    }

    private ChatAssistantService.ChatRequest request(String userText) {
        return new ChatAssistantService.ChatRequest(
            List.of(new ChatAssistantService.ChatMessage("user", userText)),
            Map.of("maxPrice", 450000, "propertyTypes", List.of("Detached", "Semi-Detached", "Bungalow")),
            List.of(Map.of("address", "1 Test Road, Bearsden", "priceGbp", 400000, "score", 82)));
    }

    public static void main(String[] args) throws Exception {
        ChatAssistantServiceTest t = new ChatAssistantServiceTest();
        t.toolDefinitionsReflectWebSearchSetting();
        t.toolDefinitionsIncludeAllAppActionToolsWhenAvailable();
        t.applyFiltersToolSanitisesModelInput();
        t.chatLoopRunsToolThenAnswersAndSeesCurrentFilters();
        t.chatFallsBackWhenModelDoesNotSupportTools();
        t.webResultParsingAndSsrfGuard();
        t.sqlValidationAcceptsOnlySingleReadOnlySelects();
        t.runSqlToolReturnsRowsAndFeedsErrorsBackToModel();
        t.getLogsReadsFileWithOptionalFilter();
        t.getPropertyEvidenceExplainsFeaturesViaVisionLLM();
        t.triggerFetchStartsScraperAndQueuesFetchStartedAction();
        t.updateBuyerWorkflowUpdatesStateAndQueuesRefreshAction();
        t.openPropertyInUiQueuesOpenPropertyAction();
        t.clearSessionToolEmitsClearSessionAction();
        System.out.println("ALL ChatAssistantServiceTest checks PASSED successfully!");
    }

    @Test
    void toolDefinitionsReflectWebSearchSetting() {
        List<String> withWeb = service(new FakeOllama(om), true).toolDefinitions().stream()
            .map(t -> (String) ((Map<?, ?>) t.get("function")).get("name")).toList();
        List<String> noWeb = service(new FakeOllama(om), false).toolDefinitions().stream()
            .map(t -> (String) ((Map<?, ?>) t.get("function")).get("name")).toList();
        assertTrue(withWeb.containsAll(List.of("search_properties", "get_property", "apply_filters", "web_search", "fetch_webpage")));
        assertTrue(noWeb.containsAll(List.of("search_properties", "get_property", "apply_filters")));
        assertFalse(noWeb.contains("web_search"));
        assertFalse(noWeb.contains("fetch_webpage"));
    }

    @Test
    void toolDefinitionsIncludeAllAppActionToolsWhenAvailable() {
        FakeScraperService scraper = new FakeScraperService();
        BuyerWorkflowService workflow = new BuyerWorkflowService();
        ChatAssistantService svc = new ChatAssistantService(
            new FakeOllama(om), om, (JdbcTemplate) null, null, null,
            new WebSearchClient(true, 5000), scraper, workflow, "gemma4:e4b"
        );
        List<String> toolNames = svc.toolDefinitions().stream()
            .map(t -> (String) ((Map<?, ?>) t.get("function")).get("name")).toList();

        assertTrue(toolNames.contains("trigger_fetch"));
        assertTrue(toolNames.contains("get_job_status"));
        assertTrue(toolNames.contains("cancel_job"));
        assertTrue(toolNames.contains("update_buyer_workflow"));
        assertTrue(toolNames.contains("open_property_in_ui"));
        assertTrue(toolNames.contains("clear_session"));
        assertTrue(toolNames.contains("get_logs"));
        assertTrue(toolNames.contains("get_property_evidence"));
    }

    @Test
    void applyFiltersToolSanitisesModelInput() {
        ChatAssistantService svc = service(new FakeOllama(om), true);
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("maxPrice", 350000);
        args.put("minBedrooms", 99);                       // out of range -> dropped
        args.put("propertyTypes", List.of("detached", "Castle"));
        args.put("evilField", "x");                         // not whitelisted -> dropped
        args.put("addCriteria", List.of(Map.of("rule", "Private driveway", "priority", "bogus")));

        String result = svc.executeTool("apply_filters", args, actions);

        assertTrue(result.contains("queued"));
        assertEquals(1, actions.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) actions.get(0).get("filters");
        assertEquals(350000, filters.get("maxPrice"));
        assertEquals(List.of("Detached"), filters.get("propertyTypes"));
        assertFalse(filters.containsKey("minBedrooms"));
        assertFalse(filters.containsKey("evilField"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> add = (List<Map<String, String>>) actions.get(0).get("addCriteria");
        assertEquals("HIGH", add.get(0).get("priority"));

        assertTrue(svc.executeTool("apply_filters", Map.of(), new ArrayList<>()).contains("error"));
        assertTrue(svc.executeTool("no_such_tool", Map.of(), new ArrayList<>()).contains("Unknown tool"));
    }

    @Test
    void chatLoopRunsToolThenAnswersAndSeesCurrentFilters() {
        FakeOllama fake = new FakeOllama(om);
        fake.script.add("""
            {"message":{"role":"assistant","content":"","tool_calls":[
              {"function":{"name":"apply_filters","arguments":{"maxPrice":300000,"requireFreeStationParking":true}}}]}}
            """);
        fake.script.add("""
            {"message":{"role":"assistant","content":"Done - I capped the price at £300k and required free station parking."}}
            """);
        ChatAssistantService svc = service(fake, true);

        ChatAssistantService.ChatResponse resp = svc.chat(request("cap my budget at 300k and I need free parking at the station"));

        assertTrue(resp.reply().contains("£300k"));
        assertEquals(1, resp.actions().size());
        assertEquals("apply_filters", resp.actions().get(0).get("type"));
        assertEquals(1, resp.toolTrace().size());
        assertEquals(2, fake.requests.size());

        // The system prompt must expose the user's current filters and top results to the model.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstMessages = (List<Map<String, Object>>) fake.requests.get(0).get("messages");
        String system = (String) firstMessages.get(0).get("content");
        assertTrue(system.contains("450000"));
        assertTrue(system.contains("Semi-Detached"));
        assertTrue(system.contains("1 Test Road, Bearsden"));
        assertNotNull(fake.requests.get(0).get("tools"));

        // The second request must contain the tool result message.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondMessages = (List<Map<String, Object>>) fake.requests.get(1).get("messages");
        assertEquals("tool", secondMessages.get(secondMessages.size() - 1).get("role"));
    }

    @Test
    void chatFallsBackWhenModelDoesNotSupportTools() {
        FakeOllama fake = new FakeOllama(om);
        fake.script.add(new RuntimeException("400 Bad Request: registry.ollama.ai/library/x does not support tools"));
        fake.script.add("{\"message\":{\"role\":\"assistant\",\"content\":\"Plain answer.\"}}");

        ChatAssistantService.ChatResponse resp = service(fake, true).chat(request("hello"));

        assertTrue(resp.reply().contains("does not support tool calling"));
        assertTrue(resp.reply().endsWith("Plain answer."));
        assertNull(fake.requests.get(1).get("tools"));
    }

    @Test
    void webResultParsingAndSsrfGuard() throws Exception {
        String html = """
            <div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Frates&amp;rut=abc">Current <b>mortgage</b> rates</a>
            <a class="result__snippet" href="x">Rates are <b>4.5%</b> today &amp; falling</a></div>
            """;
        List<WebSearchClient.WebResult> results = WebSearchClient.parseResults(html, 5);
        assertEquals(1, results.size());
        assertEquals("https://example.com/rates", results.get(0).url());
        assertEquals("Current mortgage rates", results.get(0).title());
        assertTrue(results.get(0).snippet().contains("4.5%"));

        assertThrows(IllegalArgumentException.class, () -> WebSearchClient.assertPublicHttpUri(URI.create("http://127.0.0.1:8080/x")));
        assertThrows(IllegalArgumentException.class, () -> WebSearchClient.assertPublicHttpUri(URI.create("file:///etc/passwd")));
    }

    /** Fake executor: no real database, records SQL and returns canned rows. */
    static class FakeSql extends ReadOnlySqlExecutor {
        final List<String> executed = new ArrayList<>();
        FakeSql() { super(null, 100, 8000, 400); }
        @Override public boolean isAvailable() { return true; }
        @Override public String describeSchema() { return "- indexed_properties(listing_id text, price_gbp integer)  -- 3 rows\n"; }
        @Override public QueryResult execute(String rawSql) throws Exception {
            String sql = validate(rawSql);
            executed.add(sql);
            if (sql.contains("bad_column")) throw new RuntimeException("ERROR: column \"bad_column\" does not exist");
            return new QueryResult(List.of("avg_price"), List.of(List.of(312500)), false, 3);
        }
    }

    @Test
    void sqlValidationAcceptsOnlySingleReadOnlySelects() {
        assertDoesNotThrow(() -> ReadOnlySqlExecutor.validate("SELECT count(*) FROM indexed_properties;"));
        assertDoesNotThrow(() -> ReadOnlySqlExecutor.validate("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertDoesNotThrow(() -> ReadOnlySqlExecutor.validate("select * from indexed_properties where title ilike '%delete; drop%' -- ok"));
        assertDoesNotThrow(() -> ReadOnlySqlExecutor.validate("SELECT analyzed_at, created_at FROM indexed_properties"));
        for (String bad : List.of(
                "DELETE FROM indexed_properties",
                "SELECT 1; DROP TABLE indexed_properties",
                "UPDATE indexed_properties SET price_gbp = 1",
                "SELECT pg_sleep(100)",
                "WITH d AS (DELETE FROM indexed_properties RETURNING *) SELECT * FROM d",
                "SELECT /* hidden */ 1; /* x */ INSERT INTO t VALUES (1)",
                "COPY indexed_properties TO PROGRAM 'calc'",
                "SELECT pg_read_file('/etc/passwd')",
                "  ",
                "EXPLAIN ANALYZE SELECT 1")) {
            assertThrows(IllegalArgumentException.class, () -> ReadOnlySqlExecutor.validate(bad), "should reject: " + bad);
        }
    }

    @Test
    void runSqlToolReturnsRowsAndFeedsErrorsBackToModel() {
        FakeOllama fake = new FakeOllama(om);
        FakeSql sql = new FakeSql();
        ChatAssistantService svc = new ChatAssistantService(fake, om, null, null, sql, new WebSearchClient(false, 5000), "gemma4:e4b");

        assertTrue(svc.toolDefinitions().stream().anyMatch(t -> "run_sql".equals(((Map<?, ?>) t.get("function")).get("name"))));
        assertTrue(svc.buildSystemPrompt(request("x")).contains("indexed_properties(listing_id text"));

        String ok = svc.executeTool("run_sql", Map.of("sql", "SELECT avg(price_gbp) AS avg_price FROM indexed_properties"), new ArrayList<>());
        assertTrue(ok.contains("312500") && ok.contains("avg_price"));

        String rejected = svc.executeTool("run_sql", Map.of("sql", "DROP TABLE indexed_properties"), new ArrayList<>());
        assertTrue(rejected.contains("Query rejected"));
        assertEquals(1, sql.executed.size());   // the rejected statement never reached the executor

        String err = svc.executeTool("run_sql", Map.of("sql", "SELECT bad_column FROM indexed_properties"), new ArrayList<>());
        assertTrue(err.contains("SQL error") && err.contains("does not exist"));

        // Full loop: model runs SQL, then answers from the rows.
        fake.script.add("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"run_sql\",\"arguments\":{\"sql\":\"SELECT avg(price_gbp) AS avg_price FROM indexed_properties\"}}}]}}");
        fake.script.add("{\"message\":{\"role\":\"assistant\",\"content\":\"The average asking price is about £312,500.\"}}");
        ChatAssistantService.ChatResponse resp = svc.chat(request("what is the average price?"));
        assertTrue(resp.reply().contains("312,500"));
        assertTrue(resp.toolTrace().get(0).startsWith("run_sql"));
    }

    @Test
    void getLogsReadsFileWithOptionalFilter() throws Exception {
        ChatAssistantService svc = service(new FakeOllama(om), false);
        Path logPath = Path.of("logs", "property-agent.log");
        Files.createDirectories(logPath.getParent());

        String originalContent = Files.exists(logPath) ? Files.readString(logPath, StandardCharsets.UTF_8) : null;
        try {
            String testLogs = """
                2026-11-09 10:00:00 INFO Initializing service
                2026-11-09 10:01:00 ERROR Failed to connect to portal
                2026-11-09 10:02:00 INFO Scraping completed
                2026-11-09 10:03:00 WARN High memory usage
                2026-11-09 10:04:00 ERROR Database timeout on write
                """;
            Files.writeString(logPath, testLogs, StandardCharsets.UTF_8);

            String all = svc.executeTool("get_logs", Map.of("lines", 10), new ArrayList<>());
            assertTrue(all.contains("Initializing service"));
            assertTrue(all.contains("Database timeout"));

            String errorsOnly = svc.executeTool("get_logs", Map.of("lines", 10, "filter", "ERROR"), new ArrayList<>());
            assertTrue(errorsOnly.contains("Failed to connect to portal"));
            assertTrue(errorsOnly.contains("Database timeout on write"));
            assertFalse(errorsOnly.contains("Scraping completed"));
        } finally {
            if (originalContent != null) {
                Files.writeString(logPath, originalContent, StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(logPath);
            }
        }
    }

    @Test
    void getPropertyEvidenceExplainsFeaturesViaVisionLLM() {
        Map<String, Object> fakeRow = new LinkedHashMap<>();
        fakeRow.put("listing_id", "RM-9901");
        fakeRow.put("title", "3 Bed Semi-Detached House");
        fakeRow.put("address", "14 Buchanan Gardens, Bearsden");
        fakeRow.put("property_type", "Semi-Detached");
        fakeRow.put("price_gbp", 340000);
        fakeRow.put("metadata_source", "VISION_LLM");
        fakeRow.put("metadata_model", "gemma4:e4b");
        fakeRow.put("analyzed_at", "2026-11-09T09:30:00Z");
        fakeRow.put("metadata_json", "{\"offStreetParking\":true,\"driveway\":true,\"downstairsWc\":true}");
        fakeRow.put("llm_response_log", "Photo 4 (front exterior) shows a private tarmac driveway for 2 cars with a dropped kerb. Floorplan shows ground floor cloakroom with WC.");
        fakeRow.put("raw_description", "Spacious family home in prime Bearsden location with private garden.");

        JdbcTemplate fakeJdbc = fakeJdbcTemplate(fakeRow);
        FakeOllama fake = new FakeOllama(om);
        ChatAssistantService svc = new ChatAssistantService(fake, om, fakeJdbc, null, null, new WebSearchClient(false, 5000), "gemma4:e4b");

        // Direct tool invocation test
        String result = svc.executeTool("get_property_evidence", Map.of("listing_id", "RM-9901"), new ArrayList<>());
        assertTrue(result.contains("VISION_LLM"));
        assertTrue(result.contains("private tarmac driveway for 2 cars"));
        assertTrue(result.contains("offStreetParking"));

        // Missing listing_id error test
        String errMissing = svc.executeTool("get_property_evidence", Map.of(), new ArrayList<>());
        assertTrue(errMissing.contains("listing_id is required"));

        // End-to-end conversation: User asks why it has offstreet parking when description doesn't say it
        fake.script.add("""
            {"message":{"role":"assistant","content":"","tool_calls":[
              {"function":{"name":"get_property_evidence","arguments":{"listing_id":"RM-9901"}}}]}}
            """);
        fake.script.add("""
            {"message":{"role":"assistant","content":"Although the property description doesn't mention parking, photo 4 was analyzed by the vision model and clearly shows a private tarmac driveway for 2 cars with a dropped kerb."}}
            """);

        ChatAssistantService.ChatResponse resp = svc.chat(request("how did you mark property RM-9901 as offstreet parking when property description doesn't say that?"));
        assertTrue(resp.reply().contains("photo 4"));
        assertTrue(resp.reply().contains("private tarmac driveway"));
        assertEquals(1, resp.toolTrace().size());
        assertTrue(resp.toolTrace().get(0).startsWith("get_property_evidence"));
    }

    @Test
    void triggerFetchStartsScraperAndQueuesFetchStartedAction() {
        FakeScraperService fakeScraper = new FakeScraperService();
        ChatAssistantService svc = new ChatAssistantService(
            new FakeOllama(om), om, (JdbcTemplate) null, null, null,
            new WebSearchClient(false, 5000), fakeScraper, null, "gemma4:e4b"
        );

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> args = Map.of(
            "center_location", "Glasgow Central",
            "radius_miles", 10.0,
            "provider", "rightmove",
            "property_types", List.of("Detached", "Semi-Detached"),
            "max_price", 400000,
            "min_bedrooms", 3
        );

        String result = svc.executeTool("trigger_fetch", args, actions);
        assertTrue(result.contains("Job started"));
        assertEquals(1, fakeScraper.callCount);
        assertEquals("Glasgow Central", fakeScraper.lastCenter);
        assertEquals(10.0, fakeScraper.lastRadius);
        assertEquals("rightmove", fakeScraper.lastProvider);
        assertEquals(List.of("Detached", "Semi-Detached"), fakeScraper.lastPropertyTypes);
        assertEquals(400000, fakeScraper.lastMaxPrice);
        assertEquals(3, fakeScraper.lastMinBedrooms);

        // Verify action emitted for UI to update banners and polling
        assertEquals(1, actions.size());
        assertEquals("fetch_started", actions.get(0).get("type"));

        // Verify get_job_status
        String statusJson = svc.executeTool("get_job_status", Map.of(), actions);
        assertTrue(statusJson.contains("FINISHED") || statusJson.contains("IDLE"));

        // Verify cancel_job
        String cancelJson = svc.executeTool("cancel_job", Map.of(), actions);
        assertTrue(cancelJson.contains("Cancellation requested"));
        assertEquals(1, fakeScraper.cancelCount);
    }

    @Test
    void updateBuyerWorkflowUpdatesStateAndQueuesRefreshAction() {
        BuyerWorkflowService workflow = new BuyerWorkflowService();
        ChatAssistantService svc = new ChatAssistantService(
            new FakeOllama(om), om, (JdbcTemplate) null, null, null,
            new WebSearchClient(false, 5000), null, workflow, "gemma4:e4b"
        );

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("listing_id", "RM-101");
        args.put("shortlisted", true);
        args.put("viewing_status", "BOOKED");
        args.put("notes", "Viewing scheduled for Saturday 11am");
        args.put("home_report_valuation_gbp", 335000);
        args.put("council_tax_band", "F");

        String result = svc.executeTool("update_buyer_workflow", args, actions);
        assertTrue(result.contains("updated"));

        BuyerWorkflowState state = workflow.getState("RM-101");
        assertTrue(state.shortlisted());
        assertEquals("BOOKED", state.viewingStatus());
        assertEquals("Viewing scheduled for Saturday 11am", state.viewingNotes());
        assertEquals(335000, state.homeReportValuationGbp());
        assertEquals("F", state.councilTaxBand());

        assertEquals(1, actions.size());
        assertEquals("refresh_workflow", actions.get(0).get("type"));
        assertEquals("RM-101", actions.get(0).get("listingId"));
    }

    @Test
    void openPropertyInUiQueuesOpenPropertyAction() {
        ChatAssistantService svc = service(new FakeOllama(om), false);
        List<Map<String, Object>> actions = new ArrayList<>();

        String result = svc.executeTool("open_property_in_ui", Map.of("listing_id", "RM-777"), actions);
        assertTrue(result.contains("queued"));
        assertEquals(1, actions.size());
        assertEquals("open_property", actions.get(0).get("type"));
        assertEquals("RM-777", actions.get(0).get("listingId"));
    }

    @Test
    void clearSessionToolEmitsClearSessionAction() {
        ChatAssistantService svc = service(new FakeOllama(om), false);
        List<Map<String, Object>> actions = new ArrayList<>();

        String result = svc.executeTool("clear_session", Map.of(), actions);
        assertTrue(result.contains("cleared"));
        assertEquals(1, actions.size());
        assertEquals("clear_session", actions.get(0).get("type"));

        // End-to-end Ollama script test
        FakeOllama fake = new FakeOllama(om);
        fake.script.add("""
            {"message":{"role":"assistant","content":"","tool_calls":[
              {"function":{"name":"clear_session","arguments":{}}}]}}
            """);
        fake.script.add("""
            {"message":{"role":"assistant","content":"I have cleared the active session. How can I help you afresh?"}}
            """);
        ChatAssistantService svcWithOllama = service(fake, false);
        ChatAssistantService.ChatResponse resp = svcWithOllama.chat(request("please clear our session and start over"));
        assertEquals(1, resp.actions().size());
        assertEquals("clear_session", resp.actions().get(0).get("type"));
        assertTrue(resp.reply().contains("cleared the active session"));
    }
}
