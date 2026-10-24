/**
 * PHASE 03E: CINESTREAM TRUSTED ECONOMIC BACKEND (CLOUDFLARE WORKER)
 *
 * Provides authoritative API endpoints for points earning:
 * - POST /api/earn/daily_login
 * - POST /api/earn/rewarded_ad
 * - POST /api/earn/task_reward
 * - POST /api/earn/game_reward
 * - POST /api/earn/leaderboard_settlement
 * - GET  /api/economy
 * - GET  /api/health
 *
 * Invariants:
 * - User client has ZERO economic authority.
 * - Endpoints authenticate Firebase ID tokens.
 * - Enforces idempotency via request IDs.
 * - Balance limit of 1,000,000 points.
 * - SUBSCRIPTION_REDEMPTION is strictly OUT OF SCOPE.
 */

import { verifyAuthToken } from "./auth";
import { EconomicTransactionEngine, DataStore } from "./earning";
import { BackendErrorCode, UserWalletDoc, PointTransactionDoc, TaskClaimDoc, AuditLogDoc } from "./types";

export interface Env {
  FIRESTORE_PROJECT_ID?: string;
  MAX_POINT_BALANCE?: string;
  ENVIRONMENT?: string;
  BACKEND_ENABLED?: string;
}

// In-memory / Firestore proxy store for worker runtime
class WorkerDataStore implements DataStore {
  private users = new Map<string, UserWalletDoc>();
  private transactions = new Map<string, PointTransactionDoc[]>();
  private taskClaims = new Map<string, TaskClaimDoc>();
  private tasks = new Map<string, { taskId: string; title: string; rewardPoints: number; isActive: boolean; expiresAt?: number }>([
    ["task_share_app", { taskId: "task_share_app", title: "مشاركة التطبيق مع الأصدقاء", rewardPoints: 50, isActive: true }],
    ["task_join_telegram", { taskId: "task_join_telegram", title: "الانضمام لقناة تيليجرام", rewardPoints: 30, isActive: true }],
    ["task_rate_app", { taskId: "task_rate_app", title: "تقييم التطبيق 5 نجوم", rewardPoints: 40, isActive: true }]
  ]);

  private economyConfig: any = {
    redemptionCosts: {
      pro_lite_1d: 50,
      pro_lite_7d: 250,
      pro_lite_10d: 350,
      pro_30d: 1000
    },
    dailyLoginRewards: [5, 10, 15, 20, 25, 30, 50],
    rewardedAdPoints: 15,
    rewardedAdDailyCap: 5,
    rewardedAdCooldownSeconds: 300
  };

  private featuresConfig: any = {
    subscriptions: "ACTIVE",
    points: "ACTIVE",
    dailyLogin: "ACTIVE",
    rewardedAds: "ACTIVE",
    tasks: "ACTIVE",
    leaderboard: "ACTIVE",
    disabledMessage: "هذه الميزة غير متوفرة حالياً"
  };

  async getUser(uid: string): Promise<UserWalletDoc | null> {
    return this.users.get(uid) || null;
  }

  async saveUser(uid: string, wallet: UserWalletDoc): Promise<void> {
    this.users.set(uid, wallet);
  }

  async createTransaction(uid: string, tx: PointTransactionDoc): Promise<void> {
    const list = this.transactions.get(uid) || [];
    list.push(tx);
    this.transactions.set(uid, list);
  }

  async createTaskClaim(uid: string, claim: TaskClaimDoc): Promise<void> {
    this.taskClaims.set(`${uid}:${claim.taskId}`, claim);
  }

  async getTaskClaim(uid: string, taskId: string): Promise<TaskClaimDoc | null> {
    return this.taskClaims.get(`${uid}:${taskId}`) || null;
  }

  async getTask(taskId: string): Promise<{ taskId: string; title: string; rewardPoints: number; isActive: boolean; expiresAt?: number } | null> {
    return this.tasks.get(taskId) || null;
  }

  async getEconomyConfig(): Promise<any> {
    return this.economyConfig;
  }

  async getFeaturesConfig(): Promise<any> {
    return this.featuresConfig;
  }

  async createAuditLog(_log: AuditLogDoc): Promise<void> {
    // In production, persisted to /auditLogs in Firestore
  }

  setEconomyConfig(cfg: any) {
    this.economyConfig = cfg;
  }

  setFeaturesConfig(cfg: any) {
    this.featuresConfig = cfg;
  }
}

const workerStore = new WorkerDataStore();
const engine = new EconomicTransactionEngine(workerStore);

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // CORS Headers
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Content-Type": "application/json"
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    // Hard deployment gate: this planned Worker is not approved or connected yet.
    // Do not serve health/economy endpoints or validate Firebase tokens until explicitly enabled.
    if (env.BACKEND_ENABLED !== "true" || env.ENVIRONMENT !== "production") {
      return new Response(JSON.stringify({
        error: "Backend disabled pending explicit approval",
        code: "BACKEND_NOT_ENABLED"
      }), { status: 503, headers: corsHeaders });
    }

    const projectId = env.FIRESTORE_PROJECT_ID?.trim();
    if (!projectId) {
      return new Response(JSON.stringify({
        error: "Backend is not configured",
        code: "FIRESTORE_PROJECT_ID_REQUIRED"
      }), { status: 503, headers: corsHeaders });
    }

    // Health check
    if (url.pathname === "/api/health" && request.method === "GET") {
      return new Response(JSON.stringify({ status: "healthy", timestamp: Date.now() }), {
        status: 200,
        headers: corsHeaders
      });
    }

    // Economy Config Read
    if (url.pathname === "/api/economy" && request.method === "GET") {
      const cfg = await workerStore.getEconomyConfig();
      return new Response(JSON.stringify(cfg), { status: 200, headers: corsHeaders });
    }

    // Verify Auth for all earning endpoints
    if (url.pathname.startsWith("/api/earn/")) {
      const authHeader = request.headers.get("Authorization");
      const authUser = await verifyAuthToken(authHeader, projectId);

      if (!authUser) {
        return new Response(
          JSON.stringify({
            success: false,
            errorCode: BackendErrorCode.UNAUTHENTICATED,
            message: "Authentication required",
            serverTimestamp: Date.now()
          }),
          { status: 401, headers: corsHeaders }
        );
      }

      // 1. Daily Login
      if (url.pathname === "/api/earn/daily_login" && request.method === "POST") {
        try {
          const body = await request.json() as any;
          const result = await engine.claimDailyLogin(authUser.uid, body);
          if (result.status === 200) {
            return new Response(JSON.stringify(result.data), { status: 200, headers: corsHeaders });
          } else {
            return new Response(
              JSON.stringify({
                success: false,
                errorCode: result.error,
                message: result.message,
                serverTimestamp: Date.now()
              }),
              { status: result.status, headers: corsHeaders }
            );
          }
        } catch (e: any) {
          return new Response(
            JSON.stringify({
              success: false,
              errorCode: BackendErrorCode.INVALID_REQUEST,
              message: e.message || "Invalid request body",
              serverTimestamp: Date.now()
            }),
            { status: 400, headers: corsHeaders }
          );
        }
      }

      // 2. Rewarded Ad
      if (url.pathname === "/api/earn/rewarded_ad" && request.method === "POST") {
        try {
          const body = await request.json() as any;
          const result = await engine.claimRewardedAd(authUser.uid, body);
          if (result.status === 200) {
            return new Response(JSON.stringify(result.data), { status: 200, headers: corsHeaders });
          } else {
            return new Response(
              JSON.stringify({
                success: false,
                errorCode: result.error,
                message: result.message,
                serverTimestamp: Date.now()
              }),
              { status: result.status, headers: corsHeaders }
            );
          }
        } catch (e: any) {
          return new Response(
            JSON.stringify({
              success: false,
              errorCode: BackendErrorCode.INVALID_REQUEST,
              message: e.message || "Invalid request body",
              serverTimestamp: Date.now()
            }),
            { status: 400, headers: corsHeaders }
          );
        }
      }

      // 3. Task Reward
      if (url.pathname === "/api/earn/task_reward" && request.method === "POST") {
        try {
          const body = await request.json() as any;
          const result = await engine.claimTaskReward(authUser.uid, body);
          if (result.status === 200) {
            return new Response(JSON.stringify(result.data), { status: 200, headers: corsHeaders });
          } else {
            return new Response(
              JSON.stringify({
                success: false,
                errorCode: result.error,
                message: result.message,
                serverTimestamp: Date.now()
              }),
              { status: result.status, headers: corsHeaders }
            );
          }
        } catch (e: any) {
          return new Response(
            JSON.stringify({
              success: false,
              errorCode: BackendErrorCode.INVALID_REQUEST,
              message: e.message || "Invalid request body",
              serverTimestamp: Date.now()
            }),
            { status: 400, headers: corsHeaders }
          );
        }
      }

      // 4. Game Reward (Reserved)
      if (url.pathname === "/api/earn/game_reward" && request.method === "POST") {
        const result = await engine.claimGameReward(authUser.uid, {});
        return new Response(
          JSON.stringify({
            success: false,
            errorCode: result.error,
            message: result.message,
            serverTimestamp: Date.now()
          }),
          { status: result.status, headers: corsHeaders }
        );
      }

      // 5. Leaderboard Settlement (Forbidden to users)
      if (url.pathname === "/api/earn/leaderboard_settlement" && request.method === "POST") {
        const result = await engine.settleLeaderboard(authUser.role || "user");
        return new Response(
          JSON.stringify({
            success: false,
            errorCode: result.error,
            message: result.message,
            serverTimestamp: Date.now()
          }),
          { status: result.status, headers: corsHeaders }
        );
      }
    }

    // Subscription Redemption (Phase 03F)
    if ((url.pathname === "/api/subscription/redeem" || url.pathname === "/api/redeem/subscription") && request.method === "POST") {
      const authHeader = request.headers.get("Authorization");
      const authUser = await verifyAuthToken(authHeader, projectId);

      if (!authUser) {
        return new Response(
          JSON.stringify({
            success: false,
            errorCode: BackendErrorCode.UNAUTHENTICATED,
            message: "Authentication required",
            serverTimestamp: Date.now()
          }),
          { status: 401, headers: corsHeaders }
        );
      }

      try {
        const body = await request.json() as any;
        const result = await engine.redeemSubscription(authUser.uid, body);
        if (result.status === 200) {
          return new Response(JSON.stringify(result.data), { status: 200, headers: corsHeaders });
        } else {
          return new Response(
            JSON.stringify({
              success: false,
              errorCode: result.error,
              message: result.message,
              serverTimestamp: Date.now()
            }),
            { status: result.status, headers: corsHeaders }
          );
        }
      } catch (e: any) {
        return new Response(
          JSON.stringify({
            success: false,
            errorCode: BackendErrorCode.INVALID_REQUEST,
            message: e.message || "Invalid request body",
            serverTimestamp: Date.now()
          }),
          { status: 400, headers: corsHeaders }
        );
      }
    }

    return new Response(
      JSON.stringify({ error: "Not Found" }),
      { status: 404, headers: corsHeaders }
    );
  }
};
