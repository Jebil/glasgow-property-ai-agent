package com.glasgow.propertyagent.service.app;

import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.model.SchoolAndAreaProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FEAT-03 & FEAT-07: Resolves Greater Glasgow & Strathclyde commuter-belt properties to
 * likely non-denominational & denominational secondary school catchments, S5/S6 attainment tier,
 * SIMD neighbourhood decile, Council Tax band guide, and typical area Offers-Over closing uplift.
 */
@Service
public class GlasgowSchoolAndAreaService {

    private static final Logger log = LoggerFactory.getLogger(GlasgowSchoolAndAreaService.class);
    private static final String DATASET_RESOURCE = "/data/glasgow-schools-and-areas.json";
    private static final Pattern POSTCODE_SECTOR_PATTERN = Pattern.compile("\\b((?:G|ML|PA|KA|FK)\\d{1,2})\\s+(\\d)[A-Z]{0,2}\\b");
    private static final Pattern POSTCODE_DISTRICT_PATTERN = Pattern.compile("\\b([A-Z]{1,2}\\d{1,2})[A-Z]?\\b");

    public record AreaHubEntry(
        String neighbourhoodName,
        String councilArea,
        double lat,
        double lon,
        double radiusMiles,
        List<String> postcodePrefixes,
        List<String> keywords,
        String nonDenomSecondary,
        String denomSecondary,
        String attainmentTier,
        int higherPassPctApprox,
        int simdDecile,
        String councilTaxGuide,
        int typicalOffersOverPct
    ) {}

    private final List<AreaHubEntry> areaHubs;

    public GlasgowSchoolAndAreaService() {
        this(new ObjectMapper());
    }

    @Autowired
    public GlasgowSchoolAndAreaService(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        try (InputStream is = GlasgowSchoolAndAreaService.class.getResourceAsStream(DATASET_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Missing school/area dataset: " + DATASET_RESOURCE);
            }
            List<AreaHubEntry> loaded = mapper.readValue(is, new TypeReference<List<AreaHubEntry>>() {});
            this.areaHubs = loaded != null ? Collections.unmodifiableList(loaded) : List.of();
            log.info("Loaded {} Greater Glasgow school catchment & neighbourhood profiles.", this.areaHubs.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + DATASET_RESOURCE + ": " + e.getMessage(), e);
        }
    }

    public List<AreaHubEntry> getAllAreaHubs() {
        return areaHubs;
    }

    public SchoolAndAreaProfile resolveProfile(IndexedPropertyEntity entity) {
        if (entity == null) {
            return defaultProfile();
        }
        return resolveProfile(entity.getAddress(), entity.getTitle(), entity.getLatitude(), entity.getLongitude());
    }

    public SchoolAndAreaProfile resolveProfile(String address, String title, double lat, double lon) {
        String addrUpper = address != null ? address.toUpperCase(Locale.ROOT) : "";
        String combinedLower = ((address != null ? address : "") + " " + (title != null ? title : "")).toLowerCase(Locale.ROOT);
        String postcodeSector = extractPostcodeSector(addrUpper);
        String postcodeDistrict = extractPostcodeDistrict(addrUpper);
        boolean hasCoords = lat != 0.0 && lon != 0.0;

        AreaHubEntry bestHub = null;
        double bestScore = -1.0;

        for (AreaHubEntry hub : areaHubs) {
            double score = 0.0;

            if (hub.keywords() != null) {
                for (String kw : hub.keywords()) {
                    if (kw != null && !kw.isBlank() && combinedLower.contains(kw.toLowerCase(Locale.ROOT))) {
                        score += 45.0 + Math.min(10.0, kw.length() * 0.5);
                        break;
                    }
                }
            }

            if (postcodeSector != null && matchesPostcodeSectorForHub(postcodeSector, hub.neighbourhoodName())) {
                score += 52.0;
            }

            if (postcodeDistrict != null && hub.postcodePrefixes() != null) {
                for (String pfx : hub.postcodePrefixes()) {
                    if (postcodeDistrict.equalsIgnoreCase(pfx)) {
                        score += 35.0;
                        break;
                    }
                }
            }

            if (hasCoords) {
                double distMiles = haversineMiles(lat, lon, hub.lat(), hub.lon());
                if (distMiles <= hub.radiusMiles()) {
                    double proximityRatio = 1.0 - (distMiles / Math.max(0.5, hub.radiusMiles()));
                    score += 30.0 + (proximityRatio * 25.0);
                } else if (distMiles <= hub.radiusMiles() * 1.6) {
                    score += 10.0;
                } else if (distMiles > 5.0) {
                    score -= 25.0;
                }
            }

            if (score > bestScore && score >= 20.0) {
                bestScore = score;
                bestHub = hub;
            }
        }

        if (bestHub != null) {
            return refineSectorCatchment(toProfile(bestHub), postcodeSector, combinedLower);
        }

        // Nearest geographic fallback within 4.5 miles if coordinates are present
        if (hasCoords) {
            AreaHubEntry closest = null;
            double minDist = 4.5;
            for (AreaHubEntry hub : areaHubs) {
                double d = haversineMiles(lat, lon, hub.lat(), hub.lon());
                if (d < minDist) {
                    minDist = d;
                    closest = hub;
                }
            }
            if (closest != null) {
                return refineSectorCatchment(toProfile(closest), postcodeSector, combinedLower);
            }
        }

        return defaultProfile();
    }

    private static boolean matchesPostcodeSectorForHub(String sector, String neighbourhoodName) {
        if (sector == null || neighbourhoodName == null) return false;
        String n = neighbourhoodName.toLowerCase(Locale.ROOT);
        return switch (sector) {
            case "G76 0" -> n.contains("eaglesham");
            case "G76 7", "G76 8", "G76 9" -> n.contains("clarkston");
            case "G66 4", "G66 5" -> n.contains("lenzie");
            case "G66 1", "G66 2", "G66 3", "G66 7", "G66 8" -> n.contains("kirkintilloch");
            case "G72 7", "G72 8" -> n.contains("cambuslang");
            case "G72 0", "G72 9" -> n.contains("blantyre");
            case "G74 5" -> n.contains("thorntonhall");
            case "G74 1", "G74 2", "G74 3", "G74 4", "G74 6", "G74 7", "G74 8", "G74 9" -> n.contains("east kilbride");
            default -> false;
        };
    }

    private static SchoolAndAreaProfile refineSectorCatchment(
            SchoolAndAreaProfile base,
            String postcodeSector,
            String combinedLower) {
        if (base == null) return null;
        String nonDenom = base.nonDenomSecondary();
        if (postcodeSector != null) {
            if ("G61 4".equals(postcodeSector)) {
                nonDenom = "Boclair Academy (G61 4) / Bearsden Academy";
            } else if (postcodeSector.startsWith("G61 ")) {
                nonDenom = "Bearsden Academy (" + postcodeSector + ") / Boclair Academy";
            } else if ("G46 8".equals(postcodeSector) || combinedLower.contains("thornliebank")) {
                nonDenom = "Woodfarm High (G46 8) / Williamwood High";
            } else if ("G46 6".equals(postcodeSector) || "G46 7".equals(postcodeSector)) {
                nonDenom = "Williamwood High (" + postcodeSector + ") / Woodfarm High";
            } else if ("G77 5".equals(postcodeSector)) {
                nonDenom = "Mearns Castle High (G77 5) / Eastwood High";
            } else if ("G77 6".equals(postcodeSector)) {
                nonDenom = "Eastwood High (G77 6) / Mearns Castle High";
            }
        }
        if (nonDenom.equals(base.nonDenomSecondary())) {
            return base;
        }
        return new SchoolAndAreaProfile(
            base.neighbourhoodName(),
            base.councilArea(),
            nonDenom,
            base.denomSecondary(),
            base.attainmentTier(),
            base.higherPassPctApprox(),
            base.simdDecile(),
            base.councilTaxGuide(),
            base.typicalOffersOverPct()
        );
    }

    private static SchoolAndAreaProfile toProfile(AreaHubEntry hub) {
        return new SchoolAndAreaProfile(
            hub.neighbourhoodName(),
            hub.councilArea(),
            hub.nonDenomSecondary(),
            hub.denomSecondary(),
            hub.attainmentTier(),
            hub.higherPassPctApprox(),
            hub.simdDecile(),
            hub.councilTaxGuide(),
            hub.typicalOffersOverPct()
        );
    }

    public static SchoolAndAreaProfile defaultProfile() {
        return new SchoolAndAreaProfile(
            "Greater Glasgow Commuter Belt",
            "Strathclyde",
            "Local Authority Catchment Secondary",
            "Local Denominational Secondary",
            "SOLID",
            50,
            6,
            "Band D-F",
            8
        );
    }

    private static String extractPostcodeSector(String upperText) {
        if (upperText == null || upperText.isBlank()) return null;
        Matcher m = POSTCODE_SECTOR_PATTERN.matcher(upperText);
        String found = null;
        while (m.find()) {
            found = m.group(1) + " " + m.group(2);
        }
        return found;
    }

    private static String extractPostcodeDistrict(String upperText) {
        if (upperText == null || upperText.isBlank()) return null;
        Matcher m = POSTCODE_DISTRICT_PATTERN.matcher(upperText);
        String found = null;
        while (m.find()) {
            String candidate = m.group(1);
            if (candidate.startsWith("G") || candidate.startsWith("ML") || candidate.startsWith("PA")
                    || candidate.startsWith("KA") || candidate.startsWith("FK")) {
                found = candidate;
            }
        }
        return found;
    }

    private static double haversineMiles(double lat1, double lon1, double lat2, double lon2) {
        double r = 3958.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }
}
