# PHASE 23.4-D — App / Cloudflare Worker Contract Check

## Android routes required by this project

The current Android `TmdbApiService` calls these routes at the Worker base URL:

- `GET /api/v1/tmdb/movie/popular?page={page}&language={en-US|ar-SA}`
- `GET /api/v1/tmdb/tv/popular?page={page}&language={en-US|ar-SA}`
- `GET /api/v1/tmdb/movie/upcoming?page={page}&language={en-US|ar-SA}`
- `GET /api/v1/tmdb/discover/movie` and `/api/v1/tmdb/discover/tv`, including whitelisted date, genre and original-language filters
- `GET /api/v1/tmdb/genre/movie/list` and `/api/v1/tmdb/genre/tv/list`
- `GET /api/v1/tmdb/movie/{id}` and `/api/v1/tmdb/tv/{id}` with `append_to_response=credits,translations` by default; `videos` must not be appended to details requests
- `GET /api/v1/tmdb/movie/{id}/videos?mode=primary|all`
- `GET /api/v1/tmdb/tv/{id}/videos?mode=primary|all`
- `GET /api/v1/tmdb/tv/{id}/season/{seasonNumber}`
- Person detail and combined-credit routes

Every TMDB request is authenticated using the existing Firebase ID-token interceptor. The Android app does not contain a TMDB secret.

## Required behavior for the separately deployed Worker

1. Every `/api/v1/tmdb/*` request continues to require a valid Firebase ID token, including anonymous guest tokens. Admin-only routes remain protected by `ADMIN_UIDS`.
2. The catalog router must recognize `movie/popular`, `tv/popular`, and `movie/upcoming`. Add `/api/v1/tmdb/tv/popular` to `API_ROUTES` metadata so the root endpoint also documents it.
3. Discover query validation must forward the Android filters `with_genres`, `with_original_language`, `primary_release_date.gte/lte`, and `first_air_date.gte/lte` after validation. It must never substitute unrelated Trending results when a filtered result is empty.
4. Details requests must not append `videos`. The `append_to_response` whitelist may allow `credits` and `translations`, but must honor the explicit empty value used by Continue Watching.
5. Video routes must validate `mode` (`primary` or `all`) and return the envelope `{ ok, service, media_type, resource: "videos", language, mode, data: { results: [...] } }`. `primary` returns at most one eligible YouTube video with type `Trailer`; `all` returns eligible trailer-type videos only. Both modes exclude teasers, clips, featurettes, interviews, TV spots and similar promotional assets, including misclassified titles.
6. The Android app calls `mode=primary` when loading a detail page and requests `mode=all` only after the user expands the secondary-trailers section. The Worker cannot prevent clients from issuing `mode=all`; timing is controlled by the Android UI.
7. Preserve the deployed CORS allowlist and existing TMDB credential handling. Do not set `Access-Control-Allow-Origin: *` as a shortcut. The code patch is not deployed by an Android project build.

## Deployment verification steps

With a valid Firebase bearer token, check the following requests against the actual deployed Worker:

- `/api/v1/tmdb/movie/popular?page=1&language=en-US`
- `/api/v1/tmdb/movie/upcoming?page=1&language=en-US`
- `/api/v1/tmdb/discover/tv?page=1&with_genres=16&with_original_language=ja&language=en-US`
- `/api/v1/tmdb/movie/277834?language=en-US&append_to_response=credits,translations` and confirm `data.videos` is absent
- `/api/v1/tmdb/movie/277834/videos?language=en-US&mode=primary` and confirm `data.results.length <= 1`
- `/api/v1/tmdb/movie/277834/videos?language=en-US&mode=all` and confirm results contain only eligible trailers

These are required live checks. The local build environment in this phase could not download Gradle 9.3.1 because `services.gradle.org` could not be resolved; consequently no claim is made that the revised Android app or deployed Worker passed live verification.
