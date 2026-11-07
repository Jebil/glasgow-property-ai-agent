# Phase 2.5 Completed: Refactoring, Simplification & Tech Upgrades

**Status:** Completed & Verified (`Phase1BugFixesRegressionTest` + `Phase2ImprovementsTest` + `Phase25RefactoringAndUpgradesTest` 100% Passing on OpenJDK 25 + Spring Boot 4.1.2)

---

## 1. Before vs After Hotspot Summary

| File | Before (Lines) | After (Lines) | Result |
| :--- | ---: | ---: | :--- |
| `RightmoveScraperService.java` | 2,371 | **381** | Decomposed into `PortalListingCandidate`, `RightmovePortalClient`, `OnTheMarketPortalClient`, `PropertyIngestionPipeline`, and `ScrapeJobStateTracker` |
| `OllamaAgentService.java` | 1,871 | **655** | Decomposed into `OllamaApiClient`, `VisionImagePreprocessor`, `HeuristicMetadataExtractor`, and `PropertyScoringEngine` (zero `@Lazy` cycle) |
| `index.html` | 1,629 | **906** | Modularized into `static/js/state.js`, `static/js/api.js`, `static/js/app.js`, and 20.5 KB precompiled `static/vendor/tailwind.min.css` |
| `StationCommuteService.java` | 1,066 | **912** | Externalized 143 ScotRail stations, 15 SPT Subway hubs, and 18 location coordinates to `data/glasgow-transit-network.json` |
| `PropertyAgentController.java` | 663 | **569** | Declarative `JdbcClient` + `CANDIDATE_ROW_MAPPER`, `@Valid` DTO validation, bounded Caffeine cache, and event-driven SSE push |

---

## 2. Completed Architectural Refactorings (`REF-01` – `REF-06`)

- **`REF-01` (Unified Ingestion Pipeline):** Single `PropertyIngestionPipeline` shared by `RightmovePortalClient` and `OnTheMarketPortalClient` via normalized `PortalListingCandidate` cards.
- **`REF-02` (Modular AI & Scoring Layer):** Shared `OllamaApiClient` across vision/chat and `PropertyEmbeddingService`, isolated `VisionImagePreprocessor` and `HeuristicMetadataExtractor`, and extracted pure-CPU `PropertyScoringEngine`.
- **`REF-03` (Externalized Transit Network JSON):** `src/main/resources/data/glasgow-transit-network.json` loaded at startup and ready for Phase 3 Interactive Transit Map (`FEAT-02`).
- **`REF-04` (Declarative Persistence Mapping):** Spring `JdbcClient` + `CANDIDATE_ROW_MAPPER` with `TIMESTAMPTZ -> Instant` handling replacing 60+ lines of manual `ResultSet` getters.
- **`REF-05` (Event-Driven SSE):** `ScrapeJobStateTracker` listener push replacing `@Scheduled(fixedRate = 1200)` polling; frontend HTTP polling only activates on SSE disconnect.
- **`REF-06` (Modular Frontend & Asset Caching):** Extracted `js/state.js`, `js/api.js`, `js/app.js`; configured 7-day `/property-media/**` and 30-day immutable `/vendor/**` caching in `WebMvcConfig`.

---

## 3. Completed Simplifications & Tech Upgrades (`SIMP-01`–`SIMP-05`, `UPG-01`–`UPG-06`)

- **`UPG-06`:** Upgraded to **Spring Boot `4.1.2`** (Spring Framework `7.0.10`, Hibernate `7.4.13`, Tomcat `11.0.27`, Jackson 3 `tools.jackson.*`) + **Java `25`**.
- **`UPG-02` & `UPG-03`:** Upgraded Docker Compose to **`pgvector/pgvector:0.8.7-pg17-trixie`** (`postgres_pg17_data` volume) with versioned **Flyway `12.4.0`** migrations (`V1__baseline_schema.sql`, `V2__price_tracking_and_hybrid_fts.sql`).
- **`UPG-01`:** Added **`com.twelvemonkeys.imageio:imageio-webp:3.15.1`** for native `.webp` decoding in thumbnails and automatic `.webp` -> JPEG/PNG conversion for Ollama Vision.
- **`UPG-04` & `SIMP-05`:** Replaced 407 KB runtime `vendor/tailwind.js` JIT with **20.5 KB** precompiled `vendor/tailwind.min.css` (`-95%`) and unified `createDefaultScrapeJob()` in `state.js`.
- **`UPG-05`, `SIMP-01`–`SIMP-04`:** Bumped `jsoup` to `1.24.1`, removed ~30 MB of unused `spring-ai` dependencies, enabled Java 25 Virtual Threads (`spring.threads.virtual.enabled: true`), wired `@Valid` Jakarta Bean Validation on `SearchFilterRequest`, and bounded all 4 in-memory caches with **Caffeine `3.2.4`**.
