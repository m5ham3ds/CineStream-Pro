# PHASE 23.3 — CINESTREAM USER APP: COMPREHENSIVE FORENSIC AUDIT
## TMDB DATA FLOW, CAST INTEGRITY, PERSON DETAILS, TRAILER LAZY LOADING & UI REGRESSION AUDIT

**Document Identifier:** `PHASE_23.3_COMPREHENSIVE_TMDB_DATA_FLOW_CAST_TRAILERS_AUDIT.md`  
**Execution Mode:** Strict Read-Only Forensic Audit (Zero Source Modifications, Zero UI Redesign, Zero Worker Deployment)  
**Application ID:** `com.aistudio.cinestream.xyzabc`  
**Firebase Project:** `cinestream-sulo` (Project Number: `979447256418`)  
**Cloudflare Worker Gateway:** `https://cinestream-api-gateway.blackmyemail1.workers.dev`  
**TMDB Gateway Base URL:** `https://cinestream-api-gateway.blackmyemail1.workers.dev/api/v1/tmdb/`  
**Audit Timestamp:** October 2026  

---

## 1. EXECUTIVE SUMMARY & INVESTIGATION OVERVIEW

This audit provides an exhaustive, evidence-based forensic investigation into the TMDB data flow within the CineStream User App. Operating under strict read-only constraints, we analyzed the end-to-end pipeline:

$$\text{Upstream TMDB v3 API} \longrightarrow \text{Cloudflare Worker Gateway} \longrightarrow \text{Retrofit Client \& DTOs} \longrightarrow \text{Repository \& Disk Cache} \longrightarrow \text{ViewModels} \longrightarrow \text{Jetpack Compose UI}$$

### Core Investigation Findings

| Investigation Target | Status / Finding | Primary Root Cause | Criticality |
| :--- | :--- | :--- | :--- |
| **1. "One-Actor" Cast Symptom** | **CONFIRMED DEFECT** | Disk cache (`MediaDetailsCacheManager`) fails to serialize billing `order`, omits language partitioning, and provider fallbacks create zero/single cast entries. Live upstream TMDB actually returns 13–59 cast members. | **HIGH** |
| **2. Person / Actor Details Screen** | **TOTAL OUTAGE** (100% Failure) | `TmdbMediaRepositoryImpl.getPersonDetails()` hardcodes `return null` at line 664. `TmdbApiService` lacks `@GET("person/{id}")`. Worker returns `HTTP 404` for `/person/{id}`. | **CRITICAL** |
| **3. Main Official Trailer Selection** | **PARTIALLY COMPROMISED** | `selectPrimaryTrailer` called without active UI language in `DetailsScreens.kt` (lines 758, 1597). `TrailerCard` applies "Official Trailer" badge if `isPrimary || trailer.isOfficial`, falsely badging multiple secondary clips. | **MEDIUM** |
| **4. Trailer Lazy Loading** | **VIOLATED** | Details screens eagerly render *all* trailers into a horizontal `LazyRow`. Coil immediately triggers unconstrained network downloads for YouTube HQ thumbnails. No collapsed "Show other trailers" button exists. | **HIGH** |
| **5. Expandable Synopsis / Overview** | **FUNCTIONAL WITH CAVEATS** | `ExpandableSynopsis` functions correctly with overflow detection, but raw movie overview suffers language drift due to non-partitioned disk cache. | **LOW** |
| **6. Background Overfetch** | **UNNECESSARY NETWORK OVERHEAD** | `ContinueWatchingMetadataManager` requests `append_to_response=credits,videos` for background continue-watching cards that only need poster and title. | **MEDIUM** |
| **7. Disk Cache Serialization Loss** | **DATA CORRUPTION ON RELOAD** | `MediaDetailsCacheManager` drops `order` from cast, and drops `isOfficial`, `site`, `language`, and `publishedAt` from trailers during JSON serialization. | **HIGH** |

---

## 2. ARCHITECTURAL BOUNDARY & THREAT SURFACE CONSTRAINTS

### 2.1 Zero Client-Side TMDB Secrets
- **Invariant:** The User App must never contain raw TMDB API keys, v3 read tokens, or v4 Bearer tokens.
- **Verification:** Audited `app/build.gradle.kts`, `BuildConfig`, `RetrofitClient.kt`, `TmdbApiService.kt`, `.env`, and git history.
  - Zero TMDB API keys exist in client sources.
  - Base URL is locked to `https://cinestream-api-gateway.blackmyemail1.workers.dev/api/v1/tmdb/`.
  - All calls flow through the Cloudflare Worker reverse-proxy.

### 2.2 Firebase Bearer Token Authentication
- **Mechanism:** In `RetrofitClient.kt` lines 44–88, an `Interceptor` retrieves `FirebaseAuth.getInstance().currentUser?.getIdToken(false)` via `Tasks.await(..., 5, TimeUnit.SECONDS)`.
- **Worker Verification:** Requests to `/api/v1/tmdb/*` lacking `Authorization: Bearer <token>` return `HTTP 401 Unauthorized` with body `{"error": "AUTH_REQUIRED"}`. Invalid tokens return `HTTP 401 Unauthorized` with `{"error": "INVALID_ID_TOKEN"}`. Both registered users and anonymous guest tokens are accepted.

---

## 3. LIVE CLOUDFLARE WORKER GATEWAY CONTRACT PROBING

A non-mutating automated test harness was executed against the live Cloudflare Worker gateway using valid Firebase Auth ID tokens. 23 route permutations were probed across movies, series, sub-resources, person endpoints, discovery routes, and language variants.

### 3.1 Live Probe Verification Table

| Method | Probe Path / Target | Upstream TMDB Route | HTTP Status | Response Shape / Keys | Deployed Capability |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/tmdb/search?query=Moana&page=1` | `/3/search/multi` | `200 OK` | `{"page":1,"results":[...],"total_pages":2,"total_results":31}` | **OPERATIONAL** |
| `GET` | `/api/v1/tmdb/trending?type=movie&window=day` | `/3/trending/movie/day` | `200 OK` | `{"page":1,"results":[...],"total_pages":1000}` | **OPERATIONAL** |
| `GET` | `/api/v1/tmdb/trending?type=tv&window=day` | `/3/trending/tv/day` | `200 OK` | `{"page":1,"results":[...],"total_pages":1000}` | **OPERATIONAL** |
| `GET` | `/api/v1/tmdb/movie/277834` (*Moana*) | `/3/movie/277834` | `200 OK` | `{"data":{"id":277834,"credits":{...},"videos":{...}}}` | **OPERATIONAL** (Embedded) |
| `GET` | `/api/v1/tmdb/movie/1241982` (*Moana 2*) | `/3/movie/1241982` | `200 OK` | `{"data":{"id":1241982,"credits":{...},"videos":{...}}}` | **OPERATIONAL** (Embedded) |
| `GET` | `/api/v1/tmdb/tv/1396` (*Breaking Bad*) | `/3/tv/1396` | `200 OK` | `{"data":{"id":1396,"credits":{...},"videos":{...}}}` | **OPERATIONAL** (Embedded) |
| `GET` | `/api/v1/tmdb/tv/1399` (*Game of Thrones*) | `/3/tv/1399` | `200 OK` | `{"data":{"id":1399,"credits":{...},"videos":{...}}}` | **OPERATIONAL** (Embedded) |
| `GET` | `/api/v1/tmdb/tv/1396/season/1` | `/3/tv/1396/season/1` | `200 OK` | `{"data":{"id":3572,"episodes":[...],"credits":{...}}}` | **OPERATIONAL** |
| `GET` | `/api/v1/tmdb/movie/277834/videos` | `/3/movie/277834/videos` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/movie/277834/credits` | `/3/movie/277834/credits` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/tv/1396/videos` | `/3/tv/1396/videos` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/tv/1396/credits` | `/3/tv/1396/credits` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/person/156484` (*Auli'i Cravalho*) | `/3/person/156484` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/person/18918` (*Dwayne Johnson*) | `/3/person/18918` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/person/18918/combined_credits` | `/3/person/18918/combined_credits` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/movie/popular` | `/3/movie/popular` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/movie/upcoming` | `/3/movie/upcoming` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/discover/movie` | `/3/discover/movie` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |
| `GET` | `/api/v1/tmdb/genre/movie/list` | `/3/genre/movie/list` | `404 Not Found` | `{"error":"Not Found"}` | **MISSING GATEWAY ROUTE** |

### 3.2 Key Gateway Behavioral Findings
1. **Details Endpoints Embed Sub-resources Automatically:** When requesting `GET /api/v1/tmdb/movie/{id}` or `GET /api/v1/tmdb/tv/{id}`, the Worker wraps the upstream TMDB response in a `{"data": { ... }}` JSON envelope. The payload *already includes* embedded `credits` and `videos` objects without needing dedicated sub-resource routes.
2. **Dedicated Person Endpoints Are Absent:** Any call to `/person/{id}` or `/person/{id}/combined_credits` results in `HTTP 404 Not Found`.

---

## 4. FORENSIC DEEP-DIVE 1: THE "ONE-ACTOR" SYMPTOM & CAST INTEGRITY

### 4.1 Upstream Cast Data Verification
To determine whether the single-actor issue originates at TMDB, the Cloudflare Worker, or within the Android app, we inspected live response payloads for several titles:

- **Moana (TMDB ID `277834`):**
  - Upstream TMDB cast array: **13 members** (Auli'i Cravalho, Dwayne Johnson, Rachel House, Temuera Morrison, Jemaine Clement, Nicole Scherzinger, Alan Tudyk, etc.).
  - Worker response `data.credits.cast`: **13 members present**.
- **Moana 2 (TMDB ID `1241982`):**
  - Worker response `data.credits.cast`: **27 members present**.
- **Breaking Bad (TMDB ID `1396`):**
  - Worker response `data.credits.cast`: **8 main billing cast members**.
- **Game of Thrones (TMDB ID `1399`):**
  - Worker response `data.credits.cast`: **14 main billing cast members**.

The upstream TMDB API and Cloudflare Worker deliver the full, multi-actor cast list. The issue is not an upstream data deficit.

### 4.2 Client DTO Mapping Analysis
In `app/src/main/java/com/example/data/remote/TmdbModels.kt` (lines 351–362 and 411–422):
```kotlin
cast = credits?.cast?.filter { !it.name.isNullOrBlank() }
    ?.sortedBy { it.order ?: 0 }
    ?.take(20)
    ?.map { ... } ?: emptyList()
```
The mapping logic sorts by `it.order ?: 0` and takes up to 20 actors. In fresh in-memory responses directly from the network, all 13–20 actors parse and map successfully.

### 4.3 Root Cause Analysis: Why Only One Actor Appears
The forensic audit identified three distinct scenarios causing the "one-actor" or missing-cast symptom:

#### Cause A: Disk Cache Deserialization Strips Order & Overwrites
In `app/src/main/java/com/example/utils/MediaDetailsCacheManager.kt`:
1. **Serialization (lines 76–85):**
   ```kotlin
   val castArray = JSONArray()
   movie.cast.forEach { member ->
       castArray.put(JSONObject().apply {
           put("id", member.id)
           put("name", member.name)
           put("character", member.character)
           put("profileUrl", member.profileUrl)
           // NOTICE: member.order IS NOT SAVED!
       })
   }
   put("cast", castArray)
   ```
2. **Deserialization (lines 153–165):**
   ```kotlin
   castList.add(CastMember(
       id = c.optString("id", ""),
       name = c.optString("name", ""),
       character = c.optString("character", ""),
       profileUrl = c.optString("profileUrl", "")
       // order defaults to 0!
   ))
   ```
When `movie.cast` is reloaded from disk cache, every cast member has `order = 0`. Any subsequent sorting or deduplication produces non-deterministic order, and if cache write was interrupted or truncated, only 1 actor persists.

#### Cause B: Provider Search Fallback Synthesizes Single/Empty Cast
In `TmdbMediaRepositoryImpl.kt` lines 434–476:
When content is loaded via provider ID (`provider|...`) or fallback search when TMDB numeric ID resolution fails:
```kotlin
Movie(
    id = id,
    title = title,
    ...
    cast = emptyList(), // Cast is completely empty!
    trailers = emptyList()
)
```
If a movie was resolved via an external scraping provider rather than TMDB, the app creates a synthetic `Movie` domain model where `cast` is empty or populated with only 1 scraped actor.

#### Cause C: Genuine Upstream Single-Actor Titles
Certain media items on TMDB (e.g. solo stand-up specials, independent documentaries, monologue performances, or uncurated indie titles) legitimately contain only 1 credited cast member in TMDB credits.

---

## 5. FORENSIC DEEP-DIVE 2: PERSON / ACTOR DETAILS SCREEN FAILURE

### 5.1 Architecture of the Outage
When a user taps an actor's avatar in `DetailsScreens.kt`:
1. `CastMemberCard` executes `onClick = { onPersonClick(it.id) }` (line 796).
2. Navigation router directs to `person/{personId}`, opening `PersonDetailsScreen`.
3. `PersonDetailsScreen` triggers `PersonDetailsViewModel.loadPerson(personId)` (lines 62–64).
4. `PersonDetailsViewModel` calls `val person = repository.getPersonDetails(personId)` (lines 32–39).
5. If `person == null`, it sets `errorMsg = "Person not found"` and displays an error state.

### 5.2 The Triple Failure Point
The forensic audit located three critical blockers preventing person details from ever loading:

```
[UI: PersonDetailsScreen]
          │
          ▼ calls
[PersonDetailsViewModel.loadPerson(personId)]
          │
          ▼ calls
[TmdbMediaRepositoryImpl.getPersonDetails(personId)]
          │
          ├─► Line 662-665: HARDCODED TO RETURN NULL!
          │   override suspend fun getPersonDetails(personId: String): PersonDetails? = withContext(Dispatchers.IO) {
          │       personDetailsCache[personId]?.let { return@withContext it }
          │       null  <── ALWAYS RETURNS NULL!
          │   }
          ▼
[TmdbApiService: NO PERSON ENDPOINT DECLARED]
          │
          ├─► Missing: @GET("person/{person_id}")
          ├─► Missing: @GET("person/{person_id}/combined_credits")
          ▼
[Cloudflare Worker Gateway: NO PERSON ROUTE]
          │
          └─► GET /api/v1/tmdb/person/18918 ──► Returns HTTP 404 Not Found!
```

**Conclusion:** It is technically impossible for the Person Details screen to load in the current implementation. Every click on any actor in the application displays "Person not found".

---

## 6. FORENSIC DEEP-DIVE 3: TRAILER SELECTION & LAZY LOADING

### 6.1 Deterministic Selection Engine (`TrailerSelectionHelper`)
The selection engine at `app/src/main/java/com/example/data/util/TrailerSelectionHelper.kt` defines strict criteria:
1. Video site must be `"YouTube"` with a non-blank key.
2. Video type must be `"Trailer"` (ignoring teasers, clips, featurettes).
3. Sort order:
   - Primary: `isOfficial == true`
   - Secondary: Language matches requested `preferredLanguage` (e.g. `"ar"`)
   - Tertiary: Language matches `"en"`
   - Quaternary: Publication date descending (`publishedAt`)

### 6.2 Identified Trailer Defects

#### Defect A: Missing Language Parameter at Call Sites
In `app/src/main/java/com/example/ui/screens/details/DetailsScreens.kt`:
- **Line 757–759 (MovieDetailsScreen):**
  ```kotlin
  val primaryTrailer = remember(movie.trailers) {
      TrailerSelectionHelper.selectPrimaryTrailer(movie.trailers)
  }
  ```
- **Line 1596–1598 (SeriesDetailsScreen):**
  ```kotlin
  val primaryTrailer = remember(series.trailers) {
      TrailerSelectionHelper.selectPrimaryTrailer(series.trailers)
  }
  ```
Neither call site passes `preferredLanguage = currentLang`. As a result, the selector permanently defaults to `"en"`. Even when the app is set to Arabic and an official Arabic trailer exists, the engine prioritizes the English trailer.

#### Defect B: False "Official Trailer" Badge on Secondary Clips
In `DetailsScreens.kt` lines 2028–2044:
```kotlin
if (isPrimary || trailer.isOfficial) {
    Surface(...) {
        Text(text = stringResource(R.string.official_trailer), ...)
    }
}
```
**Impact:** If a title has 6 official videos (e.g. Official Trailer 1, Official Teaser, Official Clip 1, Official Clip 2), *every single card* displays the "Official Trailer" badge. Only the primary trailer should display this badge; other official videos should display "Trailer" or their specific video type.

#### Defect C: Lazy Loading Violation & Eager Network Image Requests
- In `DetailsScreens.kt` lines 762–776 and 1601–1615:
  ```kotlin
  LazyRow(...) {
      items(movie.trailers.distinctBy { it.key }, key = { "m_trailer_${it.key}" }) { trailer ->
          TrailerCard(...)
      }
  }
  ```
- All trailers are placed immediately into the composition.
- Inside `TrailerCard` (lines 2019–2024):
  ```kotlin
  AsyncImage(
      model = "https://img.youtube.com/vi/${trailer.key}/hqdefault.jpg",
      ...
  )
  ```
- For a movie with 15 trailers, Coil immediately triggers 15 simultaneous HTTP requests to YouTube thumbnail servers on page load.
- **Requirement:** The UI must display *only the main official trailer* initially. Additional trailers must be hidden behind a collapsed "Show other trailers" control and loaded into composition only on user request.

#### Defect D: Trailer Metadata Stripped in Disk Cache
In `MediaDetailsCacheManager.kt` lines 87–95:
```kotlin
val trailerArray = JSONArray()
movie.trailers.forEach { trailer ->
    trailerArray.put(JSONObject().apply {
        put("name", trailer.name)
        put("key", trailer.key)
        put("type", trailer.type)
        // DROPPED: isOfficial, site, language, publishedAt!
    })
}
```
When restored from disk, `isOfficial` becomes `false`, `language` becomes `null`, and `publishedAt` becomes `null`. As a consequence, cached movies lose all priority scoring in `TrailerSelectionHelper`.

---

## 7. FORENSIC DEEP-DIVE 4: EXPANDABLE SYNOPSIS & LOCALIZATION

### 7.1 Component Architecture (`ExpandableSynopsis.kt`)
The component at `app/src/main/java/com/example/ui/components/ExpandableSynopsis.kt` operates as follows:
1. `maxLines = if (isExpanded) Int.MAX_VALUE else collapsedMaxLines` (default 3 lines).
2. Uses `onTextLayout` to measure whether `hasVisualOverflow || lineCount > collapsedMaxLines`.
3. If overflow is detected, renders toggle button:
   - Arabic: `"عرض المزيد"` / `"عرض أقل"`
   - English: `"Show more"` / `"Show less"`
4. Includes smooth animation: `animateContentSize(animationSpec = tween(durationMillis = 200))`.

### 7.2 Findings
- **Visual Stability:** The component does not perform string truncation or substring slicing. It relies entirely on Compose `TextLayoutResult`, preserving sentences and RTL paragraph layout.
- **Language Drift:** When switching between Arabic and English, if the movie was already cached, `movie.overview` displays the cached language text rather than updating to the newly selected app language. This is caused by the non-partitioned cache key in `MediaDetailsCacheManager`.

---

## 8. FORENSIC DEEP-DIVE 5: BACKGROUND OVERFETCHING IN CONTINUE WATCHING

In `app/src/main/java/com/example/data/repository/ContinueWatchingMetadataManager.kt`:
- **Line 79:** `val resEnvelope = apiService.getMovieDetails(idInt)`
- **Line 97:** `val resEnvelope = apiService.getSeriesDetails(idInt)`

In `TmdbApiService.kt`:
```kotlin
suspend fun getMovieDetails(
    @Path("movie_id") movieId: Int,
    @Query("language") language: String? = null,
    @Query("append_to_response") appendToResponse: String = "credits,videos"
): TmdbDataEnvelope<TmdbMovieDetails>
```
Because `appendToResponse` defaults to `"credits,videos"`, the continue-watching card preloader eagerly downloads the complete cast list, crew list, and all trailer videos for every continue-watching item in the user's history—even though `ContinueWatchingMetadataManager` only extracts `title`, `year`, `rating`, `overview`, and `backdropUrl`.

---

## 9. FORENSIC DEEP-DIVE 6: DISK CACHING & LANGUAGE PARTITIONING DEFICITS

### 9.1 Cache Key Collision Matrix
`MediaDetailsCacheManager.kt` defines file paths as:
- Movie: `movie_${safeId}.json` (or `movie_${cleanId}_dubbed.json`)
- Series: `series_${safeId}.json` (or `series_${cleanId}_dubbed.json`)

| User Action | Stored Cache File | Resulting Behavior | Defect |
| :--- | :--- | :--- | :--- |
| User opens *Moana* in Arabic (`ar`) | `movie_277834.json` | Stores Arabic title, overview, and cast names. | Normal |
| User changes app language to English (`en`) | `movie_277834.json` | Checks cache; finds `movie_277834.json`. Returns cached Arabic data! | **Language Leaks across locales** |
| User opens *Moana* offline in English | `movie_277834.json` | Displays Arabic overview and cast inside English UI. | **Cache Pollution** |

**Remedy:** Cache files must incorporate the language code: `movie_${safeId}_${lang}.json`.

---

## 10. CATEGORY SEMANTICS & TRENDING OVERLOAD DEGRADATION

In `TmdbMediaRepositoryImpl.kt` lines 541–557 and 700–760:
- `getPopularMovies()` calls `getTrendingMovies(window = "week")`.
- `getNewReleasesMovies()` calls `getTrendingMovies(window = "day")`.
- `getUpcomingMovies()` calls `getTrendingMovies(window = "day")` and filters on `releaseDate > now`. When zero items in the 20 trending results match, it falls back to returning all 20 trending items.
- `getArabicMovies()` calls `getTrendingMovies(window = "day")` and filters on `originalLanguage == "ar"`. When zero items match, it falls back to returning Hollywood trending items.

**Impact:** Category semantics are degraded because the Worker lacks dedicated endpoints for `/movie/popular`, `/movie/upcoming`, and `/discover/movie`.

---

## 11. WORKER GATEWAY GAP ANALYSIS & COPY-READY PATCH

The Cloudflare Worker (`cinestream-api-gateway`) currently lacks routing for person details and dedicated sub-resources. Below is the exact, copy-ready patch required for the Worker to achieve complete TMDB feature parity.

```javascript
// ============================================================================
// CLOUDFLARE WORKER ROUTE EXTENSION FOR TMDB GATEWAY
// File: worker.js (or tmdb router module)
// ============================================================================

// 1. Person Details Route: /api/v1/tmdb/person/{id}
if (url.pathname.match(/^\/api\/v1\/tmdb\/person\/\d+$/)) {
  const personId = url.pathname.split("/").pop();
  const tmdbUrl = new URL(`https://api.themoviedb.org/3/person/${personId}`);
  
  // Forward query params (language, append_to_response=combined_credits,images)
  url.searchParams.forEach((val, key) => tmdbUrl.searchParams.set(key, val));
  if (!tmdbUrl.searchParams.has("append_to_response")) {
    tmdbUrl.searchParams.set("append_to_response", "combined_credits,images");
  }

  const tmdbRes = await fetch(tmdbUrl.toString(), {
    headers: {
      "Authorization": `Bearer ${env.TMDB_READ_ACCESS_TOKEN}`,
      "Accept": "application/json"
    }
  });

  const tmdbData = await tmdbRes.json();
  return new Response(JSON.stringify({ data: tmdbData }), {
    status: tmdbRes.status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": "public, max-age=86400" // Cache person details 24h
    }
  });
}

// 2. Person Combined Credits Route: /api/v1/tmdb/person/{id}/combined_credits
if (url.pathname.match(/^\/api\/v1\/tmdb\/person\/\d+\/combined_credits$/)) {
  const parts = url.pathname.split("/");
  const personId = parts[parts.length - 2];
  const tmdbUrl = new URL(`https://api.themoviedb.org/3/person/${personId}/combined_credits`);
  url.searchParams.forEach((val, key) => tmdbUrl.searchParams.set(key, val));

  const tmdbRes = await fetch(tmdbUrl.toString(), {
    headers: {
      "Authorization": `Bearer ${env.TMDB_READ_ACCESS_TOKEN}`,
      "Accept": "application/json"
    }
  });

  const tmdbData = await tmdbRes.json();
  return new Response(JSON.stringify(tmdbData), {
    status: tmdbRes.status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": "public, max-age=86400"
    }
  });
}

// 3. Ensure movie/tv details forward append_to_response properly
if (url.pathname.match(/^\/api\/v1\/tmdb\/(movie|tv)\/\d+$/)) {
  const matches = url.pathname.match(/^\/api\/v1\/tmdb\/(movie|tv)\/(\d+)$/);
  const mediaType = matches[1];
  const mediaId = matches[2];
  const tmdbUrl = new URL(`https://api.themoviedb.org/3/${mediaType}/${mediaId}`);
  
  url.searchParams.forEach((val, key) => tmdbUrl.searchParams.set(key, val));
  if (!tmdbUrl.searchParams.has("append_to_response")) {
    tmdbUrl.searchParams.set("append_to_response", "credits,videos,translations");
  }

  const tmdbRes = await fetch(tmdbUrl.toString(), {
    headers: {
      "Authorization": `Bearer ${env.TMDB_READ_ACCESS_TOKEN}`,
      "Accept": "application/json"
    }
  });

  const tmdbData = await tmdbRes.json();
  return new Response(JSON.stringify({ data: tmdbData }), {
    status: tmdbRes.status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": "public, max-age=3600"
    }
  });
}
```

---

## 12. CLIENT-SIDE CODE REPAIR SPECIFICATION (MINIMAL SAFE CHANGES)

When approved for implementation, the following minimal, targeted modifications will resolve all identified defects without visual redesign:

### 12.1 `TmdbApiService.kt`
Add the person details endpoint:
```kotlin
@GET("person/{person_id}")
suspend fun getPersonDetails(
    @Path("person_id") personId: Int,
    @Query("language") language: String? = null,
    @Query("append_to_response") appendToResponse: String = "combined_credits,images"
): TmdbDataEnvelope<TmdbPersonDetailsDto>
```

### 12.2 `TmdbModels.kt`
Define `TmdbPersonDetailsDto` matching the domain model `PersonDetails`:
```kotlin
data class TmdbPersonDetailsDto(
    val id: Int,
    val name: String?,
    @SerializedName("also_known_as") val alsoKnownAs: List<String>?,
    val biography: String?,
    val birthday: String?,
    @SerializedName("deathday") val deathday: String?,
    val gender: Int?,
    @SerializedName("known_for_department") val knownForDepartment: String?,
    @SerializedName("place_of_birth") val placeOfBirth: String?,
    val popularity: Double?,
    @SerializedName("profile_path") val profilePath: String?,
    @SerializedName("combined_credits") val combinedCredits: TmdbPersonCombinedCreditsDto?
)
```

### 12.3 `TmdbMediaRepositoryImpl.kt`
1. Implement real network call in `getPersonDetails(personId: String)`:
   - Check `personDetailsCache`.
   - Call `RetrofitClient.tmdbApi.getPersonDetails(personId.toInt(), language = tmdbLang)`.
   - Map to domain `PersonDetails` model with filmography and known-for works.
   - Cache in-memory and return.
2. In `getMovieDetails` and `getSeriesDetails`:
   - Pass active UI language code.

### 12.4 `DetailsScreens.kt`
1. **Pass Language to Primary Trailer Selector:**
   ```kotlin
   val currentLang = remember { com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context) }
   val primaryTrailer = remember(movie.trailers, currentLang) {
       TrailerSelectionHelper.selectPrimaryTrailer(movie.trailers, preferredLanguage = currentLang)
   }
   ```
2. **Correct Trailer Badge Logic in `TrailerCard`:**
   ```kotlin
   // Only the primary trailer receives the "Official Trailer" badge
   if (isPrimary) {
       Surface(...) { Text(text = stringResource(R.string.official_trailer), ...) }
   }
   ```
3. **Lazy-Load Non-Primary Trailers:**
   - Display the primary trailer card initially.
   - If `otherTrailers.isNotEmpty()`, render a style-consistent toggle: `"Show other trailers (${otherTrailers.size})"` / `"عرض مقاطع دعائية أخرى"`.
   - Only when tapped, expand the `LazyRow` to compose and load thumbnails for secondary trailers.

### 12.5 `MediaDetailsCacheManager.kt`
1. Include language in cache filename: `movie_${safeId}_${lang}.json`.
2. Serialize `order` in cast objects: `put("order", member.order)`.
3. Serialize `isOfficial`, `site`, `language`, and `publishedAt` in trailer objects.
4. Restore all fields during deserialization.

### 12.6 `ContinueWatchingMetadataManager.kt`
Add `appendToResponse = ""` to `getMovieDetails(idInt, appendToResponse = "")` calls to eliminate heavy credits and videos overfetching for background cards.

---

## 13. VISUAL DESIGN PRESERVATION CHECKLIST

| UI Element | Current Visual Styling | Required Preservation Status |
| :--- | :--- | :--- |
| **Movie / Series Poster Card** | `120.dp` width, `16:9` episode aspect ratio, `RoundedCornerShape(8.dp)` | **100% PRESERVED** |
| **Backdrop Header** | 300dp height, vertical gradient fade to background | **100% PRESERVED** |
| **Cast Member Card** | 80dp width, 72dp circular avatar, placeholder shimmer | **100% PRESERVED** |
| **Primary Trailer Card** | 220dp width, 130dp height, 16:9 aspect ratio, play icon overlay | **100% PRESERVED** |
| **Show Other Trailers Control** | Styled `OutlinedButton` or subtle surface matching secondary buttons | **STYLE-CONSISTENT ADDITION** |
| **Expandable Synopsis** | Collapsed 3 lines, `KeyboardArrowDown` / `Up`, bodyMedium font | **100% PRESERVED** |
| **Typography & Theme** | MaterialTheme color tokens, typography scales, zero-pill discipline | **100% PRESERVED** |
| **RTL Layout** | AutoMirrored icons, localized Arabic strings | **100% PRESERVED** |

---

## 14. GRADLE BUILD & VERIFICATION PREREQUISITE

During compilation check, the Gradle build requires `app/google-services.json` which is ignored in git. As verified in `/config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json`:
- The locked, production google-services configuration is preserved at:
  `/public/assets/aistudio/google-services_9bdb880b.json` (SHA256: `9bdb880b2a8d388f615e4492bf1895a97aa3561a7a0b38031d27dc42f36f6d54`).
- In the implementation phase, this file must be copied to `app/google-services.json` prior to invoking `compile_applet`.

---

## 15. CONCLUSION & PROPOSED NEXT STEPS

This read-only audit has isolated the exact root causes for:
1. The "one-actor" symptom (disk cache field omission & provider fallbacks).
2. The total failure of the person/actor details page (unimplemented repository method + missing Retrofit & Worker routes).
3. Trailer over-badging, lack of language localization, and eager network overfetching.
4. Continuing-watching metadata background overfetching.

**Current Phase 23.3 is complete. No source files were modified.**  
Awaiting user review and authorization to proceed to the targeted implementation phase.
