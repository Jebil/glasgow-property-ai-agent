-- V1: Baseline Glasgow Property AI Agent Schema (PostgreSQL 17 + pgvector 0.8)

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS indexed_properties (
    listing_id VARCHAR(255) PRIMARY KEY,
    title VARCHAR(255),
    address VARCHAR(255),
    property_type VARCHAR(255),
    price_gbp INTEGER NOT NULL DEFAULT 0,
    price_qualifier VARCHAR(255),
    bedrooms INTEGER NOT NULL DEFAULT 0,
    distance_from_glasgow_central_miles DOUBLE PRECISION DEFAULT 0.0,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    rightmove_url VARCHAR(1000),
    thumbnail_url VARCHAR(1000),
    floorplan_url VARCHAR(1000),
    raw_description VARCHAR(8000),
    nearest_station_name VARCHAR(255),
    distance_to_station_miles DOUBLE PRECISION DEFAULT 0.0,
    fastest_train_mins INTEGER DEFAULT 0,
    estimated_door_to_central_mins INTEGER DEFAULT 0,
    station_free_parking BOOLEAN DEFAULT FALSE,
    station_accessibility_category VARCHAR(255),
    nearest_bus_stop_name VARCHAR(255),
    distance_to_bus_stop_miles DOUBLE PRECISION,
    walk_to_bus_stop_mins INTEGER,
    bus_frequency_peak_per_hour INTEGER,
    bus_route_refs VARCHAR(255),
    bus_primary_operator VARCHAR(255),
    bus_osm_verified BOOLEAN,
    has_downstairs_wc BOOLEAN DEFAULT FALSE,
    has_master_ensuite BOOLEAN DEFAULT FALSE,
    direct_garden_access_from_living BOOLEAN DEFAULT FALSE,
    metadata_json TEXT,
    all_images_json TEXT,
    all_floorplans_json TEXT,
    llm_response_log TEXT,
    downloaded_image_count INTEGER,
    metadata_source VARCHAR(255),
    metadata_model VARCHAR(255),
    analyzed_at TIMESTAMP WITH TIME ZONE,
    indexed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS property_embeddings (
    id BIGSERIAL PRIMARY KEY,
    listing_id VARCHAR(255) NOT NULL REFERENCES indexed_properties(listing_id) ON DELETE CASCADE,
    chunk_type VARCHAR(50) NOT NULL,
    chunk_text TEXT NOT NULL,
    image_ref VARCHAR(255),
    embedding vector(768)
);

CREATE INDEX IF NOT EXISTS idx_prop_filter ON indexed_properties(property_type, price_gbp, bedrooms);
CREATE INDEX IF NOT EXISTS idx_prop_coords ON indexed_properties(latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_prop_station ON indexed_properties(nearest_station_name);
CREATE INDEX IF NOT EXISTS idx_prop_indexed_at ON indexed_properties(indexed_at DESC);
CREATE INDEX IF NOT EXISTS idx_prop_embed_listing ON property_embeddings(listing_id);
CREATE INDEX IF NOT EXISTS idx_prop_embed_hnsw ON property_embeddings USING hnsw (embedding vector_cosine_ops);
CREATE UNIQUE INDEX IF NOT EXISTS idx_prop_embed_dedup ON property_embeddings(listing_id, chunk_type, coalesce(image_ref, ''), md5(chunk_text));
