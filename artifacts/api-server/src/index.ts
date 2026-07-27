import app from "./app.js";
import { logger } from "./lib/logger.js";
import type { Server } from "node:http";

const rawPort = process.env["PORT"];

if (!rawPort) {
  throw new Error("PORT environment variable is required but was not provided.");
}

const port = Number(rawPort);

if (Number.isNaN(port) || port <= 0) {
  throw new Error(`Invalid PORT value: "${rawPort}"`);
}

const server: Server = app.listen(port, (err?: Error) => {
  if (err) {
    logger.error({ err }, "Error listening on port");
    process.exit(1);
  }
  logger.info({ port }, "Server listening");
});

// ── Graceful shutdown ─────────────────────────────────────────────────────────
// On SIGTERM (container stop, deployment replace) or SIGINT (Ctrl-C):
//  1. Stop accepting new connections.
//  2. Let in-flight requests drain (max 10 s).
//  3. Exit cleanly so the process manager can restart immediately.

const SHUTDOWN_TIMEOUT_MS = 10_000;

function shutdown(signal: string): void {
  logger.info({ signal }, "Shutdown signal received — draining connections");

  const forceExit = setTimeout(() => {
    logger.error("Forced exit after drain timeout");
    process.exit(1);
  }, SHUTDOWN_TIMEOUT_MS);
  forceExit.unref();   // don't prevent normal exit if server closes faster

  server.close((err) => {
    if (err) {
      logger.error({ err }, "Error closing server");
      process.exit(1);
    }
    logger.info("Server closed cleanly");
    process.exit(0);
  });
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT",  () => shutdown("SIGINT"));

process.on("uncaughtException", (err) => {
  logger.fatal({ err }, "Uncaught exception — exiting");
  process.exit(1);
});

process.on("unhandledRejection", (reason) => {
  logger.fatal({ reason }, "Unhandled promise rejection — exiting");
  process.exit(1);
});
