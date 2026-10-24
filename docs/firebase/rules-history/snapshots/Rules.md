rules_version = '2';

service cloud.firestore {
  match /databases/{database}/documents {

    // ============================================================
    // CINESTREAM — UNIFIED FIRESTORE RULES
    // Canonical Ruleset for Users App + Admin App
    // Firebase Project: cinestream-sulo
    //
    // Authority:
    //   1. Owner bootstrap email: sulopros01@gmail.com
    //   2. /admins/{uid}.enabled == true
    //
    // Subscription remains REMOVE_ADS ONLY.
    // Users App economy is TEMPORARY Firebase-only mode.
    // No wildcard grants. Final catch-all deny.
    // ============================================================

    // ------------------------------------------------------------
    // Helper Functions
    // ------------------------------------------------------------

    function isAuthenticated() {
      return request.auth != null;
    }

    function isOwner(uid) {
      return isAuthenticated() && request.auth.uid == uid;
    }

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

    // Fields that normal users must never modify.
    function modifyingSensitiveUserFields() {
      return request.resource.data.diff(resource.data).affectedKeys().hasAny([
        'role',
        'admin',
        'isAdmin',
        'uid',
        'id',
        'createdAt',

        // Legacy subscription fields
        'isPremium',
        'isPro',
        'proExpiresAt',
        'proPlan',
        'plan',

        // Canonical subscription
        'subscriptionTier',
        'planId',
        'durationDays',
        'subscriptionStatus',
        'subscriptionSource',
        'subscriptionReferenceId',
        'subscriptionStartedAt',
        'subscriptionExpiresAt',

        // Economy
        'pointsBalance',
        'totalPointsEarned',
        'totalPointsSpent',

        // Account/admin controls
        'isActive',
        'isBanned',
        'banReason',
        'banExpiresAt',

        // Feature restrictions
        'canWatch',
        'canDownload',
        'canChat',
        'canStory',
        'canP2P',
        'canComment',
        'canUpload',
        'canRequest',
        'watchBan',
        'downloadBan',
        'chatBan',
        'storyBan',
        'p2pBan',

        // Technical restrictions
        'deviceLimit',
        'maxDevices',
        'allowedQuality',
        'downloadLimit',
        'offlineDaysOverride',
        'forcedAdsOverride'
      ]);
    }

    function hasSafeUserCreationDefaults(userId) {
      let data = request.resource.data;

      return isOwner(userId) &&
        (!('uid' in data) || data.uid == userId) &&
        (!('id' in data) || data.id == userId) &&
        (!('role' in data) || data.role == 'user') &&
        (!('admin' in data) || data.admin == false) &&
        (!('isAdmin' in data) || data.isAdmin == false) &&

        (!('isPremium' in data) || data.isPremium == false) &&
        (!('subscriptionTier' in data) || data.subscriptionTier in ['free', 'FREE']) &&
        (!('planId' in data) || data.planId in ['free', '']) &&
        (!('durationDays' in data) || data.durationDays == 0 || data.durationDays == null) &&
        (!('subscriptionStatus' in data) ||
          data.subscriptionStatus in [
            'none', 'NONE', 'inactive', 'INACTIVE',
            'active', 'ACTIVE', 'free', 'FREE', ''
          ]) &&
        (!('subscriptionSource' in data) ||
          data.subscriptionSource in ['LEGACY', 'legacy', 'FREE', 'free', '']) &&
        (!('subscriptionReferenceId' in data) || data.subscriptionReferenceId == null) &&
        (!('subscriptionStartedAt' in data) || data.subscriptionStartedAt == null) &&
        (!('subscriptionExpiresAt' in data) || data.subscriptionExpiresAt == null) &&

        (!('isPro' in data) || data.isPro == false) &&
        (!('proExpiresAt' in data) || data.proExpiresAt == null) &&
        (!('proPlan' in data) || data.proPlan == null || data.proPlan == '') &&
        (!('plan' in data) || data.plan in ['free', '', null]) &&

        (!('pointsBalance' in data) || data.pointsBalance == 0) &&
        (!('totalPointsEarned' in data) || data.totalPointsEarned == 0) &&
        (!('totalPointsSpent' in data) || data.totalPointsSpent == 0) &&

        (!('isActive' in data) || data.isActive == true) &&
        (!('isBanned' in data) || data.isBanned == false) &&
        (!('banReason' in data) || data.banReason == null || data.banReason == '') &&
        (!('banExpiresAt' in data) || data.banExpiresAt == null) &&

        (!('canWatch' in data) || data.canWatch == true) &&
        (!('canDownload' in data) || data.canDownload == true) &&
        (!('canChat' in data) || data.canChat == true) &&
        (!('canStory' in data) || data.canStory == true) &&
        (!('canP2P' in data) || data.canP2P == true) &&
        (!('canComment' in data) || data.canComment == true) &&
        (!('canUpload' in data) || data.canUpload == false || data.canUpload == true) &&
        (!('canRequest' in data) || data.canRequest == true) &&

        (!('watchBan' in data) || data.watchBan == false) &&
        (!('downloadBan' in data) || data.downloadBan == false) &&
        (!('chatBan' in data) || data.chatBan == false) &&
        (!('storyBan' in data) || data.storyBan == false) &&
        (!('p2pBan' in data) || data.p2pBan == false) &&

        (!('deviceLimit' in data) || data.deviceLimit <= 2) &&
        (!('maxDevices' in data) || data.maxDevices <= 2) &&
        (!('allowedQuality' in data) || data.allowedQuality == null) &&
        (!('downloadLimit' in data) || data.downloadLimit == null) &&
        (!('offlineDaysOverride' in data) || data.offlineDaysOverride == null) &&
        (!('forcedAdsOverride' in data) || data.forcedAdsOverride == null)
      ;
    }

    // ============================================================
    // ADMINS
    // ============================================================

    match /admins/{adminUid} {
      allow read: if isAdmin() || (isAuthenticated() && request.auth.uid == adminUid);

      // Owner bootstrap can establish/update their own admin document.
      // Existing admins may manage admin documents.
      allow write: if isAdmin() ||
        (
          isAuthenticated() &&
          request.auth.uid == adminUid &&
          request.auth.token.email != null &&
          request.auth.token.email.matches('(?i)sulopros01@gmail.com')
        );
    }

    // ============================================================
    // USERS
    // ============================================================

    match /users/{userId} {

      allow read: if isAdmin() || isOwner(userId);

      allow create: if isAdmin() || hasSafeUserCreationDefaults(userId);

      // TEMPORARY ECONOMY MODE:
      // Owner may update normal profile/economy/subscription fields.
      // Administrative privilege, bans, and technical restrictions remain
      // protected. This is intentionally temporary Firebase-only authority.
      allow update: if isAdmin() || (
        isOwner(userId) &&
        !modifyingSensitiveUserFields()
      );

      allow delete: if isAdmin();

      // ----------------------------
      // Point Transactions Ledger
      // ----------------------------
      match /point_transactions/{txId} {
        allow read: if isAdmin() || isOwner(userId);

        // Temporary Firebase-only economy mode.
        allow write: if isAdmin() ||
          (
            isOwner(userId) &&
            request.resource.data.userId == userId
          );
      }

      // ----------------------------
      // Task Claims
      // ----------------------------
      match /task_claims/{taskId} {
        allow read: if isAdmin() || isOwner(userId);

        // Temporary Firebase-only economy mode.
        allow write: if isAdmin() ||
          (
            isOwner(userId) &&
            request.resource.data.userId == userId
          );
      }

      // ----------------------------
      // User Library
      // ----------------------------
      match /library/{itemId} {
        allow read, write: if isAdmin() || isOwner(userId);
      }

      // ----------------------------
      // Watch History
      // ----------------------------
      match /history/{itemId} {
        allow read, write: if isAdmin() || isOwner(userId);
      }

      // ----------------------------
      // Watched Episodes
      // ----------------------------
      match /watched_episodes/{episodeId} {
        allow read, write: if isAdmin() || isOwner(userId);
      }

      // ----------------------------
      // Notification Settings
      // ----------------------------
      match /settings/notifications {
        allow read: if isAdmin() || isOwner(userId);

        allow write: if isAdmin() || (
          isOwner(userId) &&
          !request.resource.data.keys().hasAny([
            'role',
            'admin',
            'isAdmin',
            'isPremium',
            'subscriptionTier',
            'isBanned',
            'banReason'
          ])
        );
      }

      match /settings/{document=**} {
        allow read, write: if isAdmin() || isOwner(userId);
      }

      // ----------------------------
      // FCM Device Tokens
      // ----------------------------
      match /fcmTokens/{installationId} {
        allow read: if isAdmin() || isOwner(userId);

        allow create: if (isAdmin() || isOwner(userId)) &&
          request.resource.data.installationId == installationId &&
          request.resource.data.token is string &&
          request.resource.data.token.size() > 0 &&
          request.resource.data.platform == 'android' &&
          request.resource.data.deviceModel is string &&
          request.resource.data.osVersion is string &&
          request.resource.data.appVersion is string &&
          request.resource.data.isActive == true &&
          request.resource.data.createdAt == request.time &&
          request.resource.data.updatedAt == request.time &&
          request.resource.data.lastSeenAt == request.time &&
          !request.resource.data.keys().hasAny([
            'role',
            'admin',
            'isAdmin',
            'isPremium',
            'subscriptionTier',
            'uid',
            'userId'
          ]);

        allow update: if isAdmin() || (
          isOwner(userId) &&
          request.resource.data.installationId == resource.data.installationId &&
          request.resource.data.installationId == installationId &&
          request.resource.data.platform == resource.data.platform &&
          request.resource.data.platform == 'android' &&
          request.resource.data.createdAt == resource.data.createdAt &&
          request.resource.data.updatedAt == request.time &&
          request.resource.data.lastSeenAt == request.time &&
          request.resource.data.token is string &&
          request.resource.data.token.size() > 0 &&
          request.resource.data.deviceModel is string &&
          request.resource.data.osVersion is string &&
          request.resource.data.appVersion is string &&
          request.resource.data.isActive is bool &&
          (!('deactivationReason' in request.resource.data) ||
            request.resource.data.deactivationReason is string) &&
          !request.resource.data.keys().hasAny([
            'role',
            'admin',
            'isAdmin',
            'isPremium',
            'subscriptionTier',
            'uid',
            'userId'
          ])
        );

        allow delete: if isAdmin() || isOwner(userId);
      }
    }

    // ============================================================
    // CONFIGURATION
    // ============================================================

    match /config/app {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    // Canonical global extension search order.
    // Authenticated read is the deployed Phase 05J contract.
    match /config/search_order {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    match /config/features {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    match /config/economy {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    // Deprecated /config paths are denied.
    // In particular, /config/global is NOT resurrected.
    match /config/{document=**} {
      allow read, write: if false;
    }

    // ============================================================
    // MANAGED EXTENSIONS
    // ============================================================

    // Canonical catalog.
    // Users App and Admin App authenticate before reading.
    match /managed_extensions/{extensionId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();

      match /{subcollection}/{docId} {
        allow read: if isAuthenticated();
        allow write: if isAdmin();
      }
    }

    // Legacy catalog retained for migration compatibility.
    // Canonical /managed_extensions always takes precedence in application code.
    match /extensions/{extensionId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    match /extension_updates/{updateId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    // ============================================================
    // APP UPDATES
    // ============================================================

    match /app_updates/{updateId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    // ============================================================
    // NOTIFICATIONS
    // ============================================================

    match /notifications/{notificationId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    // ============================================================
    // REWARD TASKS
    // ============================================================

    match /reward_tasks/{taskId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    // ============================================================
    // LEADERBOARD
    // ============================================================

    match /leaderboard/weekly_current {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    match /leaderboard_history/{cycleId} {
      allow read: if isAuthenticated();
      allow write: if isAdmin();
    }

    // ============================================================
    // SUBSCRIPTION REQUESTS
    // ============================================================

    match /pro_requests/{requestId} {
      allow read: if isAdmin() ||
        (isAuthenticated() && resource.data.userId == request.auth.uid);

      allow create: if isAdmin() || (
        isAuthenticated() &&
        request.resource.data.userId == request.auth.uid &&
        (
          !('status' in request.resource.data) ||
          request.resource.data.status == 'PENDING' ||
          request.resource.data.status == 'pending'
        ) &&
        (!('reviewedBy' in request.resource.data) ||
          request.resource.data.reviewedBy == '' ||
          request.resource.data.reviewedBy == null) &&
        (!('reviewedAt' in request.resource.data) ||
          request.resource.data.reviewedAt == null) &&
        (!('rejectionReason' in request.resource.data) ||
          request.resource.data.rejectionReason == '' ||
          request.resource.data.rejectionReason == null) &&
        !request.resource.data.keys().hasAny([
          'approvedBy',
          'approvedAt',
          'grantedBy',
          'grantSource',
          'subscriptionTier',
          'subscriptionStatus',
          'subscriptionSource',
          'subscriptionReferenceId',
          'subscriptionStartedAt',
          'subscriptionExpiresAt',
          'isPremium',
          'isPro',
          'proPlan',
          'proExpiresAt',
          'admin',
          'isAdmin',
          'role'
        ])
      );

      allow update, delete: if isAdmin();
    }

    // ============================================================
    // REPORTS
    // ============================================================

    match /reports/{reportId} {
      allow read: if isAdmin() ||
        (isAuthenticated() && resource.data.userId == request.auth.uid);

      allow create: if isAdmin() || (
        isAuthenticated() &&
        request.resource.data.userId == request.auth.uid &&
        (
          request.resource.data.status == 'PENDING' ||
          request.resource.data.status == 'pending'
        ) &&
        (!('resolvedBy' in request.resource.data) ||
          request.resource.data.resolvedBy == '' ||
          request.resource.data.resolvedBy == null) &&
        (!('resolvedAt' in request.resource.data) ||
          request.resource.data.resolvedAt == null) &&
        (!('resolutionNotes' in request.resource.data) ||
          request.resource.data.resolutionNotes == '' ||
          request.resource.data.resolutionNotes == null)
      );

      allow update, delete: if isAdmin();
    }

    // ============================================================
    // SUPPORT
    // ============================================================

    match /support_conversations/{conversationId} {
      allow read: if isAdmin() ||
        (isAuthenticated() && resource.data.userId == request.auth.uid);

      allow create: if isAdmin() || (
        isAuthenticated() &&
        request.resource.data.userId == request.auth.uid &&
        (
          request.resource.data.status == 'open' ||
          request.resource.data.status == 'pending'
        )
      );

      allow update: if isAdmin() || (
        isAuthenticated() &&
        resource.data.userId == request.auth.uid &&
        request.resource.data.userId == resource.data.userId &&
        (
          !('createdAt' in request.resource.data) ||
          request.resource.data.createdAt == resource.data.createdAt
        )
      );

      allow delete: if isAdmin();

      match /messages/{messageId} {
        allow read: if isAdmin() || (
          isAuthenticated() &&
          get(/databases/$(database)/documents/support_conversations/$(conversationId))
            .data.userId == request.auth.uid
        );

        allow create: if isAuthenticated() && (
          isAdmin() ||
          (
            get(/databases/$(database)/documents/support_conversations/$(conversationId))
              .data.userId == request.auth.uid &&
            request.resource.data.senderId == request.auth.uid &&
            request.resource.data.senderRole == 'user'
          )
        );

        allow update, delete: if isAdmin();
      }
    }

    // ============================================================
    // SOCIAL CONVERSATIONS
    // ============================================================

    match /conversations/{conversationId} {
      allow read: if isAuthenticated() && (
        isAdmin() ||
        (
          resource != null &&
          'participants' in resource.data &&
          request.auth.uid in resource.data.participants
        ) ||
        (
          resource != null &&
          'userId' in resource.data &&
          resource.data.userId == request.auth.uid
        )
      );

      allow create: if isAuthenticated() &&
        (
          (
            'participants' in request.resource.data &&
            request.auth.uid in request.resource.data.participants
          ) ||
          (
            'userId' in request.resource.data &&
            request.resource.data.userId == request.auth.uid
          )
        ) &&
        get(/databases/$(database)/documents/users/$(request.auth.uid))
          .data.canChat != false &&
        get(/databases/$(database)/documents/users/$(request.auth.uid))
          .data.chatBan != true &&
        get(/databases/$(database)/documents/users/$(request.auth.uid))
          .data.isBanned != true;

      allow update: if isAuthenticated() && (
        isAdmin() ||
        (
          resource != null &&
          'participants' in resource.data &&
          request.auth.uid in resource.data.participants
        ) ||
        (
          resource != null &&
          'userId' in resource.data &&
          resource.data.userId == request.auth.uid
        )
      );

      allow delete: if isAdmin() || (
        isAuthenticated() &&
        resource != null &&
        (
          (
            'userId' in resource.data &&
            resource.data.userId == request.auth.uid
          ) ||
          (
            'ownerId' in resource.data &&
            resource.data.ownerId == request.auth.uid
          )
        )
      );

      match /messages/{messageId} {
        allow read: if isAuthenticated() && (
          isAdmin() ||
          request.auth.uid in
            get(/databases/$(database)/documents/conversations/$(conversationId))
              .data.participants
        );

        allow create: if isAuthenticated() &&
          request.resource.data.senderId == request.auth.uid &&
          request.auth.uid in
            get(/databases/$(database)/documents/conversations/$(conversationId))
              .data.participants &&
          get(/databases/$(database)/documents/users/$(request.auth.uid))
            .data.canChat != false &&
          get(/databases/$(database)/documents/users/$(request.auth.uid))
            .data.chatBan != true &&
          get(/databases/$(database)/documents/users/$(request.auth.uid))
            .data.isBanned != true;

        allow update, delete: if isAuthenticated() && (
          isAdmin() ||
          (
            resource != null &&
            resource.data.senderId == request.auth.uid
          )
        );
      }
    }

    // ============================================================
    // STORIES
    // ============================================================

    match /stories/{storyId} {
      allow read: if isAuthenticated();

      allow create: if isAuthenticated() &&
        request.resource.data.userId == request.auth.uid &&
        get(/databases/$(database)/documents/users/$(request.auth.uid))
          .data.canStory != false &&
        get(/databases/$(database)/documents/users/$(request.auth.uid))
          .data.storyBan != true &&
        get(/databases/$(database)/documents/users/$(request.auth.uid))
          .data.isBanned != true;

      allow update, delete: if isAdmin() ||
        (
          isAuthenticated() &&
          resource.data.userId == request.auth.uid
        );
    }

    // ============================================================
    // AUDIT LOGS
    // ============================================================

    match /auditLogs/{logId} {
      allow read, write: if isAdmin();
    }

    // Deprecated path is explicitly denied.
    match /audit_logs/{document=**} {
      allow read, write: if false;
    }

    // ============================================================
    // FINAL DEFAULT DENY
    // ============================================================

    match /{document=**} {
      allow read, write: if false;
    }
  }
}
