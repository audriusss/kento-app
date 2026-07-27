import express, { type Express } from "express";
import cors from "cors";
import pinoHttp from "pino-http";
import router from "./routes/index.js";
import { logger } from "./lib/logger.js";
import { defaultRateLimit } from "./middlewares/rateLimit.js";

const app: Express = express();

// ── CORS ──────────────────────────────────────────────────────────────────────
// In production, restrict to the deployed frontend domain.
// CORS_ALLOWED_ORIGINS is a comma-separated list; falls back to allowing the
// Replit dev domain pattern.  The Android app calls the backend directly (not
// via a browser origin), so these rules primarily guard any future web client.

const allowedOrigins = process.env["CORS_ALLOWED_ORIGINS"]
  ? process.env["CORS_ALLOWED_ORIGINS"].split(",").map((o) => o.trim())
  : [];   // empty → cors() will allow all origins (safe for API-only server)

app.use(
  cors(
    allowedOrigins.length > 0
      ? {
          origin: (origin, callback) => {
            // Allow requests with no Origin header (mobile apps, curl).
            if (!origin || allowedOrigins.includes(origin)) {
              callback(null, true);
            } else {
              callback(new Error("Not allowed by CORS"));
            }
          },
          credentials: true,
        }
      : undefined,  // wide-open for development / API-only usage
  ),
);

// ── Request logging ───────────────────────────────────────────────────────────
app.use(
  pinoHttp({
    logger,
    serializers: {
      req(req) {
        return {
          id:     req.id,
          method: req.method,
          url:    req.url?.split("?")[0],   // no query strings in logs
        };
      },
      res(res) {
        return { statusCode: res.statusCode };
      },
    },
  }),
);

// ── Body parsers ──────────────────────────────────────────────────────────────
// Explicit 100 kB limit for JSON (default, but documented).  Audio is handled
// per-route in transcribe.ts using a streaming approach to avoid buffering
// large uploads in the global middleware layer.
app.use(express.json({ limit: "100kb" }));
app.use(express.urlencoded({ extended: true, limit: "100kb" }));

// ── Default rate limit (catch-all) ────────────────────────────────────────────
// Per-endpoint tighter limits are applied inside each route module.
app.use(defaultRateLimit);

// ── Security headers ──────────────────────────────────────────────────────────
app.use((_req, res, next) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Referrer-Policy", "no-referrer");
  next();
});

// ── Routes ────────────────────────────────────────────────────────────────────
app.use("/api", router);

// ── 404 handler ───────────────────────────────────────────────────────────────
app.use((_req, res) => {
  res.status(404).json({ error: "not_found" });
});

// ── Error handler ─────────────────────────────────────────────────────────────
app.use((err: any, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  logger.error({ err }, "unhandled error");
  res.status(500).json({ error: "internal_error" });
});

export default app;
