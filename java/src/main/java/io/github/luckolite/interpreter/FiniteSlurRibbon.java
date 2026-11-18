// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.math.BigInteger;
import java.util.*;

/** Exact complete pixel-square strip certificate, independent of a selected local angle.
 * This proves only finite raw geometry, never musical ownership or a slur by itself. */
final class FiniteSlurRibbon {
    private static final int MAX_CELLS = 200000, MAX_STATES = 200000, MAX_CLIPS = 2000000;

    record Certificate(
            boolean complete,
            String reason,
            int pixelCount,
            long coveredAreaTwice,
            long maxCutSquared,
            boolean insideStrip,
            boolean noncrossing,
            boolean positiveOrientation,
            boolean limitReached) {}

    private record P(int x, int y) {}

    private record Cap(int edge, P point) {}

    private record T(long n, long d) {}

    private static Certificate no(String why, int cells) {
        return new Certificate(false, why, cells, 0, 0, false, false, false, false);
    }

    private static Certificate limit(String why, int cells) {
        return new Certificate(false, why, cells, 0, 0, false, false, false, true);
    }

    static long pixel(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    static Certificate certify(Set<Long> cells, int maximum) {
        if (cells == null || cells.isEmpty() || maximum <= 0)
            return no("undefined", cells == null ? 0 : cells.size());
        if (cells.size() > MAX_CELLS || maximum > Integer.MAX_VALUE / 8)
            return limit("resource-or-integer-limit", cells.size());
        var next = new HashMap<P, P>();
        var indegree = new HashMap<P, Integer>();
        int edges = 0;
        int left = Integer.MAX_VALUE,
                right = Integer.MIN_VALUE,
                minY = Integer.MAX_VALUE,
                maxY = Integer.MIN_VALUE;
        var ordered = new ArrayList<Long>(cells);
        Collections.sort(ordered);
        for (long id : ordered) {
            int x = (int) (id >> 32), y = (int) id;
            if (Math.abs((long) x) > Integer.MAX_VALUE / 8
                    || Math.abs((long) y) > Integer.MAX_VALUE / 8)
                return no("integer-limit", cells.size());
            left = Math.min(left, x);
            right = Math.max(right, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            P a = new P(4 * x - 2, 4 * y - 2),
                    b = new P(4 * x + 2, 4 * y - 2),
                    c = new P(4 * x + 2, 4 * y + 2),
                    d = new P(4 * x - 2, 4 * y + 2);
            P[][] proposed = {{a, b}, {b, c}, {c, d}, {d, a}};
            boolean[] exposed = {
                !cells.contains(pixel(x, y - 1)),
                !cells.contains(pixel(x + 1, y)),
                !cells.contains(pixel(x, y + 1)),
                !cells.contains(pixel(x - 1, y))
            };
            for (int i = 0; i < 4; i++)
                if (exposed[i]) {
                    P from = proposed[i][0], to = proposed[i][1];
                    if (next.put(from, to) != null)
                        return no("ambiguous-boundary-vertex", cells.size());
                    if (indegree.merge(to, 1, Integer::sum) != 1)
                        return no("ambiguous-boundary-vertex", cells.size());
                    edges++;
                }
        }
        if (edges == 0 || next.size() != indegree.size()) return no("open-boundary", cells.size());
        P start =
                Collections.min(
                        next.keySet(), Comparator.comparingInt(P::x).thenComparingInt(P::y));
        var loop = new ArrayList<P>();
        var seen = new HashSet<P>();
        P current = start;
        do {
            if (current == null || !seen.add(current))
                return no("non-simple-boundary", cells.size());
            loop.add(current);
            current = next.get(current);
        } while (!start.equals(current));
        if (loop.size() != edges) return no("hole-or-disconnected", cells.size());
        Cap first = cap(loop, cells, left, -2), last = cap(loop, cells, right, 2);
        if (first == null || last == null || first.edge() == last.edge())
            return no("cap-phase-ambiguity", cells.size());
        var upper = sample(chain(loop, first, last));
        var reverse = chain(loop, last, first);
        Collections.reverse(reverse);
        var lower = sample(reverse);
        if (!monotone(upper) || !monotone(lower)) return no("branched-side", cells.size());
        long budget = 16L * maximum * maximum;
        // Only a horizontal band of lower samples is inspected for each upper sample.
        // Store the chosen predecessor in that band so backtracking retains its tie order.
        byte[][] parents = new byte[upper.size()][];
        int[] starts = new int[upper.size()];
        int considered = 0, firstColumn = 0, endColumn = 0;
        for (int i = 0; i < upper.size(); i++) {
            P u = upper.get(i);
            while (firstColumn < lower.size() && lower.get(firstColumn).x() < u.x() - 4L * maximum)
                firstColumn++;
            endColumn = Math.max(endColumn, firstColumn);
            while (endColumn < lower.size() && lower.get(endColumn).x() <= u.x() + 4L * maximum)
                endColumn++;
            int length = endColumn - firstColumn;
            if (length > MAX_STATES - considered) return limit("state-limit", cells.size());
            considered += length;
            starts[i] = firstColumn;
            byte[] row = parents[i] = new byte[length];
            for (int j = firstColumn; j < endColumn; j++) {
                int col = j - firstColumn;
                boolean origin = i == 0 && j == 0;
                int above = i == 0 ? -1 : j - starts[i - 1];
                boolean up = i > 0 && reached(parents[i - 1], above);
                boolean leftward = col > 0 && row[col - 1] != 0;
                boolean diagonal = i > 0 && reached(parents[i - 1], above - 1);
                if (!(origin || up || leftward || diagonal)) continue;
                P l = lower.get(j);
                if (distance(u, l) > budget || !insideCut(u, l, cells)) continue;
                // An up or left step shares an endpoint and encloses a single triangle.
                if (origin) row[col] = ORIGIN;
                else if (up && cross(upper.get(i - 1), u, l) >= 0) row[col] = UP;
                else if (leftward && cross(u, l, lower.get(j - 1)) >= 0) row[col] = LEFT;
                else if (diagonal && validStep(upper.get(i - 1), lower.get(j - 1), u, l))
                    row[col] = DIAGONAL;
            }
        }
        int iEnd = upper.size() - 1, jEnd = lower.size() - 1;
        if (!reached(parents[iEnd], jEnd - starts[iEnd]))
            return no("no-complete-width-path", cells.size());
        var path = new ArrayList<Long>();
        while (true) {
            path.add(key(iEnd, jEnd));
            byte direction = parents[iEnd][jEnd - starts[iEnd]];
            if (direction == ORIGIN) break;
            if (direction == UP || direction == DIAGONAL) iEnd--;
            if (direction == LEFT || direction == DIAGONAL) jEnd--;
        }
        Collections.reverse(path);
        // No non-adjacent cut may cross, even if local free-space reachability was valid.
        int comparisons = 0;
        for (int i = 0; i + 2 < path.size(); i++) {
            long a = path.get(i);
            P top = upper.get((int) (a >> 32)), bottom = lower.get((int) a);
            int rightEdge = Math.max(top.x(), bottom.x());
            for (int j = i + 2; j < path.size(); j++) {
                long b = path.get(j);
                P nextTop = upper.get((int) (b >> 32)), nextBottom = lower.get((int) b);
                // Both sides advance monotonically. Every remaining cut lies to the right.
                if (Math.min(nextTop.x(), nextBottom.x()) > rightEdge) {
                    comparisons += path.size() - j;
                    if (comparisons > MAX_CLIPS) return limit("crossing-check-limit", cells.size());
                    break;
                }
                if (++comparisons > MAX_CLIPS) return limit("crossing-check-limit", cells.size());
                if (strictCross(top, bottom, nextTop, nextBottom))
                    return no("crossing-strip", cells.size());
            }
        }
        var covered = new HashMap<Long, Q>();
        int[] clips = {0};
        long maxCut = 0;
        for (long p : path)
            maxCut = Math.max(maxCut, distance(upper.get((int) (p >> 32)), lower.get((int) p)));
        for (int i = 1; i < path.size(); i++) {
            long a = path.get(i - 1), b = path.get(i);
            P p = upper.get((int) (a >> 32)),
                    q = upper.get((int) (b >> 32)),
                    r = lower.get((int) b),
                    s = lower.get((int) a);
            long ar = area2(p, q, r, s);
            if (ar < 0) return no("negative-orientation", cells.size());
            // A concave strip step uses its valid interior diagonal. Neither diagonal is
            // a transverse-width assertion; both original crossing cuts remain<=maximum.
            P[][] triangles;
            if (cross(p, q, r) >= 0 && cross(p, r, s) >= 0)
                triangles = new P[][] {{p, q, r}, {p, r, s}};
            else if (cross(p, q, s) >= 0 && cross(q, r, s) >= 0)
                triangles = new P[][] {{p, q, s}, {q, r, s}};
            else return no("folded-strip-step", cells.size());
            for (P[] tri : triangles)
                if (!cover(tri, cells, covered, clips))
                    return clips[0] > MAX_CLIPS
                            ? limit("clip-limit", cells.size())
                            : no("unowned-or-overlapping-area", cells.size());
        }
        Q full = Q.of(32);
        for (long id : cells)
            if (!full.equals(covered.get(id))) return no("incomplete-whole-area", cells.size());
        if (covered.size() != cells.size()) return no("foreign-area", cells.size());
        return new Certificate(
                true,
                "complete",
                cells.size(),
                32L * cells.size(),
                maxCut,
                true,
                true,
                true,
                false);
    }

    private static final byte UP = 1, LEFT = 2, DIAGONAL = 3, ORIGIN = 4;

    private static boolean reached(byte[] row, int column) {
        return column >= 0 && column < row.length && row[column] != 0;
    }

    private static boolean validStep(P a, P b, P u, P l) {
        return !strictCross(a, b, u, l) && area2(a, u, l, b) >= 0;
    }

    private static long key(int i, int j) {
        return ((long) i << 32) | (j & 0xffffffffL);
    }

    private static long distance(P a, P b) {
        long x = (long) a.x() - b.x(), y = (long) a.y() - b.y();
        return x * x + y * y;
    }

    private static long cross(P a, P b, P c) {
        return ((long) b.x() - a.x()) * (c.y() - a.y()) - ((long) b.y() - a.y()) * (c.x() - a.x());
    }

    private static long area2(P... p) {
        long sum = 0;
        for (int i = 0; i < p.length; i++) {
            P a = p[i], b = p[(i + 1) % p.length];
            sum += (long) a.x() * b.y() - (long) a.y() * b.x();
        }
        return sum;
    }

    private static boolean strictCross(P a, P b, P c, P d) {
        return opposite(cross(a, b, c), cross(a, b, d)) && opposite(cross(c, d, a), cross(c, d, b));
    }

    private static boolean opposite(long a, long b) {
        return a < 0 && b > 0 || a > 0 && b < 0;
    }

    private static Cap cap(List<P> loop, Set<Long> cells, int x, int direction) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (long id : cells)
            if ((int) (id >> 32) == x) {
                min = Math.min(min, (int) id);
                max = Math.max(max, (int) id);
            }
        long mid = 2L * (min + max), best = Long.MAX_VALUE;
        Cap result = null;
        boolean tie = false;
        for (int i = 0; i < loop.size(); i++) {
            P a = loop.get(i), b = loop.get((i + 1) % loop.size());
            if (a.x() != 4 * x + direction || b.x() != a.x() || Math.abs(a.y() - b.y()) != 4)
                continue;
            P p = new P(a.x(), (a.y() + b.y()) / 2);
            long d = Math.abs(p.y() - mid);
            if (d < best) {
                best = d;
                result = new Cap(i, p);
                tie = false;
            } else if (d == best) tie = true;
        }
        if (!tie) return result;
        // An even number of complete end-column cells has a unique geometric
        // midpoint at a boundary vertex, between the two old unit-edge midpoints.
        // Keep every previously unique cap exactly as before.
        if ((max - min + 1L) % 2 != 0 || max - min + 1L > cells.size()) return null;
        for (int y = min; y <= max; y++) if (!cells.contains(pixel(x, y))) return null;
        Cap vertex = null;
        for (int i = 0; i < loop.size(); i++) {
            P a = loop.get(i), b = loop.get((i + 1) % loop.size());
            if (a.x() != 4 * x + direction || b.x() != a.x() || Math.abs(a.y() - b.y()) != 4)
                continue;
            // Half-open segments assign the shared vertex exactly once. The point
            // lies on the original complete pixel-cell boundary; no pixel is clipped.
            if (Math.min(a.y(), b.y()) <= mid && mid < Math.max(a.y(), b.y())) {
                if (vertex != null) return null;
                vertex = new Cap(i, new P(a.x(), (int) mid));
            }
        }
        return vertex;
    }

    private static ArrayList<P> chain(List<P> loop, Cap a, Cap b) {
        var points = new ArrayList<P>();
        points.add(a.point());
        int k = (a.edge() + 1) % loop.size();
        while (true) {
            points.add(loop.get(k));
            if (k == b.edge()) break;
            k = (k + 1) % loop.size();
        }
        points.add(b.point());
        return points;
    }

    private static List<P> sample(List<P> p) {
        var out = new ArrayList<P>();
        for (int i = 1; i < p.size(); i++) {
            P a = p.get(i - 1), b = p.get(i);
            int steps = Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y())) / 2;
            if (steps == 0) continue;
            for (int s = 0; s < steps; s++)
                out.add(
                        new P(
                                a.x() + (b.x() - a.x()) * s / steps,
                                a.y() + (b.y() - a.y()) * s / steps));
        }
        out.add(p.get(p.size() - 1));
        return out;
    }

    private static boolean monotone(List<P> p) {
        for (int i = 1; i < p.size(); i++) if (p.get(i).x() < p.get(i - 1).x()) return false;
        return true;
    }

    private static boolean insideCut(P a, P b, Set<Long> cells) {
        long dx = (long) b.x() - a.x(), dy = (long) b.y() - a.y();
        long width = Math.abs(dx), height = Math.abs(dy);
        long x = firstBoundaryDistance(a.x(), dx);
        long y = firstBoundaryDistance(a.y(), dy);
        T before = new T(0, 1);
        if (!insidePoint(a, b, before, cells)) return false;
        while (true) {
            boolean crossX = x < width, crossY = y < height;
            T next;
            if (crossX && (!crossY || x * height <= y * width)) {
                next = new T(x, width);
                if (crossY && x * height == y * width) y += 4;
                x += 4;
            } else if (crossY) {
                next = new T(y, height);
                y += 4;
            } else {
                next = new T(1, 1);
            }
            T midpoint = new T(before.n() * next.d() + next.n() * before.d(),
                    2 * before.d() * next.d());
            if (!insidePoint(a, b, next, cells) || !insidePoint(a, b, midpoint, cells)) return false;
            if (!crossX && !crossY) return true;
            before = next;
        }
    }

    private static long firstBoundaryDistance(int coordinate, long delta) {
        // Pixel boundaries are congruent to two modulo four in quarter-pixel units.
        return 1 + Math.floorMod(delta >= 0 ? 1L - coordinate : coordinate - 3L, 4L);
    }

    private static boolean insidePoint(P a, P b, T t, Set<Long> cells) {
        long xn = (long) a.x() * t.d() + ((long) b.x() - a.x()) * t.n(),
                yn = (long) a.y() * t.d() + ((long) b.y() - a.y()) * t.n();
        int cx = (int) Math.floorDiv(xn + 2 * t.d(), 4 * t.d()),
                cy = (int) Math.floorDiv(yn + 2 * t.d(), 4 * t.d());
        long radius = 2 * t.d();
        for (int x = cx - 1; x <= cx; x++) {
            if (Math.abs(xn - 4L * x * t.d()) > radius) continue;
            for (int y = cy - 1; y <= cy; y++) {
                if (Math.abs(yn - 4L * y * t.d()) <= radius && cells.contains(pixel(x, y)))
                    return true;
            }
        }
        return false;
    }

    // Two products of signed 31-bit magnitudes, including their sum or difference,
    // fit in a signed long. Larger reduced fractions use arbitrary precision.
    private record Q(long n, long d, BigInteger wideN, BigInteger wideD) implements Comparable<Q> {
        private static Q small(long n, long d) {
            if (d < 0) {
                n = -n;
                d = -d;
            }
            long a = Math.abs(n), b = d;
            while (b != 0) {
                long remainder = a % b;
                a = b;
                b = remainder;
            }
            n /= a;
            d /= a;
            if (Math.abs(n) <= Integer.MAX_VALUE && d <= Integer.MAX_VALUE)
                return new Q(n, d, null, null);
            return new Q(0, 0, BigInteger.valueOf(n), BigInteger.valueOf(d));
        }

        private static Q wide(BigInteger n, BigInteger d) {
            if (d.signum() < 0) {
                n = n.negate();
                d = d.negate();
            }
            BigInteger gcd = n.gcd(d);
            n = n.divide(gcd);
            d = d.divide(gcd);
            if (n.abs().bitLength() <= 31 && d.bitLength() <= 31)
                return new Q(n.longValue(), d.longValue(), null, null);
            return new Q(0, 0, n, d);
        }

        static Q of(long value) {
            if (value >= -Integer.MAX_VALUE && value <= Integer.MAX_VALUE)
                return new Q(value, 1, null, null);
            return new Q(0, 0, BigInteger.valueOf(value), BigInteger.ONE);
        }

        private BigInteger numerator() { return wideN != null ? wideN : BigInteger.valueOf(n); }
        private BigInteger denominator() { return wideD != null ? wideD : BigInteger.valueOf(d); }

        Q add(Q v) {
            if (wideN == null && v.wideN == null) return small(n * v.d + v.n * d, d * v.d);
            return wide(numerator().multiply(v.denominator()).add(v.numerator().multiply(denominator())),
                    denominator().multiply(v.denominator()));
        }

        Q sub(Q v) {
            if (wideN == null && v.wideN == null) return small(n * v.d - v.n * d, d * v.d);
            return wide(numerator().multiply(v.denominator()).subtract(v.numerator().multiply(denominator())),
                    denominator().multiply(v.denominator()));
        }

        Q mul(Q v) {
            if (wideN == null && v.wideN == null) return small(n * v.n, d * v.d);
            return wide(numerator().multiply(v.numerator()), denominator().multiply(v.denominator()));
        }

        Q div(Q v) {
            if (wideN == null && v.wideN == null) return small(n * v.d, d * v.n);
            return wide(numerator().multiply(v.denominator()), denominator().multiply(v.numerator()));
        }

        public int compareTo(Q v) {
            if (wideN == null && v.wideN == null) return Long.compare(n * v.d, v.n * d);
            return numerator().multiply(v.denominator()).compareTo(v.numerator().multiply(denominator()));
        }
    }

    private record V(Q x, Q y) {}

    private static boolean cover(P[] triangle, Set<Long> cells, Map<Long, Q> covered, int[] clips) {
        if (cross(triangle[0], triangle[1], triangle[2]) == 0) return true;
        if (cross(triangle[0], triangle[1], triangle[2]) < 0) return false;
        int minX = Integer.MAX_VALUE,
                maxX = Integer.MIN_VALUE,
                minY = Integer.MAX_VALUE,
                maxY = Integer.MIN_VALUE;
        var poly = new ArrayList<V>();
        for (P p : triangle) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        // Clipping and signed area are invariant under a common translation.
        for (P p : triangle)
            poly.add(new V(Q.of((long) p.x() - minX), Q.of((long) p.y() - minY)));
        for (int x = Math.floorDiv(minX + 2, 4) - 1; x <= Math.floorDiv(maxX + 2, 4); x++) {
            List<V> column = 4L * x + 2 <= minX || 4L * x - 2 >= maxX
                    ? List.of() : clip(poly, 0, 4L * x - 2 - minX, true);
            column = clip(column, 0, 4L * x + 2 - minX, false);
            for (int y = Math.floorDiv(minY + 2, 4) - 1; y <= Math.floorDiv(maxY + 2, 4); y++) {
                if (++clips[0] > MAX_CLIPS) return false;
                if (column.size() < 3 || 4L * y + 2 <= minY || 4L * y - 2 >= maxY) continue;
                List<V> p = clip(column, 1, 4L * y - 2 - minY, true);
                p = clip(p, 1, 4L * y + 2 - minY, false);
                Q area = Q.of(0);
                for (int i = 0; i < p.size(); i++) {
                    V a = p.get(i), b = p.get((i + 1) % p.size());
                    area = area.add(a.x().mul(b.y()).sub(a.y().mul(b.x())));
                }
                if (area.compareTo(Q.of(0)) < 0) return false;
                if (area.compareTo(Q.of(0)) == 0) continue;
                long id = pixel(x, y);
                if (!cells.contains(id)) return false;
                Q sum = covered.getOrDefault(id, Q.of(0)).add(area);
                if (sum.compareTo(Q.of(32)) > 0) return false;
                covered.put(id, sum);
            }
        }
        return true;
    }

    private static List<V> clip(List<V> p, int axis, long boundary, boolean lower) {
        if (p.isEmpty()) return p;
        ArrayList<V> result = null;
        Q k = Q.of(boundary);
        V a = p.get(p.size() - 1);
        Q av = axis == 0 ? a.x() : a.y();
        boolean ain = lower ? av.compareTo(k) >= 0 : av.compareTo(k) <= 0;
        for (int i = 0; i < p.size(); i++) {
            V b = p.get(i);
            Q bv = axis == 0 ? b.x() : b.y();
            boolean bin = lower ? bv.compareTo(k) >= 0 : bv.compareTo(k) <= 0;
            if (ain != bin) {
                if (result == null) {
                    result = new ArrayList<>(p.size() + 1);
                    if (ain) {
                        for (int j = 0; j < i; j++) result.add(p.get(j));
                    }
                }
                Q t = k.sub(av).div(bv.sub(av));
                result.add(new V(a.x().add(b.x().sub(a.x()).mul(t)),
                        a.y().add(b.y().sub(a.y()).mul(t))));
            }
            if (bin && result != null) result.add(b);
            a = b;
            av = bv;
            ain = bin;
        }
        if (result == null) return ain ? p : List.of();
        return result;
    }
}
