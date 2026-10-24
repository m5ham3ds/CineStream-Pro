# CineStream User App — Firestore Data Contract
**Phase:** 22.21-A  
**Status:** AUDITED & EXTRACTED (READ-ONLY)  
**Target Platform:** Android (CineStream User App)  
**Firebase Canonical Project ID:** `cinestream-sulo` (Reference)  
**Local Configs:** `firebase-applet-config.json` (`ai-studio-applet-webapp-71606`), `app/google-services.json` (`remixed-project-id`)  
**Rules File:** `firestore.rules` (SHA-256: `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef`)  

---

## 1. Overview and Contract Scope

This document specifies the exact, evidence-backed Firestore data contract extracted via static code analysis of the CineStream User App (`com.aistudio.cinestream.nirydy` / `com.example`). It documents every collection, document, subcollection, field, expected data type, operation, caller location, and matching security rule condition.

---

## 2. Master Firestore Path Inventory

| # | Firestore Path Pattern | Dynamic Identifier | Client Operations | Calling Class / Repository | Evidence Location |
|---|------------------------|-------------------|-------------------|----------------------------|-------------------|
| 1 | `/admins/{adminUid}` | `adminUid` = User UID | `get`, `listen` | `UserSecurityManager` | `UserSecurityManager.kt:59, 331` |
| 2 | `/public_profiles/{userId}` | `userId` = Target UID | `get`, `query`, `set` (merge), `delete` | `AuthRepository`, `SocialRepository` | `AuthRepository.kt:579, 620, 731`<br>`SocialRepository.kt:89, 104, 336, 381` |
| 3 | `/users/{userId}` | `userId` = Current Auth UID | `get`, `set` (merge), `update`, `listen` | `AuthRepository`, `UserSecurityManager`, `PointsEarningRepository`, `NotificationRepository` | `AuthRepository.kt:406, 415, 526`<br>`UserSecurityManager.kt:74, 326`<br>`PointsEarningRepository.kt:78, 120, 165, 576`<br>`NotificationRepository.kt:49, 285` |
| 4 | `/users/{userId}/point_transactions/{txId}` | `txId` = Transaction ID | `listen` (Read-only by design; client write blocked by rules) | `PointsRepository`, `TemporaryFirebaseEconomyRepository` | `PointsRepository.kt:43`<br>`TemporaryFirebaseEconomyRepository.kt:151, 309, 452, 650` |
| 5 | `/users/{userId}/task_claims/{taskId}` | `taskId` = Task ID | `listen` (Read-only by design; client write blocked by rules) | `PointsEarningRepository`, `TemporaryFirebaseEconomyRepository` | `PointsEarningRepository.kt:210`<br>`TemporaryFirebaseEconomyRepository.kt:240` |
| 6 | `/users/{userId}/library/{itemId}` | `itemId` = Canonical `libraryId` | `set` (merge), `delete`, `get`, `listen` | `LibraryRepository`, `CloudSyncManager`, `AuthRepository` | `LibraryRepository.kt:87, 102`<br>`CloudSyncManager.kt:60, 169`<br>`AuthRepository.kt:692` |
| 7 | `/users/{userId}/history/{itemId}` | `itemId` = Canonical `id` | `set` (merge), `delete`, `get`, `listen` | `HistoryRepository`, `CloudSyncManager`, `AuthRepository` | `HistoryRepository.kt:96, 129, 189`<br>`CloudSyncManager.kt:100, 199, 258, 290`<br>`AuthRepository.kt:701` |
| 8 | `/users/{userId}/watched_episodes/{episodeId}` | `episodeId` = Episode ID | `set` (merge), `delete`, `get`, `listen` | `WatchedEpisodeRepository`, `CloudSyncManager`, `AuthRepository` | `WatchedEpisodeRepository.kt:23, 35`<br>`CloudSyncManager.kt:116, 204, 296, 302`<br>`AuthRepository.kt:710` |
| 9 | `/users/{userId}/settings/notifications` | Constant document `notifications` | `get`, `set` (merge), `listen`, `delete` | `NotificationPreferencesRepository`, `CloudSyncManager`, `AuthRepository` | `NotificationPreferencesRepository.kt:195, 241`<br>`CloudSyncManager.kt:138, 217`<br>`AuthRepository.kt:719` |
| 10 | `/users/{userId}/fcmTokens/{installationId}` | `installationId` = UUID | `get`, `set` (merge), `update`, `delete` | `FcmTokenManager`, `AuthRepository` | `FcmTokenManager.kt:196, 212, 273, 296, 335`<br>`AuthRepository.kt:683` |
| 11 | `/config/app` | Constant document `app` | `get` (SERVER/CACHE), `listen` | `AppStartupManager`, `AppUpdateManager` | `AppStartupManager.kt:48, 132, 175`<br>`AppUpdateManager.kt:377` |
| 12 | `/config/search_order` | Constant document `search_order` | `get`, `listen` | `SearchOrderDataSource`, `ManagedExtensionRealtimeSyncManager` | `SearchOrderDataSource.kt:39`<br>`ManagedExtensionRealtimeSyncManager.kt:100` |
| 13 | `/config/features` | Constant document `features` | `get`, `listen` | `EconomyConfigRepository` | `EconomyConfigRepository.kt:51, 98` |
| 14 | `/config/economy` | Constant document `economy` | `get`, `listen` | `EconomyConfigRepository` | `EconomyConfigRepository.kt:70, 107` |
| 15 | `/managed_extensions/{extensionId}` | `extensionId` = String | `get` (all docs), `listen` | `FirebaseFirestoreManagedExtensionDataSource`, `ManagedExtensionRealtimeSyncManager` | `FirebaseFirestoreManagedExtensionDataSource.kt:38`<br>`ManagedExtensionRealtimeSyncManager.kt:89` |
| 16 | `/app_updates/{updateId}` | `updateId` = Version or UUID | `get` (SERVER/CACHE) | `AppUpdateManager` | `AppUpdateManager.kt:344, 348` |
| 17 | `/notifications/{notificationId}` | `notificationId` = Firestore ID | `query` (`target` == `all`, `user`, `pro`), `listen` | `NotificationRepository` | `NotificationRepository.kt:39, 196` |
| 18 | `/reward_tasks/{taskId}` | `taskId` = Task ID | `query` (`isActive` == `true`), `get` | `PointsRepository`, `TemporaryFirebaseEconomyRepository` | `PointsRepository.kt:87`<br>`TemporaryFirebaseEconomyRepository.kt:238` |
| 19 | `/leaderboard/weekly_current` | Constant document `weekly_current` | `listen` | `PointsRepository` | `PointsRepository.kt:114` |
| 20 | `/pro_requests/{requestId}` | `requestId` = Generated ID | `set` (create), `query` (`userId` == uid), `update` (`status` -> CANCELLED) | `PointsRepository` | `PointsRepository.kt:269, 280, 300` |
| 21 | `/reports/{reportId}` | `reportId` = Auto-generated ID | `set` (create), `query` (`userId` == uid) | `ReportRepository` | `ReportRepository.kt:28, 48, 58` |
| 22 | `/support_conversations/{conversationId}` | `conversationId` = User UID | `get`, `set` (create), `update` | `SupportViewModel` | `SupportViewModel.kt:100, 105, 124` |
| 23 | `/support_conversations/{conversationId}/messages/{messageId}` | `messageId` = UUID | `set` (create), `listen` | `SupportViewModel` | `SupportViewModel.kt:52, 127, 138` |
| 24 | `/conversations/{conversationId}` | `conversationId` = `uid1_uid2` | `get`, `set` (create), `query`, `transaction` (update preview) | `SocialRepository` | `SocialRepository.kt:135, 155, 284, 293, 309, 481, 505, 528` |
| 25 | `/conversations/{conversationId}/messages/{messageId}` | `messageId` = Auto ID | `set` (create), `update` (edit, delete, react), `listen` | `SocialRepository` | `SocialRepository.kt:234, 303, 308, 414, 424, 452, 476, 500, 523` |
| 26 | `/conversations/{conversationId}/readReceipts/{uid}` | `uid` = Current Auth UID | `set` (merge) | `SocialRepository` | `SocialRepository.kt:256` |
| 27 | `/stories/{storyId}` | `storyId` = Auto ID | `listen` (`orderBy("timestamp")`), `set` (create), `delete`, `query` (`userId` == uid) | `SocialRepository`, `AuthRepository` | `SocialRepository.kt:117, 396, 406`<br>`AuthRepository.kt:723` |

---

## 3. Detailed Data Contract by Path

### 3.1 Path: `/users/{userId}`

- **Document ID:** Canonical Firebase Auth UID (`request.auth.uid`).
- **Authorization in Rules:**
  - Read: `isAdmin() || isOwner(userId)`
  - Create: `isAdmin() || hasSafeUserCreationDefaults(userId)`
  - Update: `isAdmin() || (isOwner(userId) && !modifyingSensitiveUserFields())`
  - Delete: `isAdmin()` (Client delete rejected; client performs fallback anonymization).

#### Field Specification

| Field Name | Expected Type | Requiredness | Mutability by Client | Source & Rationale |
|------------|---------------|--------------|----------------------|--------------------|
| `uid` | string | Mandatory | Set on create; immutable on update | `AuthRepository.kt:530` |
| `id` | string | Mandatory | Set on create; immutable on update | `AuthRepository.kt:531` |
| `email` | string | Optional | Set on create/update | `AuthRepository.kt:532` |
| `firstName` | string | Optional | Mutable by client | `AuthRepository.kt:533` |
| `lastName` | string | Optional | Mutable by client | `AuthRepository.kt:534` |
| `displayName` | string | Optional | Mutable by client | `AuthRepository.kt:535` |
| `username` | string | Mandatory | Mutable by client | `AuthRepository.kt:536` |
| `photoUrl` | string | Optional | Mutable by client | `AuthRepository.kt:537` |
| `isProfilePublic`| boolean | Mandatory | Mutable by client (default true) | `AuthRepository.kt:538` |
| `lastLoginAt` | timestamp | Optional | Client passes `FieldValue.serverTimestamp()` | `AuthRepository.kt:539` |
| `lastLoginTimestamp` | integer/number | Optional | Client passes `System.currentTimeMillis()` | `AuthRepository.kt:540` |
| `lastActiveAt` | integer/number | Optional | Client updates on resume/refresh | `AuthRepository.kt:417, 541` |
| `updatedAt` | timestamp | Optional | Client passes `FieldValue.serverTimestamp()` | `AuthRepository.kt:542` |
| `appVersion` | string | Optional | Updated on resume/refresh | `AuthRepository.kt:418, 543` |
| `createdAt` | timestamp | Mandatory on create | Immutable (guarded by `modifyingSensitiveUserFields`) | `AuthRepository.kt:548` |
| `isActive` | boolean | Mandatory | Protected: Defaults to `true`; Client cannot alter | `firestore.rules:90, 156` |
| `role` | string | Mandatory | Protected: Defaults to `"user"`; Client cannot alter | `firestore.rules:58, 126` |
| `subscriptionTier`| string | Mandatory | Protected: Defaults to `"FREE"`; Client cannot alter | `firestore.rules:73, 131` |
| `planId` | string | Optional | Protected: Defaults to `"free"`; Client cannot alter | `firestore.rules:74, 132` |
| `durationDays` | integer/number | Optional | Protected: Defaults to `0`; Client cannot alter | `firestore.rules:75, 133` |
| `subscriptionStatus`| string | Optional | Protected: Defaults to `"none"`; Client cannot alter | `firestore.rules:76, 134` |
| `subscriptionSource`| string | Optional | Protected: Defaults to `"LEGACY"`; Client cannot alter | `firestore.rules:77, 139` |
| `subscriptionReferenceId`| string/null | Optional | Protected: Defaults to `null`; Client cannot alter | `firestore.rules:78, 141` |
| `subscriptionStartedAt` | timestamp/null | Optional | Protected: Defaults to `null`; Client cannot alter | `firestore.rules:79, 142` |
| `subscriptionExpiresAt` | timestamp/null | Optional | Protected: Defaults to `null`; Client cannot alter | `firestore.rules:80, 143` |
| `isPremium` | boolean | Optional | Protected legacy: Defaults to `false` | `firestore.rules:66, 130` |
| `isPro` | boolean | Optional | Protected legacy: Defaults to `false` | `firestore.rules:67, 145` |
| `pointsBalance` | integer/number | Mandatory | Protected: Defaults to `0`; Client cannot alter | `firestore.rules:83, 150` |
| `totalPointsEarned` | integer/number | Mandatory | Protected: Defaults to `0`; Client cannot alter | `firestore.rules:84, 151` |
| `totalPointsSpent` | integer/number | Mandatory | Protected: Defaults to `0`; Client cannot alter | `firestore.rules:85, 152` |
| `isBanned` | boolean | Mandatory | Protected: Defaults to `false`; Client cannot alter | `firestore.rules:91, 157` |
| `banReason` | string | Optional | Protected: Defaults to `""`; Client cannot alter | `firestore.rules:92, 158` |
| `banExpiresAt` | timestamp/null | Optional | Protected: Defaults to `null`; Client cannot alter | `firestore.rules:93, 159` |
| `canWatch` | boolean | Mandatory | Protected: Defaults to `true`; Client cannot alter | `firestore.rules:96, 161` |
| `canDownload` | boolean | Mandatory | Protected: Defaults to `true`; Client cannot alter | `firestore.rules:97, 162` |
| `canChat` | boolean | Mandatory | Protected: Defaults to `true`; Client cannot alter | `firestore.rules:98, 163` |
| `canStory` | boolean | Mandatory | Protected: Defaults to `true`; Client cannot alter | `firestore.rules:99, 164` |
| `canP2P` | boolean | Mandatory | Protected: Defaults to `true`; Client cannot alter | `firestore.rules:100, 165` |
| `canComment` | boolean | Mandatory | Protected: Defaults to `true`; Client cannot alter | `firestore.rules:101, 166` |
| `canUpload` | boolean | Mandatory | Protected: Defaults to `true` | `firestore.rules:102, 167` |
| `canRequest` | boolean | Mandatory | Protected: Defaults to `true` | `firestore.rules:103, 168` |
| `deviceLimit` | integer/number | Optional | Protected: Defaults to `<= 2` | `firestore.rules:111, 176` |
| `maxDevices` | integer/number | Optional | Protected: Defaults to `<= 2` | `firestore.rules:112, 177` |
| `allowedQuality`| string/null | Optional | Protected: Admin controlled | `firestore.rules:113, 178` |
| `downloadLimit`| integer/null | Optional | Protected: Admin controlled | `firestore.rules:114, 179` |
| `offlineDaysOverride`| integer/null | Optional | Protected: Admin controlled | `firestore.rules:115, 180` |
| `forcedAdsOverride`| integer/null | Optional | Protected: Admin controlled | `firestore.rules:116, 181` |
| `deletedAt` | timestamp | Optional | Written during account deletion fallback | `AuthRepository.kt:759` |

---

### 3.2 Path: `/public_profiles/{userId}`

- **Document ID:** Same as user UID (`request.auth.uid`).
- **Authorization in Rules:**
  - Read: `isAuthenticated() && resource.data.isProfilePublic == true`
  - Create: `isAuthenticated() && request.auth.uid == userId && request.resource.data.keys().hasOnly([...]) && request.resource.data.isProfilePublic is bool && request.resource.data.isOnline is bool`
  - Update: Same allowlist, UID equality, bool type constraints.
  - Delete: `isAdmin() || (isAuthenticated() && request.auth.uid == userId)`

#### Field Specification

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `uid` | string | Mandatory | Set on create; immutable on update | `AuthRepository.kt:581`<br>`SocialRepository.kt:371` |
| `username` | string | Mandatory | Mutable by client | `AuthRepository.kt:582`<br>`SocialRepository.kt:372` |
| `firstName` | string | Optional | Mutable by client | `AuthRepository.kt:583`<br>`SocialRepository.kt:373` |
| `lastName` | string | Optional | Mutable by client | `AuthRepository.kt:584`<br>`SocialRepository.kt:374` |
| `photoUrl` | string | Optional | Mutable by client | `AuthRepository.kt:585`<br>`SocialRepository.kt:375` |
| `isOnline` | boolean | Mandatory | Mutable by client (strictly bool) | `AuthRepository.kt:586`<br>`SocialRepository.kt:376` |
| `isProfilePublic` | boolean | Mandatory | Mutable by client (strictly bool) | `AuthRepository.kt:587`<br>`SocialRepository.kt:377` |
| `bio` | string | Optional | Mutable by client | `AuthRepository.kt:588`<br>`SocialRepository.kt:378` |
| `updatedAt` | timestamp | Mandatory | Client passes `FieldValue.serverTimestamp()` | `AuthRepository.kt:589`<br>`SocialRepository.kt:379` |

*Security Rule Invariant:* Keys strictly limited to the above 9 fields via `hasOnly()`. Any additional field causes immediate rejection.

---

### 3.3 Path: `/users/{userId}/fcmTokens/{installationId}`

- **Document ID:** Client installation UUID (`installationId`).
- **Authorization in Rules:**
  - Read: `isAdmin() || isOwner(userId)`
  - Create: Strict validation: `platform == 'android'`, `isActive == true`, `token` is non-empty string, `createdAt == request.time`, `updatedAt == request.time`, `lastSeenAt == request.time`, forbidden keys check.
  - Update: Preserves `createdAt`, `platform == 'android'`, `updatedAt == request.time`, `lastSeenAt == request.time`, optional `deactivationReason` is string.
  - Delete: `isAdmin() || isOwner(userId)`

#### Field Specification

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `token` | string | Mandatory | Mutable on refresh/update | `FcmTokenDocument.kt:28` |
| `installationId`| string | Mandatory | Immutable | `FcmTokenDocument.kt:29` |
| `platform` | string | Mandatory | Constant `"android"` | `FcmTokenDocument.kt:30` |
| `deviceModel` | string | Mandatory | Device model string | `FcmTokenDocument.kt:31` |
| `osVersion` | string | Mandatory | Android SDK version string | `FcmTokenDocument.kt:32` |
| `appVersion` | string | Mandatory | Client version name string | `FcmTokenDocument.kt:33` |
| `isActive` | boolean | Mandatory | Mutable (`true` active, `false` deactivated) | `FcmTokenDocument.kt:34`<br>`FcmTokenManager.kt:341` |
| `createdAt` | timestamp | Mandatory on create | Immutable (omitted on merge update) | `FcmTokenDocument.kt:41` |
| `updatedAt` | timestamp | Mandatory | Client passes `FieldValue.serverTimestamp()` | `FcmTokenDocument.kt:35`<br>`FcmTokenManager.kt:343` |
| `lastSeenAt` | timestamp | Mandatory | Client passes `FieldValue.serverTimestamp()` | `FcmTokenDocument.kt:36`<br>`FcmTokenManager.kt:344` |
| `deactivationReason`| string | Optional | Written on deactivation | `FcmTokenManager.kt:342` |

---

### 3.4 Path: `/users/{userId}/library/{itemId}`

- **Document ID:** Canonical `libraryId` formatted as `{contentType}_{tmdbId}` or `{contentType}_{tmdbId}_{variantMode}`.
- **Authorization in Rules:** `allow read, write: if isAdmin() || isOwner(userId);`

#### Field Specification

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `libraryId` | string | Mandatory | Primary key | `LibraryItem.kt:25` |
| `tmdbId` | string | Mandatory | Canonical TMDB media ID | `LibraryItem.kt:26` |
| `title` | string | Mandatory | Media title | `LibraryItem.kt:27` |
| `posterUrl` | string | Optional | Poster image URL | `LibraryItem.kt:28` |
| `contentType` | string | Mandatory | `"movie"`, `"tv"`, or `"anime"` | `LibraryItem.kt:29` |
| `providerId` | string | Optional | Scraper / provider ID | `LibraryItem.kt:30` |
| `variantMode` | string | Mandatory | `"subtitled"` or `"dubbed"` | `LibraryItem.kt:31` |
| `addedAt` | integer/number | Mandatory | Timestamp millis | `LibraryItem.kt:32` |

---

### 3.5 Path: `/users/{userId}/history/{itemId}`

- **Document ID:** Canonical ID (`HistoryItem.id`).
- **Authorization in Rules:** `allow read, write: if isAdmin() || isOwner(userId);`

#### Field Specification

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `id` | string | Mandatory | Primary key | `HistoryRepository.kt:90, 177` |
| `title` | string | Mandatory | Media or episode title | `HistoryRepository.kt:178` |
| `posterUrl` | string | Optional | Poster image URL | `HistoryRepository.kt:179` |
| `isMovie` | boolean | Mandatory | Media type flag | `HistoryRepository.kt:180` |
| `timestamp` | integer/number | Mandatory | Last played millis | `HistoryRepository.kt:181` |
| `positionMillis` | integer/number | Mandatory | Playback progress in ms | `HistoryRepository.kt:182` |
| `durationMillis` | integer/number | Mandatory | Media duration in ms | `HistoryRepository.kt:183` |

---

### 3.6 Path: `/users/{userId}/watched_episodes/{episodeId}`

- **Document ID:** Episode ID string.
- **Authorization in Rules:** `allow read, write: if isAdmin() || isOwner(userId);`

#### Field Specification

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `id` | string | Mandatory | Episode identifier | `WatchedEpisodeRepository.kt:19` |

---

### 3.7 Path: `/users/{userId}/settings/notifications`

- **Document ID:** Constant `notifications`.
- **Authorization in Rules:**
  - Read: `isAdmin() || isOwner(userId)`
  - Write: `isAdmin() || (isOwner(userId) && !request.resource.data.keys().hasAny(['role', 'admin', 'isAdmin', 'isPremium', 'subscriptionTier', 'isBanned', 'banReason']))`

#### Field Specification

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `notificationsEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:16` |
| `announcementsEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:17` |
| `appUpdatesEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:18` |
| `maintenanceEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:19` |
| `newMoviesEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:20` |
| `newTvSeriesEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:21` |
| `newAnimeEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:22` |
| `newTvEpisodesEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:23` |
| `newAnimeEpisodesEnabled`| boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:24` |
| `newTvSeasonsEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:25` |
| `newAnimeSeasonsEnabled` | boolean | Mandatory | Mutable by client (default true) | `NotificationPreferences.kt:26` |
| `updatedAt` | timestamp | Optional | Client passes `FieldValue.serverTimestamp()` | `NotificationPreferences.kt:39` |

---

### 3.8 Path: `/config/app`

- **Document ID:** Constant `app`.
- **Authorization in Rules:** `allow read: if isAuthenticated(); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `maintenanceEnabled` | boolean | Mandatory | Read-only | `AppConfig.kt:9, 38-49` |
| `maintenanceTitle` | string | Optional | Read-only | `AppConfig.kt:10, 55` |
| `maintenanceMessage` | string | Optional | Read-only | `AppConfig.kt:11, 60` |
| `maintenanceAllowedRoles`| array of strings| Optional | Read-only | `AppConfig.kt:12, 66` |
| `minimumVersionCode` | integer/number | Mandatory | Read-only | `AppConfig.kt:13, 70` |
| `latestVersionCode` | integer/number | Mandatory | Read-only | `AppConfig.kt:14, 74` |
| `latestVersionName` | string | Optional | Read-only | `AppConfig.kt:15, 77` |
| `apkUrl` | string | Optional | Read-only | `AppConfig.kt:16, 80` |
| `apkSha256` | string | Optional | Read-only | `AppConfig.kt:17, 83` |
| `mandatoryUpdate` | boolean | Optional | Read-only | `AppConfig.kt:18, 88` |
| `releaseNotes` | string | Optional | Read-only | `AppConfig.kt:19, 93` |
| `defaultOfflineDays` | integer/number | Optional | Read-only | `AppConfig.kt:20, 96` |
| `defaultForcedAds` | integer/number | Optional | Read-only | `AppConfig.kt:21, 101` |
| `providersJson` | string | Optional | Read-only | `AppConfig.kt:22, 105` |
| `updatedAt` | timestamp/number | Optional | Read-only | `AppConfig.kt:23, 107` |

---

### 3.9 Path: `/config/search_order`

- **Document ID:** Constant `search_order`.
- **Authorization in Rules:** `allow read: if isAuthenticated(); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `movie` | array of strings | Mandatory | Read-only | `SearchOrderDataSource.kt:97` |
| `tv` | array of strings | Optional | Read-only | `SearchOrderDataSource.kt:98` |
| `series` | array of strings | Mandatory | Read-only | `SearchOrderDataSource.kt:99` |
| `anime` | array of strings | Mandatory | Read-only | `SearchOrderDataSource.kt:100` |
| `arabicDubbed` | array of strings | Optional | Read-only | `SearchOrderDataSource.kt:101` |
| `englishDubbed` | array of strings | Optional | Read-only | `SearchOrderDataSource.kt:102` |
| `dubbingConfig` | map | Optional | Read-only | `SearchOrderDataSource.kt:70` |
| `arabicDubbedStatus` | string | Optional | Read-only (ACTIVE, MAINTENANCE, DISABLED) | `SearchOrderDataSource.kt:74` |
| `englishDubbedStatus`| string | Optional | Read-only (ACTIVE, MAINTENANCE, DISABLED) | `SearchOrderDataSource.kt:82` |
| `updatedAt` | timestamp/number | Optional | Read-only | `SearchOrderDataSource.kt:89` |
| `updatedBy` | string | Optional | Read-only | `SearchOrderDataSource.kt:94` |

---

### 3.10 Path: `/config/features`

- **Document ID:** Constant `features`.
- **Authorization in Rules:** `allow read: if isAuthenticated(); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `subscriptions` | map or string | Optional | Read-only | `EconomyConfigRepository.kt:144` |
| `points` | map or string | Optional | Read-only | `EconomyConfigRepository.kt:145` |
| `dailyLogin` | map or string | Optional | Read-only | `EconomyConfigRepository.kt:146` |
| `rewardedAds` | map or string | Optional | Read-only | `EconomyConfigRepository.kt:147` |
| `tasks` | map or string | Optional | Read-only | `EconomyConfigRepository.kt:148` |
| `leaderboard` | map or string | Optional | Read-only | `EconomyConfigRepository.kt:149` |
| `comments` | map or string | Optional | Read-only | `EconomyModels.kt:73` |
| `upload` | map or string | Optional | Read-only | `EconomyModels.kt:78` |
| `requests` | map or string | Optional | Read-only | `EconomyModels.kt:81` |
| `disabledMessage` | string | Optional | Read-only | `EconomyConfigRepository.kt:141` |

---

### 3.11 Path: `/config/economy`

- **Document ID:** Constant `economy`.
- **Authorization in Rules:** `allow read: if isAuthenticated(); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `redemptionCosts` | map<string, number> | Mandatory | Read-only (e.g. `pro_lite_1d: 50`) | `EconomyConfigRepository.kt:196` |
| `dailyLoginRewards`| array of numbers | Mandatory | Read-only (e.g. `[5, 10, 15, 20, 25, 30, 50]`) | `EconomyConfigRepository.kt:211` |
| `rewardedAdPoints`| integer/number | Mandatory | Read-only (default 15) | `EconomyConfigRepository.kt:220` |
| `rewardedAdDailyCap`| integer/number | Mandatory | Read-only (default 5) | `EconomyConfigRepository.kt:227` |
| `rewardedAdCooldownSeconds`| integer/number| Mandatory | Read-only (default 300) | `EconomyConfigRepository.kt:234` |

---

### 3.12 Path: `/managed_extensions/{extensionId}`

- **Document ID:** Extension string identifier (e.g. `akwam`, `egybest`, `anime4up`).
- **Authorization in Rules:** `allow read: if isAuthenticated(); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `id` | string | Mandatory | Read-only | `ManagedExtensionDto.kt:15` |
| `name` | string | Mandatory | Read-only | `ManagedExtensionDto.kt:16` |
| `version` | string | Mandatory | Read-only | `ManagedExtensionDto.kt:17` |
| `baseUrl` | string | Mandatory | Read-only | `ManagedExtensionDto.kt:18` |
| `enabled` | boolean | Mandatory | Read-only | `ManagedExtensionDto.kt:19` |
| `status` | string | Optional | Read-only (`ACTIVE`, `MAINTENANCE`, `DISABLED`) | `ManagedExtensionDto.kt:20` |
| `contentType` | string | Optional | Read-only | `ManagedExtensionDto.kt:21` |
| `capabilities` | array of strings | Optional | Read-only | `ManagedExtensionDto.kt:22` |
| `headers` | map<string, string> | Optional | Read-only | `ManagedExtensionDto.kt:23` |
| `matchPatterns` | array of strings | Optional | Read-only | `ManagedExtensionDto.kt:24` |
| `weight` | integer/number | Optional | Read-only | `ManagedExtensionDto.kt:25` |

---

### 3.13 Path: `/app_updates/{updateId}`

- **Document ID:** Release identifier or version code.
- **Authorization in Rules:** `allow read: if isAuthenticated(); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `versionCode` | integer/number | Mandatory | Read-only | `AppUpdateManager.kt:141` |
| `versionName` | string | Mandatory | Read-only | `AppUpdateManager.kt:154` |
| `apkUrl` | string | Mandatory | Read-only | `AppUpdateManager.kt:148` |
| `status` | string | Mandatory | Read-only (`PUBLISHED`, `DRAFT`, `ARCHIVED`) | `AppUpdateManager.kt:77` |
| `releaseNotes` | string | Optional | Read-only | `AppUpdateManager.kt:159` |
| `minVersionCode` | integer/number | Optional | Read-only | `AppUpdateManager.kt:164` |
| `mandatoryUpdate`| boolean | Optional | Read-only | `AppUpdateManager.kt:170` |
| `apkSha256` | string | Optional | Read-only | `AppUpdateManager.kt:183` |
| `signature` | string | Optional | Read-only | `AppUpdateManager.kt:188` |
| `publicKey` | string | Optional | Read-only (Ignored by client verifier) | `AppUpdateManager.kt:193` |
| `createdAt` | timestamp/number | Optional | Read-only | `AppUpdateManager.kt:177` |

---

### 3.14 Path: `/notifications/{notificationId}`

- **Document ID:** Auto-generated ID.
- **Authorization in Rules:** `allow read: if isAdmin() || (isAuthenticated() && (target == 'all' || (target == 'user' && targetUid == request.auth.uid) || (target == 'pro' && isProUser))); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `target` | string | Mandatory | Filtered query constraint (`all`, `user`, `pro`) | `NotificationRepository.kt:42, 165` |
| `targetUid` | string | Optional | Filtered when `target == "user"` | `NotificationRepository.kt:44, 166` |
| `title` | string | Mandatory | Read-only | `NotificationRepository.kt:81` |
| `body` / `message`| string | Optional | Read-only | `NotificationRepository.kt:82` |
| `type` | string | Optional | Read-only (`info`, `announcement`, `update`) | `NotificationRepository.kt:89` |
| `event` | string | Optional | Read-only | `NotificationRepository.kt:90` |
| `isAnime` | boolean/string | Optional | Read-only | `NotificationRepository.kt:91` |
| `imageUrl` | string | Optional | Read-only | `NotificationRepository.kt:88` |
| `isActive` | boolean | Optional | Read-only (client ignores inactive) | `NotificationRepository.kt:149` |
| `expiresAt` | timestamp/number | Optional | Read-only (client ignores expired) | `NotificationRepository.kt:153` |
| `createdAt` | timestamp/number | Optional | Read-only | `NotificationRepository.kt:83` |

---

### 3.15 Path: `/reward_tasks/{taskId}`

- **Document ID:** Task identifier.
- **Authorization in Rules:** `allow read: if isAuthenticated(); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `taskId` | string | Mandatory | Primary key | `PointsRepository.kt:157` |
| `title` | string | Mandatory | Read-only | `PointsRepository.kt:158` |
| `description` | string | Optional | Read-only | `PointsRepository.kt:159` |
| `rewardPoints` | integer/number | Mandatory | Read-only | `PointsRepository.kt:144, 160` |
| `taskType` | string | Optional | Read-only (`GENERAL`, etc.) | `PointsRepository.kt:161` |
| `actionUrl` | string | Optional | Read-only | `PointsRepository.kt:162` |
| `isActive` | boolean | Mandatory | Query constraint (`whereEqualTo("isActive", true)`) | `PointsRepository.kt:88, 163` |
| `expiresAt` | timestamp/number | Optional | Read-only | `PointsRepository.kt:137, 164` |

---

### 3.16 Path: `/leaderboard/weekly_current`

- **Document ID:** Constant `weekly_current`.
- **Authorization in Rules:** `allow read: if isAuthenticated(); allow write: if isAdmin();`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Access | Code Evidence |
|------------|---------------|--------------|---------------|---------------|
| `rankings` / `entries`| array of maps | Mandatory | Read-only | `PointsRepository.kt:122` |
| `entries[].userId` | string | Mandatory | Read-only | `PointsRepository.kt:169` |
| `entries[].username`| string | Optional | Read-only | `PointsRepository.kt:199` |
| `entries[].displayName`| string | Optional | Read-only | `PointsRepository.kt:200` |
| `entries[].photoUrl` | string | Optional | Read-only | `PointsRepository.kt:201` |
| `entries[].rank` | integer | Mandatory | Read-only | `PointsRepository.kt:177, 202` |
| `entries[].weeklyEarnedPoints`| integer/number | Mandatory | Read-only | `PointsRepository.kt:171, 203` |
| `entries[].tier` | string | Optional | Read-only | `PointsRepository.kt:189, 207` |

---

### 3.17 Path: `/pro_requests/{requestId}`

- **Document ID:** Formatted as `req_{timestamp}_{uid.take(6)}`.
- **Authorization in Rules:**
  - Read: `isAdmin() || (isAuthenticated() && resource.data.userId == request.auth.uid)`
  - Create: `isAuthenticated() && request.resource.data.userId == request.auth.uid && request.resource.data.status in ['PENDING', 'pending'] && !keys().hasAny(['approvedBy', 'approvedAt', 'grantedBy', 'subscriptionTier', ...])`
  - Update: `isAuthenticated() && resource.data.userId == request.auth.uid && resource.data.status == 'PENDING' && request.resource.data.status == 'CANCELLED' && diff().affectedKeys().hasOnly(['status', 'updatedAt', 'cancelledAt'])`
  - Delete: `isAdmin()`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Mutability | Code Evidence |
|------------|---------------|--------------|-------------------|---------------|
| `requestId` | string | Mandatory | Written on create | `PointsRepository.kt:248` |
| `userId` | string | Mandatory | Set to `request.auth.uid` | `PointsRepository.kt:249` |
| `userEmail` | string | Optional | User email | `PointsRepository.kt:250` |
| `userName` | string | Optional | User display name | `PointsRepository.kt:251` |
| `planId` | string | Mandatory | Plan SKU | `PointsRepository.kt:252` |
| `requestedPlan` | string | Mandatory | `"pro"` or `"pro_lite"` | `PointsRepository.kt:253` |
| `requestedDuration`| string | Mandatory | Formatted duration description | `PointsRepository.kt:254` |
| `durationDays` | integer/number | Mandatory | 1, 7, 10, or 30 | `PointsRepository.kt:255` |
| `status` | string | Mandatory | Initialized to `"PENDING"`; can be updated to `"CANCELLED"` | `PointsRepository.kt:256, 319` |
| `paymentReference`| string | Mandatory | User reference | `PointsRepository.kt:257` |
| `notes` | string | Optional | User note | `PointsRepository.kt:258` |
| `userNote` | string | Optional | Legacy mirror of notes | `PointsRepository.kt:259` |
| `paymentMethod` | string | Optional | Payment method name | `PointsRepository.kt:264` |
| `paymentProofUrl`| string | Optional | Cloudinary uploaded image URL | `PointsRepository.kt:267` |
| `createdAt` | integer/number | Mandatory | Creation timestamp millis | `PointsRepository.kt:260` |
| `updatedAt` | integer/number | Mandatory | Update timestamp millis | `PointsRepository.kt:261, 321` |
| `cancelledAt` | integer/number | Optional | Set upon user cancellation | `PointsRepository.kt:320` |

---

### 3.18 Path: `/reports/{reportId}`

- **Document ID:** Auto-generated ID (`db.collection("reports").document().id`).
- **Authorization in Rules:**
  - Read: `isAdmin() || (isAuthenticated() && resource.data.userId == request.auth.uid)`
  - Create: `isAuthenticated() && request.resource.data.userId == request.auth.uid && request.resource.data.status in ['PENDING', 'pending'] && no admin resolution fields`
  - Update, Delete: `isAdmin()`

#### Field Specification

| Field Name | Expected Type | Requiredness | Client Mutability | Code Evidence |
|------------|---------------|--------------|-------------------|---------------|
| `id` / `reportId` | string | Mandatory | Generated document ID | `ReportRepository.kt:32, 33` |
| `userId` | string | Mandatory | Set to `request.auth.uid` | `ReportRepository.kt:34` |
| `userEmail` | string | Optional | User email | `ReportRepository.kt:35` |
| `type` | string | Mandatory | `"content"`, etc. | `ReportRepository.kt:36` |
| `targetType` | string | Mandatory | `"movie"`, `"tv"`, etc. | `ReportRepository.kt:37` |
| `targetId` / `contentId`| string | Mandatory | Media identifier | `ReportRepository.kt:38, 45` |
| `title` | string | Mandatory | Report title | `ReportRepository.kt:39` |
| `description` / `details`| string | Optional | Report details | `ReportRepository.kt:40, 44` |
| `status` | string | Mandatory | Must be `"PENDING"` | `ReportRepository.kt:41` |
| `reason` | string | Mandatory | Report reason code | `ReportRepository.kt:43` |
| `contentType` | string | Optional | Content type mirror | `ReportRepository.kt:46` |
| `createdAt` | integer/number | Mandatory | Timestamp millis | `ReportRepository.kt:42` |

---

### 3.19 Path: `/support_conversations/{conversationId}`

- **Document ID:** User UID (`conversationId == user.uid`).
- **Subcollection:** `/messages/{messageId}`
- **Authorization in Rules:**
  - Read: `isAdmin() || (isAuthenticated() && resource.data.userId == request.auth.uid)`
  - Create: `isAuthenticated() && request.resource.data.userId == request.auth.uid && request.resource.data.status in ['open', 'pending']`
  - Update: `isAuthenticated() && resource.data.userId == request.auth.uid && diff().affectedKeys().hasOnly(['lastMessage', 'lastMessageTime', 'updatedAt', 'unreadCount', 'unreadCounts'])`
  - Subcollection Messages:
    - Read: `isAdmin() || (isAuthenticated() && parent.userId == request.auth.uid)`
    - Create: `isAuthenticated() && (parent.userId == request.auth.uid && request.resource.data.senderId == request.auth.uid && request.resource.data.senderRole == 'user')`

#### Field Specification: `/support_conversations/{conversationId}`

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `conversationId` | string | Mandatory | Primary key (user UID) | `SupportViewModel.kt:114` |
| `userId` | string | Mandatory | Must equal `request.auth.uid` | `SupportViewModel.kt:115` |
| `userEmail` | string | Optional | User email | `SupportViewModel.kt:116` |
| `userName` | string | Optional | User name | `SupportViewModel.kt:117` |
| `status` | string | Mandatory | Initialized to `"open"` | `SupportViewModel.kt:118` |
| `lastMessage` | string | Mandatory | Updated on new message | `SupportViewModel.kt:107, 119` |
| `lastMessageTime`| integer/number | Mandatory | Updated on new message | `SupportViewModel.kt:108, 120` |
| `updatedAt` | timestamp | Mandatory | Client passes `FieldValue.serverTimestamp()` | `SupportViewModel.kt:109, 121` |
| `createdAt` | timestamp | Mandatory on create | Immutable | `SupportViewModel.kt:122` |

#### Field Specification: `/support_conversations/{conversationId}/messages/{messageId}`

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `id` / `messageId`| string | Mandatory | UUID | `SupportViewModel.kt:129, 130` |
| `conversationId` | string | Mandatory | User UID | `SupportViewModel.kt:131` |
| `senderId` | string | Mandatory | Must match `request.auth.uid` | `SupportViewModel.kt:132` |
| `senderRole` | string | Mandatory | Constant `"user"` | `SupportViewModel.kt:133` |
| `text` | string | Mandatory | Message text content | `SupportViewModel.kt:134` |
| `timestamp` | integer/number | Mandatory | Timestamp millis | `SupportViewModel.kt:135` |
| `createdAt` | timestamp | Optional | Client passes `FieldValue.serverTimestamp()` | `SupportViewModel.kt:136` |

---

### 3.20 Path: `/conversations/{conversationId}`

- **Document ID:** Sorted composite `uidA_uidB`.
- **Subcollections:** `/messages/{messageId}`, `/readReceipts/{uid}`
- **Authorization in Rules:**
  - Read: `isAdmin() || request.auth.uid in resource.data.participants`
  - Create: `request.auth.uid in participants && participants.size() == 2 && canChat != false && chatBan != true && isBanned != true`
  - Update: Only `lastMessage`, `lastMessageTime`, `updatedAt` via `affectedKeys().hasOnly(...)`.
  - Messages Subcollection:
    - Create: `senderId == request.auth.uid && request.auth.uid in parent.participants && canChat != false && not banned`
    - Update: Sender can update `text`, `isEdited`, `isDeleted`, `reactions`, `deletedFor`; recipient can update `reactions`, `deletedFor`.
    - Delete: `senderId == request.auth.uid`
  - ReadReceipts Subcollection:
    - Create/Update: `request.auth.uid == uid && lastReadAt == request.time && updatedAt == request.time`

#### Field Specification: `/conversations/{conversationId}`

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `id` | string | Mandatory | Composite `uid1_uid2` | `SocialRepository.kt:49, 289` |
| `participants` | array of strings | Mandatory | Exactly 2 distinct UIDs | `SocialRepository.kt:50, 290` |
| `participantNames`| map<string, string> | Optional | Initialized on create | `SocialRepository.kt:51, 291` |
| `lastMessage` | string | Optional | Updated on send message | `SocialRepository.kt:52, 316, 484, 508, 532` |
| `lastMessageTime`| integer/number | Optional | Updated on send message | `SocialRepository.kt:53, 317, 486, 509, 534` |
| `unreadCounts` | map<string, integer>| Optional | Blocked by rules from client update | `SocialRepository.kt:54, 312` |
| `isGroup` | boolean | Optional | Default `false` | `SocialRepository.kt:55` |
| `isRequest` | boolean | Optional | Default `false` | `SocialRepository.kt:56` |

#### Field Specification: `/conversations/{conversationId}/messages/{messageId}`

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `id` | string | Mandatory | Document ID | `SocialRepository.kt:33, 305` |
| `senderId` | string | Mandatory | Must match `request.auth.uid` | `SocialRepository.kt:34, 305` |
| `text` | string | Mandatory | Message text (or label) | `SocialRepository.kt:35, 305` |
| `timestamp` | integer/number | Mandatory | Timestamp millis | `SocialRepository.kt:36, 305` |
| `isDeleted` | boolean | Optional | Updated on delete-for-everyone | `SocialRepository.kt:38, 426` |
| `isEdited` | boolean | Optional | Updated on edit message | `SocialRepository.kt:40, 415` |
| `isVoice` | boolean | Optional | Set on voice message create | `SocialRepository.kt:42, 502, 525` |
| `mediaUrl` | string/null | Optional | Cloudinary media URL | `SocialRepository.kt:43, 478, 502` |
| `deletedFor` | array of strings | Optional | Array of UIDs who hid message | `SocialRepository.kt:44, 439` |
| `reactions` | map<string, string> | Optional | Map of `uid -> emoji` | `SocialRepository.kt:45, 462` |

#### Field Specification: `/conversations/{conversationId}/readReceipts/{uid}`

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `uid` | string | Mandatory | Must match `request.auth.uid` | `SocialRepository.kt:260` |
| `lastReadAt` | timestamp | Mandatory | Client passes `FieldValue.serverTimestamp()` | `SocialRepository.kt:261` |
| `updatedAt` | timestamp | Mandatory | Client passes `FieldValue.serverTimestamp()` | `SocialRepository.kt:262` |

---

### 3.21 Path: `/stories/{storyId}`

- **Document ID:** Auto-generated ID (`db.collection("stories").document().id`).
- **Authorization in Rules:**
  - Read: `isAuthenticated()`
  - Create: `isAuthenticated() && request.resource.data.userId == request.auth.uid && canStory != false && storyBan != true && isBanned != true`
  - Update: Only `imageUrl` and `timestamp` by creator.
  - Delete: `isAdmin() || (isAuthenticated() && resource.data.userId == request.auth.uid)`

#### Field Specification

| Field Name | Expected Type | Requiredness | Mutability | Code Evidence |
|------------|---------------|--------------|------------|---------------|
| `id` | string | Mandatory | Primary key | `SocialRepository.kt:60, 397` |
| `userId` | string | Mandatory | Must match `request.auth.uid` | `SocialRepository.kt:61, 395` |
| `imageUrl` | string | Mandatory | Cloudinary image URL | `SocialRepository.kt:62, 395` |
| `timestamp` | integer/number | Mandatory | Timestamp millis | `SocialRepository.kt:63, 395` |

---

## 4. Cancelled & Deprecated Collections Contract

| Collection Path | Intended Feature | Contract Status | Rules Rule | Code Status |
|-----------------|------------------|-----------------|------------|-------------|
| `/comments` | User comments on media | CANCELLED & FORBIDDEN | Denied by default catch-all (`match /{document=**}`) | No repository, no ViewModel, no Firestore calls. |
| `/comments/{commentId}` | Single comment | CANCELLED & FORBIDDEN | Denied by default catch-all | No active code. |
| `/config/global` | Legacy configuration | DEPRECATED & FORBIDDEN | Explicitly denied (`match /config/{document=**} { allow read, write: if false; }`) | Not called in code (replaced by `/config/app`). |
| `/audit_logs` | Legacy snake_case audit logs | DEPRECATED & FORBIDDEN | Explicitly denied (`match /audit_logs/{document=**} { allow read, write: if false; }`) | Not called in code. |
| `/auditLogs` | Authoritative audit logs | ADMIN ONLY | `allow read, write: if isAdmin();` | Handled by backend only. |
