package com.glasgow.propertyagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gives the chat assistant read-only access to the whole PostgreSQL database.
 *
 * Defence in depth (a prompt is not a security boundary):
 *  1. {@link #validate(String)} accepts exactly one SELECT/WITH statement and rejects write/DDL/admin keywords
 *     and dangerous functions.
 *  2. The statement runs on a connection marked read-only (PostgreSQL BEGIN READ ONLY), so even a validation
 *     miss cannot modify data; the transaction is always rolled back.
 *  3. A statement timeout and a row cap bound the cost; large cell values are truncated.
 */
@Component
public class ReadOnlySqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlySqlExecutor.class);

    private static final Pattern FORBIDDEN = Pattern.compile(
        "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|call|execute|vacuum|analyze|"
            + "refresh|reindex|cluster|lock|listen|notify|set|reset|do|merge|comment|security|"
            + "pg_read_file|pg_read_binary_file|pg_ls_dir|pg_sleep|lo_import|lo_export|lo_unlink|dblink|"
            + "set_config|pg_terminate_backend|pg_cancel_backend|pg_reload_conf|pg_stat_file)\\b");

    public record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated, long elapsedMs) {}

    private final DataSource dataSource;
    private final int maxRows;
    private final int timeoutMs;
    private final int maxCellChars;
    private volatile String cachedSchema;
    private volatile long cachedSchemaAt;

    @Autowired
    public ReadOnlySqlExecutor(
            JdbcTemplate jdbcTemplate,
            @Value("${property-agent.chat.sql-max-rows:100}") int maxRows,
            @Value("${property-agent.chat.sql-timeout-ms:8000}") int timeoutMs,
            @Value("${property-agent.chat.sql-max-cell-chars:400}") int maxCellChars) {
        this.dataSource = jdbcTemplate != null ? jdbcTemplate.getDataSource() : null;
        this.maxRows = Math.max(1, maxRows);
        this.timeoutMs = Math.max(500, timeoutMs);
        this.maxCellChars = Math.max(50, maxCellChars);
    }

    public boolean isAvailable() {
        return dataSource != null;
    }

    /** Validates and normalises the SQL; throws IllegalArgumentException with a model-readable reason. */
    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL is empty");
        String s = sql.trim();
        // strip comments so they can't hide keywords or terminators
        s = s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ").trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty()) throw new IllegalArgumentException("SQL is empty");
        // string literals are data, not code: remove them before keyword/terminator checks
        String code = s.replaceAll("'(?:[^']|'')*'", "''").toLowerCase(Locale.ROOT);
        if (code.contains(";")) throw new IllegalArgumentException("Only a single statement is allowed (no ';')");
        if (!(code.startsWith("select") || code.startsWith("with"))) {
            throw new IllegalArgumentException("Only SELECT queries (optionally starting with WITH) are allowed");
        }
        java.util.regex.Matcher m = FORBIDDEN.matcher(code);
        if (m.find()) throw new IllegalArgumentException("Forbidden keyword/function in read-only query: " + m.group(1));
        return s;
    }

    public QueryResult execute(String rawSql) throws Exception {
        if (dataSource == null) throw new IllegalStateException("Database unavailable");
        String sql = validate(rawSql);
        long start = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            boolean oldAutoCommit = c.getAutoCommit();
            boolean oldReadOnly = c.isReadOnly();
            try {
                c.setReadOnly(true);        // must precede setAutoCommit(false): pgjdbc then issues BEGIN READ ONLY
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.execute("SET LOCAL statement_timeout = " + timeoutMs);
                    st.setMaxRows(maxRows + 1);
                    try (ResultSet rs = st.executeQuery(sql)) {
                        return readResult(rs, start);
                    }
                }
            } finally {
                try { c.rollback(); } catch (Exception ignored) { }
                try { c.setAutoCommit(oldAutoCommit); c.setReadOnly(oldReadOnly); } catch (Exception ignored) { }
            }
        }
    }

    private QueryResult readResult(ResultSet rs, long start) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) { truncated = true; break; }
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) row.add(cell(rs.getObject(i)));
            rows.add(row);
        }
        return new QueryResult(cols, rows, truncated, System.currentTimeMillis() - start);
    }

    private Object cell(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean) return v;
        String s = v instanceof byte[] b ? "<" + b.length + " bytes>" : String.valueOf(v);
        return s.length() > maxCellChars ? s.substring(0, maxCellChars) + "…[truncated]" : s;
    }

    /** Compact schema description for the model's system prompt (cached for 10 minutes). */
    public String describeSchema() {
        long now = System.currentTimeMillis();
        String cached = cachedSchema;
        if (cached != null && now - cachedSchemaAt < 600_000L) return cached;
        if (dataSource == null) return "(database unavailable)";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            Map<String, List<String>> tables = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("""
                SELECT table_name, column_name, data_type, udt_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                ORDER BY table_name, ordinal_position
                """)) {
                while (rs.next()) {
                    String type = "USER-DEFINED".equals(rs.getString("data_type")) ? rs.getString("udt_name") : rs.getString("data_type");
                    tables.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>())
                        .add(rs.getString("column_name") + " " + type);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : tables.entrySet()) {
                if (e.getKey().equals("flyway_schema_history")) continue;
                sb.append("- ").append(e.getKey()).append("(").append(String.join(", ", e.getValue())).append(")");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + e.getKey().replace("\"", "") + "\"")) {
                    if (rs.next()) sb.append("  -- ").append(rs.getLong(1)).append(" rows");
                } catch (Exception ignored) { }
                sb.append('\n');
            }
            if (tables.containsKey("indexed_properties")) {
                try (ResultSet rs = st.executeQuery("SELECT DISTINCT property_type FROM indexed_properties WHERE property_type IS NOT NULL LIMIT 12")) {
                    List<String> vals = new ArrayList<>();
                    while (rs.next()) vals.add(rs.getString(1));
                    if (!vals.isEmpty()) sb.append("indexed_properties.property_type values: ").append(vals).append('\n');
                } catch (Exception ignored) { }
            }
            cachedSchema = sb.toString();
            cachedSchemaAt = now;
            return cachedSchema;
        } catch (Exception e) {
            log.warn("Could not introspect schema for chat assistant: {}", e.getMessage());
            return "(schema unavailable: " + e.getMessage() + ")";
        }
    }
}
