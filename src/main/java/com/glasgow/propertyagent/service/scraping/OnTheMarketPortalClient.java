package com.glasgow.propertyagent.service.scraping;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portal client for OnTheMarket search corridors, Next.js card normalization,
 * and detail page enrichment (REF-01).
 */
@Service
public class OnTheMarketPortalClient {

    private static final Logger log = LoggerFactory.getLogger(OnTheMarketPortalClient.class);
    private static final Pattern NEXT_DATA_PATTERN = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern BEDROOM_PATTERN = Pattern.compile("(\\d+)\\s*(?:bed|bedroom)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BATHROOM_PATTERN = Pattern.compile("(\\d+)\\s*(?:bath|bathroom)", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_COVER_IMG =
        "https://images.unsplash.com/photo-1568605114967-8130f3a36994?auto=format&fit=crop&w=800&q=80";

    private final ObjectMapper objectMapper;
    private final String userAgent;
    private volatile HttpClient httpClient;

    public OnTheMarketPortalClient(
            ObjectMapper objectMapper,
            @Value("${property-agent.rightmove.user-agent}") String userAgent) {
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

    public List<String> resolveCorridorSlugs(String centerLocation) {
        String primarySlug = toLocationSlug(centerLocation);
        List<String> locations = new ArrayList<>();
        locations.add(primarySlug);
        if (primarySlug.equals("glasgow")) {
            for (String extra : List.of("uddingston", "cambuslang", "east-kilbride", "motherwell")) {
                if (!locations.contains(extra)) {
                    locations.add(extra);
                }
            }
        }
        return locations;
    }

    public String buildSearchUrl(
            String corridorSlug,
            double radiusMiles,
            Set<String> allowedTypes,
            int maxPrice,
            int minBedrooms,
            int pageIdx) {
        String otmCategory = "property";
        if (allowedTypes != null && !allowedTypes.isEmpty() && allowedTypes.size() < 5) {
            boolean hasFlats = allowedTypes.contains("Flat");
            boolean hasHouses = allowedTypes.stream().anyMatch(t -> !t.equals("Flat"));
            if (hasHouses && !hasFlats) {
                otmCategory = "houses";
            } else if (hasFlats && !hasHouses) {
                otmCategory = "flats";
            }
        }

        StringBuilder otmParams = new StringBuilder();
        if (maxPrice > 0 && maxPrice < 5_000_000) {
            otmParams.append("&max-price=").append(maxPrice);
        }
        if (minBedrooms > 1) {
            otmParams.append("&min-bedrooms=").append(minBedrooms);
        }

        double portalRadius = Math.min(40.0, radiusMiles);
        int humanPage = pageIdx + 1;
        return (pageIdx == 0)
            ? String.format(Locale.ROOT, "https://www.onthemarket.com/for-sale/%s/%s/?radius=%.1f%s", otmCategory, corridorSlug, portalRadius, otmParams)
            : String.format(Locale.ROOT, "https://www.onthemarket.com/for-sale/%s/%s/?radius=%.1f&page=%d%s", otmCategory, corridorSlug, portalRadius, humanPage, otmParams);
    }

    public String fetchSearchPageNextData(String url, String corridorSlug) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
            .timeout(Duration.ofSeconds(12))
            .GET()
            .build();

        HttpResponse<String> response = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Received HTTP {} from {} — stopping pagination for corridor '{}'", response.statusCode(), url, corridorSlug);
            return null;
        }
        Matcher m = NEXT_DATA_PATTERN.matcher(response.body());
        if (!m.find()) {
            log.info("No __NEXT_DATA__ payload on {} — stopping pagination for corridor '{}'", url, corridorSlug);
            return null;
        }
        return m.group(1);
    }

    /**
     * Parses OnTheMarket's __NEXT_DATA__ search payload into normalized {@link PortalListingCandidate} cards.
     */
    public PortalListingCandidate.PageCards parseSearchCards(String nextDataJson) {
        JsonNode root = objectMapper.readTree(nextDataJson);
        JsonNode resultsNode = root.path("props")
            .path("pageProps")
            .path("initialReduxState")
            .path("results");
        JsonNode listNode = resultsNode.path("list");
        if (!listNode.isArray() || listNode.isEmpty()) {
            return new PortalListingCandidate.PageCards(0, 30, List.of());
        }

        int totalResults = resultsNode.path("totalResults").asInt(
            resultsNode.path("propertyCount").asInt(0)
        );

        List<PortalListingCandidate> candidates = new ArrayList<>();
        for (JsonNode item : listNode) {
            String rawId = item.path("id").asString("");
            if (rawId.isBlank()) continue;

            int priceGbp = parseSafePriceGbp(item);
            String qualifier = item.path("price-qualifier").asString("Offers Over");
            if (qualifier.isBlank()) qualifier = "Offers Over";

            String address = item.path("address").asString("Greater Glasgow");
            String propTitleRaw = item.path("property-title").asString("");
            String rawType = item.path("humanised-property-type").asString(propTitleRaw);
            String normalizedType = PortalListingCandidate.normalizePropertyType(rawType);

            int bedrooms = item.path("bedrooms").asInt(0);
            if (bedrooms <= 0 && !propTitleRaw.isBlank()) {
                Matcher m = BEDROOM_PATTERN.matcher(propTitleRaw);
                if (m.find()) {
                    try { bedrooms = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
                }
            }
            int bathrooms = item.path("bathrooms").asInt(0);
            if (bathrooms <= 0 && !propTitleRaw.isBlank()) {
                Matcher m = BATHROOM_PATTERN.matcher(propTitleRaw);
                if (m.find()) {
                    try { bathrooms = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
                }
            }

            double lat = item.path("location").path("lat").asDouble(0.0);
            double lon = item.path("location").path("lon").asDouble(0.0);

            List<String> featuresList = new ArrayList<>();
            JsonNode featuresNode = item.path("features");
            if (featuresNode.isArray()) {
                for (JsonNode f : featuresNode) {
                    String ft = f.asString("");
                    if (!ft.isBlank()) featuresList.add(ft);
                }
            }

            String detailsPath = item.path("details-url").asString("/details/" + rawId + "/");
            String listingUrl = detailsPath.startsWith("http")
                ? detailsPath
                : "https://www.onthemarket.com" + detailsPath;

            String coverImg = item.path("cover-image").path("default").asString(DEFAULT_COVER_IMG);
            if (coverImg.isBlank()) coverImg = DEFAULT_COVER_IMG;

            String propTitle = !propTitleRaw.isBlank() ? propTitleRaw : (bedrooms + " bed " + normalizedType);
            String agentName = item.path("agent").path("name").asString("Local Agent");

            candidates.add(new PortalListingCandidate(
                "ONTHEMARKET",
                rawId,
                "OTM-" + rawId,
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
                List.of(),
                featuresList,
                "",
                propTitle,
                agentName
            ));
        }

        return new PortalListingCandidate.PageCards(totalResults, 30, candidates);
    }

    public PortalListingCandidate.DetailEnrichment fetchDetail(PortalListingCandidate candidate) {
        return fetchDetail(candidate.listingUrl(), candidate.coverImgUrl());
    }

    public PortalListingCandidate.DetailEnrichment fetchDetail(String detailUrl, String fallbackImg) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(detailUrl))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
            HttpResponse<String> res = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                Matcher m = NEXT_DATA_PATTERN.matcher(res.body());
                if (m.find()) {
                    JsonNode prop = objectMapper.readTree(m.group(1))
                        .path("props")
                        .path("pageProps")
                        .path("initialReduxState")
                        .path("property");

                    String rawHtmlDesc = prop.path("description").asString("");
                    String cleanDesc = Jsoup.parse(rawHtmlDesc.replace("<br/>", "\n").replace("<br>", "\n")).text();

                    List<String> floorplanUrls = new ArrayList<>();
                    JsonNode floorplans = prop.path("floorplans");
                    if (floorplans.isArray() && !floorplans.isEmpty()) {
                        for (JsonNode fp : floorplans) {
                            String fpUrl = fp.path("largeUrl").asString(fp.path("original").asString(""));
                            if (!fpUrl.isBlank() && fpUrl.startsWith("http")) {
                                floorplanUrls.add(fpUrl);
                            }
                        }
                    }
                    String floorplanUrl = !floorplanUrls.isEmpty() ? floorplanUrls.get(0) : fallbackImg;

                    List<String> extraFeatures = new ArrayList<>();
                    JsonNode feats = prop.path("features");
                    if (feats.isArray()) {
                        for (JsonNode f : feats) {
                            String featText = f.path("feature").asString("");
                            if (!featText.isBlank()) extraFeatures.add(featText);
                        }
                    }

                    List<String> galleryImages = new ArrayList<>();
                    JsonNode imagesNode = prop.path("images");
                    if (imagesNode.isArray()) {
                        for (JsonNode img : imagesNode) {
                            String lg = img.path("largeUrl").asString(img.path("default").asString(""));
                            if (!lg.isBlank() && lg.startsWith("http")) {
                                galleryImages.add(lg);
                            }
                        }
                    }
                    if (galleryImages.isEmpty() && fallbackImg != null && fallbackImg.startsWith("http")) {
                        galleryImages.add(fallbackImg);
                    }
                    return new PortalListingCandidate.DetailEnrichment(cleanDesc, floorplanUrl, floorplanUrls, extraFeatures, galleryImages);
                }
            }
        } catch (Exception ignored) {}
        return new PortalListingCandidate.DetailEnrichment(
            "", fallbackImg, List.of(), List.of(), fallbackImg != null ? List.of(fallbackImg) : List.of()
        );
    }

    private int parseSafePriceGbp(JsonNode item) {
        if (item == null || item.isMissingNode()) return -1;
        JsonNode priceNode = item.path("price");
        if (priceNode.isNumber()) {
            long val = priceNode.asLong(-1L);
            return val > 0 ? (int) Math.min(Integer.MAX_VALUE, val) : -1;
        }
        int parsed = PortalListingCandidate.parseSafePriceString(priceNode.asString(""));
        if (parsed > 0) return parsed;
        return PortalListingCandidate.parseSafePriceString(item.path("short-price").asString(""));
    }

    private String toLocationSlug(String centerLocation) {
        if (centerLocation == null || centerLocation.isBlank()) return "glasgow";
        String clean = centerLocation.toLowerCase(Locale.ROOT)
            .replaceAll("\\(.*?\\)", "")
            .replace("station", "")
            .replace("central", "")
            .replace("gilmour street", "")
            .trim()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        return clean.isBlank() ? "glasgow" : clean;
    }
}
