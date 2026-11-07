-- V2: Asking-Price Reduction Tracking (IMP-05) & Hybrid Lexical-Vector FTS Index (IMP-06)

ALTER TABLE indexed_properties ADD COLUMN IF NOT EXISTS previous_price_gbp INTEGER;
ALTER TABLE indexed_properties ADD COLUMN IF NOT EXISTS price_changed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE indexed_properties ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_prop_embed_fts ON property_embeddings USING gin (to_tsvector('english', chunk_text));
