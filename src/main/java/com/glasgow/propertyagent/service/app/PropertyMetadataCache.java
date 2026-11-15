package com.glasgow.propertyagent.service.app;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.glasgow.propertyagent.model.ExtractedPropertyMetadata;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * P4 / SIMP-04: Bounded Caffeine in-memory metadata cache ensuring /rank executes in <10ms,
 * caps heap usage across large multi-corridor indexes, and invalidates cleanly on re-analysis.
 * Also manages dynamic suggestion chip cache invalidation whenever properties are indexed or updated.
 */
@Component
public class PropertyMetadataCache {

    private final Cache<String, ExtractedPropertyMetadata> cache = Caffeine.newBuilder()
        .maximumSize(3_000)
        .expireAfterAccess(Duration.ofHours(6))
        .build();

    private volatile List<String> cachedSuggestions = null;

    public ExtractedPropertyMetadata computeIfAbsent(String listingId, Function<String, ExtractedPropertyMetadata> mappingFunction) {
        return listingId == null ? null : cache.get(listingId, mappingFunction);
    }

    public ExtractedPropertyMetadata get(String listingId) {
        return listingId == null ? null : cache.getIfPresent(listingId);
    }

    public void put(String listingId, ExtractedPropertyMetadata metadata) {
        if (listingId != null && metadata != null) {
            cache.put(listingId, metadata);
            this.cachedSuggestions = null;
        }
    }

    public void invalidate(String listingId) {
        if (listingId != null) {
            cache.invalidate(listingId);
            this.cachedSuggestions = null;
        }
    }

    public void clear() {
        cache.invalidateAll();
        this.cachedSuggestions = null;
    }

    public List<String> getCachedSuggestions() {
        return cachedSuggestions;
    }

    public void setCachedSuggestions(List<String> suggestions) {
        this.cachedSuggestions = suggestions != null ? List.copyOf(suggestions) : null;
    }

    public void invalidateSuggestions() {
        this.cachedSuggestions = null;
    }

    public int size() {
        return (int) cache.estimatedSize();
    }
}
