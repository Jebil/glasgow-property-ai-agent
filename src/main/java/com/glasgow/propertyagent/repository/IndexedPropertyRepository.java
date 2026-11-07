package com.glasgow.propertyagent.repository;

import com.glasgow.propertyagent.entity.IndexedPropertyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface IndexedPropertyRepository extends JpaRepository<IndexedPropertyEntity, String> {

    interface ListingPriceSnapshot {
        String getListingId();
        int getPriceGbp();
        Integer getPreviousPriceGbp();
        String getPriceQualifier();
    }

    @Query("SELECT p.listingId AS listingId, p.priceGbp AS priceGbp, p.previousPriceGbp AS previousPriceGbp, p.priceQualifier AS priceQualifier FROM IndexedPropertyEntity p")
    List<ListingPriceSnapshot> findAllPriceSnapshots();

    @Query("SELECT p.metadataJson FROM IndexedPropertyEntity p WHERE p.metadataJson IS NOT NULL")
    List<String> findAllMetadataJson();

    @Modifying
    @Transactional
    @Query("""
        UPDATE IndexedPropertyEntity p
        SET p.previousPriceGbp = :previousPriceGbp,
            p.priceGbp = :newPriceGbp,
            p.priceQualifier = :priceQualifier,
            p.priceChangedAt = :changedAt,
            p.lastSeenAt = :seenAt
        WHERE p.listingId = :listingId
    """)
    int updateListingPriceChange(
            @Param("listingId") String listingId,
            @Param("previousPriceGbp") Integer previousPriceGbp,
            @Param("newPriceGbp") int newPriceGbp,
            @Param("priceQualifier") String priceQualifier,
            @Param("changedAt") Instant changedAt,
            @Param("seenAt") Instant seenAt
    );

    @Modifying
    @Transactional
    @Query("UPDATE IndexedPropertyEntity p SET p.lastSeenAt = :seenAt WHERE p.listingId = :listingId")
    int touchLastSeen(@Param("listingId") String listingId, @Param("seenAt") Instant seenAt);
}
