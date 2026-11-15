# Glasgow Commuter & Family Layout AI Property Agent

A local **Spring Boot 4.1.2 (Java 25) + Ollama (`gemma4:e4b` & `nomic-embed-text`)** web application that scrapes Rightmove and OnTheMarket listings across Greater Glasgow and Lanarkshire, extracts rich architectural and floorplan metadata **once per property** using a local Multimodal Vision/Text LLM, indexes 768-dim vector embeddings in **PostgreSQL 17 + pgvector 0.8.7**, enriches listings with deterministic multi-modal commute routing (**143 ScotRail stations**, **15 Glasgow Subway stations**, and **NaPTAN bus stops**), models **Scottish Home Report valuations & Revenue Scotland LBTT/ADS upfront cash**, and provides an interactive web UI with a tool-augmented **AI Chat Assistant** and **Daily Scheduled Scraper**.

---

## Key Capabilities

1. **Two-Stage Decoupled AI Pipeline:**
   - **Stage 1 (Ingestion, Vision Inspection & Vector Indexing):** Scrapes Rightmove (headless Chrome) and OnTheMarket listings, downloads and deduplicates photos and floorplans, runs a local multimodal vision + text pass (`gemma4:e4b`), persists structured `ExtractedPropertyMetadata` JSON in **PostgreSQL 17**, and indexes 768-dim semantic chunks (`nomic-embed-text`) using **pgvector HNSW**.
   - **Stage 2 (Dynamic Querying, Scottish Cash Reality & Hybrid Scoring):** Pre-filters properties deterministically (asking price, Scottish upfront cash cap, bedrooms, radius, property type, Partner A & Partner B commute limits, Park & Ride, step-free Category A) and scores surviving candidates from 0 to 100 across Commute (25 pts), Dynamic Family Layout & Semantic Criteria (60 pts), and Scottish Value/m² (15 pts).
2. **Multi-Modal Greater Glasgow Transit Engine:**
   - **143 Greater Glasgow & West of Scotland ScotRail stations** (`scotrail-glasgow-stations.json`), **15 Glasgow Subway stations** (`glasgow-subway-stations.json`), and **NaPTAN bus stop densities** (`glasgow-bus-corridors.json`), including peak frequency, Park & Ride availability, Category A/B/C step-free accessibility, and **Dual-Commuter (Partner A + Partner B)** routing.
3. **Scottish Home Buying & Workflow Tools:**
   - **Scottish Offers-Over & LBTT/ADS Upfront Cash Calculator:** Models area-specific Offers-Over premiums, lender LTV caps against Home Report valuation, cash-over-Home-Report gap, and Revenue Scotland LBTT (including First-Time Buyer relief and 8% Additional Dwelling Supplement).
   - **Home Report Single Survey Inspector:** Upload a Scottish Home Report PDF (or paste Single Survey text) to extract the chartered surveyor valuation, internal floor area ($\text{m}^2$), EPC rating, and Category 2/3 repair warnings.
   - **Postcode-Sector School Catchment & SIMD Intelligence:** Maps Greater Glasgow postcodes (including sector-level disambiguation for G76, G66, G72, G74, G61, G46, G77) to non-denominational and denominational secondary catchments and SIMD deciles.
4. **Interactive Web UI (`http://localhost:8080`) & AI Chat Assistant:**
   - **Interactive Transit & Catchment Canvas Map**, side-by-side comparison modal (up to 4 homes), shortlist & viewing tracker, and live SSE background scrape progress.
   - **Tool-Calling AI Chat Assistant (`/api/chat` & `/api/chat/stream`):** Can query PostgreSQL (`sql_query`), run hybrid semantic search (`search_properties`), inspect properties (`get_property_details`), update UI filters (`apply_filters`), manage shortlists (`update_workflow`), trigger background portal scrapes (`fetch_latest_listings`), and search the web (`web_search`).
   - **Daily Scheduled Refresh (`/api/scheduler`):** Configurable background cron job that automatically scrapes new listings, detects price drops, and records run history in `scheduled_scrape_history`.

---

## Prerequisites

1. **Java 25**, **Maven 3.9+**, and **Docker Desktop** (for PostgreSQL 17 + pgvector)
2. **Ollama** running locally on `http://localhost:11434`:
   ```bash
   # Pull the multimodal vision + text model used for floorplan/photo inspection, ranking & chat
   ollama pull gemma4:e4b

   # Pull the 768-dim embedding model for pgvector semantic search
   ollama pull nomic-embed-text
   ```
   *(Note: If Ollama is offline, the application automatically falls back to deterministic heuristic extraction and scoring.)*

---

## Running the Application

Start the PostgreSQL 17 + pgvector container (`pgvector/pgvector:0.8.2-pg17`) and launch Spring Boot:

```bash
docker compose up -d
mvn spring-boot:run
```

Then open **`http://localhost:8080`** in your browser.
