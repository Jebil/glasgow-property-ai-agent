package com.glasgow.propertyagent;

import com.glasgow.propertyagent.service.ChatAssistantService;
import com.glasgow.propertyagent.service.OllamaApiClient;
import com.glasgow.propertyagent.service.ReadOnlySqlExecutor;
import com.glasgow.propertyagent.service.WebSearchClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private ChatAssistantService service(FakeOllama fake, boolean webEnabled) {
        return new ChatAssistantService(fake, om, null, null, null, new WebSearchClient(webEnabled, 5000), "gemma4:e4b");
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
        t.applyFiltersToolSanitisesModelInput();
        t.chatLoopRunsToolThenAnswersAndSeesCurrentFilters();
        t.chatFallsBackWhenModelDoesNotSupportTools();
        t.webResultParsingAndSsrfGuard();
        t.sqlValidationAcceptsOnlySingleReadOnlySelects();
        t.runSqlToolReturnsRowsAndFeedsErrorsBackToModel();
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
}