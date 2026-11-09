package com.glasgow.propertyagent.service.scraping;

import com.glasgow.propertyagent.service.app.PropertyIngestionPipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portal-agnostic normalized property card extracted from either Rightmove or OnTheMarket search results.
 * Enables a single unified ingestion pipeline ({@link PropertyIngestionPipeline}) across all portals.
 */
public record PortalListingCandidate(
    String portal,
    String rawId,
    String listingId,
    int priceGbp,
    String priceQualifier,
    String address,
    String normalizedType,
    int bedrooms,
    int bathrooms,
    double latitude,
    double longitude,
    String listingUrl,
    String coverImgUrl,
    List<String> searchGalleryUrls,
    List<String> keyFeatures,
    String summary,
    String propertyTitle,
    String agentName
) {

    public record DetailEnrichment(
        String fullDescription,
        String floorplanUrl,
        List<String> floorplanUrls,
        List<String> extraFeatures,
        List<String> galleryImageUrls
    ) {}

    public record PageCards(
        int totalResults,
        int pageSize,
        List<PortalListingCandidate> candidates
    ) {}

    private static final Pattern FIRST_NUMBER_PATTERN = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmM])?");

    public static String normalizePropertyType(String rawType) {
        if (rawType == null || rawType.isBlank()) return "Unknown";
        String lower = rawType.toLowerCase(Locale.ROOT);
        if (lower.contains("semi-detached") || lower.contains("semi detached") || lower.contains("link-detached")) {
            return "Semi-Detached";
        }
        if (lower.contains("detached")) {
            return "Detached";
        }
        if (lower.contains("terrace") || lower.contains("town house") || lower.contains("townhouse") || lower.contains("mews")) {
            return "Terraced";
        }
        if (lower.contains("bungalow")) {
            return "Bungalow";
        }
        if (lower.contains("flat") || lower.contains("apartment") || lower.contains("maisonette") || lower.contains("penthouse")) {
            return "Flat";
        }
        return "Unknown";
    }

    public static Set<String> buildAllowedTypeSet(List<String> propertyTypes) {
        if (propertyTypes == null || propertyTypes.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String pt : propertyTypes) {
            if (pt == null || pt.isBlank()) continue;
            String lower = pt.trim().toLowerCase(Locale.ROOT);
            if (lower.contains("semi")) {
                set.add("Semi-Detached");
            } else if (lower.contains("detached")) {
                set.add("Detached");
            } else if (lower.contains("terrace")) {
                set.add("Terraced");
            } else if (lower.contains("bungalow")) {
                set.add("Bungalow");
            } else if (lower.contains("flat") || lower.contains("apartment")) {
                set.add("Flat");
            }
        }
        return set;
    }

    public static int parseSafePriceString(String rawPrice) {
        if (rawPrice == null || rawPrice.isBlank()) return -1;
        Matcher m = FIRST_NUMBER_PATTERN.matcher(rawPrice);
        if (!m.find()) return -1;
        String numPart = m.group(1).replace(",", "");
        String suffix = m.group(2);
        try {
            double base = Double.parseDouble(numPart);
            if (suffix != null) {
                if (suffix.equalsIgnoreCase("k")) base *= 1_000.0;
                else if (suffix.equalsIgnoreCase("m")) base *= 1_000_000.0;
            }
            long val = Math.round(base);
            if (val <= 0) return -1;
            return (int) Math.min(Integer.MAX_VALUE, val);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
