/**
 * PHASE 03E: ECONOMY & FEATURE CONFIGURATION VALIDATION
 *
 * Strict Requirement:
 * The trusted backend MUST read the authoritative remote configuration from `/config/economy`.
 * If missing, malformed, or invalid: FAIL CLOSED.
 * NEVER award points using an untrusted client fallback.
 */

import { EconomyConfig, FeaturesConfig } from "./types";

export function validateEconomyConfig(raw: any): { valid: true; config: EconomyConfig } | { valid: false; reason: string } {
  if (!raw || typeof raw !== "object") {
    return { valid: false, reason: "Economy config is missing or not an object" };
  }

  // 1. Validate dailyLoginRewards ladder
  if (!Array.isArray(raw.dailyLoginRewards) || raw.dailyLoginRewards.length === 0) {
    return { valid: false, reason: "dailyLoginRewards must be a non-empty array" };
  }

  for (let i = 0; i < raw.dailyLoginRewards.length; i++) {
    const val = raw.dailyLoginRewards[i];
    if (typeof val !== "number" || !Number.isInteger(val) || val <= 0) {
      return { valid: false, reason: `dailyLoginRewards[${i}] must be a positive integer, got ${val}` };
    }
  }

  // 2. Validate rewardedAdPoints
  const adPoints = raw.rewardedAdPoints;
  if (typeof adPoints !== "number" || !Number.isInteger(adPoints) || adPoints <= 0) {
    return { valid: false, reason: `rewardedAdPoints must be a positive integer, got ${adPoints}` };
  }

  // 3. Validate rewardedAdDailyCap
  const cap = raw.rewardedAdDailyCap;
  if (typeof cap !== "number" || !Number.isInteger(cap) || cap <= 0) {
    return { valid: false, reason: `rewardedAdDailyCap must be a positive integer, got ${cap}` };
  }

  // 4. Validate rewardedAdCooldownSeconds
  const cooldown = raw.rewardedAdCooldownSeconds;
  if (typeof cooldown !== "number" || !Number.isInteger(cooldown) || cooldown < 0) {
    return { valid: false, reason: `rewardedAdCooldownSeconds must be a non-negative integer, got ${cooldown}` };
  }

  // 5. Validate redemptionCosts map
  const costs: Record<string, number> = {};
  if (raw.redemptionCosts && typeof raw.redemptionCosts === "object") {
    for (const [k, v] of Object.entries(raw.redemptionCosts)) {
      if (typeof v === "number" && Number.isInteger(v) && v > 0) {
        costs[k] = v;
      }
    }
  }

  return {
    valid: true,
    config: {
      redemptionCosts: costs,
      dailyLoginRewards: raw.dailyLoginRewards,
      rewardedAdPoints: adPoints,
      rewardedAdDailyCap: cap,
      rewardedAdCooldownSeconds: cooldown
    }
  };
}

export function validateFeaturesConfig(raw: any): FeaturesConfig {
  const parseState = (val: any): "ACTIVE" | "COMING_SOON" | "DISABLED" => {
    if (!val) return "COMING_SOON";
    const str = String(val).toUpperCase().trim();
    if (str === "ACTIVE" || str === "TRUE") return "ACTIVE";
    if (str === "DISABLED" || str === "FALSE") return "DISABLED";
    return "COMING_SOON";
  };

  return {
    subscriptions: parseState(raw?.subscriptions),
    points: parseState(raw?.points),
    dailyLogin: parseState(raw?.dailyLogin),
    rewardedAds: parseState(raw?.rewardedAds),
    tasks: parseState(raw?.tasks),
    leaderboard: parseState(raw?.leaderboard),
    disabledMessage: raw?.disabledMessage || "هذه الميزة غير متوفرة حالياً"
  };
}
