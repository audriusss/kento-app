/**
 * POST /api/transcribe
 *
 * Proxy for OpenAI audio transcription (Whisper / gpt-4o-transcribe).
 * The OPENAI_API_KEY never leaves this server.
 *
 * ## Request
 *   Content-Type: audio/wav
 *   Body:         raw WAV bytes (max 5 MB ≈ ~50 s of 16 kHz mono)
 *   Query:        ?lang=lt   (BCP-47 language code, default "lt")
 *   Header:       X-Session-Id: <client-generated UUID>
 *
 * ## Response
 *   200 { text: string }
 *   400 missing/invalid body or lang
 *   413 audio too large
 *   429 rate limited (handled by middleware before this handler)
 *   502 provider error (non-2xx from OpenAI)
 *   504 provider timeout
 */

import { Router } from "express";
import { transcribeRateLimit } from "../middlewares/rateLimit.js";
import { hashSession, logSttUsage, logError } from "../lib/usageLogger.js";
import { logger } from "../lib/logger.js";

const router = Router();

const OPENAI_TRANSCRIPTION_URL = "https://api.openai.com/v1/audio/transcriptions";
const MAX_AUDIO_BYTES = 5 * 1024 * 1024; // 5 MB
const PROVIDER_TIMEOUT_MS = 30_000;       // 30 s
const TRANSCRIPTION_MODEL = "gpt-4o-transcribe";
const LT_PROMPT =
  "Kentas, navigacija, Lietuva, Vilnius, Kaunas, Klaipėda, " +
  "sukite, pasukite, važiuokite, sustokite, tiesiog.";

router.post(
  "/transcribe",
  transcribeRateLimit,
  // Accept raw audio/wav body; limit enforced here rather than globally so
  // other routes still use the default 100 kB Express limit.
  (req, res, next) => {
    // Express does not parse raw bodies automatically; we accumulate chunks.
    const chunks: Buffer[] = [];
    let totalBytes = 0;
    let oversized = false;

    req.on("data", (chunk: Buffer) => {
      if (oversized) return;   // drain silently after limit exceeded
      totalBytes += chunk.length;
      if (totalBytes > MAX_AUDIO_BYTES) {
        oversized = true;
        req.resume();          // keep draining so TCP peer is not stalled
        res.status(413).json({
          error: "audio_too_large",
          maxBytes: MAX_AUDIO_BYTES,
        });
        return;
      }
      chunks.push(chunk);
    });

    req.on("end", () => {
      if (oversized) return;   // 413 already sent
      (req as any).rawBody = Buffer.concat(chunks);
      next();
    });

    req.on("error", (err) => {
      logger.warn({ err }, "transcribe request stream error");
      if (!res.headersSent) {
        res.status(400).json({ error: "request_stream_error" });
      }
    });
  },
  async (req, res) => {
    const start = Date.now();
    const sessionId = (req.headers["x-session-id"] as string) || "unknown";
    const sessionHash = hashSession(sessionId);
    const rawBody: Buffer = (req as any).rawBody;

    // ── Validate content type ─────────────────────────────────────────────
    const contentType = req.headers["content-type"] ?? "";
    if (!contentType.includes("audio/wav") && !contentType.includes("audio/wave")) {
      logError({
        event: "REQUEST_ERROR",
        endpoint: "/api/transcribe",
        sessionHash,
        reason: `unsupported_content_type: ${contentType}`,
        durationMs: Date.now() - start,
      });
      return res.status(400).json({
        error: "unsupported_media_type",
        accepted: ["audio/wav"],
      });
    }

    // ── Validate body ─────────────────────────────────────────────────────
    if (!rawBody || rawBody.length === 0) {
      return res.status(400).json({ error: "empty_body" });
    }

    const lang = (req.query.lang as string) || "lt";

    // ── API key ───────────────────────────────────────────────────────────
    const apiKey = process.env["OPENAI_API_KEY"];
    if (!apiKey) {
      logger.error("OPENAI_API_KEY not set — transcription unavailable");
      return res.status(503).json({ error: "provider_not_configured" });
    }

    // ── Build multipart form ──────────────────────────────────────────────
    const boundary = `----BajeristasSTT${Date.now()}`;
    const crlf = "\r\n";

    const buildPart = (name: string, value: string): string =>
      `--${boundary}${crlf}` +
      `Content-Disposition: form-data; name="${name}"${crlf}${crlf}` +
      `${value}${crlf}`;

    const textParts = [
      buildPart("model", TRANSCRIPTION_MODEL),
      buildPart("language", lang),
      buildPart("response_format", "json"),
      buildPart("prompt", LT_PROMPT),
    ].join("");

    const fileHeader =
      `--${boundary}${crlf}` +
      `Content-Disposition: form-data; name="file"; filename="audio.wav"${crlf}` +
      `Content-Type: audio/wav${crlf}${crlf}`;

    const closing = `${crlf}--${boundary}--${crlf}`;

    const body = Buffer.concat([
      Buffer.from(textParts, "utf8"),
      Buffer.from(fileHeader, "utf8"),
      rawBody,
      Buffer.from(closing, "utf8"),
    ]);

    // ── Call OpenAI with timeout ───────────────────────────────────────────
    let providerStatus = 0;
    let success = false;
    let transcriptPreview = "";

    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), PROVIDER_TIMEOUT_MS);

      let providerRes: Response;
      try {
        providerRes = await fetch(OPENAI_TRANSCRIPTION_URL, {
          method: "POST",
          headers: {
            Authorization: `Bearer ${apiKey}`,
            "Content-Type": `multipart/form-data; boundary=${boundary}`,
          },
          body,
          signal: controller.signal,
        });
      } finally {
        clearTimeout(timer);
      }

      providerStatus = providerRes.status;

      if (providerStatus === 429) {
        logError({
          event: "REQUEST_ERROR",
          endpoint: "/api/transcribe",
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
          "transcription provider error",
        );
        logError({
          event: "REQUEST_ERROR",
          endpoint: "/api/transcribe",
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

      const json = (await providerRes.json()) as { text?: string };
      const text = json.text?.trim() ?? "";
      if (!text) {
        return res.status(502).json({ error: "empty_transcript" });
      }

      success = true;
      transcriptPreview = text.slice(0, 80);

      const audioSeconds = rawBody.length / (16_000 * 2); // 16 kHz, 16-bit mono

      logSttUsage({
        event: "STT_REQUEST",
        sessionHash,
        audioBytes: rawBody.length,
        audioSeconds,
        durationMs: Date.now() - start,
        providerStatus,
        success,
        transcriptPreview,
      });

      return res.json({ text });
    } catch (err: any) {
      const isTimeout = err?.name === "AbortError";
      logError({
        event: "REQUEST_ERROR",
        endpoint: "/api/transcribe",
        sessionHash,
        reason: isTimeout ? "provider_timeout" : `unexpected: ${err?.message}`,
        durationMs: Date.now() - start,
      });
      if (isTimeout) {
        return res.status(504).json({ error: "provider_timeout", retryable: true });
      }
      logger.error({ err }, "transcription unexpected error");
      return res.status(500).json({ error: "internal_error" });
    }
  },
);

export default router;
