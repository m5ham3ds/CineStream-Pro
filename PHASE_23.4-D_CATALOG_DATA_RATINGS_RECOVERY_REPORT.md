# PHASE 23.4-D — Catalog Separation, Rating Integrity & Network Recovery

## Scope

This report accompanies the full CineStream User App project archive. Changes are limited to TMDB data integrity, category separation, stale-cache invalidation, pagination error handling, detail retry behavior, and documentation of the required separately deployed Worker contract. The existing visual design and Firebase project identity were preserved.

## Source-level changes

### Catalog separation and dates

- `app/src/main/java/com/example/data/repository/TmdbMediaRepositoryImpl.kt`
  - `Coming Soon` movies now use the dedicated `/movie/upcoming` API across up to six pages, filter out any title without a parseable release date strictly after the current UTC date, deduplicate IDs, and cap the initial feed at 18 results.
  - `New Releases` movie and TV queries use a bounded last-90-days window that ends today, with strict client-side date checks. Anime releases are separately filtered to Japanese language and animation genre ID 16.
  - Upcoming TV/anime routes use first-air dates starting tomorrow and suppress ratings for unreleased items.
  - Trending movie/TV lists remain distinct from popular catalog routes.
  - Trending Anime starts with actual daily and weekly TMDB Trending anime. If the global Trending sample is too small, it backfills the shelf with verified Japanese-animation records from the dedicated Popular Anime catalog, rather than showing non-anime items or stopping after only one or two cards. IDs are deduplicated, and the existing trending items keep priority.
  - Genre/discover mappings share the same rating-validation and deduplication helpers.
  - The persisted catalog cache namespace is raised to `catalog_v5` so old lists containing 2024 dates, prior mixed categories, or suspicious ratings are not reused by the new version.
  - Page-fetch failures are rethrown instead of being converted into successful empty pages.

### Rating integrity

- `app/src/main/java/com/example/data/remote/TmdbModels.kt`
  - `vote_count` is parsed for movie, TV, and details DTOs.
- `TmdbMediaRepositoryImpl.kt`
  - A rating is considered valid only if it is finite, in the 0–10 range, and has a positive vote count.
  - Unreleased titles have their score suppressed.
  - A page with five or more valid-looking entries all sharing the same rounded one-decimal score is treated as suspicious and the shared scores are hidden instead of presented as authoritative ratings.
- `app/src/main/java/com/example/data/repository/ContinueWatchingMetadataManager.kt`
  - Ratings are populated only when the numeric score and vote count are valid. A missing/unverified rating is left blank.
- `app/src/main/java/com/example/ui/components/MediaCard.kt` already hides ratings when they are null, non-finite, or non-positive, so a suppressed `0.0` value is not rendered as a literal 0.0 score on that card.

### Cache and network recovery

- `app/src/main/java/com/example/data/remote/RetrofitClient.kt`
  - The HTTP cache directory is versioned to `http_cache_v2` to avoid reusing old response entries.
  - The response interceptor now respects upstream `Cache-Control`, including `no-store`/`no-cache`, instead of unconditionally overriding it with a one-hour cache directive.
- `app/src/main/java/com/example/data/repository/TmdbListPaginator.kt`
  - Failed requests in the “All” tab are no longer swallowed and converted into empty successful pages. Existing content remains available and failed tabs can be retried.
- `app/src/main/java/com/example/ui/screens/home/HomeViewModel.kt`
  - When connectivity returns, feeds are force-refreshed and failed paginators are asked to retry.
- `app/src/main/java/com/example/ui/screens/details/MovieDetailsViewModel.kt` and `SeriesDetailsViewModel.kt`
  - Unresolved detail pages retry after connectivity returns and continue with bounded exponential backoff while a connection is reported but the gateway still fails. Cancellation is respected and already-running detail jobs are not deliberately duplicated.
- `app/src/main/java/com/example/data/repository/ContinueWatchingMetadataManager.kt`
  - Metadata-only requests keep `appendToResponse = ""`, avoiding unnecessary credits/translations/videos.

## Worker contract

- `PHASE_23.4-C_WORKER_PATCH.md` and `docs/worker/PHASE_23.4-D_APP_WORKER_CONTRACT.md` document the required Worker behavior:
  - `/movie/popular`, `/tv/popular`, `/movie/upcoming`, `/discover/movie`, `/discover/tv`, genre lists.
  - Details must not append `videos` automatically and must respect an explicitly empty `append_to_response`.
  - `/movie/{id}/videos` and `/tv/{id}/videos` must validate `mode=primary|all`; `primary` returns no more than one eligible YouTube trailer, while `all` returns eligible trailers only.
  - Guest/registered Firebase Bearer-token authentication and the current CORS allowlist must remain intact; no wildcard CORS shortcut.
- The Worker is separately hosted. This archive contains the contract/patch instructions; it does **not** prove that the currently deployed Worker was updated or passed live tests. Test the listed routes against the deployed gateway before relying on the new video modes.

## Build configuration

- The archive includes the exact `app/google-services.json` generated from the repository's existing `public/assets/aistudio/google-services_9bdb880b.json`, and a `debug.keystore` generated from the existing CI workflow payload, to support local project validation. The identity-integrity check confirms the configuration fingerprint and keystore fingerprints match the checked-in identity lock.
- These values were not changed. Firebase project remains `cinestream-sulo`, and the User App application ID remains `com.aistudio.cinestream.xyzabc`.

## Verification

Passed:

- XML parse: all 14 Android XML resources parsed successfully.
- JSON parse: all 9 JSON files parsed successfully.
- `npm run test:identity`: 5/5 tests passed after restoring the existing project configuration and CI keystore into the local workspace.
- Static checks for strict catalog dates, `catalog_v5` cache namespace, rating vote-count validation, anime backfill, cache no-store preservation, trailer mode forwarding, and detail retry loops passed.

Not passed / unavailable:

- Android Gradle compilation could not start because the Gradle wrapper download failed with `UnknownHostException: services.gradle.org`. This is an environment network/DNS restriction, not a Kotlin compiler result; compilation remains unverified.
- `npm run test:rules` ran 8 tests: 7 passed and 1 failed. The failing static regex treats the explicit `match /comments/{document=**}` block as an active comments route, even though that block says `allow read, write: if false`. This is an existing test assertion/rules-policy mismatch; neither the Firestore rules nor the test was changed in this phase.
- No Android emulator/device runtime test was possible in this environment.
- No authenticated live test of the deployed Worker was performed.

## Deployment note

No Firebase rules, secrets, Worker deployment, or production service configuration was changed by this phase. Apply the Worker patch separately and verify the live route contract before testing the Android build on a device.
