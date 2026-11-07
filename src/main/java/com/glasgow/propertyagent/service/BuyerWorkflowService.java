package com.glasgow.propertyagent.service;

import com.glasgow.propertyagent.entity.UserPropertyStateEntity;
import com.glasgow.propertyagent.model.BuyerWorkflowState;
import com.glasgow.propertyagent.repository.UserPropertyStateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FEAT-05 & FEAT-06: Manages persistent buyer shortlist, hide/dismiss, viewing status/notes,
 * and inspected Home Report fields in PostgreSQL (with in-memory fallback for standalone unit tests).
 */
@Service
public class BuyerWorkflowService {

    public record WorkflowUpdateRequest(
        Boolean shortlisted,
        Boolean hidden,
        String viewingStatus,
        String viewingNotes,
        Integer homeReportValuationGbp,
        Double floorAreaSqm,
        String councilTaxBand
    ) {}

    public record WorkflowSummary(
        long shortlistedCount,
        long hiddenCount,
        long homeReportsCount
    ) {}

    private final UserPropertyStateRepository repository;
    private final ObjectMapper objectMapper;
    // Fallback store when repository is null in standalone non-Spring unit tests
    private final Map<String, UserPropertyStateEntity> fallbackMemoryStore = new ConcurrentHashMap<>();

    public BuyerWorkflowService() {
        this(null, new ObjectMapper());
    }

    @Autowired
    public BuyerWorkflowService(UserPropertyStateRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public Map<String, BuyerWorkflowState> loadStatesForListings(Collection<String> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return Map.of();
        }
        Map<String, BuyerWorkflowState> out = new HashMap<>();
        if (repository != null) {
            for (UserPropertyStateEntity e : repository.findAllByListingIdIn(listingIds)) {
                out.put(e.getListingId(), toDto(e));
            }
        } else {
            for (String id : listingIds) {
                UserPropertyStateEntity e = fallbackMemoryStore.get(id);
                if (e != null) {
                    out.put(id, toDto(e));
                }
            }
        }
        return out;
    }

    public BuyerWorkflowState getState(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            return BuyerWorkflowState.empty();
        }
        UserPropertyStateEntity entity = repository != null
            ? repository.findById(listingId).orElse(null)
            : fallbackMemoryStore.get(listingId);
        return entity != null ? toDto(entity) : BuyerWorkflowState.empty();
    }

    @Transactional
    public BuyerWorkflowState updateWorkflow(String listingId, WorkflowUpdateRequest req) {
        UserPropertyStateEntity entity = findOrCreate(listingId);
        if (req.shortlisted() != null) {
            entity.setShortlisted(req.shortlisted());
            if (req.shortlisted()) {
                entity.setHidden(false);
            }
        }
        if (req.hidden() != null) {
            entity.setHidden(req.hidden());
            if (req.hidden()) {
                entity.setShortlisted(false);
            }
        }
        if (req.viewingStatus() != null && !req.viewingStatus().isBlank()) {
            entity.setViewingStatus(req.viewingStatus().trim().toUpperCase(Locale.ROOT));
        }
        if (req.viewingNotes() != null) {
            entity.setViewingNotes(req.viewingNotes());
        }
        if (req.homeReportValuationGbp() != null) {
            entity.setHomeReportValuationGbp(req.homeReportValuationGbp() > 0 ? req.homeReportValuationGbp() : null);
        }
        if (req.floorAreaSqm() != null) {
            entity.setFloorAreaSqm(req.floorAreaSqm() > 0 ? req.floorAreaSqm() : null);
        }
        if (req.councilTaxBand() != null) {
            entity.setCouncilTaxBand(req.councilTaxBand().isBlank() ? null : req.councilTaxBand().trim().toUpperCase(Locale.ROOT));
        }
        entity.setUpdatedAt(Instant.now());
        return toDto(saveEntity(entity));
    }

    @Transactional
    public BuyerWorkflowState saveHomeReportAnalysis(
            String listingId,
            HomeReportAnalyzerService.HomeReportAnalysis analysis) {
        UserPropertyStateEntity entity = findOrCreate(listingId);
        if (analysis.homeReportValuationGbp() != null) {
            entity.setHomeReportValuationGbp(analysis.homeReportValuationGbp());
        }
        if (analysis.floorAreaSqm() != null) {
            entity.setFloorAreaSqm(analysis.floorAreaSqm());
        }
        if (analysis.councilTaxBand() != null) {
            entity.setCouncilTaxBand(analysis.councilTaxBand());
        }
        if (analysis.energyBand() != null) {
            entity.setHrEnergyBand(analysis.energyBand());
        }
        entity.setHrCategory1Count(analysis.category1Count());
        entity.setHrCategory2Count(analysis.category2Count());
        entity.setHrCategory3Count(analysis.category3Count());
        try {
            entity.setHrRepairWarningsJson(objectMapper.writeValueAsString(
                analysis.repairWarnings() != null ? analysis.repairWarnings() : List.of()
            ));
        } catch (Exception e) {
            entity.setHrRepairWarningsJson("[]");
        }
        entity.setHrSummary(analysis.summary());
        entity.setUpdatedAt(Instant.now());
        return toDto(saveEntity(entity));
    }

    public WorkflowSummary getSummary() {
        if (repository != null) {
            return new WorkflowSummary(
                repository.countByShortlistedTrueAndHiddenFalse(),
                repository.countByHiddenTrue(),
                repository.countByHomeReportValuationGbpIsNotNull()
            );
        }
        long shortlisted = fallbackMemoryStore.values().stream().filter(e -> e.isShortlisted() && !e.isHidden()).count();
        long hidden = fallbackMemoryStore.values().stream().filter(UserPropertyStateEntity::isHidden).count();
        long hr = fallbackMemoryStore.values().stream().filter(e -> e.getHomeReportValuationGbp() != null).count();
        return new WorkflowSummary(shortlisted, hidden, hr);
    }

    private UserPropertyStateEntity findOrCreate(String listingId) {
        if (repository != null) {
            return repository.findById(listingId).orElseGet(() -> {
                UserPropertyStateEntity fresh = new UserPropertyStateEntity();
                fresh.setListingId(listingId);
                return fresh;
            });
        }
        return fallbackMemoryStore.computeIfAbsent(listingId, id -> {
            UserPropertyStateEntity fresh = new UserPropertyStateEntity();
            fresh.setListingId(id);
            return fresh;
        });
    }

    private UserPropertyStateEntity saveEntity(UserPropertyStateEntity entity) {
        if (repository != null) {
            return repository.save(entity);
        }
        fallbackMemoryStore.put(entity.getListingId(), entity);
        return entity;
    }

    private BuyerWorkflowState toDto(UserPropertyStateEntity e) {
        List<String> warnings = List.of();
        if (e.getHrRepairWarningsJson() != null && !e.getHrRepairWarningsJson().isBlank()) {
            try {
                warnings = objectMapper.readValue(e.getHrRepairWarningsJson(), new TypeReference<List<String>>() {});
            } catch (Exception ignored) {}
        }
        return new BuyerWorkflowState(
            e.isShortlisted(),
            e.isHidden(),
            e.getViewingStatus() != null ? e.getViewingStatus() : "NONE",
            e.getViewingNotes() != null ? e.getViewingNotes() : "",
            e.getHomeReportValuationGbp(),
            e.getFloorAreaSqm(),
            e.getCouncilTaxBand(),
            e.getHrEnergyBand(),
            e.getHrCategory1Count(),
            e.getHrCategory2Count(),
            e.getHrCategory3Count(),
            warnings,
            e.getHrSummary()
        );
    }
}
