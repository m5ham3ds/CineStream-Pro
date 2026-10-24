# PHASE 23.4-C — CineStream User App
## TMDB Category Isolation, Trailer Deferral, Cache Partitioning and Interaction Performance

**Source baseline:** `CINESTREAM-PRO1-main.zip` uploaded in this conversation  
**Scope:** User App and the companion Worker route contract. No Firebase project identity changes, no authentication migration, and no unrelated UI redesign.

## 1. Root causes found in source

1. Category repository methods used Trending in place of catalog/discover endpoints for popular, new-release and upcoming feeds. Some empty-match filters returned the unfiltered source list, allowing out-of-category content to appear.
2. The Home trending mix temporarily represented TV rows as `Movie`-like cards and inferred navigation type using list positions/counts. This could open the wrong detail route.
3. List caches were not language-partitioned, and some season episode caches keyed only by series ID and season number.
4. Movie/series detail requests caused the Worker helper to append `videos` even when the app asked only for `credits,translations`, and Continue Watching could not suppress that due to the helper's required append list.
5. Movie/series details embedded every video, and detail UI rendered extra trailer cards at once. The Worker `mode=primary` query parameter was not enforced by the video subresource handler.
6. `AppNavigation` constructs shared view models for multiple sections. Their `init` blocks started catalog requests before users opened those sections; Home itself also started many feeds together.
7. Primary media cards had no press-scale interaction and route transitions lacked useful directional motion.

## 2. Code changes made

### TMDB/category correctness
- Added explicit Worker-gateway API declarations for popular catalogs, upcoming, discover and genre lists, plus video metadata mode.
- Separated popular, trending, new releases, upcoming, Arabic-language feeds and anime feeds onto semantically appropriate TMDB endpoints.
- Kept strict category filters after upstream calls; an empty matching category remains empty instead of falling back to unrelated Trending results.
- Preserved movie-vs-TV identity in Home mixed feeds and routed taps by the item's real media type.
- Added stable keys to Home LazyRows to avoid index-driven identity/recomposition churn.
- Partitioned list and detail caches by app language; included language in the repository season episode cache key; preserved cast billing order and trailer metadata in disk-cache round trips.
- Removed credits/videos overfetch from Continue Watching metadata calls by sending an explicit empty `append_to_response`.
- Separated Continue Watching metadata cache keys by media type and language (`movie_<id>_<lang>` / `tv_<id>_<lang>`) to prevent movie/TV ID collisions and Arabic/English metadata reuse.

### Trailer behavior
- Initial details UI shows only one primary trailer card.
- Other trailers are requested only after the user selects the localized “Show other trailers” action; the secondary LazyRow is only composed after that request returns.
- Only YouTube records with non-empty keys and `type == Trailer` qualify; teasers, clips, featurettes, BTS and interviews are excluded.
- Only the primary card receives a primary/official badge, and the badge says “Official Trailer” only when TMDB marks that record official.
- Added a small spring scale press animation to media/cast/trailer cards, directional fade/slide route transitions, and an expand/fade animation for the secondary trailer area. Existing typography, colors, card shapes and layout structure were otherwise retained.

### Startup and rendering load
- Deferred Home/Movies/Series/Anime catalog initialization until the corresponding screen is entered. Search's genre/trending initialization likewise waits until Search is opened.
- Added a shared maximum of five concurrent list-fetches to keep a large Home feed burst from issuing an unbounded set of simultaneous TMDB requests.
- Existing primary catalog surfaces use LazyColumn/LazyRow; stable IDs replace index-coupled keys where the relevant Home feeds changed.

## 3. Modified Android files

- `app/src/main/java/com/example/data/remote/TmdbApiService.kt`
- `app/src/main/java/com/example/data/repository/ContinueWatchingMetadataManager.kt`
- `app/src/main/java/com/example/data/repository/TmdbMediaRepositoryImpl.kt`
- `app/src/main/java/com/example/domain/repository/MediaRepository.kt`
- `app/src/main/java/com/example/navigation/AppNavigation.kt`
- `app/src/main/java/com/example/ui/components/MediaCard.kt`
- `app/src/main/java/com/example/ui/components/SharedUI.kt`
- `app/src/main/java/com/example/ui/screens/anime/AnimeScreen.kt`
- `app/src/main/java/com/example/ui/screens/anime/AnimeViewModel.kt`
- `app/src/main/java/com/example/ui/screens/details/DetailsScreens.kt`
- `app/src/main/java/com/example/ui/screens/details/MovieDetailsViewModel.kt`
- `app/src/main/java/com/example/ui/screens/details/SeriesDetailsViewModel.kt`
- `app/src/main/java/com/example/ui/screens/home/HomeScreen.kt`
- `app/src/main/java/com/example/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/example/ui/screens/movies/MoviesScreen.kt`
- `app/src/main/java/com/example/ui/screens/movies/MoviesViewModel.kt`
- `app/src/main/java/com/example/ui/screens/search/SearchScreen.kt`
- `app/src/main/java/com/example/ui/screens/search/SearchViewModel.kt`
- `app/src/main/java/com/example/ui/screens/series/SeriesScreen.kt`
- `app/src/main/java/com/example/ui/screens/series/SeriesViewModel.kt`
- `app/src/main/java/com/example/utils/MediaDetailsCacheManager.kt`
- `app/src/main/java/com/example/utils/MediaListDiskCacheManager.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ar/strings.xml`
- `app/src/test/java/com/example/data/remote/TmdbGatewayMigrationTest.kt`

## 4. Worker integration that must be applied in Cloudflare

The standalone app project does not own the deployed Cloudflare Worker editor. The Worker source copy used for this phase is `/mnt/data/worker-phase23.4.js`, with these final changes:

- Details call defaults to `append_to_response=credits,translations` and never silently adds videos.
- An explicitly empty `append_to_response=` is honored, so Continue Watching metadata has no appended credits/translations/videos.
- `/movie/{id}/videos` and `/tv/{id}/videos` validate `mode=primary|all`, filter the upstream list to valid YouTube trailers, sort by official status and active language, and return one result for `primary` or the qualified set for `all`.
- Invalid `mode` requests are rejected before contacting TMDB.
- Season details no longer append unnecessary credit/video payloads.

**The Worker patch is not deployed by this report.** Copy the function replacements from `PHASE_23.4-C_WORKER_PATCH.md` into the existing Worker editor and deploy from the user's Cloudflare environment. Keep existing Firebase token validation, admin allowlists, CORS and secret values unchanged.

## 5. Validation results

### Passed
- `node --check` on the updated Worker source.
- Six Worker route-contract tests against mocked TMDB/JWKS responses:
  1. default detail append excludes videos;
  2. explicitly empty append suppresses appended resources;
  3. `mode=primary` returns exactly one language-ranked YouTube Trailer;
  4. `mode=all` returns only qualified YouTube Trailer records, excluding teasers/clips/featurettes and other sites;
  5. invalid mode returns HTTP 400 before an upstream TMDB call;
  6. season route still responds without unnecessary appended resources.
- XML parsing for all 13 resource XML files: zero parse errors.
- Duplicate named value resources: none found in the examined `values*` XML files.
- Static checks confirmed language-partitioned list cache, strict genre ID filtering, language-partitioned episode cache, video endpoints, `mode` parameter, show/hide controls and route transition declarations.

### Not verified
- Android Gradle compilation and instrumented/unit test execution could not run because the Gradle wrapper attempted to download Gradle 9.3.1 from `services.gradle.org`, and the environment returned `UnknownHostException`. The standalone Kotlin compiler did not have the Android/Retrofit/Compose dependency classpath, so it cannot substitute for a Gradle build.
- No physical-device frame profiler was available. The catalog screens use `LazyColumn` plus `LazyRow`, and the request burst is deferred/bounded, but smooth scrolling cannot be guaranteed without on-device measurement. Movie details still uses a vertically scrolling `Column`; if jank persists there after this phase, profile that screen before converting its large section tree to `LazyColumn` items.
- The new live Cloudflare endpoints were not called with a real Firebase user token from this environment. The local tests used a signed test token and mocked upstream responses.

## 6. Deployment / next test sequence

1. Apply `PHASE_23.4-C_WORKER_PATCH.md` to the current Worker and deploy it.
2. Test authenticated movie details with `append_to_response=credits,translations` and confirm no `videos` object is appended.
3. Test `/movie/277834/videos?language=ar-SA&mode=primary` and `/movie/277834/videos?language=ar-SA&mode=all` with an authenticated app session.
4. Build the Android project in an environment with the required Gradle distribution and `app/google-services.json` present.
5. On-device: open a detail page, verify one trailer card appears initially, then expand other trailers; test several catalog categories and a person filmography; profile vertical scrolling before/after changes.

This phase does not change Firebase Authentication, ads, comments, payment flows, Cloudinary behavior, package identity or the app's overall visual theme.
