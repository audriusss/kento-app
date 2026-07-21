import { Router, type IRouter } from "express";

const router: IRouter = Router();

/**
 * POST /api/realtime-session
 *
 * Phase 1: placeholder — returns 501.
 * Phase 3 will call OpenAI POST /v1/realtime/sessions with the server-side
 * OPENAI_API_KEY and return a short-lived client_secret to the Android app.
 * The Android client uses that ephemeral token to open a WebSocket/WebRTC
 * session with the OpenAI Realtime API directly, so the API key never lives
 * in the APK.
 *
 * Expected Phase 3 response shape:
 *   { client_secret: string, expires_at: number }
 */
router.post("/realtime-session", (req, res) => {
  res.status(501).json({
    error: "not_implemented",
    message: "Phase 3: OpenAI Realtime session endpoint not yet implemented.",
  });
});

export default router;
