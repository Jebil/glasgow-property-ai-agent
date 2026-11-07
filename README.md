# Glasgow Commuter & Family Layout AI Property Agent

A local **Spring Boot 3.4 (Java 21) + Ollama** web application that scrapes Rightmove listings within a configurable radius of **Glasgow Central Station**, extracts rich architectural and floorplan metadata **once per property** using a local Multimodal Vision/Text LLM, enriches listings with deterministic ScotRail station commute & Park-and-Ride metrics, and provides a reactive web UI to filter, add dynamic natural-language criteria, click AI suggestion chips, and rank homes from 0 to 100.

---

## Key Capabilities

1. **Two-Stage Decoupled AI Pipeline:**
   - **Stage 1 (Ingestion & Exhaustive Indexing):** Scrapes Rightmove listings and floorplan images, runs a Vision + Text extraction pass via local Ollama, and persists an extensive JSON metadata index (`ExtractedPropertyMetadata`) in a Dockerized **PostgreSQL 16** database (`compose.yaml`).
   - **Stage 2 (Dynamic Querying & LLM Scoring):** Pre-filters properties deterministically (budget, bedrooms, radius, property type, max door-to-Glasgow-Central commute, free Park & Ride) and scores surviving candidates against your weighted criteria (`MUST_HAVE`, `HIGH`, `MEDIUM`, `NICE_TO_HAVE`).
2. **Deterministic Greater Glasgow Station Registry:**
   - Built-in profiles for **Uddingston**, **Newton**, **Blantyre**, **Cambuslang**, **Bellshill**, **Motherwell**, **Rutherglen**, **Wishaw**, **East Kilbride**, **Hairmyres**, and **Paisley Gilmour Street** including fastest direct train time to Glasgow Central, peak frequency (trains/hr), free Park & Ride availability, and Category A/B step-free accessibility rating.
3. **Interactive Web UI (`http://localhost:8080`):**
   - Radius slider (`1` to `20` miles from Glasgow Central) & Property Type checkboxes (`Detached`, `Semi-Detached`, `Terraced`, `Bungalow`, `Flat`).
   - Dynamic Criteria Builder with priority weights and clickable **AI Suggestion Chips** discovered from indexed property highlights.
   - Ranked Leaderboard with 0-100 scores, sightline/layout badges, station logistics, and AI family critique.

---

## Prerequisites

1. **Java 21+**, **Maven 3.9+**, and **Docker Desktop** (for PostgreSQL 16)
2. **Ollama** running locally on `http://localhost:11434`:
   ```bash
   # Pull a vision model for floorplan & photo analysis (fits 4GB-8GB VRAM quantized)
   ollama pull llama3.2-vision
   # Or lightweight alternative:
   ollama pull qwen2.5-vl:7b

   # Pull a fast text model for dynamic multi-criteria ranking
   ollama pull llama3.1:8b
   ```
   *(Note: If Ollama is offline or still downloading models, the app automatically falls back to deterministic heuristic extraction and scoring so you can test the full UI immediately.)*

---

## Running the Application

Spring Boot's Docker Compose integration will automatically start the `postgres:16-alpine` container defined in `compose.yaml` (or you can start it manually with `docker compose up -d`):

```bash
mvn spring-boot:run
```

Then open **`http://localhost:8080`** in your browser.
On startup, the application automatically seeds 6 realistic Greater Glasgow / South Lanarkshire commuter listings (Uddingston, Blantyre, Newton, Cambuslang, Motherwell, Hairmyres/East Kilbride) so you can test filtering and ranking immediately, or click **"Sync & Index Live Rightmove Listings"** to scrape live listings.
