package com.glasgow.propertyagent.service.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-key OpenStreetMap Overpass & UK NaPTAN transit stop lookup with persistent local JSON cache.
 * Finds actual nearby bus stops, walking distance, operators (First Glasgow, McGill's, Stagecoach),
 * and active bus route numbers (e.g. 2, 3, 18, 38, 60, 240, 267, X11) around each property.
 */
@Component
public class OsmOverpassTransitClient {

    private static final Logger log = LoggerFactory.getLogger(OsmOverpassTransitClient.class);
    private static final Path CACHE_FILE = Path.of("data", "transit-cache", "osm-bus-stops.json");
    private static final List<String> OVERPASS_ENDPOINTS = List.of(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    );

    public record NearbyBusTransitInfo(
        boolean osmVerified,
        String nearestStopName,
        double nearestStopDistanceMiles,
        int walkToStopMins,
        int stopsWithin600m,
        List<String> routeRefs,
        String primaryOperator,
        int estimatedPeakBusesPerHour
    ) {}

    private final ObjectMapper objectMapper;
    private volatile HttpClient httpClient;
    private final Map<String, NearbyBusTransitInfo> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> fallbackRetryAfterMs = new ConcurrentHashMap<>();

    public OsmOverpassTransitClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        loadCacheFromDisk();
    }

    private HttpClient getHttpClient() {
        HttpClient client = this.httpClient;
        if (client == null) {
            synchronized (this) {
                client = this.httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
                    this.httpClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Pure in-memory cache lookup or deterministic fallback.
     * Guaranteed never to make an HTTP network request — used during /rank evaluation.
     */
    public NearbyBusTransitInfo lookupCachedOrFallback(double lat, double lon, double distToTargetMiles, String fallbackCorridorName) {
        if (lat != 0.0 && lon != 0.0) {
            String gridKey = String.format(Locale.ROOT, "%.3f,%.3f", lat, lon);
            NearbyBusTransitInfo cached = cache.get(gridKey);
            if (cached != null) {
                return cached;
            }
        }
        return fallbackInfo(distToTargetMiles, fallbackCorridorName);
    }

    public NearbyBusTransitInfo getCached(double lat, double lon) {
        if (lat == 0.0 || lon == 0.0) return null;
        String gridKey = String.format(Locale.ROOT, "%.3f,%.3f", lat, lon);
        return cache.get(gridKey);
    }

    public NearbyBusTransitInfo lookupNearbyBusStops(double lat, double lon, double distToTargetMiles, String fallbackCorridorName) {
        if (lat == 0.0 || lon == 0.0) {
            return fallbackInfo(distToTargetMiles, fallbackCorridorName);
        }

        String gridKey = String.format(Locale.ROOT, "%.3f,%.3f", lat, lon);
        NearbyBusTransitInfo cached = cache.get(gridKey);
        if (cached != null) {
            return cached;
        }
        Long retryAt = fallbackRetryAfterMs.get(gridKey);
        if (retryAt != null && System.currentTimeMillis() < retryAt) {
            return fallbackInfo(distToTargetMiles, fallbackCorridorName);
        }

        NearbyBusTransitInfo fetched = queryOverpass(lat, lon, distToTargetMiles, fallbackCorridorName);
        if (fetched != null && fetched.osmVerified()) {
            fallbackRetryAfterMs.remove(gridKey);
            cache.put(gridKey, fetched);
            saveCacheToDisk();
            return fetched;
        }

        fallbackRetryAfterMs.put(gridKey, System.currentTimeMillis() + 60_000L);
        return fallbackInfo(distToTargetMiles, fallbackCorridorName);
    }

    private NearbyBusTransitInfo queryOverpass(double lat, double lon, double distToTargetMiles, String fallbackCorridorName) {
        String ql = String.format(Locale.ROOT,
            "[out:json][timeout:3];" +
            "(" +
            "node(around:650,%.5f,%.5f)[\"highway\"=\"bus_stop\"];" +
            "node(around:650,%.5f,%.5f)[\"public_transport\"=\"platform\"][\"bus\"=\"yes\"];" +
            ")->.stops;" +
            ".stops out;" +
            "rel(bn.stops)[\"route\"=\"bus\"];" +
            "out tags;",
            lat, lon, lat, lon
        );
        String formBody = "data=" + URLEncoder.encode(ql, StandardCharsets.UTF_8);

        for (String endpoint : OVERPASS_ENDPOINTS) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "GlasgowPropertyAiAgent/2.0 (Local Commuter Transit Lookup)")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

                HttpResponse<String> res = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200 || res.body() == null || res.body().isBlank()) {
                    continue;
                }

                JsonNode root = objectMapper.readTree(res.body());
                JsonNode elements = root.path("elements");
                if (!elements.isArray()) {
                    continue;
                }

                String bestStopName = null;
                double bestDistMiles = Double.MAX_VALUE;
                int stopCount = 0;
                Set<String> routes = new LinkedHashSet<>();
                Set<String> operators = new LinkedHashSet<>();

                for (JsonNode el : elements) {
                    String type = el.path("type").asString("");
                    JsonNode tags = el.path("tags");

                    if ("node".equals(type)) {
                        stopCount++;
                        double sLat = el.path("lat").asDouble(0.0);
                        double sLon = el.path("lon").asDouble(0.0);
                        double d = StationCommuteService.haversineMiles(lat, lon, sLat, sLon);

                        String name = firstNonBlank(
                            tags.path("name").asString(""),
                            tags.path("naptan:CommonName").asString(""),
                            tags.path("naptan:Street").asString("")
                        );
                        String indicator = tags.path("naptan:Indicator").asString("");
                        if (!name.isBlank() && !indicator.isBlank() && !name.toLowerCase().contains(indicator.toLowerCase())) {
                            name = name + " (" + indicator + ")";
                        }

                        String routeRef = tags.path("route_ref").asString("");
                        if (!routeRef.isBlank()) {
                            for (String r : routeRef.split("[;,\\s]+")) {
                                if (!r.isBlank() && routes.size() < 8) routes.add(r.trim());
                            }
                        }
                        String op = firstNonBlank(tags.path("operator").asString(""), tags.path("network").asString(""));
                        if (!op.isBlank()) operators.add(op);

                        if (d < bestDistMiles) {
                            bestDistMiles = d;
                            if (!name.isBlank()) {
                                bestStopName = name;
                            }
                        }
                    } else if ("relation".equals(type)) {
                        String ref = tags.path("ref").asString("").trim();
                        if (!ref.isBlank() && routes.size() < 8) {
                            routes.add(ref);
                        }
                        String op = firstNonBlank(tags.path("operator").asString(""), tags.path("network").asString(""));
                        if (!op.isBlank()) {
                            operators.add(op);
                        }
                    }
                }

                if (stopCount == 0 && routes.isEmpty()) {
                    // Valid OSM response, just rural / >650m from any mapped NaPTAN bus stop
                    return new NearbyBusTransitInfo(
                        true,
                        fallbackCorridorName + " Stop (>0.4 mi)",
                        0.45,
                        9,
                        0,
                        List.of(),
                        fallbackCorridorName,
                        2
                    );
                }

                double streetWalkMiles = Math.round(Math.min(0.6, bestDistMiles * 1.22) * 100.0) / 100.0;
                int walkMins = Math.max(1, (int) Math.round(streetWalkMiles * 19.0)); // ~3.1 mph walking speed
                String resolvedStop = (bestStopName != null && !bestStopName.isBlank())
                    ? bestStopName
                    : fallbackCorridorName + " NaPTAN Stop";
                String primaryOp = operators.isEmpty() ? fallbackCorridorName : operators.iterator().next();

                int routeCount = routes.size();
                int estFreq;
                if (routeCount >= 4) {
                    estFreq = distToTargetMiles <= 6.0 ? 12 : 8;
                } else if (routeCount >= 2) {
                    estFreq = distToTargetMiles <= 8.0 ? 8 : 6;
                } else if (routeCount == 1) {
                    estFreq = distToTargetMiles <= 8.0 ? 5 : 4;
                } else {
                    estFreq = stopCount >= 4 ? 6 : (distToTargetMiles <= 8.0 ? 4 : 3);
                }

                NearbyBusTransitInfo result = new NearbyBusTransitInfo(
                    true,
                    resolvedStop,
                    streetWalkMiles,
                    walkMins,
                    stopCount,
                    new ArrayList<>(routes),
                    primaryOp,
                    estFreq
                );

                log.info("[OSM-Transit] Verified NaPTAN/OSM stops near ({}, {}): stop='{}' ({} mi, {}m walk), {} stops in 650m, routes={}, op='{}'",
                    String.format(Locale.ROOT, "%.4f", lat), String.format(Locale.ROOT, "%.4f", lon),
                    resolvedStop, streetWalkMiles, walkMins, stopCount, routes, primaryOp);

                return result;
            } catch (Exception e) {
                log.debug("Overpass endpoint {} timed out or failed: {}", endpoint, e.getMessage());
            }
        }
        return null;
    }

    private NearbyBusTransitInfo fallbackInfo(double distToTargetMiles, String fallbackCorridorName) {
        int freq = distToTargetMiles <= 6.5 ? 8 : (distToTargetMiles <= 11.0 ? 6 : 4);
        return new NearbyBusTransitInfo(
            false,
            fallbackCorridorName + " Stop (~0.2 mi)",
            0.2,
            4,
            2,
            List.of(),
            fallbackCorridorName,
            freq
        );
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private void loadCacheFromDisk() {
        try {
            if (Files.exists(CACHE_FILE)) {
                Map<String, NearbyBusTransitInfo> loaded = objectMapper.readValue(
                    CACHE_FILE.toFile(),
                    new TypeReference<Map<String, NearbyBusTransitInfo>>() {}
                );
                if (loaded != null) {
                    cache.putAll(loaded);
                    log.info("Loaded {} cached OpenStreetMap/NaPTAN bus stop lookups from {}", loaded.size(), CACHE_FILE);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load OSM transit cache: {}", e.getMessage());
        }
    }

    private synchronized void saveCacheToDisk() {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(CACHE_FILE.toFile(), cache);
        } catch (Exception e) {
            log.debug("Could not persist OSM transit cache: {}", e.getMessage());
        }
    }
}
