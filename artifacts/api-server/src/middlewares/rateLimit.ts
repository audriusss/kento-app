/**
 * Rate-limiting middleware definitions.
 *
 * All limiters are keyed by IP address.  For a small deployment (≤ 100 users)
 * in-memory storage is sufficient.  If the service ever scales to multiple
 * instances, swap `MemoryStore` for a shared Redis store.
 *
 * Limits are intentionally generous to avoid blocking normal driving
 * conversation while still protecting against runaway clients or scraping.
 *
 *   /api/transcribe  — audio uploads, one every ~2 s at sustained pace
 *   /api/chat        — AI requests, slightly higher allowance for quick back-and-forth
 *   /api/markers     — community reports, rare bursts acceptable
 *   default          — catch-all for unknown routes
 */

import rateLimit from "express-rate-limit";

// ── Transcription endpoint ────────────────────────────────────────────────────
// 30 requests per minute per IP.  At ~3 s per voice turn, a single user
// could drive 10 voice commands/min; 30 gives 3× headroom before hitting
// the limit.  Prevents a single runaway client from saturating OpenAI STT.

export const transcribeRateLimit = rateLimit({
  windowMs: 60 * 1_000,   // 1 minute
  max: 30,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  message: { error: "rate_limited", retryAfterSeconds: 60 },
});

// ── AI chat endpoint ──────────────────────────────────────────────────────────
// 40 requests per minute per IP.  Chat is cheaper per token than STT, and
// users may send quick follow-up questions.

export const chatRateLimit = rateLimit({
  windowMs: 60 * 1_000,
  max: 40,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  message: { error: "rate_limited", retryAfterSeconds: 60 },
});

// ── Community markers ─────────────────────────────────────────────────────────
// 20 reports per minute per IP — higher than normal user frequency
// but low enough to block automated spam.

export const markersRateLimit = rateLimit({
  windowMs: 60 * 1_000,
  max: 20,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  message: { error: "rate_limited", retryAfterSeconds: 60 },
});

// ── Default / catch-all ───────────────────────────────────────────────────────
export const defaultRateLimit = rateLimit({
  windowMs: 60 * 1_000,
  max: 120,
  standardHeaders: "draft-7",
  legacyHeaders: false,
  message: { error: "rate_limited", retryAfterSeconds: 60 },
});
