import { Router } from "express";
import { db } from "@workspace/db";
import {
  markersTable,
  clientInsertMarkerSchema,
  MARKER_TTL,
} from "@workspace/db/schema";
import { eq, and, sql, gte } from "drizzle-orm";
import crypto from "node:crypto";

const router = Router();

// ── POST /markers ─────────────────────────────────────────────────────────────
// Report a new community marker.
//
// Body: { type, lat, lng, deviceId }
// Returns 201 + the created marker id on success.
// Rate-limited server-side: one report per device per type per 5 minutes.

router.post("/markers", async (req, res) => {
  const parsed = clientInsertMarkerSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: "Invalid request", details: parsed.error.flatten() });
  }

  const { type, lat, lng, deviceId } = parsed.data;
  const deviceIdHash = crypto.createHash("sha256").update(deviceId).digest("hex");
  const ttlMinutes = MARKER_TTL[type] ?? 120;

  // Server-side rate limit: one report per device + type per 5 minutes.
  const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1_000);
  const existing = await db
    .select({ id: markersTable.id })
    .from(markersTable)
    .where(
      and(
        eq(markersTable.deviceIdHash, deviceIdHash),
        eq(markersTable.type, type),
        gte(markersTable.createdAt, fiveMinutesAgo),
      ),
    )
    .limit(1);

  if (existing.length > 0) {
    return res.status(429).json({ error: "Rate limited. Try again in a few minutes." });
  }

  const [marker] = await db
    .insert(markersTable)
    .values({ type, lat, lng, deviceIdHash, ttlMinutes })
    .returning({ id: markersTable.id });

  return res.status(201).json({ id: marker.id });
});

// ── GET /markers ──────────────────────────────────────────────────────────────
// Fetch active markers within a bounding box derived from ?lat=&lng=&radius= (metres).
//
// Uses a simple latitude/longitude delta approximation — accurate enough for ≤ 10 km
// without requiring PostGIS.

router.get("/markers", async (req, res) => {
  const lat    = parseFloat(req.query.lat as string);
  const lng    = parseFloat(req.query.lng as string);
  const radius = parseFloat(req.query.radius as string) || 5000;

  if (isNaN(lat) || isNaN(lng)) {
    return res.status(400).json({ error: "lat and lng are required" });
  }

  // Approximate degree deltas (1° lat ≈ 111 km; 1° lng ≈ 111 km × cos(lat))
  const latDelta = radius / 111_000;
  const lngDelta = radius / (111_000 * Math.cos(Math.abs(lat) * (Math.PI / 180)));

  // Filter out expired markers (createdAt + ttlMinutes <= now)
  const now = new Date();
  const markers = await db
    .select({
      id:           markersTable.id,
      type:         markersTable.type,
      lat:          markersTable.lat,
      lng:          markersTable.lng,
      confirmCount: markersTable.confirmCount,
      createdAt:    markersTable.createdAt,
    })
    .from(markersTable)
    .where(
      and(
        eq(markersTable.status, "active"),
        sql`${markersTable.lat} BETWEEN ${lat - latDelta} AND ${lat + latDelta}`,
        sql`${markersTable.lng} BETWEEN ${lng - lngDelta} AND ${lng + lngDelta}`,
        // Only include markers whose TTL has not elapsed
        sql`${markersTable.createdAt} + (${markersTable.ttlMinutes} * interval '1 minute') > ${now}`,
      ),
    );

  return res.json(markers);
});

// ── POST /markers/:id/confirm ─────────────────────────────────────────────────
// Increment the confirm count for a marker (independent device sighting).

router.post("/markers/:id/confirm", async (req, res) => {
  const { id } = req.params;
  const parsed = clientInsertMarkerSchema.partial().safeParse(req.body);
  const deviceId = (req.body as { deviceId?: string }).deviceId;
  if (!deviceId) {
    return res.status(400).json({ error: "deviceId required" });
  }

  const [updated] = await db
    .update(markersTable)
    .set({
      confirmCount: sql`${markersTable.confirmCount} + 1`,
      updatedAt: new Date(),
    })
    .where(and(eq(markersTable.id, id), eq(markersTable.status, "active")))
    .returning({ id: markersTable.id, confirmCount: markersTable.confirmCount });

  if (!updated) {
    return res.status(404).json({ error: "Marker not found or not active" });
  }
  return res.json({ id: updated.id, confirmCount: updated.confirmCount });
});

// ── DELETE /markers/:id ───────────────────────────────────────────────────────
// Soft-delete (reporter removes their own marker).
// Only the original reporter (matched by deviceIdHash) can remove.

router.delete("/markers/:id", async (req, res) => {
  const { id } = req.params;
  const deviceId = (req.body as { deviceId?: string }).deviceId;
  if (!deviceId) {
    return res.status(400).json({ error: "deviceId required" });
  }
  const deviceIdHash = crypto.createHash("sha256").update(deviceId).digest("hex");

  const [removed] = await db
    .update(markersTable)
    .set({ status: "removed", updatedAt: new Date() })
    .where(
      and(
        eq(markersTable.id, id),
        eq(markersTable.deviceIdHash, deviceIdHash),
        eq(markersTable.status, "active"),
      ),
    )
    .returning({ id: markersTable.id });

  if (!removed) {
    return res.status(404).json({ error: "Marker not found or not yours" });
  }
  return res.json({ id: removed.id, status: "removed" });
});

export default router;
