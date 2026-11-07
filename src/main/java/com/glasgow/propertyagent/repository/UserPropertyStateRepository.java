package com.glasgow.propertyagent.repository;

import com.glasgow.propertyagent.entity.UserPropertyStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface UserPropertyStateRepository extends JpaRepository<UserPropertyStateEntity, String> {

    List<UserPropertyStateEntity> findAllByListingIdIn(Collection<String> listingIds);

    long countByShortlistedTrueAndHiddenFalse();

    long countByHiddenTrue();

    long countByHomeReportValuationGbpIsNotNull();
}
