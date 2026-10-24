# Phase 22.22-A: CineStream User App — Restore Original Firestore Rules Fingerprint and Safe Rules Update Preparation Report

**Date of Execution:** October 20, 2026  
**Auditor / Security Role:** Senior Android & Firebase Security Engineer  
**Execution Context:** Independent CineStream User App Session (Google AI Studio Build)  
**Target Platform:** Android (Kotlin / Jetpack Compose)  
**Execution Mode:** STRICT READ-ONLY / FORENSIC VERIFICATION / ARTIFACT GENERATION  
**Primary Deliverables:**
1. Reference Fingerprint Verification: `CINESTREAM_PRO firestore.rules.txt` (`54c8570a...`)
2. Current Firebase Rules Source: `Rules.md` (`4bad2bf5...`)
3. Verified Identity Source: `google-services.json` (`9bdb880b...`)
4. Candidate Unified Ruleset: `PHASE_22.22-A_FIRESTORE_RULES_UPDATE_CANDIDATE.rules` (`530eb603...`)
5. Comprehensive Audit Report: `PHASE_22.22-A_USER_APP_FIRESTORE_RULES_UPDATE_REPORT.md`

---

## 1. Executive Summary

This phase was executed under strict safety, evidence-based constraints to:
1. **Verify and restore the cryptographic fingerprint** of the canonical CineStream User App Firestore security ruleset (`54c8570a...`).
2. **Conduct a comprehensive line-by-line security and functional difference audit** against the currently active Firebase ruleset provided in `Rules.md` (`4bad2bf5...`).
3. **Forensically audit client identity and package registration** using the verified `google-services.json` (`9bdb880b...`), identifying and tagging any local project discrepancy with `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` without altering build configs.
4. **Engineer a candidate unified ruleset** (`PHASE_22.22-A_FIRESTORE_RULES_UPDATE_CANDIDATE.rules`) that reconciles Admin Dashboard management needs with User App runtime contracts, while restoring critical broken paths (`/public_profiles` and `/conversations/{id}/readReceipts`).
5. **Enforce absolute deployment safety**: No rules were deployed to Firebase (`NO_FIREBASE_DEPLOYMENT`), the original reference `firestore.rules` remains intact and verified, and no live data was modified.

---

## 2. Cryptographic Fingerprint Verification Matrix

The cryptographic SHA-256 hashes, exact line counts, and byte sizes were calculated directly on the filesystem:

| File Name / Path | SHA-256 Hash | Exact Bytes | Lines | Status & Role |
| :--- | :--- | :--- | :--- | :--- |
| **`firestore.rules`** (Local Root) | `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef` | 29,766 | 812 | **VERIFIED & RESTORED** (User App Canonical Reference) |
| **`CINESTREAM_PRO firestore.rules.txt`** | `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef` | 29,766 | 812 | **VERIFIED MATCH** (Duplicate reference artifact for export) |
| **`Rules.md`** / `Rules_4bad2bf5.md` | `4bad2bf56454ed804247dab7a112c54e2db5da66ecfb8ef7b70fb7659bc63fae` | 23,661 | 710 | **VERIFIED MATCH** (Active Firebase Rules attached by user) |
| **`google-services.json`** (`google-services_9bdb880b.json`) | `9bdb880b9e47424f8ce833de7631967ff3b37c84795b6d3075e7faa8e390d895` | 2,553 | 85 | **VERIFIED MATCH** (Official Firebase registration file for both apps) |
| **`PHASE_22.22-A_FIRESTORE_RULES_UPDATE_CANDIDATE.rules`** | `530eb603899eaf342c650e287fc2a916d650f4a14ef40765fbba97c8fea0e8ee` | 28,994 | 795 | **GENERATED** (Unified Candidate for safe upgrade) |
| **`firestore.rules.candidate.txt`** | `530eb603899eaf342c650e287fc2a916d650f4a14ef40765fbba97c8fea0e8ee` | 28,994 | 795 | **VERIFIED MATCH** (Candidate rules in text format) |

### Fingerprint Verification Verdict: **PASS (100% Cryptographic Match)**

---

## 3. Firebase Identity & Package Registration Forensic Audit

### 3.1 Verified Canonical Identity (Source: `google-services_9bdb880b.json`)

The verified Firebase registration file contains the official production configuration for the CineStream ecosystem under project `cinestream-sulo`:

- **Firebase Project ID:** `cinestream-sulo`
- **Firebase Project Number:** `979447256418`
- **Firebase Database URL:** `https://cinestream-sulo-default-rtdb.firebaseio.com`
- **Storage Bucket:** `cinestream-sulo.firebasestorage.app`

#### User App Registration (Client Index 0):
- **Android Package Name:** `com.aistudio.cinestream.xyzabc`
- **Firebase Android App ID:** `1:979447256418:android:037a9b3a1393e550a9883b`
- **Android OAuth Client ID (Type 1):** `979447256418-9jl4cl94f22a227strvhj3ups98dpka0.apps.googleusercontent.com`
- **Certificate SHA-1:** `1e0f4a4a0ee84b7023e59504b3a978382d019e2a`
- **Web OAuth Client ID (Type 3, Shared):** `979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com`
- **API Key:** `AIzaSyC9CvaM9Mw3NiD-KsOiYvHZHj6XZJQJnPs`

#### Admin App Registration (Client Index 1):
- **Android Package Name:** `com.aistudio.cinestreamadmin.adcxz`
- **Firebase Android App ID:** `1:979447256418:android:ea5b570266ae1aa6a9883b`
- **Android OAuth Client ID (Type 1):** `979447256418-m7u3batvou0mmrubfkmsnmunvpedo0dd.apps.googleusercontent.com`
- **Certificate SHA-1:** `54a814fb04debd983f336ef82e87820edb760f12`
- **Web OAuth Client ID (Type 3, Shared):** `979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com`
- **API Key:** `AIzaSyC9CvaM9Mw3NiD-KsOiYvHZHj6XZJQJnPs`

---

### 3.2 Local Workspace Forensic Audit & Discrepancy Tracking

A meticulous inspection of local configuration files reveals divergences caused by AI Studio container remix defaults:

| File Name & Path | Line Number | Inspected Local Value | Canonical Verified Value | Audit Classification |
| :--- | :--- | :--- | :--- | :--- |
| `app/build.gradle.kts` | Line 20 | `applicationId = "com.aistudio.cinestream.nirydy"` | `applicationId = "com.aistudio.cinestream.xyzabc"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `app/google-services.json` | Line 3 | `"project_id": "remixed-project-id"` | `"project_id": "cinestream-sulo"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `app/google-services.json` | Line 4 | `"project_number": "1234567890"` | `"project_number": "979447256418"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `app/google-services.json` | Line 10 | `"mobilesdk_app_id": "1:1234567890:android:remixedappid"` | `"mobilesdk_app_id": "1:979447256418:android:037a9b3a1393e550a9883b"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `app/google-services.json` | Line 12 | `"package_name": "com.aistudio.cinestream.nirydy"` | `"package_name": "com.aistudio.cinestream.xyzabc"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `app/google-services.json` | Line 17 | `"client_id": "1234567890-examplewebclientid..."` | `"client_id": "979447256418-9jl4cl94f22a227strvhj3ups98dpka0..."` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `firebase-applet-config.json` | Line 2 | `"projectId": "ai-studio-applet-webapp-71606"` | `"projectId": "cinestream-sulo"` | `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL` |
| `.github/workflows/build.yml` | Line 47 | Decodes base64 payload matching canonical `cinestream-sulo` | `cinestream-sulo` (`com.aistudio.cinestream.xyzabc`) | **MATCH (CI Production Pipeline)** |

### Strict Action Taken:
In accordance with user instructions (*"Do not alter Firebase project ID, package name, Firebase App ID, SHA keys, or build settings without explicit approval"*), **NO MODIFICATIONS** were made to `app/build.gradle.kts`, `app/google-services.json`, or `firebase-applet-config.json`. All mismatches are formally documented above and tagged `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL`.

Status Classification: **`LOCAL_REFERENCE_ONLY`** (No live console credentials available in this session).

---

## 4. Comprehensive Security and Functional Rules Diff Audit

A granular comparison was conducted between `CINESTREAM_PRO firestore.rules.txt` (812 lines, reference) and `Rules.md` (710 lines, active Firebase).

### 4.1 Helper Functions & Administrative Identity

#### Evidence from `CINESTREAM_PRO firestore.rules.txt`:
```javascript
// Lines 34-53
function isOwnerAccount() {
  return isAuthenticated() &&
    request.auth.token.email != null &&
    request.auth.token.email_verified == true &&
    request.auth.token.email.matches('(?i)sulopros01@gmail.com');
}

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

#### Evidence from `Rules.md`:
```javascript
// Function isOwnerAccount() is COMPLETELY MISSING
function isAdmin() {
  return isAuthenticated() && (
    (
      request.auth.token.email != null &&
      request.auth.token.email.matches('(?i)sulopros01@gmail.com')
    ) ||
    (
      exists(/databases/$(database)/documents/admins/$(request.auth.uid)) &&
      get(/databases/$(database)/documents/admins/$(request.auth.uid)).data.enabled == true
    )
  );
}
```

#### Difference Analysis:
1. `Rules.md` completely omits the `isOwnerAccount()` helper.
2. `Rules.md` removed `request.auth.token.email_verified == true` from `isAdmin()`.
3. **Security Assessment:** Removing `email_verified == true` allows an account registered with email/password (if unverified) or identity providers without email verification to gain full `isAdmin()` privileges if matching `sulopros01@gmail.com`. However, for Google Sign-In, Google accounts always provide `email_verified == true`.

---

### 4.2 `/admins/{adminUid}` Document Management

#### Evidence from `CINESTREAM_PRO firestore.rules.txt`:
```javascript
// Lines 189-195
match /admins/{adminUid} {
  allow read: if isAdmin() || (isAuthenticated() && request.auth.uid == adminUid);
  allow write: if isOwnerAccount();
}
```

#### Evidence from `Rules.md`:
```javascript
match /admins/{adminUid} {
  allow read: if isAdmin() || (isAuthenticated() && request.auth.uid == adminUid);
  allow write: if isAdmin() ||
    (
      isAuthenticated() &&
      request.auth.uid == adminUid &&
      request.auth.token.email != null &&
      request.auth.token.email.matches('(?i)sulopros01@gmail.com')
    );
}
```

#### Difference Analysis:
1. `CINESTREAM_PRO` restricts writes strictly to `isOwnerAccount()`, preventing subordinate admins from creating or modifying other admin records.
2. `Rules.md` allows any existing `isAdmin()` to write to `/admins/{adminUid}`, and provides a bootstrap fallback allowing `sulopros01@gmail.com` to write their own admin document (`request.auth.uid == adminUid`).
3. **Operational Assessment:** The bootstrap write in `Rules.md` is critical during initial project deployment to establish the first admin document in Firestore.

---

### 4.3 Public Profile Projection (`/public_profiles/{userId}`) — [CRITICAL BREAKAGE IN `Rules.md`]

#### Evidence from `CINESTREAM_PRO firestore.rules.txt`:
```javascript
// Lines 202-231 (30 lines of explicit security rules)
match /public_profiles/{userId} {
  allow read: if isAuthenticated() &&
    resource.data.isProfilePublic == true;

  allow create: if isAuthenticated() &&
    request.auth.uid == userId &&
    request.resource.data.uid == userId &&
    request.resource.data.keys().hasOnly([
      'uid', 'username', 'firstName', 'lastName', 'photoUrl',
      'isOnline', 'isProfilePublic', 'bio', 'updatedAt'
    ]) &&
    request.resource.data.isProfilePublic is bool &&
    request.resource.data.isOnline is bool;

  allow update: if isAuthenticated() &&
    request.auth.uid == userId &&
    request.resource.data.uid == resource.data.uid &&
    request.resource.data.keys().hasOnly([
      'uid', 'username', 'firstName', 'lastName', 'photoUrl',
      'isOnline', 'isProfilePublic', 'bio', 'updatedAt'
    ]) &&
    request.resource.data.diff(resource.data).affectedKeys().hasOnly([
      'username', 'firstName', 'lastName', 'photoUrl',
      'isOnline', 'isProfilePublic', 'bio', 'updatedAt'
    ]) &&
    request.resource.data.isProfilePublic is bool &&
    request.resource.data.isOnline is bool;

  allow delete: if isAdmin() || (isAuthenticated() && request.auth.uid == userId);
}
```

#### Evidence from `Rules.md`:
```
// COMPLETELY ABSENT FROM Rules.md!
// No match rule for /public_profiles/{userId} exists.
```

#### Severe Impact Analysis:
- In `Rules.md`, because there is no rule matching `/public_profiles/{userId}`, all operations fall through to the catch-all deny rule: `match /{document=**} { allow read, write: if false; }`.
- **User App Impact:**
  1. `AuthRepository.kt:620` (`isUsernameTaken`): Queries `/public_profiles` where `username == candidate`. **Fails with `PERMISSION_DENIED`**, breaking account creation / onboarding.
  2. `AuthRepository.kt:700` (`syncPublicProfile`): Writes to `/public_profiles/{uid}` on user profile save. **Fails with `PERMISSION_DENIED`**.
  3. `SocialRepository.kt:49` (`searchUsers`): Queries `/public_profiles` to find friends. **Fails with `PERMISSION_DENIED`**.
  4. User Profile Screen: Unable to read user display profiles.

---

### 4.4 Economy Ledgers: `/point_transactions` and `/task_claims`

#### Evidence from `CINESTREAM_PRO firestore.rules.txt`:
```javascript
// Lines 257-273
match /point_transactions/{txId} {
  allow read: if isAdmin() || isOwner(userId);
  allow write: if isAdmin();
}

match /task_claims/{taskId} {
  allow read: if isAdmin() || isOwner(userId);
  allow write: if isAdmin();
}
```

#### Evidence from `Rules.md`:
```javascript
match /point_transactions/{txId} {
  allow read: if isAdmin() || isOwner(userId);
  allow write: if isAdmin() ||
    (
      isOwner(userId) &&
      request.resource.data.userId == userId
    );
}

match /task_claims/{taskId} {
  allow read: if isAdmin() || isOwner(userId);
  allow write: if isAdmin() ||
    (
      isOwner(userId) &&
      request.resource.data.userId == userId
    );
}
```

#### Functional & Security Analysis:
1. `CINESTREAM_PRO` enforces a **Trusted Backend Economy Architecture**: all points and claims must be written by a Cloudflare Worker or backend service via Admin SDK.
2. `Rules.md` enables **Temporary Firebase-only Economy Mode**: allows the owning user client to create transactions in their own ledger subcollection.
3. **Defensive Limitation Discovered in Both:**
   In BOTH rulesets, `users/{userId}` has `modifyingSensitiveUserFields()` which blocks client updates to `pointsBalance`, `totalPointsEarned`, and `subscriptionTier`. Therefore, while `Rules.md` allows writing transaction history records, client-side mutations to the main user wallet still require a backend service.

---

### 4.5 Audience-Targeted Notifications (`/notifications/{notificationId}`)

#### Evidence from `CINESTREAM_PRO firestore.rules.txt`:
```javascript
// Lines 453-469
match /notifications/{notificationId} {
  allow read: if isAdmin() || (
    isAuthenticated() && (
      (('target' in resource.data) && resource.data.target == 'all') ||
      (('target' in resource.data) && resource.data.target == 'user' &&
        ('targetUid' in resource.data) && resource.data.targetUid == request.auth.uid) ||
      (('target' in resource.data) && resource.data.target == 'pro' &&
        exists(/databases/$(database)/documents/users/$(request.auth.uid)) &&
        (
          get(/databases/$(database)/documents/users/$(request.auth.uid)).data.isPro == true ||
          get(/databases/$(database)/documents/users/$(request.auth.uid)).data.isPremium == true ||
          get(/databases/$(database)/documents/users/$(request.auth.uid)).data.subscriptionTier in ['pro', 'PRO']
        ))
    )
  );
  allow write: if isAdmin();
}
```

#### Evidence from `Rules.md`:
```javascript
match /notifications/{notificationId} {
  allow read: if isAuthenticated();
  allow write: if isAdmin();
}
```

#### Difference Analysis:
1. `CINESTREAM_PRO` enforces granular multi-tier audience targeting (`all`, `user`, `pro`). However, because Firestore security rules act as authorizers and not query filters, client-side queries against `/notifications` that do not perfectly mirror the security conditions fail at runtime with `PERMISSION_DENIED`.
2. `Rules.md` relaxes reads to `allow read: if isAuthenticated()`. This allows User App's `NotificationRepository.kt` to run broad queries, but technically permits any authenticated user to view notifications targeted to specific users if querying the document directly.
3. Both rulesets strictly restrict writes to `isAdmin()`.

---

### 4.6 Subscription Pro Requests (`/pro_requests/{requestId}`)

#### Evidence from `CINESTREAM_PRO firestore.rules.txt`:
```javascript
// Lines 536-547
allow update: if isAdmin() || (
  isAuthenticated() &&
  resource.data.userId == request.auth.uid &&
  request.resource.data.userId == request.auth.uid &&
  (resource.data.status == 'PENDING' || resource.data.status == 'pending') &&
  request.resource.data.status == 'CANCELLED' &&
  request.resource.data.diff(resource.data).affectedKeys().hasOnly(['status', 'updatedAt', 'cancelledAt'])
);

allow delete: if isAdmin();
```

#### Evidence from `Rules.md`:
```javascript
allow update, delete: if isAdmin();
```

#### Difference Analysis:
- In `CINESTREAM_PRO`, a user who submitted a subscription request can safely cancel their own request while it is still in `PENDING` status.
- In `Rules.md`, user cancellation is completely disabled. Only admins can update or delete requests.
- **Verdict:** `CINESTREAM_PRO` provides superior user experience and self-service capability while preventing unauthorized status promotion.

---

### 4.7 Support Ticket Conversations (`/support_conversations/{conversationId}`)

#### Evidence from `CINESTREAM_PRO firestore.rules.txt`:
```javascript
// Lines 596-607
allow update: if isAdmin() || (
  isAuthenticated() &&
  resource.data.userId == request.auth.uid &&
  request.resource.data.userId == resource.data.userId &&
  request.resource.data.diff(resource.data).affectedKeys().hasOnly([
    'lastMessage', 'lastMessageTime', 'updatedAt', 'unreadCount', 'unreadCounts'
  ]) &&
  (!('createdAt' in request.resource.data) ||
    request.resource.data.createdAt == resource.data.createdAt) &&
  (!('status' in request.resource.data) ||
    request.resource.data.status == resource.data.status)
);
```

#### Evidence from `Rules.md`:
```javascript
allow update: if isAdmin() || (
  isAuthenticated() &&
  resource.data.userId == request.auth.uid &&
  request.resource.data.userId == resource.data.userId &&
  (
    !('createdAt' in request.resource.data) ||
    request.resource.data.createdAt == resource.data.createdAt
  )
);
```

#### Difference Analysis:
- `CINESTREAM_PRO` strictly limits the client update diff to conversation metadata (`lastMessage`, `lastMessageTime`, `updatedAt`, `unreadCount`), and explicitly prevents the user from altering `status` (e.g. changing from closed to open, or setting admin priority).
- `Rules.md` omits `diff().affectedKeys()` and allows normal users to overwrite ticket status, priority, or administrative notes.
- **Security Vulnerability in `Rules.md`:** Normal users can escalate privileges on their support ticket by modifying administrative fields.

---

### 4.8 Direct Messaging & Read Receipts (`/conversations` and subcollections) — [CRITICAL BREAKAGE IN `Rules.md`]

#### A. Read Receipts Subcollection (`/conversations/{convId}/readReceipts/{uid}`)
- **In `CINESTREAM_PRO firestore.rules.txt` (Lines 745-757):**
  Full rule block allowing participants to read, create, and update their own read receipts with timestamp enforcement (`request.time`).
- **In `Rules.md`:**
  **COMPLETELY OMITTED!**
- **Impact on User App:**
  `SocialRepository.kt:415` calls:
  `convRef.collection("readReceipts").document(currentUserId).set(receiptData).await()`
  In `Rules.md`, this operation **fails immediately with `PERMISSION_DENIED`**, breaking chat message read synchronization.

#### B. Conversation Document Update Diff:
- **In `CINESTREAM_PRO firestore.rules.txt` (Lines 683-685):**
  Restricts updates to `lastMessage`, `lastMessageTime`, and `updatedAt`. Explicitly protects `unreadCounts` from cross-participant tampering.
- **In `Rules.md`:**
  Omits the diff restriction, allowing participants to overwrite each other's unread counters or conversation metadata.

#### C. Conversation Creation Validation:
- **In `CINESTREAM_PRO firestore.rules.txt` (Lines 654-658):**
  Validates that participants list has size 2 and distinct IDs (`participants[0] != participants[1]`).
- **In `Rules.md`:**
  Omits participant size and distinctness validation.

---

### 4.9 Cancelled Comments Feature Isolation (`/comments`)

Both `CINESTREAM_PRO firestore.rules.txt` and `Rules.md` exhibit **100% agreement**:
- Neither file contains any rule granting access to `/comments`.
- All requests targeting `/comments` fall through to the final catch-all deny rule:
  ```javascript
  match /{document=**} {
    allow read, write: if false;
  }
  ```
- **Conclusion:** The complete decommissioning and denial of the comments feature is preserved in both rulesets.

---

## 5. Summary of Breakages if `Rules.md` Were Deployed to User App

| Feature / User App Component | Kotlin Source File & Line | Firestore Path | Result Under `Rules.md` |
| :--- | :--- | :--- | :--- |
| **Username Availability Check** | `AuthRepository.kt:620` | `/public_profiles` (query) | **FAIL (403 Permission Denied)** |
| **Public Profile Synchronization** | `AuthRepository.kt:700` | `/public_profiles/{uid}` | **FAIL (403 Permission Denied)** |
| **Social User Search / Discovery** | `SocialRepository.kt:49` | `/public_profiles` (query) | **FAIL (403 Permission Denied)** |
| **Chat Read Receipts** | `SocialRepository.kt:415` | `/conversations/{id}/readReceipts/{uid}` | **FAIL (403 Permission Denied)** |
| **Pro Request Self-Cancellation** | `PointsRepository.kt:188` | `/pro_requests/{id}` | **FAIL (403 Permission Denied)** |
| **Support Ticket Security** | `SupportViewModel.kt` | `/support_conversations/{id}` | **VULNERABILITY (Field tampering)** |
| **Notification Isolation** | `NotificationRepository.kt` | `/notifications/{id}` | **PRIVACY LEAK (Broad authenticated read)** |

---

## 6. Architecture of the Unified Candidate Ruleset

To resolve all divergences between the CineStream User App and the Admin Dashboard, the unified candidate ruleset (`PHASE_22.22-A_FIRESTORE_RULES_UPDATE_CANDIDATE.rules`) was engineered with the following principles:

1. **Restored `/public_profiles/{userId}` with Query Capability:**
   - Public profile discovery is allowed for authenticated users (`allow read: if isAuthenticated();`). This resolves the query failure in `AuthRepository.kt:620` without exposing sensitive user fields (which remain strictly in `/users/{userId}`).
   - Schema keys, types, and owner modification restrictions are fully preserved.
2. **Restored `/conversations/{id}/readReceipts/{uid}`:**
   - Subcollection rules restored with participant verification and timestamp enforcement, enabling real-time chat read receipts in `SocialRepository.kt`.
3. **Preserved Self-Cancellation for `/pro_requests`:**
   - Users can cancel their own pending requests, reducing admin ticket volume.
4. **Owner Bootstrap and Multi-Admin Management in `/admins`:**
   - Adopts the `Rules.md` bootstrap logic allowing `sulopros01@gmail.com` to establish their initial admin document, while also permitting authorized admins to manage admin records.
5. **Validated Temporary Economy Support:**
   - Allows owner writes to `/point_transactions` and `/task_claims` constrained by `request.resource.data.userId == userId`, accommodating standalone client testing while maintaining ledger separation.
6. **Hardened Support Conversation Updates:**
   - Diff validation reinstated to prevent users from altering support ticket `status` or internal notes.
7. **Maintained Zero Access to `/comments`:**
   - Retained explicit catch-all deny.

---

## 7. Cross-App Compatibility Contract (`NEEDS_EXTERNAL_COMPARISON`)

Because this session operates exclusively within the CineStream User App workspace, the following integration contracts require mutual verification in the Admin Dashboard session before deploying to production:

| Firestore Path | User App Requirement | Admin Dashboard Requirement | External Verification Tag |
| :--- | :--- | :--- | :--- |
| `/admins/{adminUid}` | Must allow owner bootstrap & read by admin UID | Must allow Super Admins to list & update admin roles | `NEEDS_EXTERNAL_COMPARISON` |
| `/public_profiles/{userId}` | Critical for search, username checks, and profiles | Admin may not directly write here; must not be removed from rules | `NEEDS_EXTERNAL_COMPARISON` |
| `/support_conversations/{id}` | User writes messages and updates unread counts | Admin assigns tickets, updates status, and replies | `NEEDS_EXTERNAL_COMPARISON` |
| `/pro_requests/{id}` | User creates pending request & can cancel | Admin approves/rejects and updates status | `NEEDS_EXTERNAL_COMPARISON` |
| `/notifications/{id}` | User reads active notifications | Admin creates, edits, and broadcasts notifications | `NEEDS_EXTERNAL_COMPARISON` |

---

## 8. Strict Safety Confirmations

1. **No Deployment to Firebase:**  
   The `firebase deploy` command was **NOT** executed. No network requests were dispatched to Firebase management endpoints.
2. **Canonical Reference Integrity:**  
   The root file `firestore.rules` remains unaltered with SHA-256 fingerprint:  
   `54c8570a25ece1fb6a57bfb0aa2560a50202240a30d4a4cf7a8afd694a18d5ef`.
3. **No Database Modification:**  
   No documents, collections, or indexes in Cloud Firestore were written, deleted, or modified.
4. **No Build Configuration Tampering:**  
   In compliance with safety instructions, `app/build.gradle.kts` and `app/google-services.json` were not altered without explicit approval; discrepancies were logged with `IDENTITY_MISMATCH_REQUIRES_EXPLICIT_APPROVAL`.

---

## 9. Recommended Next Steps

1. **Central Review:**  
   Compare `PHASE_22.22-A_FIRESTORE_RULES_UPDATE_CANDIDATE.rules` against the Admin Dashboard requirements.
2. **Identity Alignment Approval:**  
   Obtain explicit approval to align `applicationId` in `app/build.gradle.kts` and `app/google-services.json` to canonical package `com.aistudio.cinestream.xyzabc` and project `cinestream-sulo`.
3. **Single Controlled Deployment:**  
   Deploy the validated candidate ruleset once unified across both applications to prevent regression of `/public_profiles` and `/readReceipts`.
