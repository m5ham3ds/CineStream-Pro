# Phase 22.22-C: CineStream User App — Firestore Compatibility Audit Report

**Date of Execution:** October 20, 2026  
**Auditor / Security Role:** Senior Firebase Security Engineer  
**Execution Context:** CineStream User App Session (Google AI Studio Build) — Isolated Android Workspace  
**Target Platform:** Android (Kotlin / Jetpack Compose)  
**Execution Mode:** STRICT READ-ONLY / FORENSIC VERIFICATION / PROVISIONAL ARTIFACT GENERATION  
**Primary Deliverable Report:** `PHASE_22.22-C_USER_COMPATIBILITY_AUDIT_REPORT.md`  
**Provisional Compatibility Candidate:** `PHASE_22.22-C_USER_COMPATIBILITY_CANDIDATE.rules`  

---

## 1. Executive Summary & Verified Completion Status

This audit was conducted strictly within the CineStream User App workspace to evaluate whether the supplied Admin provisional Firestore security rules candidate supports the User App's actual runtime Firestore queries, mutations, transactions, and listeners safely.

### 1.1 Completion Status: **100% AUDITED (STATIC ANALYSIS & FORENSIC EXTRACTION COMPLETE)**
- **Baseline Verification:** Confirmed that local `firestore.rules` is 100% byte-for-byte identical to the canonical User App reference `54c8570a...` (29,766 bytes, 812 lines). The base rules file was **not overwritten**.
- **External Candidate Evidence Status:** As required by prompt instructions, missing external files (`PHASE_22.22-C_ADMIN_RECONCILIATION_CANDIDATE.rules.txt` and `PHASE_22.22-C_ADMIN_RECONCILIATION_AUDIT_REPORT.md`) were recorded as not present in this workspace. The audit evaluated the specific Admin candidate constraints and design rules specified in the task prompt along with the user's previously supplied candidate `Rules.md` (`4bad2bf5...`).
- **Critical Blockers & Incompatibilities Identified:**
  1. **Points & Task Claims Authority Blocker:** The User App's `TemporaryFirebaseEconomyRepository.kt` contains direct client transactions mutating `pointsBalance` and writing to `point_transactions`/`task_claims`. Admin candidates restrict ledger writes to admins (`allow write: if isAdmin()`). In `PointsEarningRepository.kt`, `isTemporaryEconomyModeEnabled` is set to `false`, returning `BACKEND_REQUIRED`. Direct client writes without a trusted backend represent an architectural **BLOCKER**.
  2. **Public Profiles Query Failure vs. Privacy:** In `AuthRepository.kt:620`, `isUsernameTaken()` executes an unconstrained query `db.collection("public_profiles").whereEqualTo("username", username).get()`. Under rules requiring `resource.data.isProfilePublic == true`, this query is **immediately rejected by Firestore with `PERMISSION_DENIED`** because Firestore security rules are not filters. Furthermore, querying only public profiles would allow collisions with usernames of private accounts.
  3. **Read Receipts Subcollection Denial:** In `SocialRepository.kt:256`, chat read receipts write to `/conversations/{id}/readReceipts/{uid}` using `FieldValue.serverTimestamp()`. Any rules candidate that omits this match block breaks real-time message read tracking with `PERMISSION_DENIED`.
  4. **Support Ticket Privilege Escalation Prevention:** Verified that `SupportViewModel.kt` only sets `"status": "open"` on creation and updates only `lastMessage`, `lastMessageTime`, and `updatedAt`. Normal users do not alter `status` or `unreadCounts`. Strict diff restrictions are required to prevent privilege escalation.
  5. **Notification Queries Alignment:** Verified that `NotificationRepository.kt` issues three separate queries (`target == 'all'`, `target == 'user'`, and `target == 'pro'`) matching the exact security rule branches and reading matching subscription fields (`isPro`, `isPremium`, `subscriptionTier`).
  6. **Extension Catalog Migration:** Proved that User App reads **exclusively** from canonical `/managed_extensions` (`COLLECTION_PATH = "managed_extensions"`). The legacy `/extensions` path is completely unused at runtime.
  7. **Comments Feature Total Denial:** Confirmed that `/comments` is 100% inactive, has no UI or repositories, and remains completely denied.
- **Safety Confirmations:**
  - `NO_FIREBASE_DEPLOYMENT` (No deployment attempted or executed).
  - No live Firestore data created, modified, or deleted.
  - No build files modified; all package/project mismatches tagged `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL`.
  - Unresolved cross-app contracts tagged `NEEDS_EXTERNAL_COMPARISON`.

---

## 2. Actual Baseline Verification Matrix

The cryptographic SHA-256 hashes, exact byte counts, and line counts were calculated directly on the filesystem:

| File Name / Path | SHA-256 Hash | Exact Byte Count | Line Count | Status & Role |
| :--- | :--- | :--- | :--- | :--- |
| `firestore.rules` (Local Root) | `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef` | 29,766 | 812 | **CANONICAL BASELINE VERIFIED** (Pristine, Unaltered) |
| `CINESTREAM_PRO firestore.rules.txt` | `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef` | 29,766 | 812 | **EXACT MATCH** (Verified Reference Copy) |
| `Rules.md` (External Candidate) | `4bad2bf56454ed804247dab7a112c54e2db5da66ecfb8ef7b70fb7659bc63fae` | 23,661 | 710 | **VERIFIED EXTERNAL ARTIFACT** |
| `PHASE_22.22-C_USER_COMPATIBILITY_CANDIDATE.rules` | `0737d9618d8374f65ba42b33c966d1bef2936d70d573e616cda49eb32ee8676d` | 29,755 | 798 | **GENERATED PROVISIONAL CANDIDATE** |

*Verification Assertion:* The local baseline `firestore.rules` matches the known User App reference fingerprint down to the exact byte. It is classified as `LOCAL_BASELINE_VERIFIED`. Direct proof of whether this exact file is currently published in Firebase Console requires live Google Cloud / Firebase API tokens not accessible within this sandbox.

---

## 3. Firestore Operation Inventory with Exact Source References

The following comprehensive inventory details every Firestore operation performed across the CineStream User App codebase:

| # | Firestore Document / Path | Operation Type | Caller Class & Location | Queried / Filtered Fields | Written Fields / Payload | Expected Role |
|---|---------------------------|----------------|-------------------------|---------------------------|--------------------------|---------------|
| 1 | `/admins/{adminUid}` | `get`, `listen` | `UserSecurityManager.kt:59, 331` | `enabled`, `isActive`, `role` | None (Read-only) | Authenticated Admin / Owner |
| 2 | `/users/{userId}` | `get`, `listen` | `AuthRepository.kt:406, 526`<br>`UserSecurityManager.kt:74, 326`<br>`PointsEarningRepository.kt:78, 120, 165` | `pointsBalance`, `subscriptionTier`, `isPro`, `isPremium`, `isBanned`, `canWatch`, etc. | `lastActiveAt`, `appVersion`, `updatedAt`, `lastLoginAt` | Authenticated Owner (`auth.uid == userId`) |
| 3 | `/users/{userId}` | `set` (merge) | `AuthRepository.kt:575` | Initial account fields | `uid`, `id`, `email`, `username`, `firstName`, `lastName`, `displayName`, `isProfilePublic`, `photoUrl`, `role='user'`, `subscriptionTier='FREE'`, `pointsBalance=0`, `canWatch=true`, `createdAt=serverTimestamp()` | Authenticated Owner (`auth.uid == userId`) |
| 4 | `/users/{userId}` | `delete` (fallback anonymize) | `AuthRepository.kt:755-765` | None | `email='deleted@cinestream.app'`, `username='DeletedUser'`, `deletedAt=serverTimestamp()`, `isActive=false` | Authenticated Owner (`auth.uid == userId`) |
| 5 | `/users/{userId}/point_transactions/{txId}` | `listen` | `PointsRepository.kt:43` | `orderBy("createdAt", DESCENDING)` | None (Production is read-only) | Authenticated Owner (`auth.uid == userId`) |
| 6 | `/users/{userId}/point_transactions/{txId}` | `transaction.set` (Temporary Mode) | `TemporaryFirebaseEconomyRepository.kt:151, 309, 452, 650` | None | `id`, `txId`, `userId`, `type`, `amount`, `balanceBefore`, `balanceAfter`, `referenceId`, `description`, `createdAt` | Authenticated Owner (`auth.uid == userId`) |
| 7 | `/users/{userId}/task_claims/{taskId}` | `listen` | `PointsEarningRepository.kt:210` | Full snapshot | None (Production is read-only) | Authenticated Owner (`auth.uid == userId`) |
| 8 | `/users/{userId}/task_claims/{taskId}` | `transaction.set` (Temporary Mode) | `TemporaryFirebaseEconomyRepository.kt:240` | None | `id`, `taskId`, `userId`, `claimedAt`, `rewardPoints` | Authenticated Owner (`auth.uid == userId`) |
| 9 | `/users/{userId}/library/{itemId}` | `get`, `set` (merge), `delete`, `listen` | `LibraryRepository.kt:87, 102`<br>`CloudSyncManager.kt:60, 169`<br>`AuthRepository.kt:692` | `id`, `title`, `posterPath`, `contentType`, `updatedAt` | `id`, `tmdbId`, `title`, `posterPath`, `contentType`, `year`, `genreIds`, `rating`, `addedAt`, `updatedAt`, `isSynced` | Authenticated Owner (`auth.uid == userId`) |
| 10 | `/users/{userId}/history/{itemId}` | `get`, `set` (merge), `delete`, `listen` | `HistoryRepository.kt:96, 129, 189`<br>`CloudSyncManager.kt:100, 199, 258`<br>`AuthRepository.kt:701` | `id`, `title`, `progress`, `duration`, `updatedAt` | `id`, `tmdbId`, `title`, `posterPath`, `contentType`, `progress`, `duration`, `season`, `episode`, `lastWatched`, `updatedAt` | Authenticated Owner (`auth.uid == userId`) |
| 11 | `/users/{userId}/watched_episodes/{episodeId}` | `get`, `set` (merge), `delete`, `listen` | `WatchedEpisodeRepository.kt:23, 35`<br>`CloudSyncManager.kt:116, 204, 296`<br>`AuthRepository.kt:710` | `episodeId`, `showId`, `seasonNumber`, `episodeNumber` | `episodeId`, `showId`, `seasonNumber`, `episodeNumber`, `watchedAt`, `updatedAt` | Authenticated Owner (`auth.uid == userId`) |
| 12 | `/users/{userId}/settings/notifications` | `get`, `set` (merge), `listen`, `delete` | `NotificationPreferencesRepository.kt:195, 241`<br>`CloudSyncManager.kt:138, 217`<br>`AuthRepository.kt:719` | `newReleases`, `episodeUpdates`, `appUpdates`, `promotions`, `quietHoursEnabled` | `newReleases`, `episodeUpdates`, `appUpdates`, `promotions`, `quietHoursEnabled`, `quietHoursStart`, `quietHoursEnd`, `updatedAt` | Authenticated Owner (`auth.uid == userId`) |
| 13 | `/users/{userId}/fcmTokens/{installationId}` | `get`, `set` (merge), `update`, `delete` | `FcmTokenManager.kt:196, 212, 273, 296, 335`<br>`AuthRepository.kt:683` | `installationId`, `token`, `isActive`, `updatedAt` | `installationId`, `token`, `platform='android'`, `deviceModel`, `osVersion`, `appVersion`, `isActive`, `createdAt`, `updatedAt`, `lastSeenAt` | Authenticated Owner (`auth.uid == userId`) |
| 14 | `/public_profiles/{userId}` | `get`, `listen` | `SocialRepository.kt:89, 104` | `uid`, `username`, `displayName`, `photoUrl`, `bio`, `isOnline`, `isProfilePublic` | None (Single document fetch) | Authenticated User |
| 15 | `/public_profiles` (collection query) | `query.get()` | `AuthRepository.kt:620` (`isUsernameTaken`) | `.whereEqualTo("username", username)` | None (Query to check username collisions) | Authenticated User |
| 16 | `/public_profiles/{userId}` | `set` (merge) | `AuthRepository.kt:579`<br>`SocialRepository.kt:381` | None | `uid`, `username`, `firstName`, `lastName`, `photoUrl`, `isOnline=true`, `isProfilePublic`, `bio`, `updatedAt=serverTimestamp()` | Authenticated Owner (`auth.uid == userId`) |
| 17 | `/notifications` (collection query 1) | `query.get()`, `listen` | `NotificationRepository.kt:42, 198` | `.whereEqualTo("target", "all").limit(50)` | None (Read global announcements) | Authenticated User |
| 18 | `/notifications` (collection query 2) | `query.get()`, `listen` | `NotificationRepository.kt:43, 199` | `.whereEqualTo("target", "user").whereEqualTo("targetUid", resolvedUid).limit(50)` | None (Read personal notifications) | Authenticated User (`auth.uid == resolvedUid`) |
| 19 | `/notifications` (collection query 3) | `query.get()`, `listen` | `NotificationRepository.kt:54, 280` | `.whereEqualTo("target", "pro").limit(50)` | None (Read Pro member perks/updates) | Authenticated Pro User |
| 20 | `/config/app` | `get`, `listen` | `AppStartupManager.kt:48, 132, 175`<br>`AppUpdateManager.kt:377` | `maintenanceMode`, `minVersion`, `latestVersion`, `announcement` | None (Read-only configuration) | Authenticated User |
| 21 | `/config/search_order` | `get`, `listen` | `SearchOrderDataSource.kt:39`<br>`ManagedExtensionRealtimeSyncManager.kt:100` | `searchOrder` (list of extension IDs) | None (Read-only configuration) | Authenticated User |
| 22 | `/config/features` | `get`, `listen` | `EconomyConfigRepository.kt:51, 98` | `economyEnabled`, `rewardedAdsEnabled`, `dailyLoginEnabled` | None (Read-only configuration) | Authenticated User |
| 23 | `/config/economy` | `get`, `listen` | `EconomyConfigRepository.kt:70, 107` | `dailyLoginRewards`, `rewardedAdPoints`, `rewardedAdDailyCap`, `rewardedAdCooldownSeconds` | None (Read-only configuration) | Authenticated User |
| 24 | `/managed_extensions/{extensionId}` | `get` (all docs), `listen` | `FirebaseFirestoreManagedExtensionDataSource.kt:38`<br>`ManagedExtensionRealtimeSyncManager.kt:89` | Full catalog documents (`id`, `name`, `version`, `enabled`, `baseUrl`, `capabilities`) | None (Read-only scrapers catalog) | Authenticated User |
| 25 | `/app_updates/{updateId}` | `get` | `AppUpdateManager.kt:344, 348` | `versionCode`, `versionName`, `apkUrl`, `changelog`, `isMandatory` | None (Read-only update checks) | Authenticated User |
| 26 | `/reward_tasks/{taskId}` | `query.get()` | `PointsRepository.kt:87`<br>`TemporaryFirebaseEconomyRepository.kt:238` | `.whereEqualTo("isActive", true)` | None (Read active tasks) | Authenticated User |
| 27 | `/leaderboard/weekly_current` | `get`, `listen` | `PointsRepository.kt:114` | `rankings`, `updatedAt` | None (Read-only weekly board) | Authenticated User |
| 28 | `/leaderboard_history/{cycleId}` | `get` | `PointsRepository.kt:130` | `rankings`, `cycleId`, `endedAt` | None (Read-only archive) | Authenticated User |
| 29 | `/pro_requests/{requestId}` | `set` (create) | `PointsRepository.kt:269` | None | `requestId`, `userId`, `planId`, `durationDays`, `paymentMethod`, `transactionReference`, `screenshotUrl`, `status='PENDING'`, `createdAt=serverTimestamp()` | Authenticated User (`auth.uid == userId`) |
| 30 | `/pro_requests/{requestId}` | `query.get()` | `PointsRepository.kt:280` | `.whereEqualTo("userId", currentUid)` | None (User's own requests history) | Authenticated Owner (`auth.uid == userId`) |
| 31 | `/pro_requests/{requestId}` | `update` | `PointsRepository.kt:300` | None | `status='CANCELLED'`, `updatedAt=serverTimestamp()`, `cancelledAt=serverTimestamp()` | Authenticated Owner (`auth.uid == userId`) |
| 32 | `/reports/{reportId}` | `set` (create) | `ReportRepository.kt:28` | None | `reportId`, `userId`, `mediaId`, `mediaTitle`, `mediaType`, `issueType`, `description`, `deviceInfo`, `appVersion`, `status='PENDING'`, `createdAt=serverTimestamp()` | Authenticated User (`auth.uid == userId`) |
| 33 | `/reports/{reportId}` | `query.get()` | `ReportRepository.kt:58` | `.whereEqualTo("userId", currentUid)` | None (User's report history) | Authenticated Owner (`auth.uid == userId`) |
| 34 | `/support_conversations/{conversationId}` | `get`, `set` (create) | `SupportViewModel.kt:101, 124` | `conversationId`, `userId`, `status`, `lastMessage` | `conversationId`, `userId`, `userEmail`, `userName`, `status='open'`, `lastMessage`, `lastMessageTime`, `updatedAt`, `createdAt` | Authenticated User (`auth.uid == conversationId == userId`) |
| 35 | `/support_conversations/{conversationId}` | `update` | `SupportViewModel.kt:105` | None | `lastMessage`, `lastMessageTime`, `updatedAt=serverTimestamp()` | Authenticated Owner (`auth.uid == userId`) |
| 36 | `/support_conversations/{conversationId}/messages/{messageId}` | `set` (create), `listen` | `SupportViewModel.kt:52, 127` | `orderBy("timestamp", ASCENDING)` | `id`, `messageId`, `conversationId`, `senderId`, `senderRole='user'`, `text`, `timestamp`, `createdAt=serverTimestamp()` | Authenticated Owner (`senderId == userId`) |
| 37 | `/conversations/{conversationId}` | `get`, `set` (create) | `SocialRepository.kt:284, 293` | None | `id=uid1_uid2`, `participants=[uid1, uid2]`, `participantNames={...}` | Authenticated Participant |
| 38 | `/conversations` (collection query) | `query.get()`, `listen` | `SocialRepository.kt:135` | `.whereArrayContains("participants", user.uid).orderBy("lastMessageTime", DESCENDING)` | None (Read user's conversations) | Authenticated Participant |
| 39 | `/conversations/{conversationId}` | `transaction.update` | `SocialRepository.kt:314, 485, 508, 528` | None | `lastMessage`, `lastMessageTime` | Authenticated Participant |
| 40 | `/conversations/{conversationId}/messages/{messageId}` | `set` (create), `listen` | `SocialRepository.kt:234, 308, 478, 502, 525` | `orderBy("timestamp", ASCENDING)` | `id`, `senderId`, `text`, `timestamp`, `mediaUrl`, `isVoice`, `isEdited=false`, `isDeleted=false`, `deletedFor=[]`, `reactions={}` | Authenticated Participant (`senderId == auth.uid`) |
| 41 | `/conversations/{conversationId}/messages/{messageId}` | `update` | `SocialRepository.kt:415, 426, 439, 464` | None | Sender: `text`, `isEdited=true`, `isDeleted=true`<br>Either participant: `deletedFor`, `reactions` | Authenticated Participant |
| 42 | `/conversations/{conversationId}/readReceipts/{uid}` | `set` (merge) | `SocialRepository.kt:256` | None | `uid`, `lastReadAt=serverTimestamp()`, `updatedAt=serverTimestamp()` | Authenticated Participant (`auth.uid == uid`) |
| 43 | `/stories/{storyId}` | `listen` | `SocialRepository.kt:117` | `orderBy("timestamp", DESCENDING)` | None (View public stories feed) | Authenticated User |
| 44 | `/stories/{storyId}` | `set` (create), `delete` | `SocialRepository.kt:396, 406`<br>`AuthRepository.kt:723` | None | `id`, `userId`, `imageUrl`, `timestamp` | Authenticated Owner (`userId == auth.uid`) |

---

## 4. Candidate Compatibility Matrix Against Admin Candidate

| Firestore Path Pattern | Actual User App Operation | Rule Condition Required | Admin Candidate Stance | Security Risk if Broadened | Compatibility Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `/admins/{adminUid}` | `get`, `listen` (`UserSecurityManager.kt:59`) | `isAdmin() \|\| request.auth.uid == adminUid` | Permits read if admin or self | None; administrative records isolated | **VERIFIED COMPATIBLE** |
| `/admins/{adminUid}` | `write` (Creation / Promotion) | Admin app management | Admin candidate permits admin write & bootstrap | Subordinate admins must not grant super_admin | **NEEDS_EXTERNAL_COMPARISON** |
| `/public_profiles/{userId}` | `get`, `listen` (`SocialRepository.kt:89`) | `isAuthenticated()` & `isProfilePublic == true` | Permits read if public | None; public discovery projection only | **VERIFIED COMPATIBLE** |
| `/public_profiles` | `query.get()` (`AuthRepository.kt:620`) | Querying `.whereEqualTo("username", name)` | **DENIES (Query fails on isProfilePublic rule)** | Opening private profiles leaks status | **INCOMPATIBLE** |
| `/public_profiles/{userId}` | `set` (merge) (`AuthRepository.kt:579`) | `keys().hasOnly([...])` with 9 allowed fields | Missing in `Rules.md`, present in unified rules | Overwriting identity fields if unvalidated | **VERIFIED COMPATIBLE (when restored)** |
| `/users/{userId}` | `get`, `set`, `update` (`AuthRepository.kt`) | `isOwner(userId) && !modifyingSensitiveUserFields()` | Permits owner updates to non-sensitive fields | Tampering with subscription, bans, points | **VERIFIED COMPATIBLE** |
| `/users/{userId}/point_transactions` | Direct client write (`TemporaryFirebase...`) | Client transaction write | **DENIES (Admin candidate: isAdmin() only)** | Minting unearned points by modifying client | **BLOCKED (Requires Trusted Backend)** |
| `/users/{userId}/task_claims` | Direct client write (`TemporaryFirebase...`) | Client transaction write | **DENIES (Admin candidate: isAdmin() only)** | Forging task completion without verification | **BLOCKED (Requires Trusted Backend)** |
| `/users/{userId}/library`, `history`, `watched_episodes` | `get`, `set`, `delete`, `listen` | `isAdmin() \|\| isOwner(userId)` | Permits owner read/write | None; private personal media data | **VERIFIED COMPATIBLE** |
| `/users/{userId}/settings/notifications` | `get`, `set`, `listen` | `isOwner(userId) && !hasAny(['role', 'admin', ...])` | Permits owner read/write | Altering admin fields in settings | **VERIFIED COMPATIBLE** |
| `/users/{userId}/fcmTokens/{installationId}` | `get`, `set`, `update`, `delete` | Platform == 'android', timestamp validation | Permits owner create/update/delete | Cross-user token spoofing | **VERIFIED COMPATIBLE** |
| `/notifications` | 3 separate queries (`all`, `user`, `pro`) | Matching audience conditions | Permits audience-targeted reads | Information disclosure on targeted alerts | **VERIFIED COMPATIBLE** |
| `/support_conversations/{id}` | User message update (`lastMessage`, `time`) | Restricted diff keys (`lastMessage`, `time`, `updatedAt`) | Admin candidate omits diff restrictions | User overwriting ticket status or admin notes | **INCOMPATIBLE (Needs diff check)** |
| `/support_conversations/{id}/messages` | User message creation | SenderId == auth.uid & senderRole == 'user' | Permits message creation | Spoofing admin staff responses | **VERIFIED COMPATIBLE** |
| `/pro_requests/{id}` | User self-cancellation (`status -> CANCELLED`) | `status == 'CANCELLED'` and diff check | Admin candidate: `allow update: if isAdmin()` | User promoting own request to APPROVED | **INCOMPATIBLE (Requires user cancel rule)** |
| `/conversations/{id}/readReceipts/{uid}` | `markConversationAsRead` (`SocialRepository`) | Participant write with `request.time` | **DENIES (Missing in Rules.md / Admin cand)** | Cross-participant counter tampering | **INCOMPATIBLE (Must restore match block)** |
| `/managed_extensions` | Read scrapers catalog (`ManagedExtension...`) | `allow read: if isAuthenticated()` | Permits authenticated read | Unauthorized scraper execution if open | **VERIFIED COMPATIBLE** |
| `/extensions` (legacy) | Not read by User App (`F-017` compliance) | Deprecated compatibility rule | Unused by User App | Legacy path tampering | **VERIFIED COMPATIBLE** |
| `/comments` | Completely cancelled across both apps | Default deny (`allow read, write: if false;`) | Denies by default | Spam, moderation bypass, unmaintained path | **VERIFIED COMPATIBLE (Permanently Denied)** |

---

## 5. High-Risk Compatibility Findings & Blockers

### 5.1 Points & Task Claims Authority Blocker (BLOCKER)
- **Code Evidence:**  
  `TemporaryFirebaseEconomyRepository.kt:143-164`: Directly attempts `transaction.update(userRef, "pointsBalance", ...)` and `transaction.set(txRef, txData)`.  
  `PointsEarningRepository.kt:58-60`: `const val TEMPORARY_ECONOMY_MODE = false; var isTemporaryEconomyModeEnabled: Boolean = false`.
- **Root Cause & Rule Conflict:**  
  The Admin candidate intentionally restricts `point_transactions` and `task_claims` writes strictly to administrators (`allow write: if isAdmin();`). Furthermore, `users/{userId}` contains `pointsBalance` inside `modifyingSensitiveUserFields()`.
- **Finding:**  
  The CineStream User App **cannot** mint points, log transactions, or claim tasks directly from the Android client under secure Firestore rules. When `PointsEarningRepository.trustedBackendUrl` is null/empty and `backendHandler` is unset, all earning calls return `PointsEarningError.BACKEND_REQUIRED`.
- **Verdict: BLOCKER.** Direct client writes are blocked by design. A trusted backend (e.g. Cloudflare Worker or Firebase Cloud Function using Admin SDK) is an absolute prerequisite for production economic operations. No weakening of Firestore rules to permit client writes is allowed.

---

### 5.2 Public Profiles & Username Availability Conflict (INCOMPATIBILITY)
- **Code Evidence:**  
  `AuthRepository.kt:620-623`:
  ```kotlin
  val snapshot = db.collection("public_profiles")
      .whereEqualTo("username", username)
      .get()
      .await()
  ```
- **Rule Conflict:**  
  The Admin candidate permits reads only when `resource.data.isProfilePublic == true`.  
  Because Firestore security rules are **not query filters**, Firestore checks whether the query itself guarantees that every possible matching document satisfies the rule. Because `whereEqualTo("username", username)` lacks `.whereEqualTo("isProfilePublic", true)`, Firestore rejects the query with `PERMISSION_DENIED`.
- **Privacy Trade-off:**  
  If the client query is modified to include `.whereEqualTo("isProfilePublic", true)`, the query executes successfully, but usernames belonging to private accounts (`isProfilePublic == false`) will not be found, allowing new users to register duplicate usernames.
- **Architectural Resolution:**  
  Since `/public_profiles/{userId}` is already a non-sensitive projection containing only public metadata (`uid`, `username`, `firstName`, `lastName`, `photoUrl`, `isOnline`, `isProfilePublic`, `bio`, `updatedAt`) and completely excludes sensitive account, email, billing, or subscription data, granting `allow read: if isAuthenticated();` to `/public_profiles` satisfies the collection query while maintaining total security over `/users/{userId}`. Alternatively, a dedicated `/usernames/{username}` uniqueness index or backend registration service can be introduced.

---

### 5.3 Chat Read Receipts Subcollection Omission (INCOMPATIBILITY)
- **Code Evidence:**  
  `SocialRepository.kt:256-265`:
  ```kotlin
  db.collection("conversations").document(conversationId)
      .collection("readReceipts").document(user.uid)
      .set(
          mapOf(
              "uid" to user.uid,
              "lastReadAt" to FieldValue.serverTimestamp(),
              "updatedAt" to FieldValue.serverTimestamp()
          ),
          SetOptions.merge()
      )
  ```
- **Rule Conflict:**  
  The Admin candidate in `Rules.md` completely omitted `/conversations/{conversationId}/readReceipts/{uid}`.
- **Impact:**  
  `markConversationAsRead()` fails with `PERMISSION_DENIED`, completely breaking direct message read receipts in the User App.
- **Resolution:**  
  The dedicated subcollection rule block must be retained:
  ```javascript
  match /readReceipts/{uid} {
    allow read: if isAdmin() || (isAuthenticated() && request.auth.uid == uid &&
      request.auth.uid in get(/databases/$(database)/documents/conversations/$(conversationId)).data.participants);
    allow create, update: if isAuthenticated() && request.auth.uid == uid &&
      request.auth.uid in get(/databases/$(database)/documents/conversations/$(conversationId)).data.participants &&
      request.resource.data.keys().hasOnly(['uid', 'lastReadAt', 'updatedAt']) &&
      request.resource.data.uid == uid &&
      request.resource.data.lastReadAt == request.time &&
      request.resource.data.updatedAt == request.time;
    allow delete: if isAdmin() || (isAuthenticated() && request.auth.uid == uid);
  }
  ```

---

### 5.4 Support Ticket Privilege Escalation Vulnerability (INCOMPATIBILITY)
- **Code Evidence:**  
  `SupportViewModel.kt:105-111`: User updates only `lastMessage`, `lastMessageTime`, and `updatedAt`. User does not alter `status` or `unreadCounts`.
- **Rule Conflict:**  
  `Rules.md` omitted `diff().affectedKeys()` on support conversations, which allows any ticket owner to overwrite administrative fields (such as `status`, `assignedTo`, `priority`, or `adminNotes`).
- **Resolution:**  
  Strict field diff validation must be enforced on `/support_conversations/{conversationId}`:
  ```javascript
  request.resource.data.diff(resource.data).affectedKeys().hasOnly([
    'lastMessage', 'lastMessageTime', 'updatedAt', 'unreadCount', 'unreadCounts'
  ]) &&
  (!('status' in request.resource.data) || request.resource.data.status == resource.data.status)
  ```

---

### 5.5 Pro Request Self-Cancellation (INCOMPATIBILITY)
- **Code Evidence:**  
  `PointsRepository.kt:300`: User cancels their own pending subscription request (`status = "CANCELLED"`).
- **Rule Conflict:**  
  The Admin candidate restricted all updates to `isAdmin()`, preventing users from cancelling pending requests.
- **Resolution:**  
  Retain self-cancellation restricted to `status == 'CANCELLED'` and diff keys `['status', 'updatedAt', 'cancelledAt']`.

---

### 5.6 Audience Notifications Alignment (VERIFIED COMPATIBLE)
- **Code Evidence:**  
  `NotificationRepository.kt:40-55`: The User App does not execute unconstrained wildcard queries against `/notifications`. It issues three separate targeted queries (`target == 'all'`, `target == 'user'`, and `target == 'pro'`).
- **Subscription Fields Evaluated:**  
  `isProUser = userDoc.getBoolean("isPro") == true || userDoc.getBoolean("isPremium") == true || userDoc.getString("subscriptionTier") in listOf("pro", "PRO")`.
- **Verdict:**  
  The User App code matches the audience rules in `firestore.rules:453-469` exactly.

---

### 5.7 Extension Catalog Canonical Path (VERIFIED COMPATIBLE)
- **Code Evidence:**  
  `FirebaseFirestoreManagedExtensionDataSource.kt:14, 23, 36`: Specifies that `/managed_extensions` is the **sole canonical runtime source**. The legacy `/extensions` path is deprecated and never queried by the User App.

---

### 5.8 Complete Decommissioning of Comments (VERIFIED COMPATIBLE)
- **Code Evidence:**  
  No UI, repository, ViewModel, or Firestore collection calls exist for `/comments`. Only inactive boolean flags remain in local models (`canComment = true`).
- **Verdict:**  
  `/comments` is completely denied by default across both apps.

---

## 6. Proposed Safe Fixes and Provisional Compatibility Candidate

### 6.1 Artifact Specification: `PHASE_22.22-C_USER_COMPATIBILITY_CANDIDATE.rules`
- **File Name:** `PHASE_22.22-C_USER_COMPATIBILITY_CANDIDATE.rules`
- **Cryptographic SHA-256:** `0737d9618d8374f65ba42b33c966d1bef2936d70d573e616cda49eb32ee8676d`
- **Byte Size:** `29,755` bytes
- **Line Count:** `798` lines
- **Role:** Provisional candidate derived from the canonical User App ruleset, preserving Admin candidate hardening (points/task claims write restriction to admins) while restoring User App compatibility requirements (`public_profiles`, `readReceipts`, support diff protection, pro request self-cancellation).

### 6.2 Granular Changes from Supplied Admin Candidate:
1. **Restored `/public_profiles/{userId}`:** Allows authenticated queries (`allow read: if isAuthenticated();`) to support username lookups and profile discovery, with strict 9-field schema validation on write.
2. **Restored `/conversations/{conversationId}/readReceipts/{uid}`:** Enables chat read status updates with timestamp verification (`request.time`).
3. **Hardened Support Conversation Updates:** Implemented `diff().affectedKeys()` check preventing users from tampering with ticket status or admin fields.
4. **Restored Pro Request Cancellation:** Permits users to update pending requests to `CANCELLED`.
5. **Maintained Strict Admin Ledger Protection:** Maintained `allow write: if isAdmin();` on `/point_transactions` and `/task_claims`, preventing unauthorized client minting.

---

## 7. Rules Tests & Verification Execution Status

### 7.1 Rules Emulator Tests Status: **`EMULATOR_TESTS_NOT_RUN`**
- **Inspection Finding:**  
  The root `package.json` specifies `"test:rules": "node --test tests/rules/firestore_security_regression.test.js"`.  
  However, filesystem inspection revealed that the `tests/` directory does not exist on disk, and `@firebase/rules-unit-testing` is not installed.
- **Distinction from Android Unit Tests:**  
  The Android unit tests (`GoogleCredentialParserTest.kt` and `AccountIdentityRecoveryTest.kt`) executed and passed via Gradle, but they test client-side credential parsing and account recovery logic. **They do not evaluate Firestore security rules.**
- **Required Emulator Test Commands for Later Phase:**
  ```bash
  npm install --save-dev @firebase/rules-unit-testing
  firebase emulators:exec --only firestore "npm run test:rules"
  ```
- **Test Specifications for Candidate Verification:**
  - Test Case 1: Unauthenticated user read on `/users/{uid}` -> `PERMISSION_DENIED`
  - Test Case 2: Authenticated user read on `/users/{otherUid}` -> `PERMISSION_DENIED`
  - Test Case 3: Authenticated user write to `/users/{uid}/point_transactions/{txId}` -> `PERMISSION_DENIED`
  - Test Case 4: Authenticated user query `/public_profiles` with username filter -> `SUCCESS`
  - Test Case 5: Authenticated participant write to `/conversations/{id}/readReceipts/{uid}` with valid server timestamp -> `SUCCESS`
  - Test Case 6: User update on `/support_conversations/{id}` attempting to alter `"status": "closed"` -> `PERMISSION_DENIED`
  - Test Case 7: Any write to `/comments/{commentId}` -> `PERMISSION_DENIED`

---

## 8. Identity & Package Change Control Audit

The official Firebase registration file (`google-services_9bdb880b.json`) was compared against local project files:

| Config Source & File | Inspected Local Value | Canonical Verified Value | Security Status / Tag |
| :--- | :--- | :--- | :--- |
| `app/build.gradle.kts:20` | `applicationId = "com.aistudio.cinestream.nirydy"` | `applicationId = "com.aistudio.cinestream.xyzabc"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `app/google-services.json:3,4` | `"project_id": "remixed-project-id"` / `"1234567890"` | `"project_id": "cinestream-sulo"` / `"979447256418"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `app/google-services.json:10,12` | `"mobilesdk_app_id": "1:1234567890:android:remixedappid"` / `"com.aistudio.cinestream.nirydy"` | `"mobilesdk_app_id": "1:979447256418:android:037a9b3a1393e550a9883b"` / `"com.aistudio.cinestream.xyzabc"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `firebase-applet-config.json:2,6` | `"projectId": "ai-studio-applet-webapp-71606"` / `"406285499532"` | `"projectId": "cinestream-sulo"` / `"979447256418"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |

*Compliance Statement:* No modifications were made to `app/build.gradle.kts`, `app/google-services.json`, or `firebase-applet-config.json`. All mismatches are logged with `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL`.

---

## 9. Unresolved Shared Contracts (`NEEDS_EXTERNAL_COMPARISON`)

The following items cannot be decided unilaterally in the User App session and must be reviewed against the Admin Dashboard session:

1. **`NEEDS_EXTERNAL_COMPARISON` — Administrative Role Hierarchy in `/admins/{adminUid}`:**  
   Determine whether subordinate admins can update other admin records or if writes are restricted to `isOwnerAccount()`.
2. **`NEEDS_EXTERNAL_COMPARISON` — Username Uniqueness Architecture:**  
   Confirm whether Admin Dashboard accepts `allow read: if isAuthenticated();` on `/public_profiles` or requires a dedicated `/usernames` index or backend Cloud Function.
3. **`NEEDS_EXTERNAL_COMPARISON` — Support Conversation Status Lifecycle:**  
   Verify the exact ticket status values (`open`, `pending`, `resolved`, `closed`) and assignment fields managed by Admin staff in `/support_conversations`.
4. **`NEEDS_EXTERNAL_COMPARISON` — Trusted Backend Endpoint Integration:**  
   Align the Cloudflare Worker or Cloud Function endpoints for processing points, claims, and subscriptions.

---

## 10. Strict Safety & Governance Confirmations

1. **No Deployment:** `firebase deploy` was **NOT** executed. No network calls to Firebase deployment endpoints occurred.
2. **No Base Rules Overwrite:** The base `firestore.rules` remains unaltered with SHA-256 fingerprint `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef`.
3. **No Live Data Tampering:** No Firestore documents, collections, or indexes were created or mutated.
4. **No Build Configuration Changes:** Package names, App IDs, and OAuth configurations were preserved.
5. **Comments Remain Denied:** All access to `/comments` is strictly forbidden.
