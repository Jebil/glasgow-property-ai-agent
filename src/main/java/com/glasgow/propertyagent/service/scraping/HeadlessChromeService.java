package com.glasgow.propertyagent.service.scraping;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * IMP-02: Persistent warm Headless Chrome service via Chrome DevTools Protocol (CDP)
 * with automatic fallback to fast image-disabled --dump-dom execution.
 *
 * Eliminates the 2.5s-4.5s cold OS process & V8 startup penalty per Rightmove page
 * and skips unnecessary image rendering while extracting embedded JSON payloads.
 */
@Service
public class HeadlessChromeService {

    private static final Logger log = LoggerFactory.getLogger(HeadlessChromeService.class);

    private final ObjectMapper objectMapper;

    @Value("${property.agent.chrome-path:}")
    private String configuredChromePath;

    @Value("${property-agent.chrome.persistent-cdp:true}")
    private boolean persistentCdpEnabled = true;

    private String resolvedChromePath;
    private boolean chromeAvailable;

    private final Object cdpLock = new Object();
    private volatile Process warmChromeProcess;
    private volatile int warmDebugPort = -1;
    private volatile Path warmProfileDir;
    private volatile HttpClient cdpHttpClient;

    public HeadlessChromeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        resolvedChromePath = detectChromeExecutable();
        chromeAvailable = (resolvedChromePath != null);
        if (chromeAvailable) {
            log.info("Headless Chrome detected & ready (persistent CDP={}): {}", persistentCdpEnabled, resolvedChromePath);
        } else {
            log.warn("Headless Chrome not found on system. Rightmove scraping requires Google Chrome.");
        }
    }

    @PreDestroy
    public void shutdown() {
        stopWarmChrome();
    }

    public boolean isChromeAvailable() {
        return chromeAvailable;
    }

    public String getResolvedChromePath() {
        return resolvedChromePath;
    }

    /**
     * Fetches and renders the given URL using the warm CDP Chrome instance (opening and closing
     * a lightweight tab in ~350-700ms), seamlessly falling back to --dump-dom if CDP fails.
     */
    public String fetchRenderedDom(String url, int timeoutSeconds) throws IOException, InterruptedException, TimeoutException {
        if (!chromeAvailable) {
            throw new IllegalStateException("Google Chrome executable not found. Install Google Chrome to scrape Rightmove.");
        }

        if (persistentCdpEnabled) {
            try {
                String cdpDom = fetchViaWarmCdp(url, timeoutSeconds);
                if (cdpDom != null && !cdpDom.isBlank()) {
                    return cdpDom;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception cdpEx) {
                log.debug("Warm CDP fetch fell back to --dump-dom for {}: {}", url, cdpEx.getMessage());
                stopWarmChrome();
            }
        }

        return fetchViaDumpDom(url, timeoutSeconds);
    }

    private String fetchViaWarmCdp(String url, int timeoutSeconds) throws Exception {
        long startMs = System.currentTimeMillis();
        int port = ensureWarmChromeRunning();
        HttpClient client = getCdpHttpClient();

        // 1. Open a fresh isolated tab target
        HttpRequest newTabReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/new"))
            .timeout(Duration.ofSeconds(5))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> newTabRes = client.send(newTabReq, HttpResponse.BodyHandlers.ofString());
        if (newTabRes.statusCode() != 200) {
            throw new IOException("CDP /json/new returned HTTP " + newTabRes.statusCode());
        }

        JsonNode tabNode = objectMapper.readTree(newTabRes.body());
        String targetId = tabNode.path("id").asString("");
        String wsUrl = tabNode.path("webSocketDebuggerUrl").asString("");
        if (targetId.isBlank() || wsUrl.isBlank()) {
            throw new IOException("CDP /json/new missing id or webSocketDebuggerUrl");
        }

        WebSocket ws = null;
        try {
            CompletableFuture<Void> loadFired = new CompletableFuture<>();
            Map<Integer, CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();

            WebSocket.Listener listener = new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        String jsonText = buffer.toString();
                        buffer.setLength(0);
                        try {
                            JsonNode msg = objectMapper.readTree(jsonText);
                            if (msg.has("id")) {
                                int id = msg.get("id").asInt();
                                CompletableFuture<JsonNode> fut = pendingResponses.remove(id);
                                if (fut != null) fut.complete(msg);
                            } else {
                                String method = msg.path("method").asString("");
                                if ("Page.loadEventFired".equals(method) || "Page.domContentEventFired".equals(method)) {
                                    loadFired.complete(null);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    webSocket.request(1);
                    return null;
                }
            };

            ws = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(wsUrl), listener)
                .get(5, TimeUnit.SECONDS);

            // Enable Page lifecycle notifications and navigate
            sendCdpCommand(ws, pendingResponses, 1, "Page.enable", Map.of(), 5);
            sendCdpCommand(ws, pendingResponses, 2, "Page.navigate", Map.of("url", url), Math.max(5, timeoutSeconds));

            try {
                loadFired.get(Math.max(4, timeoutSeconds - 2), TimeUnit.SECONDS);
            } catch (TimeoutException ignored) {
                // Even if subresources stall, inspect whatever DOM is already hydrated
            }
            Thread.sleep(200);

            JsonNode evalRes = sendCdpCommand(
                ws,
                pendingResponses,
                3,
                "Runtime.evaluate",
                Map.of("expression", "document.documentElement.outerHTML", "returnByValue", true),
                10
            );
            String dom = evalRes.path("result").path("result").path("value").asString("");
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.debug("Warm CDP Chrome fetched {} ({} chars) in {} ms", url, dom.length(), elapsedMs);
            return dom;
        } finally {
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                } catch (Exception ignored) {}
            }
            try {
                HttpRequest closeReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/close/" + targetId))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
                client.send(closeReq, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        }
    }

    private JsonNode sendCdpCommand(
            WebSocket ws,
            Map<Integer, CompletableFuture<JsonNode>> pendingResponses,
            int id,
            String method,
            Map<String, Object> params,
            int timeoutSec) throws Exception {
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pendingResponses.put(id, fut);
        String payload = objectMapper.writeValueAsString(Map.of(
            "id", id,
            "method", method,
            "params", params
        ));
        ws.sendText(payload, true).get(5, TimeUnit.SECONDS);
        return fut.get(timeoutSec, TimeUnit.SECONDS);
    }

    private int ensureWarmChromeRunning() throws Exception {
        Process proc = this.warmChromeProcess;
        if (proc != null && proc.isAlive() && this.warmDebugPort > 0) {
            return this.warmDebugPort;
        }
        synchronized (cdpLock) {
            if (this.warmChromeProcess != null && this.warmChromeProcess.isAlive() && this.warmDebugPort > 0) {
                return this.warmDebugPort;
            }
            stopWarmChrome();

            int freePort;
            try (ServerSocket ss = new ServerSocket(0)) {
                freePort = ss.getLocalPort();
            }
            Path profileDir = Files.createTempDirectory("glasgow-prop-chrome-cdp-");

            List<String> cmd = List.of(
                resolvedChromePath,
                "--headless=new",
                "--remote-debugging-port=" + freePort,
                "--remote-debugging-address=127.0.0.1",
                "--remote-allow-origins=*",
                "--user-data-dir=" + profileDir.toAbsolutePath(),
                "--blink-settings=imagesEnabled=false",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-sync",
                "--no-first-run",
                "--mute-audio",
                "--window-size=1920,1080",
                "about:blank"
            );

            Process started = new ProcessBuilder(cmd).start();
            drainStreamAsync(started.getInputStream());
            drainStreamAsync(started.getErrorStream());

            // Poll /json/version for up to 6 seconds until CDP HTTP listener is ready
            HttpClient client = getCdpHttpClient();
            URI versionUri = URI.create("http://127.0.0.1:" + freePort + "/json/version");
            boolean ready = false;
            for (int attempt = 0; attempt < 24; attempt++) {
                if (!started.isAlive()) {
                    break;
                }
                try {
                    HttpRequest req = HttpRequest.newBuilder(versionUri).timeout(Duration.ofMillis(400)).GET().build();
                    HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 200) {
                        ready = true;
                        break;
                    }
                } catch (Exception ignored) {
                    Thread.sleep(250);
                }
            }

            if (!ready) {
                killProcessTree(started);
                deleteDirectoryQuietly(profileDir);
                throw new IOException("Timed out waiting for warm Chrome CDP port " + freePort);
            }

            this.warmChromeProcess = started;
            this.warmDebugPort = freePort;
            this.warmProfileDir = profileDir;
            log.info("Started warm persistent Headless Chrome CDP daemon on 127.0.0.1:{}", freePort);
            return freePort;
        }
    }

    private void stopWarmChrome() {
        synchronized (cdpLock) {
            Process proc = this.warmChromeProcess;
            this.warmChromeProcess = null;
            this.warmDebugPort = -1;
            if (proc != null) {
                killProcessTree(proc);
            }
            Path dir = this.warmProfileDir;
            this.warmProfileDir = null;
            if (dir != null) {
                deleteDirectoryQuietly(dir);
            }
        }
    }

    private HttpClient getCdpHttpClient() {
        HttpClient client = this.cdpHttpClient;
        if (client == null) {
            synchronized (cdpLock) {
                client = this.cdpHttpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                    this.cdpHttpClient = client;
                }
            }
        }
        return client;
    }

    private void drainStreamAsync(InputStream stream) {
        Thread.ofVirtual().start(() -> {
            try (InputStream in = stream) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {}
        });
    }

    private void deleteDirectoryQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private String fetchViaDumpDom(String url, int timeoutSeconds) throws IOException, InterruptedException, TimeoutException {
        List<String> command = List.of(
            resolvedChromePath,
            "--headless=new",
            "--blink-settings=imagesEnabled=false",
            "--disable-gpu",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-extensions",
            "--disable-background-networking",
            "--no-first-run",
            "--mute-audio",
            "--window-size=1920,1080",
            "--dump-dom",
            url
        );

        long startMs = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Thread outThread = Thread.ofVirtual().start(() -> {
            try (InputStream is = process.getInputStream()) {
                is.transferTo(stdout);
            } catch (IOException ignored) {}
        });

        Thread errThread = Thread.ofVirtual().start(() -> {
            try (InputStream es = process.getErrorStream()) {
                es.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {}
        });

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            killProcessTree(process);
            Thread.currentThread().interrupt();
            throw ie;
        }
        if (!finished) {
            killProcessTree(process);
            throw new TimeoutException("Headless Chrome timed out after " + timeoutSeconds + "s fetching " + url);
        }

        try {
            outThread.join(2000);
            errThread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        int exitCode = process.exitValue();
        String dom = stdout.toString(StandardCharsets.UTF_8);
        long elapsedMs = System.currentTimeMillis() - startMs;
        log.debug("Headless Chrome (--dump-dom) fetched {} ({} chars, exitCode={}) in {} ms", url, dom.length(), exitCode, elapsedMs);

        return dom;
    }

    private void killProcessTree(Process process) {
        if (process == null) return;
        try {
            process.descendants().forEach(ph -> {
                try {
                    ph.destroyForcibly();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {}
    }

    private String detectChromeExecutable() {
        if (configuredChromePath != null && !configuredChromePath.isBlank()) {
            Path p = Path.of(configuredChromePath);
            if (Files.isExecutable(p)) return p.toAbsolutePath().toString();
        }

        String envChrome = System.getenv("CHROME_PATH");
        if (envChrome != null && !envChrome.isBlank()) {
            Path p = Path.of(envChrome);
            if (Files.isExecutable(p)) return p.toAbsolutePath().toString();
        }

        List<String> candidates = List.of(
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            System.getenv("LOCALAPPDATA") != null ? System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe" : "",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        );

        for (String c : candidates) {
            if (c.isBlank()) continue;
            try {
                Path p = Path.of(c);
                if (Files.exists(p) && Files.isExecutable(p)) {
                    return p.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {}
        }

        return null;
    }
}
