/**
 * Structured usage logging for cost visibility.
 *
 * Every completed AI request writes a single JSON line to stdout via pino.
 * Fields are chosen to let you estimate:
 *   - Daily STT minutes         → sum(audioSeconds) / 60
 *   - AI requests               → count(event=AI_REQUEST)
 *   - Input/output tokens       → sum(promptTokens), sum(completionTokens)
 *   - TTS characters            → (not server-side; TTS is client-only for now)
 *   - Approximate cost          → see PRICING.md in project root
 *
 * ## What is NOT logged
 *   - Raw audio bytes
 *   - Full transcript text (truncated to 80 chars for debug)
 *   - API keys or Authorization headers
 *   - IP addresses (sessionHash is a one-way SHA-256 of sessionId)
 */

import crypto from "node:crypto";
import { logger } from "./logger.js";

/** One-way hash of sessionId for correlation without PII. */
export function hashSession(sessionId: string): string {
  return crypto.createHash("sha256").update(sessionId).digest("hex").slice(0, 12);
}

// ── Event types ───────────────────────────────────────────────────────────────

export interface SttUsageEvent {
  event: "STT_REQUEST";
  sessionHash: string;
  audioBytes: number;
  /** Estimated from sample rate — not authoritative. */
  audioSeconds: number;
  durationMs: number;
  providerStatus: number;
  success: boolean;
  /** Truncated transcript for debug; empty on failure. */
  transcriptPreview: string;
}

export interface AiUsageEvent {
  event: "AI_REQUEST";
  sessionHash: string;
  model: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  durationMs: number;
  providerStatus: number;
  success: boolean;
}

export interface ErrorEvent {
  event: "REQUEST_ERROR";
  endpoint: string;
  sessionHash: string;
  reason: string;
  durationMs: number;
}

// ── Logging helpers ───────────────────────────────────────────────────────────

export function logSttUsage(e: SttUsageEvent): void {
  logger.info(e, "usage");
}

export function logAiUsage(e: AiUsageEvent): void {
  logger.info(e, "usage");
}

export function logError(e: ErrorEvent): void {
  logger.warn(e, "usage_error");
}

/**
 * Simple cost estimation helper.
 * Prices are in the pricing config so they can be updated without code changes.
 * Returns USD values — multiply by your actual pricing.
 */
export interface PricingConfig {
  sttPerMinute: number;    // USD per minute of audio
  chatInputPer1kTokens: number;
  chatOutputPer1kTokens: number;
}

export const DEFAULT_PRICING: PricingConfig = {
  // gpt-4o-transcribe pricing (update as provider pricing changes)
  sttPerMinute: 0.006,
  // gpt-4o-mini pricing (update as provider pricing changes)
  chatInputPer1kTokens: 0.00015,
  chatOutputPer1kTokens: 0.0006,
};

export function estimateCost(
  sttMinutes: number,
  promptTokens: number,
  completionTokens: number,
  pricing: PricingConfig = DEFAULT_PRICING,
): { sttUsd: number; chatUsd: number; totalUsd: number } {
  const sttUsd = sttMinutes * pricing.sttPerMinute;
  const chatUsd =
    (promptTokens / 1_000) * pricing.chatInputPer1kTokens +
    (completionTokens / 1_000) * pricing.chatOutputPer1kTokens;
  return { sttUsd, chatUsd, totalUsd: sttUsd + chatUsd };
}
