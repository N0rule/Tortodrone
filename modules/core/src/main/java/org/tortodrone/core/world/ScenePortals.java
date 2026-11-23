package org.tortodrone.core.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Server-only portal facts tied to the exact archived layout. No client coordinates are used. */
public record ScenePortals(Map<String, Entrance> entrances, List<Trigger> triggers) {
    // Verified Character/PC/Default circle, collision flags 4; portal sensor mask is 65535.
    public static final float PAWN_RADIUS = .333333f;
    public static final ScenePortals EMPTY = new ScenePortals(Map.of(), List.of());

    public ScenePortals {
        entrances = Map.copyOf(entrances);
        triggers = List.copyOf(triggers);
    }

    public record Entrance(int id, float x, float y, float rotation) {}

    public record Vertex(float x, float y) {}
    /** Rectangle-compatible trigger; nonempty vertices select a convex polygon sensor. */
    public record Trigger(
            int id,
            int destination,
            String entrance,
            float minX,
            float minY,
            float maxX,
            float maxY,
            List<Vertex> vertices) {
        public Trigger(int id, int destination, String entrance, float minX, float minY, float maxX, float maxY) {
            this(id, destination, entrance, minX, minY, maxX, maxY, List.of());
        }

        public Trigger {
            vertices = List.copyOf(vertices);
            if (!vertices.isEmpty())
                try {
                    validatePolygon(vertices);
                    float actualMinX =
                            vertices.stream().map(Vertex::x).min(Float::compare).orElseThrow();
                    float actualMinY =
                            vertices.stream().map(Vertex::y).min(Float::compare).orElseThrow();
                    float actualMaxX =
                            vertices.stream().map(Vertex::x).max(Float::compare).orElseThrow();
                    float actualMaxY =
                            vertices.stream().map(Vertex::y).max(Float::compare).orElseThrow();
                    if (minX != actualMinX || minY != actualMinY || maxX != actualMaxX || maxY != actualMaxY)
                        throw new IllegalArgumentException("Polygon trigger bounds do not match vertices");
                } catch (IOException e) {
                    throw new IllegalArgumentException("Invalid polygon trigger", e);
                }
        }

        public boolean intersects(float x, float y) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) return false;
            float r = PAWN_RADIUS;
            if (x < minX - r || x > maxX + r || y < minY - r || y > maxY + r) return false;
            if (!vertices.isEmpty()) {
                boolean inside = true;
                float sign = 0;
                for (int i = 0; i < vertices.size(); i++) {
                    Vertex a = vertices.get(i), b = vertices.get((i + 1) % vertices.size());
                    float cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
                    if (cross != 0) {
                        if (sign == 0) sign = Math.signum(cross);
                        else if (Math.signum(cross) != sign) inside = false;
                    }
                }
                if (inside) return true;
                for (int i = 0; i < vertices.size(); i++) {
                    Vertex a = vertices.get(i), b = vertices.get((i + 1) % vertices.size());
                    float dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
                    float t = Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / length, 0f, 1f);
                    float ex = x - (a.x + t * dx), ey = y - (a.y + t * dy);
                    if (ex * ex + ey * ey <= r * r) return true;
                }
                return false;
            }
            float dx = x - Math.clamp(x, minX, maxX), dy = y - Math.clamp(y, minY, maxY);
            return dx * dx + dy * dy <= r * r;
        }
    }

    public static ScenePortals load(Path folder, String layoutDigest, CollisionMap terrain) throws IOException {
        return ScenePortalCodec.load(folder, layoutDigest, terrain);
    }

    public static ScenePortals load(Properties p, String layoutDigest, MapCollision collision, int mask)
            throws IOException {
        return ScenePortalCodec.load(p, layoutDigest, collision, mask);
    }

    public static ScenePortals load(Properties p, String layoutDigest, CollisionMap terrain) throws IOException {
        return ScenePortalCodec.load(p, layoutDigest, terrain);
    }

    /** Shared convex-polygon rule, also enforced when a trigger is constructed directly. */
    static void validatePolygon(List<Vertex> points) throws IOException {
        ScenePortalCodec.validatePolygon(points);
    }
}
