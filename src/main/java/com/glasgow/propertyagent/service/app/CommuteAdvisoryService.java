package com.glasgow.propertyagent.service.app;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import com.glasgow.propertyagent.service.ai.OllamaAgentService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Coordinates grounded public transport dossier assembly and local LLM commute synthesis
 * with bounded Caffeine caching (REF-07 / SIMP-04).
 */
@Service
public class CommuteAdvisoryService {

    private final StationCommuteService stationService;
    private final OllamaAgentService ollamaAgent;

    private final Cache<String, OllamaAgentService.CommuteResearchReport> commuteReportCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofHours(2))
        .build();

    public CommuteAdvisoryService(StationCommuteService stationService, OllamaAgentService ollamaAgent) {
        this.stationService = stationService;
        this.ollamaAgent = ollamaAgent;
    }

    public OllamaAgentService.CommuteResearchReport researchCommute(
            IndexedPropertyEntity entity,
            String targetLocation,
            List<String> transportModes) {
        String target = (targetLocation != null && !targetLocation.isBlank())
            ? targetLocation.trim()
            : "Glasgow Central";
        List<String> modes = (transportModes != null && !transportModes.isEmpty())
            ? transportModes
            : List.of("Train", "Subway", "Bus");

        String cacheKey = entity.getListingId() + ":" + target + ":" + String.join(",", modes);
        return commuteReportCache.get(cacheKey, k -> {
            String dossier = stationService.buildGroundedCommuteDossier(entity, target, modes);
            return ollamaAgent.researchCommuteWithLlm(entity.getListingId(), target, dossier);
        });
    }

    public void invalidateAll() {
        commuteReportCache.invalidateAll();
    }
}
