# PHASE 22.19-A — ACCOUNT RECOVERY & AUTH STATE CONSISTENCY REPORT

**Date**: 2026-10-19  
**Target**: CineStream User App — Critical Account Separation & Auth State Consistency  
**Package Name**: `com.aistudio.cinestream.xyzabc`  
**Firebase Project**: `cinestream-sulo` (Project Number: `979447256418`)  
**Debug Keystore Fingerprints**:  
- **SHA-1**: `1E:0F:4A:4A:0E:E8:4B:70:23:E5:95:04:B3:A9:78:38:2D:01:9E:2A`  
- **SHA-256**: `C3:AE:D0:B9:55:2C:BB:73:00:57:30:94:95:A3:79:E1:E8:CC:73:EE:FA:BC:4D:EA:CC:39:61:6F:C1:2D:27:D3`  
**Artifact**: `/app/applet/app/build/outputs/apk/debug/app-debug.apk`

---

## 1. Executive Summary

In response to the critical account separation issue reported after activating explicit Google Sign-In, a comprehensive investigation and repair was conducted across the CineStream User App.

### Problems Resolved:
1. **Account Separation & Disjoint UIDs**: Users previously registered with Email & Password who subsequently tapped "Sign in with Google" received a separate, unlinked account (`UID_google`) with 0 points.
2. **Community Gate ("Sign in required")**: Despite an active Firebase session, opening the Community section presented a "Sign in required" gate due to a timing race between `AuthStateListener` and profile document loading.
3. **Raw Internal Error Messages**: Attempting to sign back in with email/password displayed raw internal Firebase diagnostics (`"The supplied auth credential is incorrect, malformed or has expired."`) rather than clean, localized, secure user messages.
4. **Local Favorites Bleeding**: Room cached favorites from the previous account were displayed in the library after switching to the new Google session because `SessionManager.onUserLoggedIn` did not purge old local records on account change.

---

## 2. Canonical Identity & Signing Verification

| Attribute | Configured Value | Status |
|:---|:---|:---|
| **applicationId** | `com.aistudio.cinestream.xyzabc` | **PASS (Canonical)** |
| **namespace** | `com.example` | **PASS (Preserved)** |
| **Firebase Project ID** | `cinestream-sulo` | **PASS** |
| **Firebase Project Number** | `979447256418` | **PASS** |
| **Android Firebase App ID** | `1:979447256418:android:037a9b3a1393e550a9883b` | **PASS** |
| **Web Server Client ID** | `979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com` | **PASS** |
| **Android Client ID** | `979447256418-9jl4cl94f22a227strvhj3ups98dpka0.apps.googleusercontent.com` | **PASS** |
| **Debug Keystore SHA-1** | `1E:0F:4A:4A:0E:E8:4B:70:23:E5:95:04:B3:A9:78:38:2D:01:9E:2A` | **PASS (100% Match)** |
| **Debug Keystore SHA-256** | `C3:AE:D0:B9:55:2C:BB:73:00:57:30:94:95:A3:79:E1:E8:CC:73:EE:FA:BC:4D:EA:CC:39:61:6F:C1:2D:27:D3` | **PASS (100% Match)** |

---

## 3. Architectural & Code Changes Implemented

### A. Provider Linking Flow (`linkWithCredential`)
- **`AuthRepository.kt`**:
  - Implemented `linkCurrentUserWithGoogle(idToken: String)` using `FirebaseUser.linkWithCredential(credential)`.
  - Added helper methods `isGoogleLinked()` and `isPasswordLinked()`.
- **`AuthViewModel.kt`**:
  - Implemented `linkGoogleAccount(idToken: String, onComplete: (Boolean, String?) -> Unit)`.
  - Gracefully intercepts `FirebaseAuthUserCollisionException` and maps to `R.string.google_account_already_linked` without destructive account overwrites.
- **`SecurityScreen.kt`**:
  - Added a dedicated **"Linked Sign-In Methods" (`linked_accounts_title`)** card.
  - Displays status for both Password and Google providers.
  - Provides an explicit "Link Google Account" button that triggers Android Credential Manager `GetSignInWithGoogleOption` to link Google credentials to the authenticated user's existing UID.

### B. Safe Handling of Split Accounts
- Both existing accounts (`UID_email` and `UID_google`) are preserved without automated destructive actions.
- Cross-account merges require explicit administrative verification and are marked as **`BLOCKED_PENDING_VERIFIED_ACCOUNT_MERGE`**.
- Original points balances, roles, and transaction history in `/users/UID_email` remain intact and unmutated.

### C. Profile State Machine & Race Condition Elimination
- **`AuthProfileState`**:
  - Defined explicit states: `Loading`, `Unauthenticated`, `AuthenticatedWithProfile(user)`, `AuthenticatedProfileMissing(firebaseUser)`, `ProfileLoadFailed(firebaseUser, error)`.
- **`AuthRepository.kt`**:
  - Added `authProfileStateFlow: StateFlow<AuthProfileState>`.
  - In `getCurrentUser()`: if the Firestore document `/users/{uid}` does not exist yet or is experiencing transient network latency, the repository **never overwrites `currentUserFlow.value` with `null`** and **never invokes `auth.signOut()`**.
  - Emits safe fallback user representations to guarantee that active sessions are never treated as unauthenticated.

### D. Community Gate Repair (`SocialViewModel.kt`)
- Updated `SocialViewModel` to observe `AuthRepository.currentUserFlow` with active session verification against `FirebaseAuth.currentUser`.
- If a valid `FirebaseUser` session exists, `SocialViewModel` provides an active `UserProfile` even when the Firestore document is loading or missing.
- Prevents the "Sign in required" gate from falsely blocking authenticated users.

### E. Cross-Account State Isolation (`SessionManager.kt`)
- Updated `SessionManager.onUserLoggedIn(context, uid)`:
  - Detects account switching (`oldUid != null && oldUid != uid`).
  - Automatically triggers `clearUserScopedLocalData(context)` to purge previous Room database records (`library_items`, `history`, `watched_episodes`).
  - Prevents local favorites or playback history from leaking into different user accounts.

### F. Localized & Sanitized Authentication Error Messages
- Added `mapAuthException(e: Exception)` to `AuthViewModel.kt`:
  - `ERROR_INVALID_CREDENTIAL`, `ERROR_WRONG_PASSWORD`, `ERROR_USER_NOT_FOUND` -> `R.string.invalid_email_or_password` ("البريد الإلكتروني أو كلمة المرور غير صحيحة").
  - `ERROR_USER_DISABLED` -> `R.string.account_disabled` ("تم تعطيل هذا الحساب").
  - `ERROR_TOO_MANY_REQUESTS` -> `R.string.too_many_requests` ("تم تسجيل محاولات غير ناجحة كثيرة").
  - `FirebaseNetworkException` -> `R.string.network_error_auth` ("خطأ في الاتصال بالشبكة").
  - Completely eliminated raw display of internal Firebase string: `"The supplied auth credential is incorrect, malformed or has expired."`
  - Preserved security by preventing user enumeration attacks.

---

## 4. Automated Unit Testing Results

Created and executed `AccountIdentityRecoveryTest.kt` with 10 comprehensive tests:

| Test Case | Scenario Verified | Result |
|:---|:---|:---|
| `existingUserWithPoints_preservesPointsBalanceAndLedger` | Validates point preservation on existing UID | **PASSED** |
| `linkProvider_preservesCanonicalUid` | Validates provider linking without altering canonical UID | **PASSED** |
| `accountCollision_doesNotCreateSecondAccountOrOverwriteExisting` | Handles credential-already-in-use safely | **PASSED** |
| `missingProfileDocument_doesNotEmitUnauthenticated` | Verifies `AuthenticatedProfileMissing` state | **PASSED** |
| `raceConditionPrevention_preservesExistingInMemoryProfile` | Prevents profile wipe to null during timing races | **PASSED** |
| `communityGate_withAuthenticatedUser_providesProfile` | Allows community access when user is authenticated | **PASSED** |
| `sessionManager_accountSwitchPurgesOldUserLocalData` | Verifies local data purge on account switch | **PASSED** |
| `pointsLedger_isServerAuthoritativeAndImmutableFromUserApp` | Enforces ledger immutability and server authority | **PASSED** |
| `mapAuthException_sanitizesInvalidCredentialError` | Sanitizes `ERROR_INVALID_CREDENTIAL` | **PASSED** |
| `mapAuthException_mapsDisabledAndNetworkErrors` | Maps disabled user, rate-limiting, and network errors | **PASSED** |

In addition, all existing `GoogleCredentialParserTest` tests passed without regressions (Total: 21 passing unit tests: 10 in `AccountIdentityRecoveryTest`, 11 in `GoogleCredentialParserTest`).

---

## 5. Build Verification Results

| Gradle Task | Command | Result | Details |
|:---|:---|:---|:---|
| **Google Services Setup** | `./gradlew :app:processDebugGoogleServices --no-daemon` | **PASS** | Processed canonical `app/google-services.json` |
| **Unit Tests** | `./gradlew :app:testDebugUnitTest --no-daemon` | **PASS** | 21/21 unit tests passed (100%) |
| **Debug Build** | `./gradlew :app:assembleDebug --no-daemon` | **PASS** | Generated debug APK with valid Web Client ID |
| **Package Name Inspection** | `aapt2 dump badging` | **PASS** | Package name confirmed: `com.aistudio.cinestream.xyzabc` |
| **Android Lint** | `./gradlew :app:lintDebug --no-daemon` | **PASS** | Lint completed successfully without fatal errors |

---

## 6. Safety & Operational Guardrails Confirmation

1. **Firebase Console**: Zero modifications made to Firebase Console, project settings, or OAuth client configurations.
2. **Firestore Security Rules**: `firestore.rules` remained completely untouched and undeployed.
3. **Production Data**: No points, subscriptions, roles, or database documents were deleted or altered on production.
4. **Account Merges**: Automated data merging is marked **`BLOCKED_PENDING_VERIFIED_ACCOUNT_MERGE`**; both accounts are safely preserved without loss of data.
5. **Comments Feature**: Remains permanently disabled as required.
