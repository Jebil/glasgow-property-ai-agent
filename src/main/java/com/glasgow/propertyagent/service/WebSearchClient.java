package com.glasgow.propertyagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight internet access for the chat assistant (web search + page fetch).
 * Gemma itself cannot browse; the backend runs these on its behalf as tools.
 * Uses the keyless DuckDuckGo HTML endpoint. If the endpoint presents an anti-bot challenge
 * the search simply reports failure (no attempt is made to bypass it).
 */
@Component
public class WebSearchClient {

    private static final Logger log = LoggerFactory.getLogger(WebSearchClient.class);
    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final Pattern RESULT_LINK = Pattern.compile(
        "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern RESULT_SNIPPET = Pattern.compile(
        "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    public record WebResult(String title, String url, String snippet) {}

    private final boolean enabled;
    private final int maxPageChars;
    private volatile HttpClient httpClient;

    public WebSearchClient(
            @Value("${property-agent.chat.web-search-enabled:true}") boolean enabled,
            @Value("${property-agent.chat.max-page-chars:5000}") int maxPageChars) {
        this.enabled = enabled;
        this.maxPageChars = Math.max(500, maxPageChars);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private HttpClient client() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                c = httpClient;
                if (c == null) {
                    c = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                    httpClient = c;
                }
            }
        }
        return c;
    }

    public List<WebResult> search(String query, int limit) throws Exception {
        if (!enabled) throw new IllegalStateException("Web search is disabled");
        String body = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&kl=uk-en";
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://html.duckduckgo.com/html/"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());
        String html = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() != 200 || html.contains("anomaly-modal") || html.toLowerCase().contains("unusual traffic")) {
            throw new IllegalStateException("Search provider unavailable or rate-limited (HTTP " + resp.statusCode() + ")");
        }
        return parseResults(html, limit);
    }

    public static List<WebResult> parseResults(String html, int limit) {
        List<String> snippets = new ArrayList<>();
        Matcher sm = RESULT_SNIPPET.matcher(html);
        while (sm.find()) snippets.add(cleanText(sm.group(1)));

        List<WebResult> out = new ArrayList<>();
        Matcher lm = RESULT_LINK.matcher(html);
        int i = 0;
        while (lm.find() && out.size() < Math.max(1, limit)) {
            String url = unwrapRedirect(lm.group(1));
            String title = cleanText(lm.group(2));
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            i++;
            if (url.isBlank() || title.isBlank()) continue;
            out.add(new WebResult(title, url, snippet));
        }
        return out;
    }

    private static String unwrapRedirect(String href) {
        String h = href.replace("&amp;", "&");
        int idx = h.indexOf("uddg=");
        if (idx >= 0) {
            String enc = h.substring(idx + 5);
            int amp = enc.indexOf('&');
            if (amp >= 0) enc = enc.substring(0, amp);
            return URLDecoder.decode(enc, StandardCharsets.UTF_8);
        }
        if (h.startsWith("//")) return "https:" + h;
        return h;
    }

    public String fetchPageText(String url) throws Exception {
        if (!enabled) throw new IllegalStateException("Web access is disabled");
        URI uri = URI.create(url.trim());
        assertPublicHttpUri(uri);
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,text/plain")
            .GET()
            .build();
        HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("Page returned HTTP " + resp.statusCode());
        }
        String text = cleanText(stripNonContent(resp.body() == null ? "" : resp.body()));
        return text.length() > maxPageChars ? text.substring(0, maxPageChars) + " …[truncated]" : text;
    }

    /** SSRF guard: only public http(s) hosts may be fetched on the model's behalf. */
    public static void assertPublicHttpUri(URI uri) throws Exception {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http/https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Invalid URL host");
        for (InetAddress addr : InetAddress.getAllByName(host)) {
            if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                throw new IllegalArgumentException("Refusing to fetch non-public address");
            }
        }
    }

    private static String stripNonContent(String html) {
        return html
            .replaceAll("(?is)<script.*?</script>", " ")
            .replaceAll("(?is)<style.*?</style>", " ")
            .replaceAll("(?is)<nav.*?</nav>", " ")
            .replaceAll("(?is)<footer.*?</footer>", " ");
    }

    private static String cleanText(String html) {
        String noTags = html.replaceAll("(?s)<[^>]+>", " ");
        String decoded = noTags
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return decoded.replaceAll("\\s+", " ").trim();
    }
}
