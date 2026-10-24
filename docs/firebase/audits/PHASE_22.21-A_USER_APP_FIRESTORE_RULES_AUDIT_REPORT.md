# Phase 22.21-A: CineStream User App — Comprehensive Firestore Security Rules, Data Contract, and Client Access Audit Report

**Date of Audit:** October 20, 2026  
**Auditor Role:** Senior Android & Firebase Security Engineer  
**Audit Type:** Static Analysis, Architecture Forensic, Data Contract Extraction, Security & Traceability Audit  
**Execution Mode:** STRICT READ-ONLY (No code, rules, or database modification)  
**Report File:** `PHASE_22.21-A_USER_APP_FIRESTORE_RULES_AUDIT_REPORT.md`  
**Companion Contract File:** `PHASE_22.21-A_USER_APP_FIRESTORE_CONTRACT.md`  

---

## 1. Executive Summary

A comprehensive, evidence-based security audit of Google Cloud Firestore rules, client-side data contracts, and client-to-rules access alignment was conducted across the CineStream User App Android codebase. The audit inspected all Kotlin source files, data sources, repositories, ViewModels, services, deployment configurations, and the local unified ruleset (`firestore.rules`).

### Key Findings Summary:
1. **Security Architecture & Rules Posture:** The local unified ruleset (`firestore.rules`, 813 lines) adopts an explicit deny-by-default stance (`match /{document=**} { allow read, write: if false; }`), isolates administrative functions (`isAdmin()`), forbids client mutation of sensitive user fields (`modifyingSensitiveUserFields()`), and strictly restricts subcollections such as `/users/{userId}/fcmTokens`, `/support_conversations`, `/conversations`, and `/pro_requests`.
2. **Critical Economic / Subscription Rule Mismatch (Defensive Inconsistency):** In `TemporaryFirebaseEconomyRepository.kt`, client-side transactions attempt to directly mutate `pointsBalance`, `totalPointsEarned`, and `subscriptionTier`. However, `firestore.rules` (lines 56–118, line 250) explicitly rejects non-admin mutations of these fields via `modifyingSensitiveUserFields()`. While the comments in `firestore.rules` (lines 243–246) refer to a "TEMPORARY ECONOMY MODE: Owner may update normal profile/economy/subscription fields", the actual security rule code blocks these mutations. In the client, `PointsEarningRepository.isTemporaryEconomyModeEnabled` defaults to `false` and requires a trusted backend (`BACKEND_REQUIRED`). If temporary mode were toggled in the client without rule changes, all client earning and redemption transactions would fail with `PERMISSION_DENIED`. This is a fail-closed protection, but represents an architectural and code-contract divergence.
3. **Active Query Failure in Account Creation / Username Check:** In `AuthRepository.kt:620`, the method `isUsernameTaken()` executes `db.collection("public_profiles").whereEqualTo("username", username).get()`. In `firestore.rules:203`, reading `/public_profiles/{userId}` requires `resource.data.isProfilePublic == true`. Because Firestore rules are not filters, queries lacking `.whereEqualTo("isProfilePublic", true)` are immediately rejected by the Firestore engine with `PERMISSION_DENIED`. Consequently, username availability checks fail at runtime.
4. **Cancelled Comments Feature Isolation:** The comments feature is verified to be completely cancelled and inactive in the User App. No Firestore rules grant access to `/comments`. Any attempt to access `/comments` is blocked by the final catch-all deny rule. In the Kotlin codebase, no repositories, ViewModels, or UI components for comments exist; only dormant permission flags (`canComment`) remain in model classes.
5. **Multi-Project Environment Divergence:** Local workspace files contain diverging project configurations:
   - CI Workflow (`.github/workflows/build.yml:47`): Decodes `google-services.json` targeting `cinestream-sulo` (project number `979447256418`).
   - Unified Rules Header (`firestore.rules:9`): Annotated for `cinestream-sulo`.
   - Local App Config (`firebase-applet-config.json`): Targets `ai-studio-applet-webapp-71606` (number `406285499532`).
   - Local Android Config (`app/google-services.json`): Contains placeholder project `remixed-project-id` (number `1234567890`).
   - Cloudflare Worker (`backend/wrangler.toml:13`): Configured for `ai-studio-applet-webapp-e138b`.
   Live production rules deployment status cannot be established from this local session (`LOCAL_CONFIGURATION_ONLY`).

---

## 2. Audit Scope and Limitations

### In-Scope:
- All Kotlin files in `app/src/main/java/` (Repositories, Data Sources, ViewModels, Services, Sync Managers, Models).
- Local rules file `firestore.rules`.
- Local Firebase configuration files: `firebase.json`, `firebase-applet-config.json`, `app/google-services.json`.
- Cloudflare Worker trusted backend in `backend/`.
- CI/CD workflows in `.github/workflows/build.yml`.
- Unit tests in `app/src/test/`.

### Limitations & Strict Boundaries:
- **No Direct Admin Dashboard Access:** The CineStream Admin Dashboard codebase was not part of this workspace session. Any verification depending on the Admin Dashboard is tagged `NEEDS_EXTERNAL_COMPARISON`.
- **No Live Console Access:** The live Firebase production console was not queried directly; live production rules status is categorized as `LOCAL_CONFIGURATION_ONLY`.
- **Strict Read-Only Enforcement:** No files were modified, no rules were deployed, and no write requests were dispatched to any live Firebase environment.

---

## 3. Repository and File Inventory

The following files were inspected as primary audit evidence:

| File Path | Description / Component |
|-----------|-------------------------|
| `/firestore.rules` | Unified Firestore Security Ruleset (canonical rules source) |
| `/firebase.json` | Firebase CLI configuration (points to `firestore.rules`, emulator port 8085) |
| `/firebase-applet-config.json` | Web Applet Firebase configuration |
| `/app/google-services.json` | Android client Google Services configuration |
| `/.github/workflows/build.yml` | CI build workflow containing base64 production `google-services.json` |
| `/backend/wrangler.toml` | Cloudflare Worker configuration for trusted backend |
| `/backend/src/index.ts` | Trusted backend economic endpoints & in-memory proxy store |
| `/app/src/main/java/com/example/data/repository/AuthRepository.kt` | User profile, authentication, account deletion, username lookup |
| `/app/src/main/java/com/example/data/repository/UserSecurityManager.kt` | Entitlements, real-time security restrictions, admin role listener |
| `/app/src/main/java/com/example/data/repository/PointsRepository.kt` | Point transactions listener, reward tasks, leaderboard, pro requests |
| `/app/src/main/java/com/example/data/repository/PointsEarningRepository.kt` | Earning dispatcher, wallet flow, task claims, daily login, ads |
| `/app/src/main/java/com/example/data/repository/TemporaryFirebaseEconomyRepository.kt` | Non-trusted client-side economy transactions fallback |
| `/app/src/main/java/com/example/data/repository/EconomyConfigRepository.kt` | `/config/features` and `/config/economy` listeners & parsers |
| `/app/src/main/java/com/example/data/repository/LibraryRepository.kt` | User personal media library CRUD against `/users/{uid}/library` |
| `/app/src/main/java/com/example/data/repository/HistoryRepository.kt` | Watch progress and history CRUD against `/users/{uid}/history` |
| `/app/src/main/java/com/example/data/repository/WatchedEpisodeRepository.kt` | Watched episode flags CRUD against `/users/{uid}/watched_episodes` |
| `/app/src/main/java/com/example/data/repository/NotificationRepository.kt` | Querying & listening to scoped notifications (`target` all/user/pro) |
| `/app/src/main/java/com/example/data/repository/NotificationPreferencesRepository.kt` | Notification preferences sync with `/users/{uid}/settings/notifications` |
| `/app/src/main/java/com/example/data/repository/SocialRepository.kt` | Direct messaging, conversations, stories, public profiles, read receipts |
| `/app/src/main/java/com/example/data/repository/ReportRepository.kt` | Content & stream bug report creation against `/reports` |
| `/app/src/main/java/com/example/data/repository/AppStartupManager.kt` | Maintenance and app configuration check from `/config/app` |
| `/app/src/main/java/com/example/data/repository/AppUpdateManager.kt` | OTA update check against `/app_updates` and `/config/app` |
| `/app/src/main/java/com/example/data/sync/CloudSyncManager.kt` | Multi-collection bidirectional real-time sync & guest data migration |
| `/app/src/main/java/com/example/data/notification/FcmTokenManager.kt` | Multi-device FCM token lifecycle against `/users/{uid}/fcmTokens` |
| `/app/src/main/java/com/example/extension/managed/repository/FirebaseFirestoreManagedExtensionDataSource.kt` | Reading active scrapers catalog from `/managed_extensions` |
| `/app/src/main/java/com/example/extension/managed/repository/ManagedExtensionRealtimeSyncManager.kt` | Real-time listener on `/managed_extensions` and `/config/search_order` |
| `/app/src/main/java/com/example/extension/managed/searchorder/SearchOrderDataSource.kt` | Reading extension search order from `/config/search_order` |
| `/app/src/main/java/com/example/ui/screens/profile/SupportViewModel.kt` | Support ticket conversations & messages against `/support_conversations` |

---

## 4. Firestore Rules Files and SHA-256 Hashes

Exact cryptographic SHA-256 hashes calculated directly in the project environment:

| File Path | SHA-256 Hash | File Status & Linkage |
|-----------|--------------|-----------------------|
| `/firestore.rules` | `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef` | Canonical unified rules file referenced by `firebase.json:3`. |
| `/firebase.json` | `60df99bbc407687681d24b8de758370540ccdd211af073e39835fc24d4321afc` | Points directly to `firestore.rules`. Configures emulator on port 8085. |
| `/firebase-applet-config.json` | `83ddc3299482209ed4e7d1a28677510cc2cc7a496377c748d0912254a1124a47` | Contains `projectId`: `ai-studio-applet-webapp-71606`. |
| `/app/google-services.json` | `b7ee2899a48517c2a3d25eed4e87e13bd3ae0a24504c56817a415c0a0f64ef1f` | Local placeholder file containing `remixed-project-id`. |

*Note on alternate rules:* A filesystem-wide search revealed no duplicate or alternate rules files (such as `firestore.test.rules` or rules backups). Only `/firestore.rules` exists in the repository.

---

## 5. Firebase Project and Deployment Configuration

| Config Source | Project ID Found | Project Number | Target Package / App ID | Evidence |
|---------------|------------------|----------------|-------------------------|----------|
| `firestore.rules` header | `cinestream-sulo` | N/A | Unified Users + Admin rules | `firestore.rules:9` |
| CI Build Secret (`.github/workflows/build.yml`) | `cinestream-sulo` | `979447256418` | `com.aistudio.cinestream.xyzabc` / `com.aistudio.cinestreamadmin.adcxz` | Line 47 (base64 decoded payload) |
| `app/build.gradle.kts` | N/A | N/A | `com.aistudio.cinestream.nirydy` | Line 16 |
| `app/google-services.json` | `remixed-project-id` | `1234567890` | `com.aistudio.cinestream.nirydy` | Lines 3, 4, 10 |
| `firebase-applet-config.json` | `ai-studio-applet-webapp-71606` | `406285499532` | Web Client App ID | Lines 2, 6 |
| `backend/wrangler.toml` | `ai-studio-applet-webapp-e138b` | N/A | Cloudflare Worker target | Line 13 |

### Status Classification: `LOCAL_CONFIGURATION_ONLY`
Because the local environment has not connected to Firebase CLI using authenticated production tokens, it is impossible to verify whether `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef` is the active ruleset in production on project `cinestream-sulo`. It is proven that `firebase.json` binds directly to `firestore.rules`.

---

## 6. All Discovered Firestore Collections and Path Patterns

A comprehensive code audit revealed the following 27 distinct Firestore paths actively referenced in the User App:

```
/admins/{adminUid}
/public_profiles/{userId}
/users/{userId}
/users/{userId}/point_transactions/{txId}
/users/{userId}/task_claims/{taskId}
/users/{userId}/library/{itemId}
/users/{userId}/history/{itemId}
/users/{userId}/watched_episodes/{episodeId}
/users/{userId}/settings/notifications
/users/{userId}/settings/{document=**}
/users/{userId}/fcmTokens/{installationId}
/config/app
/config/search_order
/config/features
/config/economy
/config/{document=**}
/managed_extensions/{extensionId}
/managed_extensions/{extensionId}/{subcollection}/{docId}
/app_updates/{updateId}
/notifications/{notificationId}
/reward_tasks/{taskId}
/leaderboard/weekly_current
/leaderboard_history/{cycleId}
/pro_requests/{requestId}
/reports/{reportId}
/support_conversations/{conversationId}
/support_conversations/{conversationId}/messages/{messageId}
/conversations/{conversationId}
/conversations/{conversationId}/messages/{messageId}
/conversations/{conversationId}/readReceipts/{uid}
/stories/{storyId}
/auditLogs/{logId}
/audit_logs/{document=**}
/{document=**}
```

---

## 7. Detailed Firestore Data Contract

*(See the dedicated companion document `/PHASE_22.21-A_USER_APP_FIRESTORE_CONTRACT.md` for complete field-by-field models, JSON schema structures, and serialization helpers).*

---

## 8. Fields, Types, Requiredness, and Mutability

### 8.1 Summary of Sensitive Fields Protected in `/users/{userId}`

The helper function `modifyingSensitiveUserFields()` in `firestore.rules:56-118` strictly forbids the client from updating 45 sensitive fields:

1. **Identity & Authority:** `role`, `admin`, `isAdmin`, `uid`, `id`, `createdAt`.
2. **Subscription Tier & Entitlements:** `isPremium`, `isPro`, `proExpiresAt`, `proPlan`, `plan`, `subscriptionTier`, `planId`, `durationDays`, `subscriptionStatus`, `subscriptionSource`, `subscriptionReferenceId`, `subscriptionStartedAt`, `subscriptionExpiresAt`.
3. **Economy & Ledger Balances:** `pointsBalance`, `totalPointsEarned`, `totalPointsSpent`, `pointsEarnedTotal`, `pointsSpentTotal`.
4. **Account Lifecycle & Administrative Bans:** `isActive`, `isBanned`, `banReason`, `banExpiresAt`.
5. **Feature Ban Restrictions:** `canWatch`, `canDownload`, `canChat`, `canStory`, `canP2P`, `canComment`, `canUpload`, `canRequest`, `watchBan`, `downloadBan`, `chatBan`, `storyBan`, `p2pBan`.
6. **Technical & Device Limits:** `deviceLimit`, `maxDevices`, `allowedQuality`, `downloadLimit`, `offlineDaysOverride`, `forcedAdsOverride`.

### 8.2 Client-Mutable Fields in `/users/{userId}`

The client is permitted to update only non-sensitive personal profile and activity fields:
- `displayName` (string)
- `firstName` (string)
- `lastName` (string)
- `username` (string)
- `photoUrl` (string)
- `bio` (string)
- `isProfilePublic` (boolean)
- `lastActiveAt` (integer / number)
- `lastLoginAt` / `lastLoginTimestamp` (timestamp / integer)
- `updatedAt` (timestamp)
- `appVersion` (string)
- `deletedAt` (timestamp, during account anonymization fallback)

---

## 9. Complete Client Operation Inventory

| Operation ID | Firestore Path | Method Call | Calling Location |
|--------------|----------------|-------------|------------------|
| OP-ADM-01 | `/admins/{uid}` | `.addSnapshotListener` | `UserSecurityManager.kt:58` |
| OP-ADM-02 | `/admins/{uid}` | `.get(Source.SERVER).await()` | `UserSecurityManager.kt:330` |
| OP-PUB-01 | `/public_profiles/{uid}` | `.get().await()` | `SocialRepository.kt:90` |
| OP-PUB-02 | `/public_profiles/{uid}` | `.addSnapshotListener` | `SocialRepository.kt:104` |
| OP-PUB-03 | `/public_profiles` | `.whereEqualTo("isProfilePublic", true).startAt(...).endAt(...)` | `SocialRepository.kt:336` |
| OP-PUB-04 | `/public_profiles/{uid}` | `.set(publicMap, SetOptions.merge()).await()` | `AuthRepository.kt:591`, `SocialRepository.kt:381` |
| OP-PUB-05 | `/public_profiles` | `.whereEqualTo("username", username).get().await()` | `AuthRepository.kt:620` |
| OP-PUB-06 | `/public_profiles/{uid}` | `.delete().await()` | `AuthRepository.kt:731` |
| OP-USR-01 | `/users/{uid}` | `.get().await()` | `AuthRepository.kt:406`, `PointsEarningRepository.kt:576` |
| OP-USR-02 | `/users/{uid}` | `.update(mapOf("lastActiveAt" to ..., "appVersion" to ...))` | `AuthRepository.kt:415` |
| OP-USR-03 | `/users/{uid}` | `.set(userMap, SetOptions.merge()).await()` | `AuthRepository.kt:575` |
| OP-USR-04 | `/users/{uid}` | `.delete().await()` (Fails -> falls back to anonymization) | `AuthRepository.kt:746` |
| OP-USR-05 | `/users/{uid}` | `.update(mapOf("displayName" to "Deleted User", ...))` | `AuthRepository.kt:751` |
| OP-USR-06 | `/users/{uid}` | `.addSnapshotListener` | `UserSecurityManager.kt:73`, `PointsEarningRepository.kt:78` |
| OP-USR-07 | `/users/{uid}` | `.get(Source.SERVER).await()` | `UserSecurityManager.kt:325` |
| OP-LIB-01 | `/users/{uid}/library` | `.addSnapshotListener` | `CloudSyncManager.kt:60` |
| OP-LIB-02 | `/users/{uid}/library` | `.get().await()` | `CloudSyncManager.kt:169`, `AuthRepository.kt:692` |
| OP-LIB-03 | `/users/{uid}/library/{id}` | `.set(map, SetOptions.merge())` | `LibraryRepository.kt:87` |
| OP-LIB-04 | `/users/{uid}/library/{id}` | `.delete()` | `LibraryRepository.kt:104, 107` |
| OP-HIS-01 | `/users/{uid}/history` | `.addSnapshotListener` | `CloudSyncManager.kt:100` |
| OP-HIS-02 | `/users/{uid}/history` | `.get().await()` | `CloudSyncManager.kt:199, 258`, `AuthRepository.kt:701` |
| OP-HIS-03 | `/users/{uid}/history/{id}` | `.set(item, SetOptions.merge())` | `HistoryRepository.kt:96, 189`, `CloudSyncManager.kt:290` |
| OP-HIS-04 | `/users/{uid}/history/{id}` | `.delete()` | `HistoryRepository.kt:129, 131` |
| OP-WAT-01 | `/users/{uid}/watched_episodes` | `.addSnapshotListener` | `CloudSyncManager.kt:116` |
| OP-WAT-02 | `/users/{uid}/watched_episodes` | `.get().await()` | `CloudSyncManager.kt:204, 296`, `AuthRepository.kt:710` |
| OP-WAT-03 | `/users/{uid}/watched_episodes/{id}` | `.set(episode, SetOptions.merge())` | `WatchedEpisodeRepository.kt:23`, `CloudSyncManager.kt:302` |
| OP-WAT-04 | `/users/{uid}/watched_episodes/{id}` | `.delete()` | `WatchedEpisodeRepository.kt:35` |
| OP-FCM-01 | `/users/{uid}/fcmTokens/{instId}` | `.get().await()` | `FcmTokenManager.kt:200` |
| OP-FCM-02 | `/users/{uid}/fcmTokens/{instId}` | `.set(payload, SetOptions.merge()).await()` | `FcmTokenManager.kt:202` |
| OP-FCM-03 | `/users/{uid}/fcmTokens/{instId}` | `.delete()` | `FcmTokenManager.kt:216, 277, 300` |
| OP-FCM-04 | `/users/{uid}/fcmTokens/{instId}` | `.update(mapOf("isActive" to false, ...)).await()` | `FcmTokenManager.kt:339` |
| OP-FCM-05 | `/users/{uid}/fcmTokens` | `.get().await()` | `AuthRepository.kt:683` |
| OP-SET-01 | `/users/{uid}/settings/notifications`| `.get().await()` | `NotificationPreferencesRepository.kt:200` |
| OP-SET-02 | `/users/{uid}/settings/notifications`| `.set(map, SetOptions.merge())` | `NotificationPreferencesRepository.kt:209, 243` |
| OP-SET-03 | `/users/{uid}/settings/notifications`| `.addSnapshotListener` | `CloudSyncManager.kt:138` |
| OP-SET-04 | `/users/{uid}/settings/notifications`| `.delete().await()` | `AuthRepository.kt:719` |
| OP-CFG-01 | `/config/app` | `.get(Source.SERVER).await()` | `AppStartupManager.kt:53, 135`, `AppUpdateManager.kt:381` |
| OP-CFG-02 | `/config/app` | `.addSnapshotListener` | `AppStartupManager.kt:177` |
| OP-CFG-03 | `/config/search_order` | `.get().await()` | `SearchOrderDataSource.kt:39` |
| OP-CFG-04 | `/config/search_order` | `.addSnapshotListener` | `ManagedExtensionRealtimeSyncManager.kt:100` |
| OP-CFG-05 | `/config/features` | `.addSnapshotListener` | `EconomyConfigRepository.kt:51` |
| OP-CFG-06 | `/config/features` | `.get().await()` | `EconomyConfigRepository.kt:98` |
| OP-CFG-07 | `/config/economy` | `.addSnapshotListener` | `EconomyConfigRepository.kt:70` |
| OP-CFG-08 | `/config/economy` | `.get().await()` | `EconomyConfigRepository.kt:107` |
| OP-EXT-01 | `/managed_extensions` | `.get().await()` | `FirebaseFirestoreManagedExtensionDataSource.kt:38` |
| OP-EXT-02 | `/managed_extensions` | `.addSnapshotListener` | `ManagedExtensionRealtimeSyncManager.kt:89` |
| OP-UPD-01 | `/app_updates` | `.get(Source.SERVER).await()` | `AppUpdateManager.kt:345` |
| OP-NTF-01 | `/notifications` | `.whereEqualTo("target", "all").limit(50)` | `NotificationRepository.kt:42, 198` |
| OP-NTF-02 | `/notifications` | `.whereEqualTo("target", "user").whereEqualTo("targetUid", uid)` | `NotificationRepository.kt:43, 199` |
| OP-NTF-03 | `/notifications` | `.whereEqualTo("target", "pro").limit(50)` | `NotificationRepository.kt:54, 293` |
| OP-PRQ-01 | `/pro_requests/{reqId}` | `.set(payload).await()` | `PointsRepository.kt:269` |
| OP-PRQ-02 | `/pro_requests` | `.whereEqualTo("userId", uid).get().await()` | `PointsRepository.kt:280` |
| OP-PRQ-03 | `/pro_requests/{reqId}` | `.update(mapOf("status" to "CANCELLED", ...)).await()` | `PointsRepository.kt:317` |
| OP-REP-01 | `/reports/{repId}` | `.set(reportMap).await()` | `ReportRepository.kt:48` |
| OP-REP-02 | `/reports` | `.whereEqualTo("userId", uid).get().await()` | `ReportRepository.kt:58` |
| OP-SUP-01 | `/support_conversations/{uid}` | `.get().await()` | `SupportViewModel.kt:101` |
| OP-SUP-02 | `/support_conversations/{uid}` | `.set(convData).await()` | `SupportViewModel.kt:124` |
| OP-SUP-03 | `/support_conversations/{uid}` | `.update(mapOf("lastMessage" to ..., ...)).await()` | `SupportViewModel.kt:105` |
| OP-SUP-04 | `/support_conversations/{uid}/messages`| `.addSnapshotListener` | `SupportViewModel.kt:52` |
| OP-SUP-05 | `/support_conversations/{uid}/messages/{id}`| `.set(msgData).await()` | `SupportViewModel.kt:138` |
| OP-SOC-01 | `/conversations` | `.whereArrayContains("participants", uid).orderBy("lastMessageTime")` | `SocialRepository.kt:135` |
| OP-SOC-02 | `/conversations/{id}` | `.get().await()` | `SocialRepository.kt:155, 285` |
| OP-SOC-03 | `/conversations/{id}` | `.set(conv).await()` | `SocialRepository.kt:293` |
| OP-SOC-04 | `/conversations/{id}` | `transaction.update(docRef, "lastMessage", ..., "lastMessageTime", ...)` | `SocialRepository.kt:316, 485, 508, 532` |
| OP-SOC-05 | `/conversations/{id}/messages`| `.addSnapshotListener` | `SocialRepository.kt:234` |
| OP-SOC-06 | `/conversations/{id}/messages/{id}`| `.set(msg)` | `SocialRepository.kt:308, 480, 504, 527` |
| OP-SOC-07 | `/conversations/{id}/messages/{id}`| `.update("text", ..., "isEdited", true)` | `SocialRepository.kt:415` |
| OP-SOC-08 | `/conversations/{id}/messages/{id}`| `.update("isDeleted", true)` | `SocialRepository.kt:426` |
| OP-SOC-09 | `/conversations/{id}/messages/{id}`| `transaction.update(msgRef, "deletedFor", ...)` | `SocialRepository.kt:439` |
| OP-SOC-10 | `/conversations/{id}/messages/{id}`| `transaction.update(msgRef, "reactions", ...)` | `SocialRepository.kt:464` |
| OP-SOC-11 | `/conversations/{id}/readReceipts/{uid}`| `.set(map, SetOptions.merge())` | `SocialRepository.kt:256` |
| OP-STO-01 | `/stories` | `.orderBy("timestamp", DESC).addSnapshotListener` | `SocialRepository.kt:117` |
| OP-STO-02 | `/stories/{id}` | `.set(story).await()` | `SocialRepository.kt:397` |
| OP-STO-03 | `/stories/{id}` | `.delete().await()` | `SocialRepository.kt:406` |
| OP-STO-04 | `/stories` | `.whereEqualTo("userId", uid).get().await()` | `AuthRepository.kt:723` |

---

## 10. Rules Coverage and Authorization Analysis

### 10.1 Administrative Authority (`isAdmin()`)
In `firestore.rules:41-53`:
```javascript
function isAdmin() {
  return isAuthenticated() && (
    (
      request.auth.token.email != null &&
      request.auth.token.email_verified == true &&
      request.auth.token.email.matches('(?i)sulopros01@gmail.com')
    ) ||
    (
      exists(/databases/$(database)/documents/admins/$(request.auth.uid)) &&
      get(/databases/$(database)/documents/admins/$(request.auth.uid)).data.enabled == true
    )
  );
}
```
- **Owner Account Bootstrap:** Verified email `sulopros01@gmail.com` acts as super-admin.
- **Admin Delegation:** Document in `/admins/{uid}` with `enabled == true`.
- **Delegation Protection:** Only the verified owner account can create, modify, or delete documents in `/admins` (`allow write: if isOwnerAccount();`). Enabled admins cannot self-replicate or promote other users.

### 10.2 User Isolation (`isOwner(uid)`)
In `firestore.rules:28-30`:
```javascript
function isOwner(uid) {
  return isAuthenticated() && request.auth.uid == uid;
}
```
All personal user documents, subcollections (`library`, `history`, `watched_episodes`, `settings`, `fcmTokens`, `point_transactions`, `task_claims`) strictly enforce `isOwner(userId)` on reads and mutations.

---

## 11. Path-by-Path Read/Write Permissions Matrix

| Path Pattern | Read Condition | Create Condition | Update Condition | Delete Condition |
|--------------|----------------|------------------|------------------|------------------|
| `/admins/{adminUid}` | `isAdmin() \|\| request.auth.uid == adminUid` | `isOwnerAccount()` | `isOwnerAccount()` | `isOwnerAccount()` |
| `/public_profiles/{userId}` | `isAuthenticated() && isProfilePublic == true` | `request.auth.uid == userId && hasOnly([...]) && types` | `request.auth.uid == userId && hasOnly([...]) && types` | `isAdmin() \|\| request.auth.uid == userId` |
| `/users/{userId}` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| hasSafeUserCreationDefaults(userId)` | `isAdmin() \|\| (isOwner(userId) && !modifyingSensitiveUserFields())` | `isAdmin()` |
| `/users/{userId}/point_transactions/{txId}` | `isAdmin() \|\| isOwner(userId)` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/users/{userId}/task_claims/{taskId}` | `isAdmin() \|\| isOwner(userId)` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/users/{userId}/library/{itemId}` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` |
| `/users/{userId}/history/{itemId}` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` |
| `/users/{userId}/watched_episodes/{epId}` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| isOwner(userId)` |
| `/users/{userId}/settings/notifications` | `isAdmin() \|\| isOwner(userId)` | `isAdmin() \|\| (isOwner(userId) && !hasAny(sensitive))` | `isAdmin() \|\| (isOwner(userId) && !hasAny(sensitive))` | `isAdmin() \|\| isOwner(userId)` |
| `/users/{userId}/fcmTokens/{installationId}`| `isAdmin() \|\| isOwner(userId)` | `(isAdmin() \|\| isOwner) && strictSchema` | `(isAdmin() \|\| isOwner) && strictSchema` | `isAdmin() \|\| isOwner(userId)` |
| `/config/app` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/config/search_order` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/config/features` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/config/economy` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/config/{document=**}` | `false` | `false` | `false` | `false` |
| `/managed_extensions/{extensionId}` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/app_updates/{updateId}` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/notifications/{notificationId}` | `isAdmin() \|\| (isAuthenticated() && target match)` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/reward_tasks/{taskId}` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/leaderboard/weekly_current` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/leaderboard_history/{cycleId}` | `isAuthenticated()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/pro_requests/{requestId}` | `isAdmin() \|\| (auth && userId == auth.uid)` | `auth && userId == auth.uid && status == 'PENDING' && !hasAny(...)` | `auth && userId == auth.uid && status == 'CANCELLED' && affectedKeys == status,updatedAt,cancelledAt` | `isAdmin()` |
| `/reports/{reportId}` | `isAdmin() \|\| (auth && userId == auth.uid)` | `auth && userId == auth.uid && status == 'PENDING' && noAdminFields` | `isAdmin()` | `isAdmin()` |
| `/support_conversations/{convId}` | `isAdmin() \|\| (auth && userId == auth.uid)` | `auth && userId == auth.uid && status in ['open', 'pending']` | `auth && userId == auth.uid && affectedKeys in ['lastMessage', ...]` | `isAdmin()` |
| `/support_conversations/{convId}/messages/{msgId}`| `isAdmin() \|\| (auth && conv.userId == auth.uid)` | `auth && conv.userId == auth.uid && senderId == auth.uid && senderRole == 'user'` | `isAdmin()` | `isAdmin()` |
| `/conversations/{convId}` | `isAdmin() \|\| auth.uid in participants` | `auth.uid in participants && size == 2 && distinct && !isBanned` | `(auth.uid in participants) && affectedKeys in [lastMessage, lastMessageTime, updatedAt]` | `isAdmin() \|\| (auth && (userId == auth.uid \|\| ownerId == auth.uid))` |
| `/conversations/{convId}/messages/{msgId}`| `isAdmin() \|\| auth.uid in conv.participants` | `senderId == auth.uid && auth.uid in conv.participants && !isBanned` | `sender can edit/delete/react; recipient can react/deleteFor` | `isAdmin() \|\| (auth && senderId == auth.uid)` |
| `/conversations/{convId}/readReceipts/{uid}`| `isAdmin() \|\| (auth.uid == uid && in participants)` | `auth.uid == uid && lastReadAt == request.time && updatedAt == request.time` | Same as create | `isAdmin() \|\| auth.uid == uid` |
| `/stories/{storyId}` | `isAuthenticated()` | `auth && userId == auth.uid && !storyBan && !isBanned` | `auth && userId == auth.uid && affectedKeys in [imageUrl, timestamp]` | `isAdmin() \|\| (auth && userId == auth.uid)` |
| `/auditLogs/{logId}` | `isAdmin()` | `isAdmin()` | `isAdmin()` | `isAdmin()` |
| `/audit_logs/{document=**}` | `false` | `false` | `false` | `false` |
| `/{document=**}` (Default Catch-All)| `false` | `false` | `false` | `false` |

---

## 12. User Profile and Account Security

1. **Self-Service Initial Registration:** When a new user signs in for the first time, `AuthRepository.saveUser()` creates their initial document in `/users/{uid}`. Security rules strictly enforce `hasSafeUserCreationDefaults(userId)` (`firestore.rules:120-183`). The document must default `role` to `'user'`, `pointsBalance` to `0`, `subscriptionTier` to `'FREE'`, `isBanned` to `false`, and device limits to `<= 2`. Any attempt by a client to initialize itself as an admin or grant itself free Pro/points on creation is rejected at the Firestore layer.
2. **Account Deletion Flow:** In `AuthRepository.deleteAccount()` (`AuthRepository.kt:667-764`), the client first deletes its subcollections (`fcmTokens`, `library`, `history`, `watched_episodes`, `public_profiles`). Next, it attempts `userDoc.delete()`. Under `firestore.rules:252`, `allow delete: if isAdmin();`, so this client delete call fails with `PERMISSION_DENIED`. The repository catches this error and gracefully falls back to anonymizing the document (`displayName = "Deleted User"`, empty names/bio/photoUrl, `isProfilePublic = false`, `deletedAt = serverTimestamp()`), followed by deleting the user from Firebase Authentication. This prevents unauthorized document deletion while respecting privacy anonymization.
3. **Public vs. Private Profile Isolation:** Private email, subscriptions, balances, and security bans stay strictly in `/users/{uid}`. Only an explicit 9-field projection is mirrored to `/public_profiles/{uid}`, with keys strictly validated via `hasOnly()`.

---

## 13. Points, Economy, and Transaction Security

### The Economy Authority Architecture:
- **Design Invariant:** The client application is never the economic authority. It must never mint points, mutate balances, or create ledger records.
- **Rules Protection:**
  - `point_transactions` subcollection: `allow write: if isAdmin();`
  - `task_claims` subcollection: `allow write: if isAdmin();`
  - User document economy fields (`pointsBalance`, `totalPointsEarned`, `totalPointsSpent`): strictly blocked by `modifyingSensitiveUserFields()`.
- **Client Fallback vs. Rules Reality:**
  - `PointsEarningRepository.kt` defines `isTemporaryEconomyModeEnabled = false` and `trustedBackendUrl = null`. Calls to `claimDailyLogin`, `claimRewardedAd`, `claimTaskReward`, and `redeemSubscription` default to returning `PointsOperationResult.Error(PointsEarningError.BACKEND_REQUIRED)`.
  - In `TemporaryFirebaseEconomyRepository.kt`, local transactions attempt direct client mutations of `pointsBalance`, `totalPointsEarned`, and `point_transactions`. If enabled, these transactions would be blocked by `firestore.rules`.
  - **Verdict:** The rules successfully prevent unauthorized client point minting. The temporary economy fallback in the Android client is non-functional under the active ruleset. A trusted backend (Cloud Functions or Cloudflare Worker) must perform these mutations using administrative privileges.

---

## 14. Subscription and Pro Entitlement Security

1. **Ad Removal Only:** Under the CineStream entitlement architecture, subscriptions (`PRO`, `PRO_LITE`) grant ad removal only. Video streaming and download quality are strictly decoupled from subscriptions and managed via technical account restrictions (`UserRestrictions.kt:149`).
2. **Subscription Requests (`/pro_requests`):** Users submit purchase requests for manual or automated admin review. The security rule (`firestore.rules:502-537`) enforces that:
   - The user cannot approve themselves (`approvedBy`, `approvedAt`, `grantedBy` are forbidden on creation).
   - The user cannot alter `subscriptionTier` or `subscriptionStatus` via the request.
   - Initial status must be `PENDING`.
   - The user can only transition their own request from `PENDING` to `CANCELLED` (`firestore.rules:539-547`).
   - The client cannot delete requests (`allow delete: if isAdmin();`).

---

## 15. Support, Reports, Requests, and Messages

1. **Reports (`/reports`):**
   - Users can create bug/stream reports (`ReportRepository.submitReport`).
   - Creation requires `userId == request.auth.uid` and initial status `PENDING`.
   - Admin resolution fields (`resolvedBy`, `resolvedAt`, `resolutionNotes`) cannot be set by normal users.
   - Updates and deletes are restricted to admins (`allow update, delete: if isAdmin();`).
2. **Support Tickets (`/support_conversations`):**
   - The conversation document ID is the user UID.
   - Users can create tickets with status `open` or `pending`.
   - Updates are restricted to message metadata (`lastMessage`, `lastMessageTime`, `updatedAt`). Status changes cannot be made by the user.
   - Messages in subcollection `/messages/{messageId}` require `senderId == request.auth.uid` and `senderRole == 'user'`.

---

## 16. Configuration, Notifications, and Other Data

1. **Global Configuration:** `/config/app`, `/config/search_order`, `/config/features`, `/config/economy` are strictly read-only for authenticated users and writable only by administrators (`allow write: if isAdmin();`).
2. **Deprecated Paths:** `/config/global` and other subpaths under `/config/{document=**}` are explicitly denied.
3. **Targeted Notifications:** Reading `/notifications/{notificationId}` enforces target-matching logic at the rule layer (`target == 'all'`, `targetUid == request.auth.uid`, or `target == 'pro'`). The queries in `NotificationRepository.kt:42-55` match these conditions directly.

---

## 17. Community and Communication Features

1. **Social Conversations (`/conversations`):**
   - Creation requires exactly 2 distinct participants, and the creating user must be one of them.
   - Active chat ban (`chatBan == true`, `canChat == false`, or `isBanned == true`) blocks conversation and message creation.
   - Shared unread counts (`unreadCounts`) cannot be mutated directly by participants, preventing spoofing of the recipient's counter.
   - Per-user read receipts (`/readReceipts/{uid}`) isolate receipt updates to the authenticated owner.
2. **Stories (`/stories`):**
   - Any authenticated user can read active stories.
   - Posting stories requires `userId == request.auth.uid`, `canStory != false`, and `storyBan != true`.
   - Users can only delete their own stories (`resource.data.userId == request.auth.uid`).

---

## 18. Cancelled Comments Feature Audit

In compliance with the project-wide directive that comments are cancelled and deferred:
- **Rules Status:** There are no rules allowing read or write access to `/comments`. Any attempt to access `/comments` is blocked by the final catch-all deny rule (`match /{document=**} { allow read, write: if false; }`).
- **Codebase Status:** No `CommentsRepository`, `CommentsViewModel`, or UI components exist.
- **Residual Model Fields:** Model classes retain dead flags:
  - `User.canComment` (`AuthRepository.kt:83`)
  - `UserRestrictions.canComment` (`UserRestrictions.kt:48`)
  - `FeaturesConfig.commentsItem` (`EconomyModels.kt:73`)
- **Conclusion:** The feature is completely disabled, dormant, and protected against unauthorized data creation.

---

## 19. Query and Index Compatibility

### 19.1 Active Query Compatibility Analysis

| Query in Code | Target Path | Filters / Order | Firestore Rule Condition | Compatibility Verdict |
|---------------|-------------|-----------------|--------------------------|-----------------------|
| `SocialRepository.searchUsers` | `/public_profiles` | `.whereEqualTo("isProfilePublic", true).orderBy("username").startAt(...).endAt(...)` | `resource.data.isProfilePublic == true` | **PASS**: Query constraints match rule filter. |
| `AuthRepository.isUsernameTaken` | `/public_profiles` | `.whereEqualTo("username", username)` | `resource.data.isProfilePublic == true` | **FAIL (RULE_MISMATCH)**: Missing `isProfilePublic == true` causes `PERMISSION_DENIED`. |
| `NotificationRepository.syncCloudNotifications` | `/notifications` | `.whereEqualTo("target", "all").limit(50)` | `resource.data.target == 'all'` | **PASS**: Query matches rule. |
| `NotificationRepository.syncCloudNotifications` | `/notifications` | `.whereEqualTo("target", "user").whereEqualTo("targetUid", uid).limit(50)` | `resource.data.target == 'user' && resource.data.targetUid == auth.uid` | **PASS**: Query matches rule. |
| `NotificationRepository.syncCloudNotifications` | `/notifications` | `.whereEqualTo("target", "pro").limit(50)` | `resource.data.target == 'pro' && isProUser` | **PASS**: Query matches rule. |
| `PointsRepository.observePointTransactions` | `/users/{uid}/point_transactions` | `.orderBy("createdAt", DESC).limit(50)` | `isOwner(userId)` | **PASS**: User is owner. |
| `PointsRepository.observeRewardTasks` | `/reward_tasks` | `.whereEqualTo("isActive", true)` | `isAuthenticated()` | **PASS**: Authenticated read allowed. |
| `PointsRepository.getUserProRequests` | `/pro_requests` | `.whereEqualTo("userId", uid)` | `resource.data.userId == auth.uid` | **PASS**: Query matches rule. |
| `ReportRepository.getUserReports` | `/reports` | `.whereEqualTo("userId", uid)` | `resource.data.userId == auth.uid` | **PASS**: Query matches rule. |
| `SocialRepository.getConversations` | `/conversations` | `.whereArrayContains("participants", uid).orderBy("lastMessageTime", DESC)` | `auth.uid in resource.data.participants` | **PASS**: Requires composite index on `participants` (array-contains) + `lastMessageTime` (desc). |
| `SocialRepository.getStories` | `/stories` | `.orderBy("timestamp", DESC)` | `isAuthenticated()` | **PASS**: Single field order. |

### 19.2 Index Requirements
The query in `SocialRepository.kt:136`:
`db.collection("conversations").whereArrayContains("participants", user.uid).orderBy("lastMessageTime", Query.Direction.DESCENDING)`
requires a composite index in Firestore on collection `conversations`:
- `participants` (ARRAY_CONTAINS)
- `lastMessageTime` (DESCENDING)
*Evidence:* There is no `firestore.indexes.json` file in the repository. If this index is not configured in the live Firebase console, this query will fail with `FAILED_PRECONDITION`.

---

## 20. Firestore Rules Gaps and Potentially Overbroad Access

1. **Guest Read Rejection on Global Config:**
   - Rules for `/config/app`, `/config/search_order`, `/config/features`, `/config/economy`, and `/managed_extensions` all require `isAuthenticated()`.
   - When a user opens the app as a guest (prior to login or anonymous sign-in), Firestore calls to these collections fail with `PERMISSION_DENIED`.
   - The app falls back to local cache and hardcoded defaults, but cannot fetch updated configurations in guest mode.
2. **Username Check Query Denial:**
   - In `AuthRepository.kt:620`, the query to check if a username is taken fails because it does not filter on `isProfilePublic == true`.
3. **Hardcoded Owner Bootstrap Email:**
   - `firestore.rules:38, 46` contains hardcoded email `sulopros01@gmail.com`. As noted in the rule comments, using an email string check in rules is a bootstrap pattern that should be migrated to an immutable UID check.

---

## 21. Existing Emulator and Security Test Coverage

### Inventory of Existing Tests:
- `app/src/test/java/com/example/ui/screens/auth/GoogleCredentialParserTest.kt`: Unit tests for Google Sign-In credential parsing.
- `app/src/test/java/com/example/ui/screens/auth/AccountIdentityRecoveryTest.kt`: Unit tests for account identity recovery logic.

### Coverage Assessment:
- **Firestore Security Rules Unit Tests:** **ZERO (0%)**. There are no test files utilizing `@firebase/rules-unit-testing`, Mocha, Jest, or the Firebase Emulator Suite for security rules.
- **Missing Test Suites:**
  - Unauthenticated access rejection tests.
  - Cross-user data isolation tests (User A attempting to read/write User B's `/library`, `/history`, `/fcmTokens`).
  - Sensitive field mutation attempts (normal user attempting to write `role = 'admin'`, `pointsBalance = 1000`, `subscriptionTier = 'PRO'`).
  - Request privilege escalation tests (user setting `status = 'APPROVED'` on `/pro_requests`).
  - Negative tests confirming `/comments` remains completely blocked.

---

## 22. Risk Register with Severity and Evidence

| Risk ID | Severity | Category | Description | Evidence Location | Exploitation Scenario / Operational Impact |
|---------|----------|----------|-------------|-------------------|---------------------------------------------|
| **RSK-01** | **HIGH** | Functionality / Rules Mismatch | `isUsernameTaken()` fails with `PERMISSION_DENIED` due to missing `isProfilePublic` query constraint. | `AuthRepository.kt:620`<br>`firestore.rules:203` | User registration or username change fails or falls back to an error state because rules evaluate the query as potentially returning non-public profiles. |
| **RSK-02** | **MEDIUM** | Architecture / Rules Inconsistency | Code in `TemporaryFirebaseEconomyRepository` attempts client-side point/tier mutation, which is blocked by `firestore.rules`. | `TemporaryFirebaseEconomyRepository.kt:136, 623`<br>`firestore.rules:57-118, 250` | If temporary economy mode is activated in the client, transactions fail with `PERMISSION_DENIED`. |
| **RSK-03** | **MEDIUM** | Performance / Missing Index | Social conversation query requires a composite index that is not tracked in `firestore.indexes.json`. | `SocialRepository.kt:135` | Conversation list fails to load with `FAILED_PRECONDITION` unless index is manually built in Firebase Console. |
| **RSK-04** | **MEDIUM** | Operational / Config Drift | Divergence between project IDs across CI, `google-services.json`, `firebase-applet-config.json`, and Worker backend. | `.github/workflows/build.yml:47`<br>`app/google-services.json:3`<br>`backend/wrangler.toml:13` | High risk of deploying rules or backend services to mismatched Firebase projects or testing against different project schemas. |
| **RSK-05** | **LOW** | User Experience | Unauthenticated guest users are denied read access to app config and catalog. | `firestore.rules:385, 419`<br>`AppStartupManager.kt:155` | Guests rely on local defaults and cannot see live maintenance banners or updated search orders until authenticated. |
| **RSK-06** | **LOW** | Identity Robustness | Bootstrap owner admin authority relies on email regex rather than immutable UID. | `firestore.rules:38, 46` | Email identity could be vulnerable if auth email verification is misconfigured in Firebase Auth. |

---

## 23. Client-to-Rules Traceability Matrix

*(Selected representative operations showing full traceability across app components)*

| ID | Firestore Path Pattern | Operation | Calling File & Line | Evidence Location | Fields Read / Written | Authentication & Role Required | Matching Rule Location | Rule Condition Summary | Rule Alignment Status | Confidence |
|----|------------------------|-----------|---------------------|-------------------|-----------------------|--------------------------------|------------------------|------------------------|-----------------------|------------|
| TR-01 | `/users/{userId}` | `get` | `AuthRepository.kt:406` | Line 406 | Read: all user profile fields | Authenticated owner (`request.auth.uid == userId`) | `firestore.rules:239` | `allow read: if isAdmin() \|\| isOwner(userId);` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-02 | `/users/{userId}` | `update` | `AuthRepository.kt:415` | Line 415 | Written: `lastActiveAt`, `appVersion` | Authenticated owner | `firestore.rules:247` | `allow update: if ... !modifyingSensitiveUserFields()` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-03 | `/users/{userId}` | `set` (create) | `AuthRepository.kt:575` | Line 575 | Written: initial user defaults | Authenticated owner | `firestore.rules:241` | `allow create: if ... hasSafeUserCreationDefaults(userId)` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-04 | `/users/{userId}` | `delete` | `AuthRepository.kt:746` | Line 746 | Delete document | Admin only in rules (Client caught & anonymizes) | `firestore.rules:252` | `allow delete: if isAdmin();` | **PASS_WITH_EVIDENCE** (Client fallback handles failure) | HIGH |
| TR-05 | `/public_profiles` | `query` | `AuthRepository.kt:620` | Line 620 | Read: `username` | Authenticated user | `firestore.rules:203` | `allow read: if isAuthenticated() && resource.data.isProfilePublic == true;` | **RULE_MISMATCH** (Query missing `isProfilePublic == true`) | HIGH |
| TR-06 | `/public_profiles/{id}` | `set` (merge) | `SocialRepository.kt:381` | Line 381 | Written: 9 public fields | Authenticated owner | `firestore.rules:216` | `allow update: if ... hasOnly([...]) && types` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-07 | `/users/{uid}/fcmTokens/{id}` | `set` (merge) | `FcmTokenManager.kt:202` | Line 202 | Written: `token`, `installationId`, `platform`, `deviceModel`, etc. | Authenticated owner | `firestore.rules:326, 348` | `allow create/update: if ... platform == 'android' && timestamps` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-08 | `/users/{uid}/library/{id}` | `set` (merge) | `LibraryRepository.kt:87` | Line 87 | Written: `libraryId`, `tmdbId`, `contentType`, etc. | Authenticated owner | `firestore.rules:279` | `allow read, write: if isAdmin() \|\| isOwner(userId);` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-09 | `/users/{uid}/history/{id}` | `set` (merge) | `HistoryRepository.kt:96` | Line 96 | Written: `id`, `positionMillis`, `durationMillis`, `timestamp` | Authenticated owner | `firestore.rules:285` | `allow read, write: if isAdmin() \|\| isOwner(userId);` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-10 | `/pro_requests/{reqId}` | `set` (create) | `PointsRepository.kt:269` | Line 269 | Written: `requestId`, `userId`, `planId`, `status: "PENDING"`, etc. | Authenticated owner | `firestore.rules:502` | `allow create: if ... userId == auth.uid && status == 'PENDING' && !hasAny(...)` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-11 | `/pro_requests/{reqId}` | `update` | `PointsRepository.kt:317` | Line 317 | Written: `status: "CANCELLED"`, `cancelledAt`, `updatedAt` | Authenticated owner | `firestore.rules:539` | `allow update: if ... status == 'CANCELLED' && affectedKeys == status,updatedAt,cancelledAt` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-12 | `/reports/{repId}` | `set` (create) | `ReportRepository.kt:48` | Line 48 | Written: `id`, `userId`, `type`, `status: "PENDING"`, etc. | Authenticated owner | `firestore.rules:559` | `allow create: if ... userId == auth.uid && status in ['PENDING', 'pending'] && noAdminFields` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-13 | `/notifications` | `query` | `NotificationRepository.kt:42` | Line 42 | Read: `target == "all"`, `target == "user"` | Authenticated matching user | `firestore.rules:454` | `allow read: if isAdmin() \|\| (target match)` | **PASS_WITH_EVIDENCE** | HIGH |
| TR-14 | `/comments/{id}` | `read/write` | None (Feature cancelled) | None | N/A | None | `firestore.rules:808` | `allow read, write: if false;` | **PASS_WITH_EVIDENCE** (Blocked by default deny) | HIGH |
| TR-15 | `/users/{uid}/point_transactions/{txId}` | `set` (create) | `TemporaryFirebaseEconomyRepository.kt:151` | Line 151 | Written: `amount`, `balanceAfter`, `type` | Client owner (Non-admin) | `firestore.rules:262` | `allow write: if isAdmin();` | **RULE_MISMATCH / SECURITY_BLOCK** (Client write blocked by rule) | HIGH |

---

## 24. Unverified Areas and Missing Information

1. **Live Production Rules (`UNVERIFIED`):** The local `firestore.rules` file has SHA-256 `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef`. Without authenticated access to the live Firebase Console for `cinestream-sulo`, it cannot be verified whether this exact file is currently active in production.
2. **Firestore Composite Indexes (`UNVERIFIED`):** The repository lacks `firestore.indexes.json`. Whether composite indexes exist in production for queries like `/conversations` (`participants` + `lastMessageTime`) is unverified.
3. **Cloudflare Worker Production Deployment (`UNVERIFIED`):** In `backend/src/index.ts`, the Worker implementation utilizes an in-memory `WorkerDataStore`. Whether an updated version with direct Firestore Admin SDK integration is deployed in production is unverified.

---

## 25. NEEDS_EXTERNAL_COMPARISON Items

The following items require cross-referencing with the CineStream Admin Dashboard repository / report:

1. **Admin Authority Grant Path (`NEEDS_EXTERNAL_COMPARISON`):**
   - The User App inspects `/admins/{uid}.enabled == true` (`UserSecurityManager.kt:58`).
   - Need to verify if the Admin Dashboard writes to `/admins/{uid}` with this exact schema and field name (`enabled: bool`).
2. **Pro Request Review Schema (`NEEDS_EXTERNAL_COMPARISON`):**
   - The User App submits requests to `/pro_requests/{reqId}` with `status: "PENDING"`, and expects admins to write `approvedBy`, `approvedAt`, `rejectionReason`, or update `/users/{uid}` directly.
   - Need to verify if the Admin Dashboard consumes and updates `/pro_requests` using these exact fields.
3. **App Update Release Lifecycle (`NEEDS_EXTERNAL_COMPARISON`):**
   - `AppUpdateManager.kt` checks for `status: "PUBLISHED"`, ignoring `"DRAFT"` and `"ARCHIVED"`.
   - Need to verify if the Admin Dashboard publishes updates to `/app_updates` with this exact status enum.
4. **Targeted Notification Payloads (`NEEDS_EXTERNAL_COMPARISON`):**
   - The User App queries `/notifications` where `target` is `"all"`, `"user"`, or `"pro"`.
   - Need to verify if the Admin Dashboard notification creation tool populates `target` and `targetUid` matching this exact schema.
5. **Support Ticket Admin Flow (`NEEDS_EXTERNAL_COMPARISON`):**
   - The User App writes user messages to `/support_conversations/{uid}/messages` with `senderRole = 'user'`.
   - Need to verify if the Admin Dashboard writes replies with `senderRole = 'admin'` or `'support'` and updates `status` to `'resolved'` or `'closed'`.

---

## 26. Recommended Remediation Plan, Ordered by Risk

*(Note: These are defensive architectural recommendations; no code or rules were modified during this audit).*

### Priority 1 (High): Fix Username Availability Query Constraint
- **Issue:** `AuthRepository.isUsernameTaken` fails at runtime because it queries `/public_profiles` without `whereEqualTo("isProfilePublic", true)`.
- **Recommendation:** Update `AuthRepository.isUsernameTaken()` to include `.whereEqualTo("isProfilePublic", true)` to match `firestore.rules:203` (similar to `SocialRepository.searchUsers()`).

### Priority 2 (Medium): Authoritative Economy & Subscription Resolution
- **Issue:** `TemporaryFirebaseEconomyRepository` attempts client-side point mutations that are blocked by rules, while `PointsEarningRepository` requires a trusted backend (`BACKEND_REQUIRED`).
- **Recommendation:** Fully deploy the trusted Cloudflare Worker / Cloud Functions backend using Firebase Admin SDK to execute point earning, claims, and subscription redemption authoritatively. Deprecate and remove `TemporaryFirebaseEconomyRepository` to eliminate architectural dead code.

### Priority 3 (Medium): Add `firestore.indexes.json`
- **Issue:** Missing index definitions for composite queries in `SocialRepository` (`participants` array-contains + `lastMessageTime` desc) and `NotificationRepository`.
- **Recommendation:** Export production indexes into `firestore.indexes.json` and track them in version control.

### Priority 4 (Medium): Unify Firebase Project Configuration
- **Issue:** Project configuration diverges across `app/google-services.json` (`remixed-project-id`), `firebase-applet-config.json` (`ai-studio-applet-webapp-71606`), `.github/workflows/build.yml` (`cinestream-sulo`), and `backend/wrangler.toml` (`ai-studio-applet-webapp-e138b`).
- **Recommendation:** Harmonize local development configs with the canonical production project ID (`cinestream-sulo`).

### Priority 5 (Low): Implement Security Rules Test Suite
- **Issue:** 0% test coverage for Firestore Security Rules.
- **Recommendation:** Add an automated test suite using `@firebase/rules-unit-testing` and the Firebase Emulator Suite to validate permissions for all 27 paths.

---

## 27. Safe Next Steps Before Any Rules Change

1. **Do Not Manually Relax Rules:** Do not relax `firestore.rules` to allow client-side writing to `point_transactions` or user economy fields.
2. **Coordinate with Admin Dashboard:** Cross-reference this audit report with the Admin Dashboard audit before making any shared rules adjustments.
3. **Run In-Memory / Emulator Tests First:** If rules changes are proposed in future phases, validate them in the local Firebase Firestore Emulator (`firebase.json:8085`) before production deployment.

---

## 28. Final Audit Status

- **Audit Completion:** 100% Complete (Static Analysis, Data Contract Extraction, Traceability Matrix, and Security Risk Assessment).
- **Rule Modifications:** NONE (Read-Only invariant maintained).
- **Rules Deployed:** NONE.
- **Production Data Altered:** NONE.
- **Ruleset Cryptographic Hash:** `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef` (`firestore.rules`).

---

# EXTERNAL COMPARISON HANDOFF

*This structured handoff summary is formatted for central architectural coordination between CineStream User App and CineStream Admin Dashboard.*

### A. Firebase Project Identifiers Found
- Reference / Target Project: `cinestream-sulo` (Number: `979447256418`)
- CI GitHub Secret (`.github/workflows/build.yml:47`): `cinestream-sulo` (App ID: `1:979447256418:android:037a9b3a1393e550a9883b`, package: `com.aistudio.cinestream.xyzabc`)
- Local App Config (`firebase-applet-config.json`): `ai-studio-applet-webapp-71606`
- Local Android Config (`app/google-services.json`): `remixed-project-id` (Package: `com.aistudio.cinestream.nirydy`)
- Backend Config (`backend/wrangler.toml`): `ai-studio-applet-webapp-e138b`

### B. Rules File Paths and SHA-256 Hashes
- `/firestore.rules`: `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef`
- `/firebase.json`: `60df99bbc407687681d24b8de758370540ccdd211af073e39835fc24d4321afc`

### C. Selected Rules File
`firebase.json` specifies `"rules": "firestore.rules"`. No other rules files exist in the project.

### D. Production Verification Status
`LOCAL_CONFIGURATION_ONLY`. Live console was not queried directly.

### E. Discovered Firestore Paths (27 paths)
`/admins/{adminUid}`, `/public_profiles/{userId}`, `/users/{userId}`, `/users/{userId}/point_transactions/{txId}`, `/users/{userId}/task_claims/{taskId}`, `/users/{userId}/library/{itemId}`, `/users/{userId}/history/{itemId}`, `/users/{userId}/watched_episodes/{episodeId}`, `/users/{userId}/settings/notifications`, `/users/{userId}/fcmTokens/{installationId}`, `/config/app`, `/config/search_order`, `/config/features`, `/config/economy`, `/managed_extensions/{extensionId}`, `/app_updates/{updateId}`, `/notifications/{notificationId}`, `/reward_tasks/{taskId}`, `/leaderboard/weekly_current`, `/pro_requests/{requestId}`, `/reports/{reportId}`, `/support_conversations/{conversationId}`, `/support_conversations/{conversationId}/messages/{messageId}`, `/conversations/{conversationId}`, `/conversations/{conversationId}/messages/{messageId}`, `/conversations/{conversationId}/readReceipts/{uid}`, `/stories/{storyId}`.

### F. Operations Summary
- `get document`: 14 paths
- `list / query`: 8 paths
- `create`: 12 paths
- `update`: 8 paths
- `delete`: 9 paths
- `snapshot listener`: 13 paths
- `transaction`: 2 paths (Social conversation preview update, Temporary economy fallback)
- `batch`: 0 paths

### G. Expected Data Types
Strictly documented in `PHASE_22.21-A_USER_APP_FIRESTORE_CONTRACT.md`. Types conform to Firestore primitives: string, boolean, integer/number (Long), timestamp (`FieldValue.serverTimestamp()`), map, and list.

### H. Permission Conditions
- Admin: verified email `sulopros01@gmail.com` OR `/admins/{uid}.enabled == true`.
- User isolation: `request.auth.uid == userId` for all private subcollections.
- Public read: `resource.data.isProfilePublic == true` for `/public_profiles`.
- Target matching: `target == 'all'`, `target == 'user'`, `target == 'pro'` for `/notifications`.

### I. Sensitive Fields Protection
45 sensitive fields in `/users/{userId}` are blocked from client mutation by `modifyingSensitiveUserFields()`.

### J. Identified Access Mismatches
1. `AuthRepository.isUsernameTaken()` omits `.whereEqualTo("isProfilePublic", true)`, causing query failure.
2. `TemporaryFirebaseEconomyRepository` attempts client-side point and subscription updates that are rejected by security rules.

### K. Security Risks & Severity
- RSK-01 (HIGH): Query rejection on username check.
- RSK-02 (MEDIUM): Economy fallback blocked by rules.
- RSK-03 (MEDIUM): Missing composite index tracking.
- RSK-04 (MEDIUM): Project configuration drift.
- RSK-05 (LOW): Guest read denial on config.
- RSK-06 (LOW): Hardcoded owner email.

### L. Test Coverage
0% Security Rules test coverage. Only 2 Android unit tests exist, neither testing rules or emulator.

### M. Comments Feature Findings
Comments feature is CANCELLED. No rules grant access to `/comments`. Denied by default catch-all. Zero active code references.

### N. Uncertain Information
- Live production rules deployment status.
- Live production composite indexes status.

### O. Explicit Admin Dashboard Comparison Requirements
1. Verify `/admins/{uid}` schema (`enabled: bool`).
2. Verify `/pro_requests` approval fields.
3. Verify `/app_updates` publication status values (`PUBLISHED`, `DRAFT`, `ARCHIVED`).
4. Verify `/notifications` target schema (`target`, `targetUid`).
5. Verify `/support_conversations` reply roles (`senderRole: "admin"`).
