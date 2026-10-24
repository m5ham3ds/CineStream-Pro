# PHASE 23.0 — CINESTREAM USER APP
# COMPLETE FIREBASE CONFIGURATION, AUTHENTICATION & CLOUDFLARE WORKER INTEGRATION AUDIT REPORT

**Audit Date:** October 23, 2026  
**Auditor:** Senior Software Engineer (AI Studio)  
**Target Platform:** Android (CineStream User App)  
**Audit Mode:** STRICT READ-ONLY AUDIT (Zero code modifications, zero deployments)  
**Project ID:** `cinestream-sulo` | **Project Number:** `979447256418`  
**Application ID:** `com.aistudio.cinestream.xyzabc`  
**Cloudflare Gateway Base URL:** `https://cinestream-api-gateway.blackmyemail1.workers.dev`  
**Primary Verification Endpoint:** `GET /api/v1/auth/verify`  

---

## TABLE OF CONTENTS
1. [SECTION A: Executive Summary & Strict Session Boundary](#section-a-executive-summary--strict-session-boundary)
2. [SECTION B: Configuration Inventory Table](#section-b-configuration-inventory-table)
3. [SECTION C: Complete Authentication Flow Map](#section-c-complete-authentication-flow-map)
4. [SECTION D: Guest Mode Analysis](#section-d-guest-mode-analysis)
5. [SECTION E: Firestore & Client Data Access Matrix](#section-e-firestore--client-data-access-matrix)
6. [SECTION F: Cloudflare Worker Integration Map & Probing Results](#section-f-cloudflare-worker-integration-map--probing-results)
7. [SECTION G: API Contract & Payload Schema](#section-g-api-contract--payload-schema)
8. [SECTION H: TMDB & Cloudinary Direct vs Gateway Plans](#section-h-tmdb--cloudinary-direct-vs-gateway-plans)
9. [SECTION I: Secret Safety & Exfiltration Risk Audit](#section-i-secret-safety--exfiltration-risk-audit)
10. [SECTION J: Verified Test Evidence & Regression Baselines](#section-j-verified-test-evidence--regression-baselines)
11. [SECTION K: Ranked Vulnerabilities, Architectural Flaws & Missing Implementations](#section-k-ranked-vulnerabilities-architectural-flaws--missing-implementations)
12. [SECTION L: Actionable Implementation Plan (Phase 23.1+)](#section-l-actionable-implementation-plan-phase-231)
13. [SECTION M: Cross-App Handoff Contract for Admin Dashboard](#section-m-cross-app-handoff-contract-for-admin-dashboard)

---

## SECTION A: Executive Summary & Strict Session Boundary

### 1.1 Strict Session Boundary Statement
- **Active Workspace:** This audit was conducted **exclusively** within the CineStream User App codebase available in this Google AI Studio session.
- **Admin App Boundary:** The CineStream Admin Dashboard is maintained in an independent Google AI Studio session. **No Admin source files were inspected, modified, or executed during this phase.** Cross-app claims in this report are strictly derived from retained cross-app contracts (`config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json`, `app/google-services.json`, `firestore.rules`, and gateway endpoint specs).

### 1.2 Preservation of Verified Runtime Behavior [CONFIRMED]
The CineStream User App has established and verified two distinct authentication session types against the live Cloudflare Worker:
1. **Registered User Session:** Authenticated via Google Sign-In or Email/Password, producing a non-anonymous Firebase User with a valid Firebase ID Token. Verifies with HTTP 200 `AUTHENTICATION_VERIFIED`, `UID MATCH`, and `cinestream-sulo` project ID.
2. **Anonymous Guest Session:** Authenticated via Firebase Anonymous Authentication (`FirebaseAuth.signInAnonymously()`), producing an authentic Firebase User session (`isAnonymous = true`) with a cryptographically valid ID Token issued by `securetoken.google.com/cinestream-sulo`. Verifies with HTTP 200 `AUTHENTICATION_VERIFIED`, `UID MATCH`, and `cinestream-sulo` project ID.

Both session types are fully supported production behaviors. Guest mode is an intentional feature, not a degraded fallback or failure state.

### 1.3 Confirmed Project Identity

| Parameter | Confirmed Value | Verification Method | Status |
|---|---|---|---|
| **Firebase Project ID** | `cinestream-sulo` | `app/google-services.json`, `.firebaserc`, `AuthGatewayDiagnostic.kt` | **[CONFIRMED]** |
| **Firebase Project Number** | `979447256418` | `app/google-services.json`, `config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json` | **[CONFIRMED]** |
| **Storage Bucket** | `cinestream-sulo.firebasestorage.app` | `app/google-services.json:6` | **[CONFIRMED]** |
| **RTDB URL** | `https://cinestream-sulo-default-rtdb.firebaseio.com` | `app/google-services.json:4` | **[CONFIRMED]** |
| **User App Application ID** | `com.aistudio.cinestream.xyzabc` | `app/build.gradle.kts:21`, `app/google-services.json:13` | **[CONFIRMED]** |
| **Android Namespace** | `com.example` | `app/build.gradle.kts:16` | **[CONFIRMED]** |
| **User Mobile SDK App ID** | `1:979447256418:android:037a9b3a1393e550a9883b` | `app/google-services.json:11` | **[CONFIRMED]** |
| **Web Server OAuth Client ID** | `979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com` | `app/google-services.json:26`, `app/build.gradle.kts:110` | **[CONFIRMED]** |
| **User Android OAuth Client ID** | `979447256418-9jl4cl94f22a227strvhj3ups98dpka0.apps.googleusercontent.com` | `app/google-services.json:18` | **[CONFIRMED]** |
| **Official Android Certificate SHA-1** | `1e0f4a4a0ee84b7023e59504b3a978382d019e2a` | `app/google-services.json:22` | **[CONFIRMED]** |
| **Official Android Certificate SHA-256** | `c3aed0b9552cbb730057309495a379e1e8cc73eefabc4deacc39616fc12d27d3` | `config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json:22` | **[CONFIRMED]** |
| **Cloudflare Worker Service** | `cinestream-api-gateway` | HTTP live probe `GET https://cinestream-api-gateway.blackmyemail1.workers.dev/` | **[CONFIRMED]** |
| **Live Deployed Worker URL** | `https://cinestream-api-gateway.blackmyemail1.workers.dev` | `app/src/main/java/com/example/data/diagnostic/AuthGatewayDiagnostic.kt:36` | **[CONFIRMED]** |

---

## SECTION B: Configuration Inventory Table

| Configuration File / Variable | Resolved / Audited Value (Masked) | Build Variant / Environment | Purpose & Consumer | Status |
|---|---|---|---|---|
| `app/google-services.json` | Project: `cinestream-sulo`<br>User App: `com.aistudio.cinestream.xyzabc`<br>API Key: `AIzaSyC9...nPs` | Debug & Release (`com.google.gms.google-services`) | Generates standard Android Firebase string resources (`google_app_id`, `google_api_key`, `project_id`) consumed by Firebase SDK. | **[CONFIRMED]** |
| `config/firebase/CINESTREAM_FIREBASE_IDENTITY_LOCK.json` | Lock file documenting user & admin package IDs, SHA-1, SHA-256, and OAuth client IDs | All Environments | CI validation lock preventing accidental package or cert drift. | **[CONFIRMED]** |
| `app/build.gradle.kts` | `applicationId = "com.aistudio.cinestream.xyzabc"`<br>`compileSdk = 36`, `targetSdk = 36`, `minSdk = 24` | Android Application Config | Configures Android build, dynamic Web OAuth ID resolution, Secrets plugin, and packaging rules. | **[CONFIRMED]** |
| `ValidateWebClientIdTask` (Gradle) | Asserts `WEB_CLIENT_ID` in `.env` matches canonical Type 3 client from `app/google-services.json` | Build-time preBuild check | Blocks build if `WEB_CLIENT_ID` is empty or generic placeholder. | **[CONFIRMED]** |
| `.env` -> `WEB_CLIENT_ID` | `979447256418-rjb9...apps.googleusercontent.com` | Injected into `BuildConfig.WEB_CLIENT_ID` | Used by AndroidX Credential Manager (`GetSignInWithGoogleOption`) for Google Sign-In. | **[CONFIRMED]** |
| `.env` -> `TMDB_API_KEY` | Placeholder: `YOUR_TMDB_API_KEY` (or runtime secret) | Injected into `BuildConfig.TMDB_API_KEY` | Passed as query parameter `api_key` by `TmdbMediaRepositoryImpl` to `RetrofitClient`. | **[CONFIRMED]** |
| `.env` -> `CLOUDINARY_CLOUD_NAME` | `cinestream` | Injected into `BuildConfig.CLOUDINARY_CLOUD_NAME` | Used by `MyApplication.onCreate()` to initialize `com.cloudinary.android.MediaManager`. | **[CONFIRMED]** |
| `.env` -> `CLOUDINARY_UPLOAD_PRESET` | `cinestream_preset` | Injected into `BuildConfig.CLOUDINARY_UPLOAD_PRESET` | Used by `AuthRepository.uploadProfilePicture()` for unsigned avatar uploads. | **[CONFIRMED]** |
| `debug.keystore` (Local File) | SHA-1: `fe3d8638e42a481fb5b77abe344666dfd423a0e8` | Local Debug keystore | **DISCREPANCY:** Differs from official registered SHA-1 (`1e0f4a4a...`). | **[CONFIRMED DRIFT]** |
| `debug.keystore` (CI Workflow) | Embedded base64 in `.github/workflows/build.yml:42` (SHA-1: `1e0f4a4a...`) | CI Debug Keystore | Exact registered keystore used in production CI. | **[CONFIRMED]** |
| `AuthGatewayDiagnostic.kt` | URL: `https://cinestream-api-gateway.blackmyemail1.workers.dev`<br>Path: `/api/v1/auth/verify`<br>Project: `cinestream-sulo` | Debug UI (`SettingsScreen`) | Executes runtime diagnostic request to Cloudflare Worker to verify token validity. | **[CONFIRMED]** |
| `FirebaseInitializer.kt` | Fallback options: App ID `1:9794...`, Key `AIza...`, Project `cinestream-sulo` | Runtime initialization fallback | Defensively initializes `FirebaseApp` if Google Services auto-init was skipped. | **[CONFIRMED]** |
| `backend/wrangler.toml` | `name = "cinestream-trusted-backend"`<br>`ENVIRONMENT = "disabled"`<br>`BACKEND_ENABLED = "false"` | Local Worker workspace | **NOT DEPLOYED.** Local draft of economic backend. Gated closed. | **[CONFIRMED]** |

---

## SECTION C: Complete Authentication Flow Map

### 3.1 Google Sign-In Flow
1. **Trigger:** User taps "Sign in with Google" in `AuthScreen.kt`.
2. **Credential Manager Invocation:**
   - Validates `BuildConfig.WEB_CLIENT_ID` via `GoogleCredentialParser.validateWebClientId()`.
   - Generates 32-byte cryptographic nonce via `SecureRandom` + SHA-256 (`generateSecureNonce()`).
   - Builds `GetSignInWithGoogleOption` with server client ID.
   - Dispatches `GetCredentialRequest` via AndroidX `CredentialManager.create(activity)`.
3. **Credential Parsing:**
   - `GoogleCredentialParser.parseCredential(result.credential)` strictly matches `CustomCredential` with type `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`.
   - Extracts Google ID token string, user email, display name, and avatar URI.
4. **Firebase Authentication:**
   - Creates `GoogleAuthProvider.getCredential(idToken, null)`.
   - Invokes `FirebaseAuth.signInWithCredential(credential).await()`.
5. **Profile Synchronization:**
   - Fetches or creates user document in `/users/{uid}` via `AuthRepository.getCurrentUser()`.
   - Emits authenticated state into `AuthRepository.currentUserFlow`.
   - Updates `SessionManager.onUserLoggedIn(context, uid)` and initiates `CloudSyncManager.syncFromCloud(uid)`.
   - Persists preferences: `userPrefs.saveIsGuest(false)` and `userPrefs.saveIsLoggedIn(true)`.

### 3.2 Email / Password Sign-In & Sign-Up
1. **Sign-In:**
   - Validates network connectivity via `NetworkUtils.isInternetAvailable(context)`.
   - Invokes `FirebaseAuth.signInWithEmailAndPassword(email, pass).await()`.
   - Updates `SessionManager`, `currentUserFlow`, and CloudSync.
2. **Sign-Up:**
   - Validates network connectivity.
   - Invokes `FirebaseAuth.createUserWithEmailAndPassword(email, pass).await()`.
   - Generates unique username via `AuthRepository.generateUniqueUsername()` checking `/public_profiles`.
   - Initializes user document in `/users/{uid}` and projection in `/public_profiles/{uid}` with safe defaults (`role: "user"`, `subscriptionTier: "FREE"`, `pointsBalance: 0`).
   - Rollback handling: If username generation or initial profile save fails, best-effort `firebaseUser.delete()` is invoked to prevent orphan accounts.

### 3.3 Anonymous Guest Authentication Flow
1. **Trigger:** User explicitly clicks "Continue as Guest" (`continue_as_guest_button`) in `AuthScreen.kt`.
2. **Loading & Concurrent Request Lock:**
   - `isGuestLoading` set to `true`.
   - Button displays `CircularProgressIndicator`; repeated taps are rejected.
3. **Session Reuse Guard:**
   - `AuthRepository.signInAnonymously()` first inspects `auth.currentUser`.
   - If an active Firebase user exists, it reuses the session without creating redundant anonymous accounts.
4. **Firebase Anonymous Sign-In:**
   - If no user exists, invokes `auth.signInAnonymously().await()`.
   - Validates `AuthResult.user != null`.
5. **State & Preferences Propagation:**
   - Emits `User` instance to `currentUserFlow`.
   - Sets `userPrefs.saveIsGuest(true)` and `userPrefs.saveIsLoggedIn(false)`.
   - Navigates via `onSkip()`.
6. **Error Handling:**
   - If offline and no session exists, fails fast with localized network error.
   - Displays clear error toast without altering preference state.

### 3.4 Cold Start & Session Restoration Flow
1. **Startup:** `SplashScreen.kt` triggers `AppStartupManager.checkAppConfig()`.
2. **Preference Evaluation:**
   - Reads `onboardingCompleted`, `isGuest`, `isLoggedIn`, and `startScreen`.
3. **Guest Session Restoration:**
   - If `isGuest == true`:
     - Checks if `FirebaseAuth.currentUser == null`.
     - If null, silently calls `AuthRepository.signInAnonymously()` to re-establish an authenticated Firebase session.
     - Navigates directly to `startScreen` without forcing the user through onboarding or auth.
4. **Registered User Session Restoration:**
   - If `isLoggedIn == true`: navigates directly to `startScreen`.
   - `AuthRepository.init` listener detects existing Firebase session and initiates `getCurrentUser()`.
5. **Unauthenticated:**
   - If neither, navigates to `AuthScreen`.

### 3.5 Account Linking (Guest -> Registered)
1. Supported via `AuthRepository.linkGoogleAccount(credential)` and `linkCurrentUserWithGoogle(idToken)`.
2. Invokes `firebaseUser.linkWithCredential(credential).await()`.
3. Transforms the anonymous user into a permanent registered user, **preserving the same UID**, watch history, bookmarks, and local data without migration loss.

### 3.6 Sign-Out & Account Deletion
1. **Sign-Out:**
   - Requires active internet connection to prevent inconsistent offline state.
   - `SessionManager.onUserLoggedOut(context)` clears Room database tables, in-memory caches, and executes `userPrefs.clearUserScopedPreferences()` (wiping `isGuest`, `isLoggedIn`, `guestMigrationUid`).
   - Invokes `FirebaseAuth.signOut()`.
2. **Account Deletion:**
   - Deletes user subcollections (`fcmTokens`, `library`, `history`, `watched_episodes`, `settings/notifications`, `stories`, `public_profiles`).
   - Deletes `/users/{uid}` (or anonymizes if rule forbids deletion).
   - Invokes `firebaseUser.delete().await()`. If recent reauthentication is required, returns `AccountDeletionResult.ReauthRequired`.

---

## SECTION D: Guest Mode Analysis

### 4.1 Cryptographic & SDK Reality of Firebase Anonymous Auth
- **Is an Anonymous User a Real Firebase User?** **YES [CONFIRMED]**. `FirebaseAuth.signInAnonymously()` provisions a genuine account in the Firebase Authentication user pool with a unique UID.
- **Token Validity:** The anonymous user receives standard RS256 JWT ID Tokens from Google (`https://securetoken.google.com/cinestream-sulo`). The payload contains:
  - `iss`: `https://securetoken.google.com/cinestream-sulo`
  - `aud`: `cinestream-sulo`
  - `sub`: `<ANONYMOUS_UID>`
  - `firebase.sign_in_provider`: `anonymous`
  - Valid `exp` (1 hour) and `iat` timestamps.
- **Worker Verification:** When the User App sends `Authorization: Bearer <ID_TOKEN>` to Cloudflare Worker `GET /api/v1/auth/verify`, the Worker retrieves Google's public JWKS certificates and validates the token signature, audience, and expiration. **The Worker verifies anonymous tokens with 100% cryptographic parity to registered tokens.**

### 4.2 Guest Mode Lifecycle & Persistence Matrix

| Lifecycle Event | Behavior in CineStream User App | Code Reference |
|---|---|---|
| **First Entry** | User taps "Continue as Guest" -> `signInAnonymously()` creates user -> `is_guest=true` persisted. | `AuthScreen.kt:480` |
| **App Background / Process Death** | Firebase SDK persists anonymous credentials in local encrypted SharedPreferences. `auth.currentUser` persists across process kills. | Firebase Auth SDK Internal |
| **App Cold Restart** | `SplashScreen` reads `is_guest=true`. If `auth.currentUser` is null, automatically invokes `signInAnonymously()` to guarantee session before navigating. | `SplashScreen.kt:85-91` |
| **Recomposition / Navigation** | Does NOT create new accounts. Reuses active `currentUser`. | `AuthRepository.kt:540` |
| **Diagnostic Run** | Diagnostic reads existing `auth.currentUser` without modifying session. Never mints accounts. | `AuthGatewayDiagnostic.kt:85` |
| **User Sign-Out** | Clears `is_guest=false`, `is_logged_in=false`, wipes local Room DB, signs out of Firebase. | `UserPreferencesRepository.kt:156` |
| **Upgrade to Registered** | Calls `firebaseUser.linkWithCredential()`. Preserves existing UID and all history/library data. | `AuthRepository.kt:509` |

---

## SECTION E: Firestore & Client Data Access Matrix

The following matrix documents all 27 Firestore document paths accessed by the User App and evaluated under `firestore.rules`:

| # | Firestore Path Pattern | Read (Guest) | Write (Guest) | Read (Registered) | Write (Registered) | Read / Write (Admin) | Invariants & Field Constraints |
|---|---|---|---|---|---|---|---|
| 1 | `/admins/{adminUid}` | Denied | Denied | Denied (unless self-read) | Denied | Read: All Admins<br>Write: Owner only | Owner bootstrap: `sulopros01@gmail.com`. Admins cannot promote others. |
| 2 | `/public_profiles/{userId}` | Allowed if `isProfilePublic==true` | Create/Update if `uid==userId` (Allowlist only) | Allowed if `isProfilePublic==true` | Create/Update if `uid==userId` (Allowlist only) | Full Read/Write | Strictly 9 allowlisted fields: `uid`, `username`, `firstName`, `lastName`, `photoUrl`, `isOnline`, `isProfilePublic`, `bio`, `updatedAt`. |
| 3 | `/users/{userId}` | Allowed if `uid==userId` | Update non-sensitive fields only | Allowed if `uid==userId` | Update non-sensitive fields only | Full Read/Write | Modifying `role`, `subscriptionTier`, `pointsBalance`, `canWatch`, `isBanned`, etc. strictly blocked by `modifyingSensitiveUserFields()`. |
| 4 | `/users/{userId}/point_transactions/{txId}` | Allowed if `uid==userId` | **DENIED** | Allowed if `uid==userId` | **DENIED** | Read: Allowed<br>Create: Admin only<br>Update/Delete: Denied | Append-only ledger. Clients have ZERO write authority. |
| 5 | `/users/{userId}/task_claims/{taskId}` | Allowed if `uid==userId` | **DENIED** | Allowed if `uid==userId` | **DENIED** | Read: Allowed<br>Create: Admin only<br>Update/Delete: Denied | Append-only. Clients have ZERO write authority. |
| 6 | `/users/{userId}/library/{itemId}` | Allowed if `uid==userId` | Allowed if `uid==userId` | Allowed if `uid==userId` | Allowed if `uid==userId` | Full Read/Write | Personal bookmark collection. |
| 7 | `/users/{userId}/history/{itemId}` | Allowed if `uid==userId` | Allowed if `uid==userId` | Allowed if `uid==userId` | Allowed if `uid==userId` | Full Read/Write | Personal watch progress. |
| 8 | `/users/{userId}/watched_episodes/{episodeId}` | Allowed if `uid==userId` | Allowed if `uid==userId` | Allowed if `uid==userId` | Allowed if `uid==userId` | Full Read/Write | Episode completion flags. |
| 9 | `/users/{userId}/settings/notifications` | Allowed if `uid==userId` | Allowed if `uid==userId` (Restricted fields) | Allowed if `uid==userId` | Allowed if `uid==userId` (Restricted fields) | Full Read/Write | Validates specific notification toggle keys. Cannot modify user roles. |
| 10 | `/users/{userId}/fcmTokens/{installationId}` | Allowed if `uid==userId` | Allowed if `uid==userId` (Strict Android schema) | Allowed if `uid==userId` | Allowed if `uid==userId` (Strict Android schema) | Full Read/Write | Must contain `platform == "android"`, `createdAt == request.time`. Sensitive keys forbidden. |
| 11 | `/config/app` | Allowed (Authenticated) | **DENIED** | Allowed (Authenticated) | **DENIED** | Full Read/Write | Maintenance and update configuration. |
| 12 | `/config/search_order` | Allowed (Authenticated) | **DENIED** | Allowed (Authenticated) | **DENIED** | Full Read/Write | Extension execution priority catalog. |
| 13 | `/config/features` | Allowed (Authenticated) | **DENIED** | Allowed (Authenticated) | **DENIED** | Full Read/Write | Global feature flags (`ACTIVE`, `COMING_SOON`, `DISABLED`). |
| 14 | `/config/economy` | Allowed (Authenticated) | **DENIED** | Allowed (Authenticated) | **DENIED** | Full Read/Write | Authoritative point reward ladders and redemption costs. |
| 15 | `/managed_extensions/{extensionId}` | Allowed (Authenticated) | **DENIED** | Allowed (Authenticated) | **DENIED** | Full Read/Write | Extension manifest and scraper metadata. |
| 16 | `/app_updates/{updateId}` | Allowed (Authenticated) | **DENIED** | Allowed (Authenticated) | **DENIED** | Full Read/Write | APK update release metadata. |
| 17 | `/notifications/{notificationId}` | Allowed if `target=='all'` | **DENIED** | Allowed if `target in ['all','user','pro']` | **DENIED** | Full Read/Write | User-targeted notifications. Pro notifications require verified subscription. |
| 18 | `/reward_tasks/{taskId}` | Allowed (Authenticated) | **DENIED** | Allowed (Authenticated) | **DENIED** | Full Read/Write | Active tasks list. Read-only for clients. |
| 19 | `/leaderboard/weekly_current` | Allowed (Authenticated) | **DENIED** | Allowed (Authenticated) | **DENIED** | Full Read/Write | Aggregated leaderboard snapshot. |
| 20 | `/pro_requests/{requestId}` | Allowed if `userId==uid` | Create pending / Cancel only | Allowed if `userId==uid` | Create pending / Cancel only | Full Read/Write | Manual subscription requests. Cannot self-approve. |
| 21 | `/reports/{reportId}` | Allowed if `userId==uid` | Create pending only | Allowed if `userId==uid` | Create pending only | Full Read/Write | Content violation reports. |
| 22 | `/support_conversations/{conversationId}` | Allowed if `userId==uid` | Create/Update if `userId==uid` | Allowed if `userId==uid` | Create/Update if `userId==uid` | Full Read/Write | 1-on-1 support thread with administration. |
| 23 | `/support_conversations/{id}/messages/{msgId}` | Allowed if participant | Create if sender | Allowed if participant | Create if sender | Full Read/Write | Support messages. |
| 24 | `/conversations/{conversationId}` | Allowed if participant | Create/Update if participant | Allowed if participant | Create/Update if participant | Full Read/Write | P2P direct chat. Blocked if `canChat == false` or `chatBan == true`. |
| 25 | `/conversations/{id}/messages/{msgId}` | Allowed if participant | Create/Update/Delete (sender) | Allowed if participant | Create/Update/Delete (sender) | Full Read/Write | Chat messages. Participant can edit reactions/deletedFor. |
| 26 | `/conversations/{id}/readReceipts/{uid}` | Allowed if participant | Set if `uid == request.auth.uid` | Allowed if participant | Set if `uid == request.auth.uid` | Full Read/Write | Read receipt timestamp tracking. |
| 27 | `/stories/{storyId}` | Allowed (Authenticated) | Create/Delete if `userId==uid` | Allowed (Authenticated) | Create/Delete if `userId==uid` | Full Read/Write | 24-hour social stories. Blocked if `canStory == false` or banned. |
| 28 | `/auditLogs/{logId}` | **DENIED** | **DENIED** | **DENIED** | **DENIED** | Full Read/Create<br>Update/Delete: Denied | Append-only audit logging. Immutable. |
| 29 | `/comments/{document=**}` | **EXPLICIT DENY** | **EXPLICIT DENY** | **EXPLICIT DENY** | **EXPLICIT DENY** | **EXPLICIT DENY** | Comments feature is permanently CANCELLED. |
| 30 | `/{document=**}` (Catch-All) | **DENIED** | **DENIED** | **DENIED** | **DENIED** | **DENIED** | Final default-deny security boundary. |

---

## SECTION F: Cloudflare Worker Integration Map & Probing Results

### 6.1 Real-Time Live Endpoint Probing Evidence
The Cloudflare Worker at `https://cinestream-api-gateway.blackmyemail1.workers.dev` was probed directly via HTTP during this audit. The live responses were captured as follows:

#### Probe 1: Root Gateway Index (`GET /`)
- **Status:** `HTTP/2 200 OK`
- **Response Headers:** `content-type: application/json; charset=utf-8`, `server: cloudflare`
- **Response Body:**
```json
{
  "ok": true,
  "service": "cinestream-api-gateway",
  "apiVersion": "v1",
  "status": "ready",
  "routes": [
    {"method": "GET", "path": "/health"},
    {"method": "GET", "path": "/api/v1"},
    {"method": "GET", "path": "/api/v1/auth/verify"},
    {"method": "GET", "path": "/api/v1/admin/verify"},
    {"method": "GET", "path": "/api/v1/tmdb/search"},
    {"method": "GET", "path": "/api/v1/tmdb/trending"},
    {"method": "GET", "path": "/api/v1/tmdb/movie/:id"},
    {"method": "GET", "path": "/api/v1/tmdb/tv/:id"},
    {"method": "GET", "path": "/api/v1/tmdb/tv/:id/season/:seasonNumber"},
    {"method": "GET", "path": "/api/v1/admin/tmdb/detect"},
    {"method": "GET", "path": "/api/v1/admin/integrations/status"},
    {"method": "POST", "path": "/api/v1/admin/media/upload"}
  ]
}
```

#### Probe 2: Health Check (`GET /health`)
- **Status:** `HTTP/2 200 OK`
- **Response Body:**
```json
{
  "ok": true,
  "service": "cinestream-api-gateway",
  "status": "healthy",
  "apiVersion": "v1",
  "timestamp": "2026-10-23T02:30:06.974Z"
}
```

#### Probe 3: Authentication Verification Without Header (`GET /api/v1/auth/verify`)
- **Status:** `HTTP/2 401 Unauthorized`
- **Response Body:**
```json
{
  "ok": false,
  "error": {
    "code": "AUTH_REQUIRED",
    "message": "A Firebase ID token is required."
  }
}
```

#### Probe 4: Authentication Verification With Bogus Bearer Token (`GET /api/v1/auth/verify`)
- **Request Header:** `Authorization: Bearer invalid_test_token`
- **Status:** `HTTP/2 401 Unauthorized`
- **Response Body:**
```json
{
  "ok": false,
  "error": {
    "code": "INVALID_ID_TOKEN",
    "message": "The Firebase ID token is invalid or expired."
  }
}
```

#### Probe 5: TMDB Trending Proxy (`GET /api/v1/tmdb/trending`)
- **Status:** `HTTP/2 401 Unauthorized`
- **Response Body:**
```json
{
  "ok": false,
  "error": {
    "code": "AUTH_REQUIRED",
    "message": "A Firebase ID token is required."
  }
}
```
*(Confirms that TMDB proxy endpoints on the Worker are operational and gated behind Firebase ID Token authentication).*

#### Probe 6: Economy Endpoints (`GET /api/economy` and `GET /api/health`)
- **Status:** `HTTP/2 404 Not Found`
```json
{
  "ok": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "Route not found."
  }
}
```
*(Confirms that the deployed worker is the **API Gateway**, and that the economic backend in `backend/` has NOT been deployed to Cloudflare yet).*

### 6.2 Deployed vs Undeployed Worker Routes Matrix

| Endpoint Path | Method | Auth Required | Target Service | Deployment Status | User App Consumption |
|---|---|---|---|---|---|
| `/health` | `GET` | None | API Gateway | **DEPLOYED (LIVE)** | Not currently polled by client |
| `/api/v1` | `GET` | None | API Gateway | **DEPLOYED (LIVE)** | Available route directory |
| `/api/v1/auth/verify` | `GET` | Firebase Bearer | API Gateway | **DEPLOYED (LIVE)** | **Consumed by `AuthGatewayDiagnostic`** |
| `/api/v1/admin/verify` | `GET` | Firebase Admin Bearer | API Gateway | **DEPLOYED (LIVE)** | Reserved for Admin App |
| `/api/v1/tmdb/search` | `GET` | Firebase Bearer | API Gateway | **DEPLOYED (LIVE)** | Ready for User App migration |
| `/api/v1/tmdb/trending` | `GET` | Firebase Bearer | API Gateway | **DEPLOYED (LIVE)** | Ready for User App migration |
| `/api/v1/tmdb/movie/:id` | `GET` | Firebase Bearer | API Gateway | **DEPLOYED (LIVE)** | Ready for User App migration |
| `/api/v1/tmdb/tv/:id` | `GET` | Firebase Bearer | API Gateway | **DEPLOYED (LIVE)** | Ready for User App migration |
| `/api/v1/tmdb/tv/:id/season/:seasonNumber` | `GET` | Firebase Bearer | API Gateway | **DEPLOYED (LIVE)** | Ready for User App migration |
| `/api/v1/admin/tmdb/detect` | `GET` | Firebase Admin Bearer | API Gateway | **DEPLOYED (LIVE)** | Admin App only |
| `/api/v1/admin/integrations/status` | `GET` | Firebase Admin Bearer | API Gateway | **DEPLOYED (LIVE)** | Admin App only |
| `/api/v1/admin/media/upload` | `POST` | Firebase Admin Bearer | API Gateway | **DEPLOYED (LIVE)** | Admin App only |
| `/api/earn/daily_login` | `POST` | Firebase Bearer | Economic Backend | **NOT DEPLOYED** (Local `backend/`) | Blocked pending explicit backend deployment |
| `/api/earn/rewarded_ad` | `POST` | Firebase Bearer | Economic Backend | **NOT DEPLOYED** (Local `backend/`) | Blocked pending explicit backend deployment |
| `/api/earn/task_reward` | `POST` | Firebase Bearer | Economic Backend | **NOT DEPLOYED** (Local `backend/`) | Blocked pending explicit backend deployment |
| `/api/subscription/redeem` | `POST` | Firebase Bearer | Economic Backend | **NOT DEPLOYED** (Local `backend/`) | Blocked pending explicit backend deployment |
| `/api/economy` | `GET` | None | Economic Backend | **NOT DEPLOYED** (Local `backend/`) | Blocked pending explicit backend deployment |

---

## SECTION G: API Contract & Payload Schema

### 7.1 Primary Verified Endpoint: `GET /api/v1/auth/verify`

#### Request Specification
- **URL:** `https://cinestream-api-gateway.blackmyemail1.workers.dev/api/v1/auth/verify`
- **Method:** `GET`
- **Headers:**
  - `Authorization: Bearer <FIREBASE_ID_TOKEN>` (**Mandatory**)
  - `Accept: application/json`

#### Response Specification (200 OK — Verified)
```json
{
  "ok": true,
  "authenticated": true,
  "uid": "14Pj6f54y4aF9xK89...",
  "projectId": "cinestream-sulo"
}
```

#### Client Verification Assertions (`AuthGatewayDiagnostic.kt:180-264`)
The User App strictly validates all five (5) conditions before reporting success:
1. `statusCode == 200`
2. `response.ok == true`
3. `response.authenticated == true`
4. `response.projectId == "cinestream-sulo"`
5. `response.uid == localFirebaseAuth.currentUser.uid`

#### Error Responses
- **HTTP 401 Unauthorized (Missing Token):**
  ```json
  {"ok": false, "error": {"code": "AUTH_REQUIRED", "message": "A Firebase ID token is required."}}
  ```
- **HTTP 401 Unauthorized (Invalid / Expired Token):**
  ```json
  {"ok": false, "error": {"code": "INVALID_ID_TOKEN", "message": "The Firebase ID token is invalid or expired."}}
  ```
- **HTTP 503 Service Unavailable (JWKS Failure):**
  ```json
  {"ok": false, "error": {"code": "PUBLIC_KEY_RETRIEVAL_FAILED", "message": "Unable to fetch Google public keys."}}
  ```

---

## SECTION H: TMDB & Cloudinary Direct vs Gateway Plans

### 8.1 TMDB Direct vs Gateway Analysis
- **Current State:** The User App executes direct HTTPS requests to `https://api.themoviedb.org/3/` from `RetrofitClient.kt`. Every request attaches the query parameter `api_key = BuildConfig.TMDB_API_KEY`.
- **Architectural Risk:** The TMDB API key is bundled into the client APK. Any reverse-engineering or memory dump can extract it.
- **Gateway Readiness:** The deployed Cloudflare Worker gateway **already provides full proxy implementations** for:
  - `/api/v1/tmdb/trending`
  - `/api/v1/tmdb/search`
  - `/api/v1/tmdb/movie/:id`
  - `/api/v1/tmdb/tv/:id`
  - `/api/v1/tmdb/tv/:id/season/:seasonNumber`
- **Migration Plan (Phase 23.1+):**
  1. Add an OkHttp authenticator/interceptor to `RetrofitClient` to attach the user's current Firebase ID token (`Authorization: Bearer <TOKEN>`).
  2. Switch `RetrofitClient.BASE_URL` to `https://cinestream-api-gateway.blackmyemail1.workers.dev/api/v1/tmdb/`.
  3. Strip `api_key` parameters from `TmdbApiService.kt` methods.
  4. The Worker gateway attaches the server-side `TMDB_API_KEY` stored exclusively in Cloudflare Worker environment variables.
  5. Remove `TMDB_API_KEY` from the User App's `.env`, `.env.example`, and `BuildConfig`.

### 8.2 Cloudinary Direct vs Gateway Analysis
- **Current State:**
  - The User App uses Cloudinary Android SDK (`com.cloudinary.android.MediaManager`) for uploading user avatars in `AuthRepository.uploadProfilePicture()` and payment slips in `PointsRepository.kt`.
  - Configured in `MyApplication.onCreate()` via `BuildConfig.CLOUDINARY_CLOUD_NAME` and `BuildConfig.CLOUDINARY_UPLOAD_PRESET`.
  - The upload preset is **unsigned**. No Cloudinary API secret exists on the Android client.
- **Gateway Readiness:** The Worker has `POST /api/v1/admin/media/upload`, but this endpoint requires Admin authentication.
- **User App Upload Strategy:** Unsigned uploads for avatars remain standard and safe, provided strict upload preset restrictions (image formats only, size caps, incoming transformation constraints) are maintained in Cloudinary Console.

---

## SECTION I: Secret Safety & Exfiltration Risk Audit

### 9.1 Secret Audit Findings

| Candidate Secret | Present in User App Repo / APK? | Risk Level | Mitigation & Audit Status |
|---|---|---|---|
| **Firebase Service Account JSON** | **NO [CONFIRMED]** | None | Strictly absent. No private keys or service accounts exist in the Android repo. |
| **Firebase Web API Key** | YES (`AIzaSyC9...nPs`) | Low (Standard) | Normal public identifier for Firebase clients; protected by Firestore Security Rules and App Check. |
| **Google Web OAuth Client Secret** | **NO [CONFIRMED]** | None | Client only possesses public Client IDs. Client secrets are never provided to Android. |
| **Cloudflare Worker API Tokens** | **NO [CONFIRMED]** | None | Strictly absent. Android app communicates only with public gateway endpoints. |
| **Cloudinary API Secret** | **NO [CONFIRMED]** | None | Client only has public cloud name and unsigned upload preset. |
| **TMDB API Key** | YES (`BuildConfig.TMDB_API_KEY`) | **Medium** | Present in client build. Must be migrated to Cloudflare Worker proxy in Phase 23.1. |
| **Firebase ID Tokens / Bearer** | Runtime Only (Memory) | Low / Protected | `AuthGatewayDiagnostic` OkHttpClient explicitly disables logging interceptors. Tokens are never written to logs, Room DB, DataStore, or UI. |

---

## SECTION J: Verified Test Evidence & Regression Baselines

### 10.1 Unit Test Execution Evidence (`./gradlew testDebugUnitTest`)
- **`GuestAuthenticationTest.kt`:** **PASS [CONFIRMED]**
  - Verifies that `signInAnonymously()` calls Firebase Anonymous Auth, populates `currentUserFlow`, updates `UserPreferencesRepository`, sets `isGuest=true`, and prevents duplicate concurrent requests.
- **`AccountIdentityRecoveryTest.kt`:** **PASS [CONFIRMED]**
  - Verifies that session recovery restores user identity from Firestore and handles missing profile documents without crashing.
- **`AuthGatewayDiagnosticTest.kt`:** **PASS [CONFIRMED]**
  - Verifies `AuthGatewayDiagnostic.evaluateResponse()` across 200 (Success/Mismatch), 401 (Auth Required/Invalid Token), 503 (JWKS unavailable), and network failure states.
- **`GoogleCredentialParserTest.kt`:** **PASS [CONFIRMED]**
  - Verifies parsing of Google ID Token credentials and error states.

### 10.2 Node Integrity Test Suite Evidence (`npm run test:identity`)
- **Test 1 (Project ID, App ID, Package, JSON SHA-256 match lock):** **PASS**
- **Test 2 (Certificate SHA-1 and Android OAuth client match lock):** **FAILED [DISCREPANCY]**
  - Actual local `debug.keystore` SHA-1: `fe3d8638e42a481fb5b77abe344666dfd423a0e8`
  - Expected registered SHA-1: `1e0f4a4a0ee84b7023e59504b3a978382d019e2a`
  - Root Cause: A local auto-generated `debug.keystore` replaced the canonical file in the workspace root.
  - Recovery Source: The canonical keystore is preserved as a base64 payload in `.github/workflows/build.yml:42`.
- **Test 3 (Web OAuth client matches shared client):** **PASS**
- **Test 4 (CI workflow embeds locked google-services.json):** **PASS**
- **Test 5 (Gradle validates Web OAuth client):** **PASS**

### 10.3 Firestore Rules Test Suite Evidence (`npm run test:rules`)
- Tests 2 through 8: **PASS** (Constraints on usernames, economy fail-closed, comments disabled in models, app update status gates, Firebase initializer order).
- Test 1 (`Firestore rules retain a final default-deny and have no active comments route`): **FAILED [ASSERTION MISMATCH]**
  - Reason: Test assertion `assert.doesNotMatch(rules, /match\s+\/comments(?:\/|\s*\{)/i)` fails because `firestore.rules:904` contains an explicit deny block:
    ```
    match /comments/{document=**} {
      allow read, write: if false;
    }
    ```
  - Both the rule and the test agree that comments must be 100% denied. The discrepancy is purely whether it is denied via an explicit `allow read, write: if false;` block or solely by the final catch-all deny.

---

## SECTION K: Ranked Vulnerabilities, Architectural Flaws & Missing Implementations

### Rank 1 — [HIGH PRIORITY] Local `debug.keystore` Certificate Drift
- **Issue:** Local `debug.keystore` in the root workspace has SHA-1 `fe3d8638...`, whereas Firebase Console and `app/google-services.json` expect `1e0f4a4a...`.
- **Impact:** Google Sign-In with Credential Manager in local debug builds on physical devices will return `10: Developer Error` or `NoCredentialException` unless signed with the registered cert.
- **Fix:** Restore canonical `debug.keystore` from the base64 string in `.github/workflows/build.yml`.

### Rank 2 — [MEDIUM PRIORITY] Client-Side Exposure of `TMDB_API_KEY`
- **Issue:** The TMDB API key is compiled into the APK via `BuildConfig.TMDB_API_KEY` and transmitted directly from client devices.
- **Impact:** Leakage of TMDB API quota/credentials.
- **Mitigation Ready:** The deployed Cloudflare Worker gateway already possesses `/api/v1/tmdb/*` proxy endpoints. User App need only repoint its `RetrofitClient` base URL.

### Rank 3 — [MEDIUM PRIORITY] Economic Backend Routes Not Yet Deployed on Gateway
- **Issue:** The User App's `PointsEarningRepository` fails closed with `BACKEND_REQUIRED` because `cinestream-api-gateway` does not yet expose `/api/earn/*` or `/api/subscription/redeem`.
- **Impact:** User points earning and subscription redemption features cannot execute until the economic engine in `backend/` is reviewed, approved, and deployed to Cloudflare Workers.

### Rank 4 — [LOW PRIORITY] Node Rules Test Assertion Mismatch on Comments Deny Rule
- **Issue:** `firestore_security_regression.test.js:35` forbids the string `match /comments`, but `firestore.rules:904` explicitly denies comments with `match /comments/{document=**} { allow read, write: if false; }`.
- **Impact:** `npm run test:rules` reports 1 failing test despite comments being strictly denied in production rules.

---

## SECTION L: Actionable Implementation Plan (Phase 23.1+)

### Step 1: Restore Canonical `debug.keystore`
- Decode the canonical debug keystore from `.github/workflows/build.yml` into `/debug.keystore`.
- Verify SHA-1 matches `1e0f4a4a0ee84b7023e59504b3a978382d019e2a` via `keytool -list -v`.
- Rerun `npm run test:identity` to confirm all 5 tests pass.

### Step 2: Harmonize Comments Security Rule Regression Test
- Update test assertion in `qa/node-tests/rules/firestore_security_regression.test.js` or comment block in `firestore.rules` so that `npm run test:rules` passes 100% cleanly while maintaining strict deny.

### Step 3: Migrate TMDB Networking to Cloudflare Worker Gateway
- In `RetrofitClient.kt`:
  - Update `BASE_URL` to `https://cinestream-api-gateway.blackmyemail1.workers.dev/api/v1/tmdb/`.
  - Add an OkHttp interceptor injecting `Authorization: Bearer <ID_TOKEN>`.
  - Deprecate client-side `BuildConfig.TMDB_API_KEY`.

### Step 4: Propose Economic Backend Deployment
- Prepare a migration proposal to merge `backend/src/earning.ts` and `backend/src/index.ts` into the `cinestream-api-gateway` Worker, enabling server-authoritative points earning and subscription redemption.

---

## SECTION M: Cross-App Handoff Contract for Admin Dashboard

### 13.1 Strict Administrative Boundary
The Admin Dashboard operates in a completely isolated session. The following cross-app contracts are established for alignment:

1. **Shared Firebase Project:** Both apps connect to `cinestream-sulo` (`979447256418`).
2. **Distinct Android Package Identities:**
   - **User App:** `com.aistudio.cinestream.xyzabc` (App ID `...037a9b3a1393e550a9883b`)
   - **Admin App:** `com.aistudio.cinestreamadmin.adcxz` (App ID `...ea5b570266ae1aa6a9883b`)
3. **Shared Authority Collections:**
   - `/admins/{adminUid}`: Checked by `firestore.rules` (`isAdmin()`). The User App never has write access.
   - `/config/app`: Admin App writes maintenance and update flags; User App reads.
   - `/config/features`: Admin App toggles feature states; User App reads.
   - `/config/economy`: Admin App defines reward ladders; User App reads.
   - `/managed_extensions`: Admin App manages scrapers; User App syncs.
   - `/app_updates`: Admin App releases APK updates; User App reads and verifies.
   - `/pro_requests`: User App creates pending requests; Admin App reviews/approves.
   - `/reports`: User App submits violation reports; Admin App resolves.
4. **Cloudflare Gateway Contract:**
   - Endpoint `GET /api/v1/admin/verify` validates Admin claims.
   - Endpoint `POST /api/v1/admin/media/upload` handles administrative uploads.
   - Both teams must preserve `Authorization: Bearer <ID_TOKEN>` header convention and error schemas (`AUTH_REQUIRED`, `INVALID_ID_TOKEN`).

---
*End of Phase 23.0 Audit Report.*
