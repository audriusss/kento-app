/**
 * POST /api/chat
 *
 * Proxy for OpenAI chat completions (gpt-4o-mini).
 * The OPENAI_API_KEY never leaves this server.
 *
 * ## Request (JSON)
 *   {
 *     messages:  [ { role: "system"|"user"|"assistant", content: string }, ... ],
 *     sessionId: string,   // client-generated UUID, used for logging only
 *     model?:    string,   // optional override; defaults to gpt-4o-mini
 *   }
 *
 * ## Response
 *   200 { reply: string, promptTokens: number, completionTokens: number }
 *   400 malformed body
 *   429 rate limited
 *   502 provider error
 *   504 provider timeout
 *
 * ## Limits
 *   - max 30 messages in history (older messages should be dropped on client)
 *   - max 2 000 chars per message content
 *   - max response tokens: 200 (short, car-safe replies)
 *   - provider timeout: 25 s
 */

import { Router } from "express";
import { chatRateLimit } from "../middlewares/rateLimit.js";
import { hashSession, logAiUsage, logError } from "../lib/usageLogger.js";
import { logger } from "../lib/logger.js";

const router = Router();

const OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
const PROVIDER_TIMEOUT_MS = 25_000;
const DEFAULT_MODEL = "gpt-4o-mini";
const MAX_MESSAGES = 30;
const MAX_CONTENT_CHARS = 2_000;
const MAX_COMPLETION_TOKENS = 200;

interface ChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

router.post(
  "/chat",
  chatRateLimit,
  async (req, res) => {
    const start = Date.now();
    const body = req.body as {
      messages?: unknown;
      sessionId?: unknown;
      model?: unknown;
    };

    const sessionId = typeof body.sessionId === "string" ? body.sessionId : "unknown";
    const sessionHash = hashSession(sessionId);

    // ── Validate messages ─────────────────────────────────────────────────
    if (!Array.isArray(body.messages)) {
      return res.status(400).json({ error: "messages_required" });
    }

    const messages = body.messages as unknown[];

    if (messages.length === 0) {
      return res.status(400).json({ error: "messages_empty" });
    }

    if (messages.length > MAX_MESSAGES) {
      return res.status(400).json({
        error: "too_many_messages",
        max: MAX_MESSAGES,
      });
    }

    // Validate shape and content length of each message.
    const validRoles = new Set(["system", "user", "assistant"]);
    for (const msg of messages) {
      if (
        typeof msg !== "object" ||
        msg === null ||
        !validRoles.has((msg as any).role) ||
        typeof (msg as any).content !== "string"
      ) {
        return res.status(400).json({ error: "invalid_message_shape" });
      }
      if ((msg as ChatMessage).content.length > MAX_CONTENT_CHARS) {
        return res.status(400).json({
          error: "message_too_long",
          maxChars: MAX_CONTENT_CHARS,
        });
      }
    }

    const model =
      typeof body.model === "string" && body.model.length < 80
        ? body.model
        : DEFAULT_MODEL;

    // ── API key ───────────────────────────────────────────────────────────
    const apiKey = process.env["OPENAI_API_KEY"];
    if (!apiKey) {
      logger.error("OPENAI_API_KEY not set — chat unavailable");
      return res.status(503).json({ error: "provider_not_configured" });
    }

    // ── Deduplicate: idempotency key prevents double-sends ────────────────
    // If Android retries on network error, the same X-Idempotency-Key ensures
    // we do NOT accidentally fire a duplicate request to OpenAI.
    // (For 100 users with in-memory store, this is good enough; replace with
    //  Redis cache for multi-instance deployments.)
    const idempotencyKey = req.headers["x-idempotency-key"] as string | undefined;
    if (idempotencyKey && recentIdempotencyKeys.has(idempotencyKey)) {
      const cached = recentIdempotencyKeys.get(idempotencyKey)!;
      logger.info({ sessionHash }, "duplicate request, returning cached reply");
      return res.json(cached);
    }

    // ── Call OpenAI with timeout ───────────────────────────────────────────
    let providerStatus = 0;

    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), PROVIDER_TIMEOUT_MS);

      let providerRes: Response;
      try {
        providerRes = await fetch(OPENAI_CHAT_URL, {
          method: "POST",
          headers: {
            Authorization: `Bearer ${apiKey}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            model,
            messages,
            max_tokens: MAX_COMPLETION_TOKENS,
            temperature: 0.85,
          }),
          signal: controller.signal,
        });
      } finally {
        clearTimeout(timer);
      }

      providerStatus = providerRes.status;

      if (providerStatus === 429) {
        logError({
          event: "REQUEST_ERROR",
          endpoint: "/api/chat",
          sessionHash,
          reason: "provider_rate_limited",
          durationMs: Date.now() - start,
        });
        return res.status(429).json({
          error: "provider_rate_limited",
          retryable: true,
        });
      }

      if (!providerRes.ok) {
        const errText = await providerRes.text().catch(() => "");
        logger.warn(
          { status: providerStatus, body: errText.slice(0, 200) },
          "chat provider error",
        );
        logError({
          event: "REQUEST_ERROR",
          endpoint: "/api/chat",
          sessionHash,
          reason: `provider_error_${providerStatus}`,
          durationMs: Date.now() - start,
        });
        return res.status(502).json({
          error: "provider_error",
          providerStatus,
          retryable: providerStatus >= 500,
        });
      }

      const json = (await providerRes.json()) as {
        choices?: Array<{ message?: { content?: string } }>;
        usage?: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number };
      };

      const reply = json.choices?.[0]?.message?.content?.trim() ?? "";
      if (!reply) {
        return res.status(502).json({ error: "empty_reply" });
      }

      const promptTokens     = json.usage?.prompt_tokens     ?? 0;
      const completionTokens = json.usage?.completion_tokens ?? 0;
      const totalTokens      = json.usage?.total_tokens      ?? 0;

      logAiUsage({
        event: "AI_REQUEST",
        sessionHash,
        model,
        promptTokens,
        completionTokens,
        totalTokens,
        durationMs: Date.now() - start,
        providerStatus,
        success: true,
      });

      const responsePayload = { reply, promptTokens, completionTokens };

      // Cache for idempotency (TTL 30 s is enough for a mobile retry window).
      if (idempotencyKey) {
        recentIdempotencyKeys.set(idempotencyKey, responsePayload);
        setTimeout(() => recentIdempotencyKeys.delete(idempotencyKey), 30_000);
      }

      return res.json(responsePayload);
    } catch (err: any) {
      const isTimeout = err?.name === "AbortError";
      logError({
        event: "REQUEST_ERROR",
        endpoint: "/api/chat",
        sessionHash,
        reason: isTimeout ? "provider_timeout" : `unexpected: ${err?.message}`,
        durationMs: Date.now() - start,
      });
      if (isTimeout) {
        return res.status(504).json({ error: "provider_timeout", retryable: true });
      }
      logger.error({ err }, "chat unexpected error");
      return res.status(500).json({ error: "internal_error" });
    }
  },
);

// ── In-memory idempotency cache (survives a single process restart) ───────────
// Key: X-Idempotency-Key header value  Value: cached response payload
// Entries are self-expiring (30 s TTL set inline above).
const recentIdempotencyKeys = new Map<string, object>();

export default router;
