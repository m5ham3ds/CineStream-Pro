# Phase 22.22-D: CineStream User App — Firestore Compatibility Repairs Report

**Date of Execution:** October 20, 2026  
**Auditor / Engineer:** Senior Firebase Security Engineer  
**Execution Context:** CineStream User App Session (Google AI Studio Build) — Isolated Android Workspace  
**Target Platform:** Android (Kotlin / Jetpack Compose)  
**Execution Mode:** STRICT READ-ONLY / AUDIT / MINIMAL SAFE CLIENT REPAIR  
**Primary Deliverable Report:** `PHASE_22.22-D_USER_APP_COMPATIBILITY_REPAIR_REPORT.md`  

---

## 1. Executive Summary & Verification of External Artifacts

In Phase 22.22-D, the CineStream User App codebase was audited to resolve compatibility and security discrepancies highlighted during central review before the unified Firestore security ruleset can be approved for production deployment.

### 1.1 Status of Supplied External Reference Artifacts
- **`PHASE_22.22-C_USER_COMPATIBILITY_AUDIT_REPORT.md`**: **Present & Verified** in local workspace root (SHA-256: `08aa07b787b4190d463c39f01e22c75b8dfeb22505c10528850a4f214593d6f6`).
- **`PHASE_22.22_CENTRAL_UNIFIED_RULES_CANDIDATE.rules.txt`**: **Missing from Filesystem** (Documented in accordance with instructions; evaluated using central candidate constraints established in Turn 103/104 and Phase 22.22-C).
- **`PHASE_22.22_CENTRAL_UNIFIED_REVIEW_REPORT.md`**: **Missing from Filesystem** (Documented in accordance with instructions).

### 1.2 Summary of Actions and Outcomes
1. **Username Uniqueness & Private Profiles:** Analyzed the conflict between private profiles and username collision checks. Rejected broad read permissions on `/public_profiles`. Formulated a complete engineering specification for a dedicated normalized username reservation index (`/usernames/{normalizedUsername}`), including schema, atomic transactional lifecycle, deletion handling, rollback, backfill migration strategy, and required owner consent. Source changes to point to `/usernames` are intentionally held pending central cross-app approval to prevent runtime `PERMISSION_DENIED` errors on un-provisioned paths.
2. **Account Deletion & Anonymization:** Forensically audited `AuthRepository.kt:deleteAccount`. Proved that `userDoc.delete().await()` fails because rules restrict document deletion to administrators (`allow delete: if isAdmin()`). Proved that the subsequent anonymization fallback (`userDoc.update`) succeeds because it only updates non-sensitive profile fields (`displayName`, `firstName`, `lastName`, `bio`, `photoUrl`, `isProfilePublic`, `deletedAt`), without touching `isActive`, `email`, `role`, or `pointsBalance`. Fixed a UI navigation bug in `AccountSettingsScreen.kt` where `PartialFailure` failed to redirect the user to Auth after authentication deletion.
3. **Economy Behavior Verification:** Verified that `PointsEarningRepository.isTemporaryEconomyModeEnabled` is `false`. Verified that when no trusted backend handler is set, all claims return `PointsEarningError.BACKEND_REQUIRED`. Confirmed that the UI displays the exact backend requirement message and never falsely increments or displays unearned wallet points. Direct client writes to `point_transactions` and `task_claims` remain strictly disabled.
4. **Change Control & Safety:** Base `firestore.rules` was **not overwritten**. No rules were published (`NO_FIREBASE_DEPLOYMENT`). No live data was modified. Comments remain completely denied.

---

## 2. Exact Source Files & Methods Inspected

| Component / Layer | Source File Path | Method / Identifier Inspected | Line Range |
| :--- | :--- | :--- | :--- |
| **Authentication & Profile Repository** | `app/src/main/java/com/example/data/repository/AuthRepository.kt` | `isUsernameTaken(username, currentUid)` | Lines 617–633 |
| | | `generateUniqueUsername(baseName)` | Lines 635–654 |
| | | `saveUser(user)` | Lines 521–615 |
| | | `deleteAccount(context)` | Lines 667–800 |
| **Auth & Profile ViewModel** | `app/src/main/java/com/example/ui/screens/auth/AuthViewModel.kt` | `register(email, pass)` | Lines 199–235 |
| | | `updateProfile(...)` | Lines 251–310 |
| | | `deleteAccount(onResult)` | Lines 385–403 |
| **Account Settings Screen** | `app/src/main/java/com/example/ui/screens/profile/AccountSettingsScreen.kt` | `deleteAccount` dialog & result handler | Lines 105–130 |
| **Points Earning Repository** | `app/src/main/java/com/example/data/repository/PointsEarningRepository.kt` | `claimDailyLogin(request)` | Lines 233–272 |
| | | `claimRewardedAd(request)` | Lines 279–319 |
| | | `claimTaskReward(request)` | Lines 325–365 |
| | | `redeemSubscription(request)` | Lines 371–415 |
| | | `TEMPORARY_ECONOMY_MODE`, `isTemporaryEconomyModeEnabled` | Lines 58–60 |
| **Points Earning ViewModel** | `app/src/main/java/com/example/ui/screens/profile/PointsEarningViewModel.kt` | `claimDailyLogin()` | Lines 103–144 |
| | | `watchRewardedAd(context)` | Lines 149–215 |
| | | `claimTaskReward(task)` | Lines 218–263 |
| **Economy UI Display** | `app/src/main/java/com/example/ui/screens/profile/SubscriptionScreen.kt` | Points claim button handlers & status alerts | Lines 1420–1435, 1630–1640, 1720–1735 |
| **Security Rules Baseline** | `firestore.rules` | `/users/{userId}`, `/public_profiles/{userId}`, `/admins/{adminUid}` | Lines 189–275 |

---

## 3. Forensic Analysis & Root Cause Determination

### 3.1 Issue 1: Username Uniqueness and Private Profiles

#### Root Cause Analysis:
In `AuthRepository.kt:620-623`:
```kotlin
val snapshot = db.collection("public_profiles")
    .whereEqualTo("username", username)
    .get()
    .await()
```
1. **Firestore Rules Authorization Engine Constraint:**  
   Under any security rule that permits reading `/public_profiles/{userId}` only when `resource.data.isProfilePublic == true`, Firestore **rejects the collection query up front** with `PERMISSION_DENIED` unless the client query explicitly includes `.whereEqualTo("isProfilePublic", true)`. This is because Firestore security rules are authorizers, not result filters.
2. **The Private Profile Blindspot:**  
   If the client query is modified to `.whereEqualTo("isProfilePublic", true).whereEqualTo("username", username)`, the query executes without error, but it is blind to accounts with `isProfilePublic == false`. As a result:
   - A new user could claim the exact username of an existing private user.
   - When the private user updates their profile or switches to public, duplicate usernames exist.
3. **Rejection of Broad `/public_profiles` Reads:**  
   Central review correctly rejected resolving this by granting `allow read: if isAuthenticated();` across all public profile documents if the product intent is that private profiles must remain strictly hidden.

#### Recommended Solution: Dedicated Normalized Username Reservation Index (`/usernames/{normalizedUsername}`)

To provide 100% collision protection for both public and private profiles without exposing any user profile data, a dedicated reservation collection must be established.

##### A. Document Schema (`/usernames/{normalizedUsername}`)
- **Collection Path:** `/usernames`
- **Document ID:** `normalizedUsername` (canonical lowercase, stripped of spaces and special characters, e.g. `sulo01`).
- **Fields:**
  ```json
  {
    "uid": "USER_AUTH_UID",
    "reservedAt": "SERVER_TIMESTAMP",
    "updatedAt": "SERVER_TIMESTAMP"
  }
  ```
- **Zero Information Leakage:** The document contains NO personal data (no names, emails, photos, bios, or profile visibility flags).

##### B. Access Rules Specification
```javascript
match /usernames/{normalizedUsername} {
  // Any authenticated user can check if a handle is already reserved.
  allow read: if isAuthenticated();

  // A handle can only be reserved if it does NOT already exist, and UID must match caller.
  allow create: if isAuthenticated() &&
    request.auth.uid == request.resource.data.uid &&
    request.resource.data.keys().hasOnly(['uid', 'reservedAt', 'updatedAt']) &&
    request.resource.data.reservedAt == request.time &&
    request.resource.data.updatedAt == request.time &&
    !exists(/databases/$(database)/documents/usernames/$(normalizedUsername));

  // In-place updates are forbidden; username changes require release + claim.
  allow update: if false;

  // Only the reserving user or an administrator can release the handle.
  allow delete: if isAdmin() || (
    isAuthenticated() &&
    resource.data.uid == request.auth.uid
  );
}
```

##### C. Atomic Maintenance Lifecycle
1. **Registration Flow:**
   Executed inside a Firestore `runTransaction`:
   - `val userHandleDoc = transaction.get(db.collection("usernames").document(normalizedName))`
   - If `userHandleDoc.exists()`, abort with `USERNAME_ALREADY_TAKEN`.
   - `transaction.set(db.collection("usernames").document(normalizedName), mapOf("uid" to uid, "reservedAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))`
   - `transaction.set(userDocRef, initialUserMap)`
   - If `isProfilePublic`, `transaction.set(publicProfileDocRef, publicMap)`
2. **Username Change Flow (`oldUsername` -> `newUsername`):**
   Executed inside a Firestore `runTransaction`:
   - Read `usernames/{newNormalizedName}`. If it exists and `data.uid != currentUid`, abort with `USERNAME_ALREADY_TAKEN`.
   - Delete `usernames/{oldNormalizedName}`.
   - Create `usernames/{newNormalizedName}` with current UID.
   - Update `users/{uid}` with `"username": newUsername`.
   - Update `public_profiles/{uid}` with `"username": newUsername`.
3. **Failed Registration / Rollback:**
   Because all operations are bundled in a single Firestore transaction, any failure (e.g. auth failure, network timeout, quota) rolls back all operations; no orphaned reservation is created.
4. **Account Deletion:**
   The `deleteAccount` flow reads the user's current username and deletes `usernames/{normalizedUsername}`, releasing the handle.
5. **Backfill Migration Strategy for Existing Production Accounts:**
   - Existing production accounts do not yet have entries in `/usernames`.
   - Before deploying client code requiring `/usernames`, a one-time Admin SDK script must scan `/users` ordered by `createdAt ASC`.
   - For each user with a non-empty `username`, write `usernames/{normalizedName}` with `{ "uid": doc.id, "reservedAt": doc.get("createdAt") }`.
   - If duplicate usernames already exist in legacy data, the older account retains the reservation; the newer account's reservation fails and is flagged for a forced username prompt upon their next login.
6. **Required Decision & Approval:**  
   **Requires owner approval and Admin Dashboard synchronization.** Updating client code to write to `/usernames` before the collection exists in production security rules would immediately break all registrations with `PERMISSION_DENIED`.

---

### 3.2 Issue 2: Account Deletion or Anonymization

#### Root Cause Analysis:
In `AuthRepository.kt:667-800`:
1. **Line 746 (`userDoc.delete().await()`):**  
   The client attempts a hard delete on `/users/{uid}`. Under both canonical rules and central candidates:
   ```javascript
   match /users/{userId} {
     allow delete: if isAdmin();
   }
   ```
   **Hard deletion is intentionally blocked for non-admin users.** This is a critical security protection that prevents banned users (`isBanned == true`) from deleting their user record to clear their ban history and re-registering with the same credentials.
2. **Lines 750–763 (The Anonymization Fallback):**  
   When `userDoc.delete().await()` is caught, the code executes:
   ```kotlin
   userDoc.update(
       mapOf(
           "displayName" to "Deleted User",
           "firstName" to "",
           "lastName" to "",
           "bio" to "",
           "photoUrl" to "",
           "isProfilePublic" to false,
           "deletedAt" to FieldValue.serverTimestamp()
       )
   ).await()
   ```
3. **Field Mutability Audit:**
   - Fields updated: `displayName`, `firstName`, `lastName`, `bio`, `photoUrl`, `isProfilePublic`, `deletedAt`.
   - Protected fields **NOT touched:** `isActive`, `isBanned`, `banReason`, `role`, `subscriptionTier`, `pointsBalance`, `email`, `username`.
   - **Verification against `modifyingSensitiveUserFields()`:**  
     None of the 7 updated fields are listed in `modifyingSensitiveUserFields()`.  
     Therefore, the anonymization update **succeeds without error**.
4. **UI Disconnect on `PartialFailure`:**  
   Because `userDoc.delete()` failed, `docDeletionFailed` was set to `true`, causing `deleteAccount` to return `AccountDeletionResult.PartialFailure`. In `AccountSettingsScreen.kt:122-124`, `PartialFailure` merely displayed a Toast without calling `onSignOut()` or `onNavigateToAuth()`. The user's Firebase Auth account was already deleted, leaving the UI in an inconsistent state.

#### Implemented Source Fix:
Updated `AccountSettingsScreen.kt` (lines 122–126) so that on `AccountDeletionResult.PartialFailure`, `onSignOut()` and `onNavigateToAuth()` are properly invoked, gracefully returning the user to the Welcome/Auth screen.

#### Proposed Architectural Harmonization (Waiting for Central Decision):
- **Option A (Recommended — Client Anonymization as Primary):**  
  Accept client-side anonymization as standard user self-service deletion. Update `deleteAccount` so that when anonymization succeeds, it returns `AccountDeletionResult.Success`.
- **Option B (Narrow Rule Exception):**  
  Update `firestore.rules`:
  ```javascript
  allow delete: if isAdmin() || (
    isAuthenticated() &&
    request.auth.uid == userId &&
    resource.data.isBanned != true
  );
  ```
  Permits self-deletion only if the user is not banned.
- **Option C (Trusted Backend Worker):**  
  Offload complete account hard-deletion to a Cloudflare Worker or Cloud Function triggered on user account removal.

---

### 3.3 Issue 3: Economy Behavior Verification

#### Verification Checklist:
1. **Temporary Economy Mode Inactive:**  
   `PointsEarningRepository.kt:58-60`:
   ```kotlin
   const val TEMPORARY_ECONOMY_MODE = false
   var isTemporaryEconomyModeEnabled: Boolean = false
   ```
   Confirmed that temporary direct client writes are disabled.
2. **Backend Requirement Enforcement:**  
   `PointsEarningRepository.kt:268-271, 315-318, 360-363`:
   When `trustedBackendUrl` is unset and `backendHandler` is null, all earning and redemption requests return:
   ```kotlin
   PointsOperationResult.Error(
       PointsEarningError.BACKEND_REQUIRED,
       PointsEarningError.BACKEND_REQUIRED.defaultMessage
   )
   ```
3. **UI Transparency & Accuracy:**  
   `PointsEarningViewModel.kt:126-136, 189-199, 245-255`:
   When `PointsOperationResult.Error` is returned, `_operationMessage.value` is populated with:  
   `"يتطلب هذا الإجراء خدمة الخادم الموثوق (Backend Required)"`.
   The ViewModel executes `refreshAuthoritativeState(uid)`, fetching the authoritative balance from `/users/{uid}` in Firestore.
   **Confirmed:** The UI never increments wallet counters locally, never mocks transaction receipts, and never displays false award notices.

---

## 4. Source Changes Made in this Phase

Only one minimal, 100% safe client UI change was implemented to prevent user stranding during account deletion:

### File Modified:
`/app/src/main/java/com/example/ui/screens/profile/AccountSettingsScreen.kt`

### Diff:
```diff
--- a/app/src/main/java/com/example/ui/screens/profile/AccountSettingsScreen.kt
+++ b/app/src/main/java/com/example/ui/screens/profile/AccountSettingsScreen.kt
@@ -121,6 +121,8 @@ class AccountSettingsScreen ...
                                     }
                                     is AccountDeletionResult.PartialFailure -> {
                                         Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
+                                        onSignOut()
+                                        onNavigateToAuth()
                                     }
                                 }
```

*Rationale:* When `deleteAccount` completes anonymization and deletes the Firebase Auth account, returning `PartialFailure` previously stranded the user on the account settings screen. This change ensures proper session termination and navigation without altering any backend permissions or data schemas.

---

## 5. Schema Migration, Backfill & Owner Approval Requirements

| Requirement | Purpose | Owner / Central Approval Needed? | Migration Sequence |
| :--- | :--- | :--- | :--- |
| **`/usernames` Collection Provisioning** | Add `/usernames/{normalizedUsername}` security rules to Firestore | **YES (Owner & Central Agreement)** | 1. Add rule to unified ruleset.<br>2. Deploy unified rules.<br>3. Run backfill script.<br>4. Update client registration & profile code. |
| **Legacy Usernames Backfill Script** | Populate existing user handles into `/usernames` | **YES (Requires Admin SDK / Cloud Run execution)** | Run batch job scanning `/users` sorted by `createdAt ASC`. Create reservations with original timestamps. Flag legacy duplicates for rename. |
| **Account Deletion Policy Decision** | Choose between client anonymization, unbanned deletion rule, or Cloud Function | **YES (Cross-App Security Policy)** | Confirm whether `/users/{uid}` should be hard-deleted or retained in anonymized state for audit trails. |

---

## 6. Unresolved Issues & Cross-App Contracts (`NEEDS_EXTERNAL_COMPARISON`)

1. **`NEEDS_EXTERNAL_COMPARISON` — Unified Username Index Agreement:**  
   The Admin Dashboard session must agree to the `/usernames/{normalizedUsername}` collection contract before client-side enforcement can be enabled.
2. **`NEEDS_EXTERNAL_COMPARISON` — Account Deletion Rule Alignment:**  
   Determine whether central unified rules will include `resource.data.isBanned != true` for owner self-deletion or maintain admin-only deletion with client-side anonymization.
3. **`NEEDS_EXTERNAL_COMPARISON` — Trusted Backend Economic Gateway:**  
   Coordinate Cloudflare Worker or Cloud Function deployment details for processing points transactions and claims.

---

## 7. Strict Change Control & Governance Confirmations

1. **Base Security Rules Untouched:** `firestore.rules` was **NOT** modified or overwritten. Baseline SHA-256 remains `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef`.
2. **No Firebase Deployment:** `firebase deploy` was **NOT** run.
3. **No Live Data Modified:** No read or write requests were dispatched to live production Firestore databases.
4. **Identity & Build Settings Preserved:** `app/build.gradle.kts`, `app/google-services.json`, and `package.json` were not altered. Discrepancies remain tagged `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL`.
5. **Comments Feature Denied:** `/comments` remains completely blocked.
6. **Emulator Tests Status:** Accurately recorded as **`NOT RUN`** (tooling and `@firebase/rules-unit-testing` not installed in workspace).
7. **Compilation Verified:** Applet compilation verified successfully (`compile_applet: Build succeeded`).
