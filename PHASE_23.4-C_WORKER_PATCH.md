# PHASE 23.4-D — Copy/Paste Worker Patch

Apply these changes to the currently deployed Cloudflare Gateway `worker.js`. This is a source patch guide; it does not mean the hosted Worker was deployed. Preserve the existing Firebase JWT verification, `ADMIN_UIDS`, CORS allowlist, rate limits, TMDB credential handling, and Cloudinary configuration.

## 1. Document the TV popular catalog route

Add this entry next to the movie popular/upcoming entries in `API_ROUTES`:

```javascript
{ method: "GET", path: "/api/v1/tmdb/tv/popular" },
```

The catalog router must continue to support `GET /api/v1/tmdb/tv/popular` because Android uses `@GET("tv/popular")`.

## 2. Details must not append videos

Use this helper:

```javascript
function getDetailAppendToResponse(requested) {
  if (requested === "") return "";

  const allowed = new Set([
    "credits", "translations", "images", "keywords",
    "recommendations", "similar", "release_dates", "external_ids",
    "aggregate_credits"
  ]);
  const source = requested == null ? "credits,translations" : String(requested);
  const supplied = source
    .split(",")
    .map(value => value.trim().toLowerCase())
    .filter(value => allowed.has(value));
  return [...new Set(["credits", "translations", ...supplied])].join(",");
}
```

In `tmdbDetails`, use the helper for both movie and TV details. Do not append `videos` or add `include_video_language` to the detail request. A season request should retrieve the season resource (and credits only when required); it must not append videos. The explicit empty string is used by Continue Watching cards to avoid additional data.

## 3. Filter videos and support `mode=primary|all`

Add these helpers near `tmdbMediaSubresource`:

```javascript
function isEligibleTrailer(video) {
  if (!video || typeof video !== "object") return false;

  const site = String(video.site || "").trim().toLowerCase();
  const type = String(video.type || "").trim().toLowerCase();
  const key = String(video.key || "").trim();
  const name = String(video.name || "").trim();

  if (site !== "youtube" || type !== "trailer") return false;
  if (!/^[A-Za-z0-9_-]{6,}$/.test(key) || !name) return false;

  const excludedTitlePattern =
    /\b(featurette|behind[ -]?the[ -]?scenes|bts|interview|deleted scene|tv spot|teaser|making of|sneak peek|clip|promo|promotional)\b/i;
  return !excludedTitlePattern.test(name);
}

function sortTrailersForDisplay(videos, language) {
  const preferred = String(language || "en-US").split("-")[0].toLowerCase();

  return videos
    .filter(isEligibleTrailer)
    .map((video, index) => {
      const name = String(video.name || "").toLowerCase();
      let score = 0;
      if (video.official === true) score += 1000;
      if (String(video.iso_639_1 || "").toLowerCase() === preferred) score += 400;
      if (String(video.iso_639_1 || "").toLowerCase() === "en") score += 100;
      if (/\bofficial\s+trailer\b/i.test(name)) score += 300;
      else if (/\btrailer\b/i.test(name)) score += 100;
      if (/\b(trailer\s*#?\s*[2-9]|second trailer|final trailer|international trailer|red band|dubbed trailer)\b/i.test(name)) {
        score -= 250;
      }
      const parsedDate = Date.parse(video.published_at || "");
      return {
        video,
        index,
        score,
        publishedAt: Number.isFinite(parsedDate) ? parsedDate : 0
      };
    })
    .sort((a, b) =>
      b.score - a.score || b.publishedAt - a.publishedAt || a.index - b.index
    )
    .map(entry => entry.video);
}
```

In `tmdbMediaSubresource`, validate the mode before requesting videos:

```javascript
const params = { language };
let videoMode = null;

if (resource === "videos") {
  videoMode = (url.searchParams.get("mode") || "primary").trim().toLowerCase();
  if (!["primary", "all"].includes(videoMode)) {
    return errorResponse(
      request, env, "INVALID_VIDEO_MODE",
      "mode must be primary or all.", 400
    );
  }
  params.include_video_language = getVideoLanguageFallback(language);
}
```

Then replace the direct response of `tmdbGet` with:

```javascript
const result = await tmdbGet(env, `/${type}/${id}/${resource}`, params);
let data = result;

if (resource === "videos") {
  const sorted = sortTrailersForDisplay(
    Array.isArray(result?.results) ? result.results : [],
    language
  );
  data = {
    ...result,
    results: videoMode === "primary" ? sorted.slice(0, 1) : sorted,
    total_results: videoMode === "primary" ? Math.min(sorted.length, 1) : sorted.length
  };
}

return jsonResponse(request, env, {
  ok: true,
  service: "tmdb",
  media_type: type,
  resource,
  language,
  ...(resource === "videos" ? { mode: videoMode } : {}),
  data
});
```

## 4. Contract required by Android

- `GET /api/v1/tmdb/movie/{id}?language=en-US&append_to_response=credits,translations`
- `GET /api/v1/tmdb/tv/{id}?language=ar-SA&append_to_response=credits,translations`
- `GET /api/v1/tmdb/movie/{id}/videos?language=en-US&mode=primary`
- `GET /api/v1/tmdb/movie/{id}/videos?language=en-US&mode=all`
- Equivalent TV routes for details and videos.

The video response must use the envelope `{ ok, service, media_type, resource: "videos", language, mode, data: { results: [...] } }`. Android calls `mode=primary` for the initial detail screen and `mode=all` only after the user expands the other-trailers section. The app also applies a client-side video type/title filter in case the deployed Worker has not yet been updated.

The Worker must keep `movie/popular`, `tv/popular`, `movie/upcoming`, and `discover/{movie|tv}` routes. Date, genre and original-language filters must be validated and forwarded; an empty category result must not fall back to unrelated Trending results. All requests remain protected by the existing Firebase Bearer-token authentication. Do not use wildcard CORS.

## 5. Live verification still required

With a valid Firebase bearer token, verify `/movie/popular`, `/movie/upcoming`, `/discover/tv?with_genres=16&with_original_language=ja`, the movie/TV detail routes, and both video modes against the deployed Worker. A root `/api/v1` response reporting `ready` only confirms that the Worker route table responds; it does not confirm upstream data success. This project archive does not imply that a Worker deployment occurred.
