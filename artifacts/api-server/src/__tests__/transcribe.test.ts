/**
 * Tests for POST /api/transcribe
 *
 * Uses supertest for HTTP-level assertions.
 * OpenAI calls are intercepted with a fetch mock injected via vi.stubGlobal.
 */

import { describe, it, expect, beforeEach, vi } from "vitest";
import request from "supertest";
import app from "../app.js";

// ── Helpers ───────────────────────────────────────────────────────────────────

const VALID_WAV = Buffer.from("RIFF" + "\x00".repeat(40));   // minimal fake WAV

function makeFetchMock(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response);
}

beforeEach(() => {
  process.env["OPENAI_API_KEY"] = "test-key";
  vi.restoreAllMocks();
});

// ── T-01  Missing body → 400 ──────────────────────────────────────────────────
it("T-01 rejects empty body with 400", async () => {
  const res = await request(app)
    .post("/api/transcribe")
    .set("Content-Type", "audio/wav")
    .send(Buffer.alloc(0));
  expect(res.status).toBe(400);
  expect(res.body.error).toBe("empty_body");
});

// ── T-02  Wrong MIME type → 400 ───────────────────────────────────────────────
it("T-02 rejects non-wav content-type with 400", async () => {
  const res = await request(app)
    .post("/api/transcribe")
    .set("Content-Type", "audio/mp3")
    .send(VALID_WAV);
  expect(res.status).toBe(400);
  expect(res.body.error).toBe("unsupported_media_type");
  expect(res.body.accepted).toContain("audio/wav");
});

// ── T-03  Oversized audio → 413 ───────────────────────────────────────────────
it("T-03 rejects audio exceeding 5 MB with 413", async () => {
  const oversized = Buffer.alloc(5 * 1024 * 1024 + 1, 0x42);
  const res = await request(app)
    .post("/api/transcribe")
    .set("Content-Type", "audio/wav")
    .send(oversized);
  expect(res.status).toBe(413);
  expect(res.body.error).toBe("audio_too_large");
});

// ── T-04  Provider 429 is forwarded ──────────────────────────────────────────
it("T-04 returns 429 when provider rate-limits", async () => {
  vi.stubGlobal("fetch", makeFetchMock(429, { error: "rate_limit_exceeded" }));
  const res = await request(app)
    .post("/api/transcribe")
    .set("Content-Type", "audio/wav")
    .set("X-Session-Id", "test-session")
    .send(VALID_WAV);
  expect(res.status).toBe(429);
  expect(res.body.retryable).toBe(true);
});

// ── T-05  Provider 500 → 502 ─────────────────────────────────────────────────
it("T-05 returns 502 when provider returns 500", async () => {
  vi.stubGlobal("fetch", makeFetchMock(500, { error: "server_error" }));
  const res = await request(app)
    .post("/api/transcribe")
    .set("Content-Type", "audio/wav")
    .set("X-Session-Id", "test-session")
    .send(VALID_WAV);
  expect(res.status).toBe(502);
  expect(res.body.retryable).toBe(true);
});

// ── T-06  Provider timeout → 504 ─────────────────────────────────────────────
it("T-06 returns 504 on provider timeout (AbortError)", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockRejectedValue(Object.assign(new Error("aborted"), { name: "AbortError" })),
  );
  const res = await request(app)
    .post("/api/transcribe")
    .set("Content-Type", "audio/wav")
    .set("X-Session-Id", "test-session")
    .send(VALID_WAV);
  expect(res.status).toBe(504);
  expect(res.body.error).toBe("provider_timeout");
  expect(res.body.retryable).toBe(true);
});

// ── T-07  Successful transcription → 200 { text } ─────────────────────────────
it("T-07 returns 200 with text on success", async () => {
  vi.stubGlobal("fetch", makeFetchMock(200, { text: "sukite dešinėn" }));
  const res = await request(app)
    .post("/api/transcribe")
    .set("Content-Type", "audio/wav")
    .set("X-Session-Id", "test-session")
    .send(VALID_WAV);
  expect(res.status).toBe(200);
  expect(res.body.text).toBe("sukite dešinėn");
});

// ── T-08  Missing OPENAI_API_KEY → 503 ────────────────────────────────────────
it("T-08 returns 503 when OPENAI_API_KEY is not set", async () => {
  delete process.env["OPENAI_API_KEY"];
  const res = await request(app)
    .post("/api/transcribe")
    .set("Content-Type", "audio/wav")
    .set("X-Session-Id", "test-session")
    .send(VALID_WAV);
  expect(res.status).toBe(503);
  expect(res.body.error).toBe("provider_not_configured");
});
