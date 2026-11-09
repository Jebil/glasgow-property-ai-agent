package com.glasgow.propertyagent.service.scraping;

import com.glasgow.propertyagent.service.app.StationCommuteService;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portal client for Rightmove search corridors, typeahead location resolution,
 * Next.js card normalization, and Headless Chrome detail/floorplan enrichment (REF-01, SIMP-04).
 */
@Service
public class RightmovePortalClient {

    private static final Logger log = LoggerFactory.getLogger(RightmovePortalClient.class);
    private static final Pattern NEXT_DATA_PATTERN = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern RM_FLOORPLAN_PATTERN = Pattern.compile(
        "https?://media\\.rightmove\\.co\\.uk/(?:property-floorplan/|dir/property-floorplan/)([^\"'\\s\\\\]+)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RM_PAGE_MODEL_PATTERN = Pattern.compile(
        "window\\.__PAGE_MODEL\\s*=\\s*\\{\\s*\"data\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"encoding\"",
        Pattern.DOTALL
    );
    private static final Pattern BEDROOM_PATTERN = Pattern.compile("(\\d+)\\s*(?:bed|bedroom)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BATHROOM_PATTERN = Pattern.compile("(\\d+)\\s*(?:bath|bathroom)", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_COVER_IMG =
        "https://images.unsplash.com/photo-1568605114967-8130f3a36994?auto=format&fit=crop&w=800&q=80";

    public record RightmoveCorridor(
        String code,
        String displayName,
        double approxLat,
        double approxLon,
        double spanMiles,
        double queryRadiusMiles
    ) {}

    private final HeadlessChromeService headlessChromeService;
    private final StationCommuteService stationService;
    private final ObjectMapper objectMapper;
    private final String userAgent;
    private volatile HttpClient httpClient;

    // SIMP-04: Bounded Caffeine cache replacing unbounded ConcurrentHashMap
    private final Cache<String, RightmoveCorridor> typeaheadCorridorCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofHours(24))
        .build();

    public RightmovePortalClient(
            HeadlessChromeService headlessChromeService,
            StationCommuteService stationService,
            ObjectMapper objectMapper,
            @Value("${property-agent.rightmove.user-agent}") String userAgent) {
        this.headlessChromeService = headlessChromeService;
        this.stationService = stationService;
        this.objectMapper = objectMapper;
        this.userAgent = userAgent;
    }

    private HttpClient getHttpClient() {
        HttpClient client = this.httpClient;
        if (client == null) {
            synchronized (this) {
                client = this.httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(12))
                        .build();
                    this.httpClient = client;
                }
            }
        }
        return client;
    }

    public boolean isAvailable() {
        return headlessChromeService != null && headlessChromeService.isChromeAvailable();
    }

    public String buildRightmoveQueryParams(List<String> propertyTypes, int maxPrice, int minBedrooms) {
        StringBuilder sb = new StringBuilder();
        if (propertyTypes != null && !propertyTypes.isEmpty() && propertyTypes.size() < 5) {
            List<String> tokens = new ArrayList<>();
            for (String pt : propertyTypes) {
                if (pt == null || pt.isBlank()) continue;
                String lower = pt.trim().toLowerCase(Locale.ROOT);
                if (lower.contains("semi")) {
                    tokens.add("semi-detached");
                } else if (lower.contains("detached")) {
                    tokens.add("detached");
                } else if (lower.contains("terrace")) {
                    tokens.add("terraced");
                } else if (lower.contains("bungalow")) {
                    tokens.add("bungalow");
                } else if (lower.contains("flat") || lower.contains("apartment")) {
                    tokens.add("flat");
                }
            }
            if (!tokens.isEmpty()) {
                sb.append("&propertyTypes=").append(String.join("%2C", tokens));
            }
        }
        if (maxPrice > 0 && maxPrice < 5_000_000) {
            sb.append("&maxPrice=").append(maxPrice);
        }
        if (minBedrooms > 1) {
            sb.append("&minBedrooms=").append(minBedrooms);
        }
        return sb.toString();
    }

    public double normalizeRightmoveRadius(double radiusMiles) {
        if (radiusMiles <= 0.5) return 0.5;
        if (radiusMiles <= 1.0) return 1.0;
        if (radiusMiles <= 3.0) return 3.0;
        if (radiusMiles <= 5.0) return 5.0;
        if (radiusMiles <= 10.0) return 10.0;
        if (radiusMiles <= 15.0) return 15.0;
        if (radiusMiles <= 20.0) return 20.0;
        if (radiusMiles <= 30.0) return 30.0;
        return 40.0;
    }

    /**
     * BUG-06: Dynamically resolves Rightmove corridors so outer commuter stations and small-radius searches
     * hit the exact target location via Rightmove's typeahead endpoint first, then append spatially-intersecting
     * regional corridors when radius warrants broader coverage.
     */
    public List<RightmoveCorridor> resolveCorridors(String centerLocation, double radiusMiles) {
        String locTrimmed = (centerLocation == null || centerLocation.isBlank()) ? "Glasgow Central" : centerLocation.trim();
        String locLower = locTrimmed.toLowerCase(Locale.ROOT);
        boolean isCentralGlasgow = locLower.equals("glasgow")
            || locLower.startsWith("glasgow central")
            || locLower.startsWith("glasgow queen street");

        double baseRmRadius = normalizeRightmoveRadius(radiusMiles);
        List<RightmoveCorridor> selected = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        if (!isCentralGlasgow) {
            RightmoveCorridor directMatch = lookupRightmoveTypeahead(locTrimmed, baseRmRadius);
            if (directMatch != null && seenCodes.add(directMatch.code())) {
                selected.add(directMatch);
                if (radiusMiles <= 6.0) {
                    return selected;
                }
            }
        }

        List<RightmoveCorridor> regionalDefaults = new ArrayList<>(List.of(
            new RightmoveCorridor("REGION^550", "Greater Glasgow", 55.8609, -4.2514, 7.5, baseRmRadius),
            new RightmoveCorridor("REGION^1021", "Paisley & Renfrewshire", 55.8456, -4.4239, 7.5, baseRmRadius),
            new RightmoveCorridor("REGION^61413", "South Lanarkshire", 55.7780, -4.1100, 12.0, baseRmRadius),
            new RightmoveCorridor("REGION^61412", "North Lanarkshire", 55.8650, -3.9800, 10.0, baseRmRadius),
            new RightmoveCorridor("REGION^462", "East Kilbride", 55.7644, -4.1769, 4.5, baseRmRadius)
        ));

        record OuterHub(String queryName, double lat, double lon, double spanMiles) {}
        List<OuterHub> outerHubs = List.of(
            new OuterHub("East Dunbartonshire", 55.9350, -4.2300, 6.5),
            new OuterHub("East Renfrewshire", 55.7850, -4.3350, 6.0),
            new OuterHub("West Dunbartonshire", 55.9450, -4.5300, 7.5),
            new OuterHub("Helensburgh", 56.0026, -4.7331, 5.0),
            new OuterHub("Inverclyde", 55.9450, -4.7600, 7.0),
            new OuterHub("North Ayrshire", 55.6550, -4.6950, 10.0),
            new OuterHub("South Ayrshire", 55.4650, -4.6150, 10.0)
        );
        for (OuterHub hub : outerHubs) {
            double dist = stationService.calculateDistanceFromCenterMiles(locTrimmed, hub.lat(), hub.lon());
            if (dist - hub.spanMiles() <= radiusMiles) {
                RightmoveCorridor resolved = lookupRightmoveTypeahead(hub.queryName(), baseRmRadius);
                if (resolved != null) {
                    regionalDefaults.add(new RightmoveCorridor(
                        resolved.code(), resolved.displayName(), hub.lat(), hub.lon(), hub.spanMiles(), baseRmRadius
                    ));
                }
            }
        }

        for (RightmoveCorridor reg : regionalDefaults) {
            double distFromCenter = stationService.calculateDistanceFromCenterMiles(locTrimmed, reg.approxLat(), reg.approxLon());
            if (distFromCenter - reg.spanMiles() <= radiusMiles && seenCodes.add(reg.code())) {
                double effectiveQueryRadius = selected.isEmpty() && !isCentralGlasgow
                    ? normalizeRightmoveRadius(Math.min(40.0, Math.max(radiusMiles, distFromCenter - reg.spanMiles() * 0.5 + radiusMiles)))
                    : baseRmRadius;
                selected.add(new RightmoveCorridor(
                    reg.code(), reg.displayName(), reg.approxLat(), reg.approxLon(), reg.spanMiles(), effectiveQueryRadius
                ));
            }
        }

        if (selected.isEmpty()) {
            selected.add(new RightmoveCorridor("REGION^550", "Greater Glasgow", 55.8609, -4.2514, 7.5, normalizeRightmoveRadius(Math.max(15.0, radiusMiles))));
        }
        return selected;
    }

    private RightmoveCorridor lookupRightmoveTypeahead(String rawLocation, double queryRadiusMiles) {
        if (rawLocation == null || rawLocation.isBlank()) return null;
        String clean = rawLocation.replaceAll("\\(.*?\\)", "").replace("&", " ").replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return null;

        RightmoveCorridor cached = typeaheadCorridorCache.getIfPresent(clean.toLowerCase(Locale.ROOT));
        if (cached != null) {
            return new RightmoveCorridor(cached.code(), cached.displayName(), cached.approxLat(), cached.approxLon(), cached.spanMiles(), queryRadiusMiles);
        }

        boolean looksLikePostcode = clean.matches("(?i)^(G|PA|ML|KA|FK|G\\d|EH)\\d.*");
        List<String> queries = looksLikePostcode ? List.of(clean) : List.of(clean + " Scotland", clean);
        Set<String> scottishHints = Set.of(
            "scotland", "glasgow", "lanarkshire", "renfrewshire", "dunbartonshire",
            "ayrshire", "inverclyde", "argyll", "stirling", "falkirk", "edinburgh", "lothian", "clyde"
        );

        for (String q : queries) {
            try {
                String url = "https://los.rightmove.co.uk/typeahead?query="
                    + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&limit=6";
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("Referer", "https://www.rightmove.co.uk/")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                HttpResponse<String> resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    JsonNode matches = objectMapper.readTree(resp.body()).path("matches");
                    if (matches.isArray()) {
                        for (JsonNode m : matches) {
                            String id = m.path("id").asString("");
                            String type = m.path("type").asString("");
                            String displayName = m.path("displayName").asString(clean);
                            if (id.isBlank() || type.isBlank()) continue;
                            String dispLower = displayName.toLowerCase(Locale.ROOT);
                            boolean scottishMatch = looksLikePostcode
                                || q.endsWith("Scotland")
                                || scottishHints.stream().anyMatch(dispLower::contains);
                            if (scottishMatch) {
                                RightmoveCorridor resolved = new RightmoveCorridor(
                                    type + "^" + id, displayName, 0.0, 0.0, 5.0, queryRadiusMiles
                                );
                                typeaheadCorridorCache.put(clean.toLowerCase(Locale.ROOT), resolved);
                                log.info("Resolved Rightmove location '{}' -> {} ({})", rawLocation, resolved.code(), resolved.displayName());
                                return resolved;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Rightmove typeahead lookup skipped for '{}': {}", q, e.getMessage());
            }
        }
        return null;
    }

    public String buildSearchUrl(RightmoveCorridor corridor, int pageIdx, String queryFilters) {
        String encodedIdentifier = URLEncoder.encode(corridor.code(), StandardCharsets.UTF_8);
        int indexOffset = pageIdx * 24;
        return String.format(Locale.ROOT,
            "https://www.rightmove.co.uk/property-for-sale/find.html?searchType=SALE&locationIdentifier=%s&radius=%.1f&sortType=6&index=%d%s",
            encodedIdentifier, corridor.queryRadiusMiles(), indexOffset, queryFilters
        );
    }

    public String fetchSearchPageNextData(String url, String corridorName) throws Exception {
        String html = headlessChromeService.fetchRenderedDom(url, 30);
        if (html == null || html.isBlank()) {
            log.warn("Empty DOM from Rightmove {} — stopping corridor '{}'", url, corridorName);
            return null;
        }
        Matcher m = NEXT_DATA_PATTERN.matcher(html);
        if (!m.find()) {
            log.info("No __NEXT_DATA__ payload on Rightmove {} — stopping corridor '{}'", url, corridorName);
            return null;
        }
        return m.group(1);
    }

    /**
     * Parses Rightmove's __NEXT_DATA__ search payload into normalized {@link PortalListingCandidate} cards.
     */
    public PortalListingCandidate.PageCards parseSearchCards(String nextDataJson) {
        JsonNode root = objectMapper.readTree(nextDataJson);
        JsonNode searchResults = root.path("props").path("pageProps").path("searchResults");
        JsonNode propList = searchResults.path("properties");
        if (!propList.isArray() || propList.isEmpty()) {
            return new PortalListingCandidate.PageCards(0, 24, List.of());
        }

        String resultCountStr = searchResults.path("resultCount").asString("0").replace(",", "").trim();
        int totalResults = 0;
        try { totalResults = Integer.parseInt(resultCountStr); } catch (Exception ignored) {}

        List<PortalListingCandidate> candidates = new ArrayList<>();
        for (JsonNode item : propList) {
            String rawId = item.path("id").asString("");
            if (rawId.isBlank()) continue;

            int priceGbp = item.path("price").path("amount").asInt(0);
            if (priceGbp <= 0) {
                priceGbp = PortalListingCandidate.parseSafePriceString(
                    item.path("price").path("displayPrices").path(0).path("displayPrice").asString("")
                );
            }
            String qualifier = item.path("price").path("displayPrices").path(0).path("displayPriceQualifier").asString("Offers Over");
            if (qualifier.isBlank()) qualifier = "Offers Over";

            String address = item.path("displayAddress").asString("Greater Glasgow")
                .replace("\r\n", ", ").replace("\n", ", ").trim();
            String fullTypeDesc = item.path("propertyTypeFullDescription").asString("");
            String rawType = item.path("propertySubType").asString(fullTypeDesc);
            String normalizedType = PortalListingCandidate.normalizePropertyType(rawType);

            int bedrooms = item.path("bedrooms").asInt(0);
            if (bedrooms <= 0 && !fullTypeDesc.isBlank()) {
                Matcher mb = BEDROOM_PATTERN.matcher(fullTypeDesc);
                if (mb.find()) {
                    try { bedrooms = Integer.parseInt(mb.group(1)); } catch (Exception ignored) {}
                }
            }

            int bathrooms = item.path("bathrooms").asInt(0);
            List<String> featuresList = new ArrayList<>();
            JsonNode kfNode = item.path("keyFeatures");
            if (kfNode.isArray()) {
                for (JsonNode kf : kfNode) {
                    String f = kf.path("description").asString("");
                    if (!f.isBlank()) featuresList.add(f);
                }
            }
            if (bathrooms <= 0) {
                for (String f : featuresList) {
                    Matcher mbath = BATHROOM_PATTERN.matcher(f);
                    if (mbath.find()) {
                        try { bathrooms = Integer.parseInt(mbath.group(1)); break; } catch (Exception ignored) {}
                    }
                }
            }

            double lat = item.path("location").path("latitude").asDouble(0.0);
            double lon = item.path("location").path("longitude").asDouble(0.0);

            String propUrl = item.path("propertyUrl").asString("");
            String listingUrl = propUrl.startsWith("http") ? propUrl : "https://www.rightmove.co.uk" + propUrl;

            List<String> galleryImages = new ArrayList<>();
            JsonNode imgArray = item.path("propertyImages").path("images");
            if (!imgArray.isArray() || imgArray.isEmpty()) {
                imgArray = item.path("images");
            }
            if (imgArray.isArray()) {
                for (JsonNode img : imgArray) {
                    String path = img.path("url").asString("");
                    if (!path.isBlank()) {
                        galleryImages.add(path.startsWith("http") ? path : "https://media.rightmove.co.uk/" + path);
                    } else {
                        String src = img.path("srcUrl").asString("");
                        if (!src.isBlank()) {
                            galleryImages.add(src);
                        }
                    }
                }
            }

            String coverImg = item.path("propertyImages").path("mainImageSrc").asString("");
            if (coverImg.isBlank() && !galleryImages.isEmpty()) {
                coverImg = galleryImages.get(0);
            }
            if (coverImg.isBlank()) {
                coverImg = DEFAULT_COVER_IMG;
            }

            String summary = item.path("summary").asString("");
            String propTitle = !fullTypeDesc.isBlank() ? fullTypeDesc : (bedrooms + " bed " + normalizedType);
            String agentName = item.path("customer").path("branchDisplayName").asString(
                item.path("customer").path("brandTradingName").asString("Estate Agent")
            );

            candidates.add(new PortalListingCandidate(
                "RIGHTMOVE",
                rawId,
                "RM-" + rawId,
                priceGbp,
                qualifier,
                address,
                normalizedType,
                bedrooms,
                bathrooms,
                lat,
                lon,
                listingUrl,
                coverImg,
                galleryImages,
                featuresList,
                summary,
                propTitle,
                agentName
            ));
        }

        return new PortalListingCandidate.PageCards(totalResults, 24, candidates);
    }

    public PortalListingCandidate.DetailEnrichment fetchDetail(PortalListingCandidate candidate) {
        return fetchDetail(
            candidate.listingUrl(),
            candidate.coverImgUrl(),
            candidate.searchGalleryUrls(),
            candidate.keyFeatures(),
            candidate.summary()
        );
    }

    public PortalListingCandidate.DetailEnrichment fetchDetail(
            String listingUrl,
            String fallbackImg,
            List<String> searchGalleryUrls,
            List<String> keyFeatures,
            String summary) {
        List<String> floorplanUrls = new ArrayList<>();
        List<String> galleryImages = searchGalleryUrls != null ? new ArrayList<>(searchGalleryUrls) : new ArrayList<>();
        List<String> extraFeatures = keyFeatures != null ? new ArrayList<>(keyFeatures) : new ArrayList<>();
        String fullDescription = "";

        try {
            String html = headlessChromeService != null ? headlessChromeService.fetchRenderedDom(listingUrl, 25) : null;
            if (html != null && !html.isBlank()) {
                Matcher fpMatcher = RM_FLOORPLAN_PATTERN.matcher(html);
                Set<String> fullResFp = new LinkedHashSet<>();
                Set<String> thumbFp = new LinkedHashSet<>();
                while (fpMatcher.find()) {
                    String u = fpMatcher.group(0);
                    if (u.contains("_max_")) {
                        thumbFp.add(u);
                    } else {
                        fullResFp.add(u);
                    }
                }
                if (!fullResFp.isEmpty()) {
                    floorplanUrls.addAll(fullResFp);
                } else if (!thumbFp.isEmpty()) {
                    floorplanUrls.addAll(thumbFp);
                }

                Matcher pmMatcher = RM_PAGE_MODEL_PATTERN.matcher(html);
                if (pmMatcher.find()) {
                    try {
                        String rawEscaped = pmMatcher.group(1);
                        String unescaped = objectMapper.readValue("\"" + rawEscaped + "\"", String.class);
                        JsonNode arr = objectMapper.readTree(unescaped);
                        if (arr.isArray() && !arr.isEmpty()) {
                            JsonNode root = arr.get(0);
                            int propDataIdx = root.path("propertyData").asInt(-1);
                            if (propDataIdx >= 0 && propDataIdx < arr.size()) {
                                JsonNode propData = arr.get(propDataIdx);
                                int textIdx = propData.path("text").asInt(-1);
                                if (textIdx >= 0 && textIdx < arr.size()) {
                                    JsonNode textObj = arr.get(textIdx);
                                    int descIdx = textObj.path("description").asInt(-1);
                                    if (descIdx >= 0 && descIdx < arr.size()) {
                                        String rawHtmlDesc = arr.get(descIdx).asString("");
                                        fullDescription = Jsoup.parse(rawHtmlDesc.replace("<br/>", "\n").replace("<br>", "\n")).text();
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("Could not parse PAGE_MODEL for {}: {}", listingUrl, ex.getMessage());
                    }
                }

                if (fullDescription.isBlank()) {
                    Matcher mDesc = Pattern.compile("<div[^>]*itemprop=\"description\"[^>]*>(.*?)</div>", Pattern.DOTALL).matcher(html);
                    if (mDesc.find()) {
                        fullDescription = Jsoup.parse(mDesc.group(1)).text();
                    }
                }

                if (galleryImages.isEmpty()) {
                    Matcher imgMatcher = Pattern.compile(
                        "https?://media\\.rightmove\\.co\\.uk/(?!property-floorplan/|dir/property-floorplan/)(?:dir/)?[^\"'\\s\\\\]+\\.(?:jpe?g|png|webp)",
                        Pattern.CASE_INSENSITIVE
                    ).matcher(html);
                    Set<String> foundImgs = new LinkedHashSet<>();
                    while (imgMatcher.find()) {
                        String imgUrl = imgMatcher.group(0);
                        if (!imgUrl.contains("_max_135x") && !imgUrl.contains("company-logo") && !imgUrl.contains("branch-logo")) {
                            foundImgs.add(imgUrl);
                        }
                    }
                    galleryImages.addAll(foundImgs);
                }
            }
        } catch (Exception e) {
            log.warn("Error fetching Rightmove detail page {} via headless Chrome: {}", listingUrl, e.getMessage());
        }

        String safeSummary = summary == null ? "" : summary;
        if (fullDescription.isBlank()) {
            if (!safeSummary.isBlank() && !extraFeatures.isEmpty()) {
                fullDescription = safeSummary + " Key features: " + String.join("; ", extraFeatures) + ".";
            } else if (!safeSummary.isBlank()) {
                fullDescription = safeSummary;
            } else if (!extraFeatures.isEmpty()) {
                fullDescription = "Key features: " + String.join("; ", extraFeatures) + ".";
            }
        }

        String floorplanUrl = !floorplanUrls.isEmpty() ? floorplanUrls.get(0) : fallbackImg;
        if (galleryImages.isEmpty() && fallbackImg != null && fallbackImg.startsWith("http")) {
            galleryImages.add(fallbackImg);
        }

        return new PortalListingCandidate.DetailEnrichment(fullDescription, floorplanUrl, floorplanUrls, extraFeatures, galleryImages);
    }
}
