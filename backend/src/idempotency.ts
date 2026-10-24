/**
 * PHASE 03E: IDEMPOTENCY SERVICE
 *
 * Enforces canonical backend idempotency:
 * {uid}:{operation}:{requestId}
 *
 * Guarantees that duplicate requests and retries return the exact same response
 * without duplicate economic transactions or balance increments.
 */

import { IdempotencyRecord } from "./types";

export class MemoryOrFirestoreIdempotencyStore {
  private cache = new Map<string, IdempotencyRecord>();

  buildKey(userId: string, operation: string, requestId: string): string {
    return `${userId}:${operation}:${requestId}`;
  }

  async getRecord(key: string): Promise<IdempotencyRecord | null> {
    return this.cache.get(key) || null;
  }

  async saveRecord(record: IdempotencyRecord): Promise<void> {
    this.cache.set(record.idempotencyKey, record);
  }

  clear(): void {
    this.cache.clear();
  }
}

export const defaultIdempotencyStore = new MemoryOrFirestoreIdempotencyStore();
