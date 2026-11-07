package com.glasgow.propertyagent.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "indexed_properties", indexes = {
    @Index(name = "idx_prop_filter", columnList = "propertyType, priceGbp, bedrooms"),
    @Index(name = "idx_prop_coords", columnList = "latitude, longitude"),
    @Index(name = "idx_prop_station", columnList = "nearestStationName"),
    @Index(name = "idx_prop_indexed_at", columnList = "indexedAt")
})
public class IndexedPropertyEntity {

    @Id
    private String listingId;

    private String title;
    private String address;
    private String propertyType;
    private int priceGbp;
    private Integer previousPriceGbp;
    private String priceQualifier;
    private Instant priceChangedAt;
    private Instant lastSeenAt;
    private int bedrooms;
    private double distanceFromGlasgowCentralMiles;
    private Double latitude;
    private Double longitude;

    @Column(length = 1000)
    private String rightmoveUrl;

    @Column(length = 1000)
    private String thumbnailUrl;

    @Column(length = 1000)
    private String floorplanUrl;

    @Column(length = 8000)
    private String rawDescription;

    private String nearestStationName;
    private double distanceToStationMiles;
    private int fastestTrainMins;
    private int estimatedDoorToCentralMins;
    private boolean stationFreeParking;
    private String stationAccessibilityCategory;

    // Bus transit fields (resolved at index time to prevent network calls during ranking)
    private String nearestBusStopName;
    private Double distanceToBusStopMiles;
    private Integer walkToBusStopMins;
    private Integer busFrequencyPeakPerHour;
    private String busRouteRefs;
    private String busPrimaryOperator;
    private Boolean busOsmVerified;

    // Convenience boolean columns for ultra-fast SQL pre-filtering
    private boolean hasDownstairsWc;
    private boolean hasMasterEnsuite;
    private boolean directGardenAccessFromLiving;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(columnDefinition = "TEXT")
    private String allImagesJson;

    @Column(columnDefinition = "TEXT")
    private String allFloorplansJson;

    @Column(columnDefinition = "TEXT")
    private String llmResponseLog;

    private Integer downloadedImageCount;

    // Provenance of extracted metadata (N2: VISION_LLM, TEXT_LLM, HEURISTIC)
    private String metadataSource;
    private String metadataModel;
    private Instant analyzedAt;

    private Instant indexedAt;

    public IndexedPropertyEntity() {}

    // Getters and Setters
    public String getListingId() { return listingId; }
    public void setListingId(String listingId) { this.listingId = listingId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPropertyType() { return propertyType; }
    public void setPropertyType(String propertyType) { this.propertyType = propertyType; }
    public int getPriceGbp() { return priceGbp; }
    public void setPriceGbp(int priceGbp) { this.priceGbp = priceGbp; }
    public Integer getPreviousPriceGbp() { return previousPriceGbp; }
    public void setPreviousPriceGbp(Integer previousPriceGbp) { this.previousPriceGbp = previousPriceGbp; }
    public String getPriceQualifier() { return priceQualifier; }
    public void setPriceQualifier(String priceQualifier) { this.priceQualifier = priceQualifier; }
    public Instant getPriceChangedAt() { return priceChangedAt; }
    public void setPriceChangedAt(Instant priceChangedAt) { this.priceChangedAt = priceChangedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public int getBedrooms() { return bedrooms; }
    public void setBedrooms(int bedrooms) { this.bedrooms = bedrooms; }
    public double getDistanceFromGlasgowCentralMiles() { return distanceFromGlasgowCentralMiles; }
    public void setDistanceFromGlasgowCentralMiles(double distanceFromGlasgowCentralMiles) { this.distanceFromGlasgowCentralMiles = distanceFromGlasgowCentralMiles; }
    public double getLatitude() { return latitude == null ? 0.0 : latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public double getLongitude() { return longitude == null ? 0.0 : longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getRightmoveUrl() { return rightmoveUrl; }
    public void setRightmoveUrl(String rightmoveUrl) { this.rightmoveUrl = rightmoveUrl; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
    public String getFloorplanUrl() { return floorplanUrl; }
    public void setFloorplanUrl(String floorplanUrl) { this.floorplanUrl = floorplanUrl; }
    public String getRawDescription() { return rawDescription; }
    public void setRawDescription(String rawDescription) { this.rawDescription = rawDescription; }
    public String getNearestStationName() { return nearestStationName; }
    public void setNearestStationName(String nearestStationName) { this.nearestStationName = nearestStationName; }
    public double getDistanceToStationMiles() { return distanceToStationMiles; }
    public void setDistanceToStationMiles(double distanceToStationMiles) { this.distanceToStationMiles = distanceToStationMiles; }
    public int getFastestTrainMins() { return fastestTrainMins; }
    public void setFastestTrainMins(int fastestTrainMins) { this.fastestTrainMins = fastestTrainMins; }
    public int getEstimatedDoorToCentralMins() { return estimatedDoorToCentralMins; }
    public void setEstimatedDoorToCentralMins(int estimatedDoorToCentralMins) { this.estimatedDoorToCentralMins = estimatedDoorToCentralMins; }
    public boolean isStationFreeParking() { return stationFreeParking; }
    public void setStationFreeParking(boolean stationFreeParking) { this.stationFreeParking = stationFreeParking; }
    public String getStationAccessibilityCategory() { return stationAccessibilityCategory; }
    public void setStationAccessibilityCategory(String stationAccessibilityCategory) { this.stationAccessibilityCategory = stationAccessibilityCategory; }
    public boolean isHasDownstairsWc() { return hasDownstairsWc; }
    public void setHasDownstairsWc(boolean hasDownstairsWc) { this.hasDownstairsWc = hasDownstairsWc; }
    public boolean isHasMasterEnsuite() { return hasMasterEnsuite; }
    public void setHasMasterEnsuite(boolean hasMasterEnsuite) { this.hasMasterEnsuite = hasMasterEnsuite; }
    public boolean isDirectGardenAccessFromLiving() { return directGardenAccessFromLiving; }
    public void setDirectGardenAccessFromLiving(boolean directGardenAccessFromLiving) { this.directGardenAccessFromLiving = directGardenAccessFromLiving; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getAllImagesJson() { return allImagesJson; }
    public void setAllImagesJson(String allImagesJson) { this.allImagesJson = allImagesJson; }
    public String getAllFloorplansJson() { return allFloorplansJson; }
    public void setAllFloorplansJson(String allFloorplansJson) { this.allFloorplansJson = allFloorplansJson; }
    public String getLlmResponseLog() { return llmResponseLog; }
    public void setLlmResponseLog(String llmResponseLog) { this.llmResponseLog = llmResponseLog; }
    public int getDownloadedImageCount() { return downloadedImageCount == null ? 0 : downloadedImageCount; }
    public void setDownloadedImageCount(Integer downloadedImageCount) { this.downloadedImageCount = downloadedImageCount; }
    public Instant getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Instant indexedAt) { this.indexedAt = indexedAt; }

    public String getMetadataSource() { return metadataSource; }
    public void setMetadataSource(String metadataSource) { this.metadataSource = metadataSource; }
    public String getMetadataModel() { return metadataModel; }
    public void setMetadataModel(String metadataModel) { this.metadataModel = metadataModel; }
    public Instant getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(Instant analyzedAt) { this.analyzedAt = analyzedAt; }

    public String getNearestBusStopName() { return nearestBusStopName; }
    public void setNearestBusStopName(String nearestBusStopName) { this.nearestBusStopName = nearestBusStopName; }
    public Double getDistanceToBusStopMiles() { return distanceToBusStopMiles; }
    public void setDistanceToBusStopMiles(Double distanceToBusStopMiles) { this.distanceToBusStopMiles = distanceToBusStopMiles; }
    public Integer getWalkToBusStopMins() { return walkToBusStopMins; }
    public void setWalkToBusStopMins(Integer walkToBusStopMins) { this.walkToBusStopMins = walkToBusStopMins; }
    public Integer getBusFrequencyPeakPerHour() { return busFrequencyPeakPerHour; }
    public void setBusFrequencyPeakPerHour(Integer busFrequencyPeakPerHour) { this.busFrequencyPeakPerHour = busFrequencyPeakPerHour; }
    public String getBusRouteRefs() { return busRouteRefs; }
    public void setBusRouteRefs(String busRouteRefs) { this.busRouteRefs = busRouteRefs; }
    public String getBusPrimaryOperator() { return busPrimaryOperator; }
    public void setBusPrimaryOperator(String busPrimaryOperator) { this.busPrimaryOperator = busPrimaryOperator; }
    public Boolean getBusOsmVerified() { return busOsmVerified; }
    public void setBusOsmVerified(Boolean busOsmVerified) { this.busOsmVerified = busOsmVerified; }
}
