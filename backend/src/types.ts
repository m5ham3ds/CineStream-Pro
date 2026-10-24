/**
 * PHASE 03E: TRUSTED ECONOMIC BACKEND TYPES
 *
 * Absolute Invariants:
 * 1. The Users App is NEVER the economic authority.
 * 2. Only this trusted backend executes balance mutations and creates point ledger records.
 * 3. Max point balance is 1,000,000 points.
 * 4. SUBSCRIPTION_REDEMPTION is strictly OUT OF SCOPE for Phase 03E.
 */

export const CanonicalTransactionType = {
  DAILY_LOGIN: "DAILY_LOGIN",
  REWARDED_AD: "REWARDED_AD",
  TASK_REWARD: "TASK_REWARD",
  GAME_REWARD: "GAME_REWARD",
  LEADERBOARD_REWARD: "LEADERBOARD_REWARD",
  SUBSCRIPTION_REDEMPTION: "SUBSCRIPTION_REDEMPTION",
  ADMIN_GRANT: "ADMIN_GRANT",
  ADMIN_ADJUSTMENT: "ADMIN_ADJUSTMENT",
  REVERSAL: "REVERSAL"
} as const;

export type CanonicalTransactionType = typeof CanonicalTransactionType[keyof typeof CanonicalTransactionType];

export const BackendErrorCode = {
  UNAUTHENTICATED: "UNAUTHENTICATED",
  INVALID_REQUEST: "INVALID_REQUEST",
  INVALID_SKU: "INVALID_SKU",
  SKU_NOT_REDEEMABLE: "SKU_NOT_REDEEMABLE",
  INVALID_ECONOMY_CONFIG: "INVALID_ECONOMY_CONFIG",
  FEATURE_DISABLED: "FEATURE_DISABLED",
  FEATURE_COMING_SOON: "FEATURE_COMING_SOON",
  INSUFFICIENT_POINTS: "INSUFFICIENT_POINTS",
  BALANCE_LIMIT_ERROR: "BALANCE_LIMIT_ERROR",
  BALANCE_LIMIT_EXCEEDED: "BALANCE_LIMIT_EXCEEDED",
  SUBSCRIPTION_STATE_INVALID: "SUBSCRIPTION_STATE_INVALID",
  REDEMPTION_UNAVAILABLE: "REDEMPTION_UNAVAILABLE",
  DUPLICATE_REQUEST: "DUPLICATE_REQUEST",
  CONCURRENT_REDEMPTION: "CONCURRENT_REDEMPTION",
  ALREADY_CLAIMED: "ALREADY_CLAIMED",
  DAILY_LOGIN_ALREADY_CLAIMED: "DAILY_LOGIN_ALREADY_CLAIMED",
  DAILY_CAP_REACHED: "DAILY_CAP_REACHED",
  COOLDOWN_ACTIVE: "COOLDOWN_ACTIVE",
  TASK_NOT_FOUND: "TASK_NOT_FOUND",
  TASK_EXPIRED: "TASK_EXPIRED",
  TASK_NOT_ELIGIBLE: "TASK_NOT_ELIGIBLE",
  TASK_ALREADY_CLAIMED: "TASK_ALREADY_CLAIMED",
  REWARD_VERIFICATION_FAILED: "REWARD_VERIFICATION_FAILED",
  BACKEND_VERIFICATION_REQUIRED: "BACKEND_VERIFICATION_REQUIRED",
  GAME_REWARD_UNAVAILABLE: "GAME_REWARD_UNAVAILABLE",
  LEADERBOARD_SETTLEMENT_UNAVAILABLE: "LEADERBOARD_SETTLEMENT_UNAVAILABLE",
  INTERNAL_ERROR: "INTERNAL_ERROR"
} as const;

export type BackendErrorCode = typeof BackendErrorCode[keyof typeof BackendErrorCode];

export interface EconomyConfig {
  redemptionCosts: Record<string, number>;
  dailyLoginRewards: number[];
  rewardedAdPoints: number;
  rewardedAdDailyCap: number;
  rewardedAdCooldownSeconds: number;
}

export interface FeaturesConfig {
  subscriptions: "ACTIVE" | "COMING_SOON" | "DISABLED";
  points: "ACTIVE" | "COMING_SOON" | "DISABLED";
  dailyLogin: "ACTIVE" | "COMING_SOON" | "DISABLED";
  rewardedAds: "ACTIVE" | "COMING_SOON" | "DISABLED";
  tasks: "ACTIVE" | "COMING_SOON" | "DISABLED";
  leaderboard: "ACTIVE" | "COMING_SOON" | "DISABLED";
  disabledMessage?: string;
}

export interface UserWalletDoc {
  pointsBalance: number;
  totalPointsEarned: number;
  totalPointsSpent: number;
  dailyStreak?: number;
  lastDailyLoginDate?: string;
  rewardedAdsWatchedToday?: number;
  lastRewardedAdWatchedAt?: number;
  lastRewardedAdWatchDate?: string;

  // Canonical Subscription Fields (stored on /users/{uid})
  subscriptionTier?: "FREE" | "PRO_LITE" | "PRO" | string;
  planId?: string;
  durationDays?: number;
  subscriptionStatus?: "ACTIVE" | "EXPIRED" | "CANCELED" | "PENDING" | string;
  subscriptionSource?: "MONEY" | "POINTS" | "ADMIN_GRANT" | "LEGACY" | string;
  subscriptionReferenceId?: string;
  subscriptionStartedAt?: number;
  subscriptionExpiresAt?: number;

  // Legacy compatibility mirrors
  isPremium?: boolean;
  isPro?: boolean;
  plan?: string;
  proPlan?: string;
  proExpiresAt?: number;

  [key: string]: any;
}

export interface PointTransactionDoc {
  id: string;
  txId: string;
  userId: string;
  type: CanonicalTransactionType;
  amount: number;
  balanceBefore: number;
  balanceAfter: number;
  referenceId: string;
  description: string;
  createdAt: number;
}

export interface TaskClaimDoc {
  claimId: string;
  taskId: string;
  userId: string;
  pointsAwarded: number;
  status: "COMPLETED";
  claimedAt: number;
}

export interface IdempotencyRecord {
  idempotencyKey: string;
  userId: string;
  operation: string;
  requestId: string;
  response: any;
  createdAt: number;
}

export interface AuditLogDoc {
  auditId: string;
  userId: string;
  operation: string;
  requestId: string;
  transactionId?: string;
  amount?: number;
  result: "SUCCESS" | "FAILURE";
  errorCode?: string;
  timestamp: number;
}

export interface DailyLoginClaimRequest {
  requestId: string;
}

export interface DailyLoginClaimResponse {
  success: boolean;
  requestId: string;
  transactionId: string;
  transactionType: "DAILY_LOGIN";
  amount: number;
  newBalance: number;
  streak: number;
  serverTimestamp: number;
}

export interface RewardedAdClaimRequest {
  requestId: string;
  adUnitId?: string;
  verificationToken?: string;
}

export interface RewardedAdClaimResponse {
  success: boolean;
  requestId: string;
  transactionId: string;
  transactionType: "REWARDED_AD";
  amount: number;
  newBalance: number;
  dailyCount: number;
  cooldownSeconds: number;
  serverTimestamp: number;
}

export interface TaskRewardClaimRequest {
  requestId: string;
  taskId: string;
}

export interface TaskRewardClaimResponse {
  success: boolean;
  requestId: string;
  taskId: string;
  transactionId: string;
  transactionType: "TASK_REWARD";
  amount: number;
  newBalance: number;
  serverTimestamp: number;
}

export interface SubscriptionRedemptionRequest {
  requestId: string;
  sku: string;
}

export interface SubscriptionRedemptionResponse {
  success: boolean;
  requestId: string;
  transactionId: string;
  transactionType: "SUBSCRIPTION_REDEMPTION";
  sku: string;
  tier: "PRO_LITE" | "PRO";
  durationDays: number;
  pointsCost: number;
  balanceBefore: number;
  balanceAfter: number;
  subscriptionStatus: "ACTIVE";
  subscriptionStartedAt: number;
  subscriptionExpiresAt: number;
  subscriptionSource: "POINTS";
  serverTimestamp: number;
}

export interface BackendErrorResponse {
  success: false;
  errorCode: BackendErrorCode;
  message: string;
  serverTimestamp: number;
}
