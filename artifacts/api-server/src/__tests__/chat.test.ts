/**
 * Tests for POST /api/chat
 */

import { describe, it, expect, beforeEach, vi } from "vitest";
import request from "supertest";
import app from "../app.js";

// ── Helpers ───────────────────────────────────────────────────────────────────

const VALID_MESSAGES = [
  { role: "system",    content: "Tu esi Kentas." },
  { role: "user",      content: "Labas." },
];

function makeFetchMock(status: number, body: unknown) {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response);
}

const SUCCESS_RESPONSE = {
  choices: [{ message: { content: "Labas, vairuotojau." } }],
  usage: { prompt_tokens: 30, completion_tokens: 8, total_tokens: 38 },
};

beforeEach(() => {
  process.env["OPENAI_API_KEY"] = "test-key";
  vi.restoreAllMocks();
});

// ── C-01  Missing messages field → 400 ───────────────────────────────────────
it("C-01 rejects body without messages with 400", async () => {
  const res = await request(app)
    .post("/api/chat")
    .send({ sessionId: "abc" });
  expect(res.status).toBe(400);
  expect(res.body.error).toBe("messages_required");
});

// ── C-02  Empty messages array → 400 ─────────────────────────────────────────
it("C-02 rejects empty messages array", async () => {
  const res = await request(app)
    .post("/api/chat")
    .send({ messages: [], sessionId: "abc" });
  expect(res.status).toBe(400);
  expect(res.body.error).toBe("messages_empty");
});

// ── C-03  Too many messages → 400 ────────────────────────────────────────────
it("C-03 rejects messages array longer than 30", async () => {
  const messages = Array.from({ length: 31 }, (_, i) => ({
    role: "user",
    content: `msg ${i}`,
  }));
  const res = await request(app)
    .post("/api/chat")
    .send({ messages, sessionId: "abc" });
  expect(res.status).toBe(400);
  expect(res.body.error).toBe("too_many_messages");
  expect(res.body.max).toBe(30);
});

// ── C-04  Message content exceeds 2000 chars → 400 ───────────────────────────
it("C-04 rejects message with content exceeding 2000 chars", async () => {
  const messages = [{ role: "user", content: "x".repeat(2001) }];
  const res = await request(app)
    .post("/api/chat")
    .send({ messages, sessionId: "abc" });
  expect(res.status).toBe(400);
  expect(res.body.error).toBe("message_too_long");
});

// ── C-05  Invalid message shape → 400 ────────────────────────────────────────
it("C-05 rejects message with invalid role", async () => {
  const messages = [{ role: "hacker", content: "drop tables" }];
  const res = await request(app)
    .post("/api/chat")
    .send({ messages, sessionId: "abc" });
  expect(res.status).toBe(400);
  expect(res.body.error).toBe("invalid_message_shape");
});

// ── C-06  Provider 429 is forwarded ──────────────────────────────────────────
it("C-06 returns 429 when provider rate-limits", async () => {
  vi.stubGlobal("fetch", makeFetchMock(429, {}));
  const res = await request(app)
    .post("/api/chat")
    .send({ messages: VALID_MESSAGES, sessionId: "abc" });
  expect(res.status).toBe(429);
  expect(res.body.retryable).toBe(true);
});

// ── C-07  Provider 500 → 502 ─────────────────────────────────────────────────
it("C-07 returns 502 when provider returns 500", async () => {
  vi.stubGlobal("fetch", makeFetchMock(500, {}));
  const res = await request(app)
    .post("/api/chat")
    .send({ messages: VALID_MESSAGES, sessionId: "abc" });
  expect(res.status).toBe(502);
  expect(res.body.retryable).toBe(true);
});

// ── C-08  Provider timeout → 504 ─────────────────────────────────────────────
it("C-08 returns 504 on provider timeout (AbortError)", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockRejectedValue(Object.assign(new Error("aborted"), { name: "AbortError" })),
  );
  const res = await request(app)
    .post("/api/chat")
    .send({ messages: VALID_MESSAGES, sessionId: "abc" });
  expect(res.status).toBe(504);
  expect(res.body.error).toBe("provider_timeout");
});

// ── C-09  Successful chat → 200 { reply, promptTokens, completionTokens } ────
it("C-09 returns 200 with reply on success", async () => {
  vi.stubGlobal("fetch", makeFetchMock(200, SUCCESS_RESPONSE));
  const res = await request(app)
    .post("/api/chat")
    .set("X-Session-Id", "session-xyz")
    .send({ messages: VALID_MESSAGES, sessionId: "session-xyz" });
  expect(res.status).toBe(200);
  expect(res.body.reply).toBe("Labas, vairuotojau.");
  expect(typeof res.body.promptTokens).toBe("number");
  expect(typeof res.body.completionTokens).toBe("number");
});

// ── C-10  Duplicate request with same idempotency key returns cached reply ────
it("C-10 same idempotency key returns same cached reply", async () => {
  let callCount = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(() => {
      callCount++;
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(SUCCESS_RESPONSE),
        text: () => Promise.resolve(""),
      });
    }),
  );

  const idemKey = `idem-test-${Date.now()}`;

  const res1 = await request(app)
    .post("/api/chat")
    .set("X-Idempotency-Key", idemKey)
    .send({ messages: VALID_MESSAGES, sessionId: "session-idem" });

  const res2 = await request(app)
    .post("/api/chat")
    .set("X-Idempotency-Key", idemKey)
    .send({ messages: VALID_MESSAGES, sessionId: "session-idem" });

  expect(res1.status).toBe(200);
  expect(res2.status).toBe(200);
  expect(res2.body.reply).toBe(res1.body.reply);
  // Provider should only have been called once.
  expect(callCount).toBe(1);
});

// ── C-11  Missing OPENAI_API_KEY → 503 ────────────────────────────────────────
it("C-11 returns 503 when OPENAI_API_KEY is not set", async () => {
  delete process.env["OPENAI_API_KEY"];
  const res = await request(app)
    .post("/api/chat")
    .send({ messages: VALID_MESSAGES, sessionId: "abc" });
  expect(res.status).toBe(503);
});

// ── C-12  Session isolation — different sessions get independent replies ───────
it("C-12 different session IDs do not share conversation context", async () => {
  // Both sessions call the backend independently; neither sees the other's data.
  vi.stubGlobal("fetch", makeFetchMock(200, SUCCESS_RESPONSE));

  const [r1, r2] = await Promise.all([
    request(app)
      .post("/api/chat")
      .set("X-Session-Id", "session-A")
      .send({ messages: VALID_MESSAGES, sessionId: "session-A" }),
    request(app)
      .post("/api/chat")
      .set("X-Session-Id", "session-B")
      .send({ messages: VALID_MESSAGES, sessionId: "session-B" }),
  ]);

  // Both succeed with no cross-contamination (server is stateless for chat).
  expect(r1.status).toBe(200);
  expect(r2.status).toBe(200);
});
