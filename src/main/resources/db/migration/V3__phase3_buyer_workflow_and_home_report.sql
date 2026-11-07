-- =================================================================================
-- V3: Phase 3 Buyer Workflow (Shortlist, Hide, Viewing Notes) & Home Report State
-- Persisted independently by listing_id so buyer notes & Home Reports survive DB refreshes
-- =================================================================================

CREATE TABLE IF NOT EXISTS user_property_state (
    listing_id                VARCHAR(255) PRIMARY KEY,
    shortlisted               BOOLEAN NOT NULL DEFAULT FALSE,
    hidden                    BOOLEAN NOT NULL DEFAULT FALSE,
    viewing_status            VARCHAR(32) NOT NULL DEFAULT 'NONE',
    viewing_notes             TEXT,
    home_report_valuation_gbp INTEGER,
    floor_area_sqm            DOUBLE PRECISION,
    council_tax_band          VARCHAR(8),
    hr_energy_band            VARCHAR(8),
    hr_category1_count        INTEGER,
    hr_category2_count        INTEGER,
    hr_category3_count        INTEGER,
    hr_repair_warnings_json   TEXT,
    hr_summary                TEXT,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_prop_shortlist_hidden
    ON user_property_state(shortlisted, hidden);
