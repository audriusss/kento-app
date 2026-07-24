import { pgTable, text, timestamp, real, integer } from "drizzle-orm/pg-core";
import { createInsertSchema, createSelectSchema } from "drizzle-zod";
import { z } from "zod/v4";

/**
 * Community speed-camera / police marker table.
 *
 * Each row represents one user-submitted marker at a GPS coordinate.
 * Markers are anonymous (only a hashed device ID is stored — no user account required).
 *
 * Lifecycle:
 *   - Inserted via POST /api/markers (status = 'active').
 *   - Soft-deleted via DELETE /api/markers/:id (status = 'removed').
 *   - Confirmed by a second device via POST /api/markers/:id/confirm (confirmCount++).
 *   - Aged out by a cron job: status set to 'expired' after [ttlMinutes] elapses.
 */
export const markersTable = pgTable("markers", {
  id: text("id").primaryKey().$defaultFn(() => crypto.randomUUID()),

  /**
   * Marker category. Matches CommunityMarkerRepository.MarkerType.apiName on Android.
   * Valid values: 'speed_camera' | 'police' | 'accident' | 'hazard'
   */
  type: text("type").notNull(),

  /** WGS-84 latitude (−90 to +90). */
  lat: real("lat").notNull(),

  /** WGS-84 longitude (−180 to +180). */
  lng: real("lng").notNull(),

  /**
   * Hashed anonymous device identifier.
   * SHA-256 of the raw UUID from CommunityMarkerRepository so we can rate-limit
   * per device without storing the raw ID.
   */
  deviceIdHash: text("device_id_hash").notNull(),

  /**
   * Lifecycle state.
   * 'active'  — visible to nearby devices.
   * 'removed' — soft-deleted by the original reporter.
   * 'expired' — TTL elapsed (not returned to clients).
   */
  status: text("status").notNull().default("active"),

  /** Number of independent devices that have confirmed this marker. */
  confirmCount: integer("confirm_count").notNull().default(0),

  /** UTC timestamp of the first report. */
  createdAt: timestamp("created_at").notNull().defaultNow(),

  /** UTC timestamp of the last status change. */
  updatedAt: timestamp("updated_at").notNull().defaultNow(),

  /**
   * Minutes after [createdAt] before the marker auto-expires.
   * Defaults: speed_camera = 480 (8 h), police = 120 (2 h), accident/hazard = 60 (1 h).
   * Stored in the row so it can be customised per type at insertion time.
   */
  ttlMinutes: integer("ttl_minutes").notNull().default(120),
});

// ── Zod schemas ───────────────────────────────────────────────────────────────

export const insertMarkerSchema = createInsertSchema(markersTable).omit({
  id: true,
  status: true,
  confirmCount: true,
  createdAt: true,
  updatedAt: true,
});

export const selectMarkerSchema = createSelectSchema(markersTable);

// ── Client-facing insert type (from Android POST body) ─────────────────────

export const clientInsertMarkerSchema = z.object({
  type: z.enum(["speed_camera", "police", "accident", "hazard"]),
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  deviceId: z.string().min(1).max(200),   // raw UUID; server hashes before storing
});

export type ClientInsertMarker = z.infer<typeof clientInsertMarkerSchema>;
export type InsertMarker = z.infer<typeof insertMarkerSchema>;
export type Marker = typeof markersTable.$inferSelect;

// TTL defaults per marker type (in minutes)
export const MARKER_TTL: Record<string, number> = {
  speed_camera: 480,
  police: 120,
  accident: 60,
  hazard: 60,
};
