// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.security.MessageDigest;
import java.util.*;

/** Optional whole-frame raw proof when the existing printed-slur track is unavailable. */
final class WholeStaffSlurRules {
    private record Domain(int top, int bottom, int gap) {}

    private record Peak(int row, int strength) {}

    static final class Page {
        final byte[] raw;
        final int width, height;
        final Thread thread = Thread.currentThread();
        final long workLimit, byteLimit;
        long workUsed, bytesUsed;
        int projections;
        boolean failed;
        byte[] digest;
        final Map<Domain, Optional<StaffPitchTrack>> cache = new HashMap<>();

        Page(byte[] raw, int width, int height) {
            this(raw, width, height, 128L * 1024 * 1024, 8L * 1024 * 1024);
        }

        Page(byte[] raw, int width, int height, long work, long bytes) {
            if (raw == null
                    || width <= 0
                    || height <= 0
                    || raw.length != (long) width * height
                    || work < 0
                    || bytes < 0) throw new IllegalArgumentException();
            this.raw = raw;
            this.width = width;
            this.height = height;
            workLimit = work;
            byteLimit = bytes;
            // Optional capacity is not a restriction on the existing page API.
            failed = raw.length > 20_000_000;
        }

        void take(long work, long bytes) {
            if (work < 0
                    || bytes < 0
                    || failed
                    || work > workLimit - workUsed
                    || bytes > byteLimit - bytesUsed) {
                failed = true;
                throw new Refusal();
            }
            workUsed += work;
            bytesUsed += bytes;
        }

        byte[] hash() {
            take(raw.length, 4096);
            try {
                return MessageDigest.getInstance("SHA-256").digest(raw);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }

        boolean finish() {
            if (failed) return false;
            if (digest == null) return true;
            try {
                if (thread == Thread.currentThread() && Arrays.equals(digest, hash())) return true;
                failed = true;
                cache.clear();
                return false;
            } catch (Refusal e) {
                return false;
            }
        }

        StaffPitchTrack prove(byte[] gray, int w, int h, PlayingTechniqueDetector.Staff staff) {
            if (failed) return null;
            try {
                if (thread != Thread.currentThread() || raw != gray || width != w || height != h) {
                    failed = true;
                    return null;
                }
                if (!Float.isFinite(staff.top() + staff.bottom() + staff.gap())
                        || staff.gap() < 3
                        || staff.bottom() <= staff.top()) return null;
                if (digest == null) digest = hash();
                take(1, 64);
                var key =
                        new Domain(
                                Float.floatToIntBits(staff.top()),
                                Float.floatToIntBits(staff.bottom()),
                                Float.floatToIntBits(staff.gap()));
                var prior = cache.get(key);
                if (prior != null) return prior.orElse(null);
                var result = scan(this, staff);
                if (result == null) result = sampledTrack(this, staff);
                take(1, 128);
                cache.put(key, Optional.ofNullable(result));
                return result;
            } catch (Refusal e) {
                cache.clear();
                return null;
            }
        }
    }

    private static final class Refusal extends RuntimeException {}

    private WholeStaffSlurRules() {}

    record Supplement(
            PrintedSlurPageCapture.Result captured,
            Set<Integer> unboundOwners,
            int unprovedStaffs) {}

    static <T extends PrintedSlurOwners.Location> Supplement append(
            int page,
            ScorePageInterpretation score,
            byte[] gray,
            byte[] labels,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<T> heads,
            PrintedSlurFrames.Result<T> legacy,
            PrintedSlurPageCapture.Result captured,
            Set<PrintedSlurOwners.Tie> ties,
            boolean geometryOnly) {
        var unchanged = new Supplement(captured, legacy.unboundOwners(), legacy.unprovedStaffs());
        if (captured.limitReached()
                || legacy.unprovedStaffSlots().isEmpty()
                || legacy.unboundOwners().isEmpty()) return unchanged;
        var additional =
                PrintedSlurFrames.prepareSupplemental(
                        gray, width, height, staffs, score.notes(), heads, legacy, score.measures());
        if (additional.frames().isEmpty()) return unchanged;
        var observed =
                geometryOnly
                        ? PrintedSlurPageCapture.captureGeometry(
                                page,
                                score.notes().size(),
                                gray,
                                labels,
                                width,
                                height,
                                additional.frames(),
                                score.notes(), score.measures())
                        : PrintedSlurPageCapture.capture(
                                page,
                                score.notes().size(),
                                gray,
                                labels,
                                width,
                                height,
                                additional.frames(),
                                ties,
                                score.notes(), score.measures());
        if (observed.limitReached()) return unchanged;
        var spans = new ArrayList<>(captured.source().spans());
        var candidates = new ArrayList<>(captured.source().candidates());
        for (var s : observed.source().spans())
            if (!isBeamCurve(s, gray, width, height, additional.frames(), score.notes()))
            spans.add(
                    new ScoreSlurPages.Span(
                            "whole-staff:" + s.id(),
                            s.start(),
                            s.end(),
                            s.placement(),
                            s.lineType(),
                            s.evidence()));
        for (var s : observed.source().candidates())
            if (!isBeamCurve(s, gray, width, height, additional.frames(), score.notes()))
            candidates.add(
                    new ScoreSlurPages.Span(
                            "whole-staff:" + s.id(),
                            s.start(),
                            s.end(),
                            s.placement(),
                            s.lineType(),
                            s.evidence()));
        var statuses =
                new EnumMap<PrintedSlurOwners.Status, Integer>(PrintedSlurOwners.Status.class);
        statuses.putAll(captured.statuses());
        observed.statuses().forEach((k, v) -> statuses.merge(k, v, Integer::sum));
        var source =
                ScoreSlurSource.capture(
                        spans,
                        candidates,
                        List.of(new ScoreSlurPages.Page(page, score.notes().size())));
        var unbound = new HashSet<>(legacy.unboundOwners());
        for (var frame : additional.frames())
            for (var head : frame.heads()) unbound.remove(head.owner());
        return new Supplement(
                new PrintedSlurPageCapture.Result(source, statuses, false, captured.declined(), mergePageEdges(captured, observed)),
                Set.copyOf(unbound),
                legacy.unprovedStaffs() - (staffs.size() - additional.unprovedStaffs()));
    }

    /** A downstem beam inside the staff cannot also label its upper-ledger heads as a slur. */
    private static <T extends PrintedSlurOwners.Location> boolean isBeamCurve(
            ScoreSlurPages.Span span, byte[] gray, int width, int height,
            List<PrintedSlurPageCapture.Frame<T>> frames, List<ScoreNoteEvent> notes) {
        if (span.placement() != ScoreSlurSpan.Placement.BELOW || span.evidence().size() != 1) return false;
        int start = span.start().noteIndex(), end = span.end().noteIndex();
        if (start < 0 || end >= notes.size() || start + 1 != end
                || notes.get(start).staffStep() < 10 || notes.get(end).staffStep() < 10
                || notes.get(start).stemDirection() != -1 || notes.get(end).stemDirection() != -1
                || notes.get(start).measureIndex() != notes.get(end).measureIndex()
                || notes.get(start).beamCount() < 1 || notes.get(end).beamCount() < 1) return false;
        var arc = span.evidence().get(0);
        for (var frame : frames) {
            T left = null, right = null;
            for (var head : frame.heads()) {
                if (head.owner() == start) left = head;
                if (head.owner() == end) right = head;
            }
            if (left == null || right == null) continue;
            var track = frame.track();
            float[] floor = track.at(left.x());
            float gap = floor[1];
            if (arc.startY() * height < floor[0] - 4.25f * gap
                    || arc.endY() * height < track.at(right.x())[0] - 4.25f * gap)
                return false;
            var budget = new PrintedBeamShafts.Budget(60_000);
            var a = PrintedBeamShafts.at(gray, width, height, left.x(), left.y(), gap, budget);
            var b = PrintedBeamShafts.at(gray, width, height, right.x(), right.y(), gap, budget);
            if (budget.refused() || a.size() != 1 || b.size() != 1
                    || a.get(0).direction() != 1 || b.get(0).direction() != 1) return false;
            var sa = a.get(0);
            var sb = b.get(0);
            return Math.abs(arc.startX() * width - (sa.left() + sa.right()) * .5f) <= .55f * gap
                    && Math.abs(arc.endX() * width - (sb.left() + sb.right()) * .5f) <= .55f * gap
                    && Math.abs(arc.startY() * height - sa.endY()) <= .62f * gap
                    && Math.abs(arc.endY() * height - sb.endY()) <= .62f * gap
                    && WrappedEighthTriplets.trackedRailCount(gray, width, height, track, sa, sb,
                            gap, Math.min(2, Math.min(notes.get(start).beamCount(), notes.get(end).beamCount())));
        }
        return false;
    }

    /** Sample real local five-rule groups when mild slope spreads a page-wide projection. */
    private static StaffPitchTrack sampledTrack(Page page, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap(), width = page.width;
        if (staff.count() != 1 || gap < 10 || gap > 24 || width < gap * 40) return null;
        float left = Math.max(gap * 6, width * .10f);
        float right = Math.min(width - gap * 6, width * .94f);
        var samples = new ArrayList<float[]>();
        float[] previous = null;
        for (int i = 0; i <= 12; i++) {
            float x = left + (right - left) * i / 12;
            long pixels = (long) Math.ceil((gap * 11 + 3) * (gap * 8 + 3));
            page.take(160 * pixels, 4 * pixels + 512);
            float[] rule = PrintedDirectionStaff.printedRules(
                    page.raw, page.width, page.height, x, staff.bottom(), gap);
            if (rule == null) continue;
            if (Math.abs(rule[0] - staff.bottom()) > gap * .38f
                    || Math.abs(rule[1] - gap) > gap * .07f
                    || previous != null
                            && (Math.abs(rule[0] - previous[1]) > gap * .20f
                                    || Math.abs(rule[1] - previous[2]) > gap * .07f
                                    || x - previous[0] > (right - left) * .27f))
                return null;
            previous = new float[] {x, rule[0], rule[1]};
            samples.add(previous);
        }
        if (samples.size() < 7 || samples.get(0)[0] > left + (right - left) * .17f
                || samples.get(samples.size() - 1)[0] < right - (right - left) * .17f)
            return null;
        float minBottom = Float.POSITIVE_INFINITY, maxBottom = Float.NEGATIVE_INFINITY;
        for (var sample : samples) {
            minBottom = Math.min(minBottom, sample[1]);
            maxBottom = Math.max(maxBottom, sample[1]);
        }
        // This path supplies varying printed phase after the flat proof failed.
        if (maxBottom - minBottom < gap * .04f) return null;
        var track = StaffPitchTrack.fromVerifiedSamples(samples);
        return sampledRulesAreUnique(page, track, gap) ? track : null;
    }

    /** Confirm all five curved rails and veto a sixth using the original contrast law. */
    private static boolean sampledRulesAreUnique(Page page, StaffPitchTrack track, float gap) {
        int radius = Math.max(1, Math.round(gap * .20f));
        int flank = Math.max(2, Math.round(gap * .32f));
        page.take(7L * page.width * (2L * radius + 1) * 4 + page.width * 24L,
                32L * page.width + 128);
        int[] hits = new int[7];
        for (int x = 0; x < page.width; x++) {
            if (!track.verifiedAt(x)) continue;
            float[] frame = track.at(x);
            for (int i = 0; i < hits.length; i++) {
                int center = Math.round(frame[0] - (5 - i) * frame[1]);
                for (int y = Math.max(flank, center - radius);
                        y <= Math.min(page.height - 1 - flank, center + radius); y++) {
                    int pixel = y * page.width + x;
                    int ink = page.raw[pixel] & 255;
                    if (ink <= 225 && (page.raw[pixel - flank * page.width] & 255) >= ink + 12
                            && (page.raw[pixel + flank * page.width] & 255) >= ink + 12) {
                        hits[i]++;
                        break;
                    }
                }
            }
        }
        if (hits[0] >= page.width * .60f || hits[6] >= page.width * .60f) return false;
        for (int i = 1; i <= 5; i++) if (hits[i] < page.width * .60f) return false;
        return true;
    }

    private static StaffPitchTrack scan(Page p, PlayingTechniqueDetector.Staff seed) {
        int first = Math.max(0, Math.round(seed.top() - seed.gap() * 6)),
                last = Math.min(p.height, Math.round(seed.bottom() + seed.gap() * 6));
        int flank = Math.max(2, Math.round(seed.gap() * .32f)), height = last - first;
        if (height <= flank * 2) return null;
        p.take(height + 1, 32L + 4L * height);
        int[] strength = new int[height];
        p.projections++;
        // Same contrasted pixels and projection admission used by the existing subtle-track route.
        p.take((long) (height - 2 * flank) * p.width * 3, 0);
        for (int y = first + flank; y < last - flank; y++)
            for (int x = 0; x < p.width; x++) {
                int at = y * p.width + x, ink = p.raw[at] & 255;
                if (ink <= 225
                        && (p.raw[at - flank * p.width] & 255) >= ink + 12
                        && (p.raw[at + flank * p.width] & 255) >= ink + 12) strength[y - first]++;
            }
        int minimum = Math.max(24, Math.round(p.width * .25f));
        var peaks = new ArrayList<Peak>();
        p.take(1, 64);
        for (int row = 0; row < height; row++) {
            p.take(1, 0);
            if (strength[row] < minimum) continue;
            boolean maximum = true, prominent = false;
            for (int check = Math.max(0, row - 2);
                    check <= Math.min(height - 1, row + 2);
                    check++) {
                p.take(1, 0);
                if (strength[check] > strength[row]) maximum = false;
                if (strength[check] < strength[row]) prominent = true;
            }
            if (!maximum || !prominent) continue;
            // Contiguous equal maxima are the same printed stroke, not competing five-rule
            // families.
            int end = row;
            while (end + 1 < height && strength[end + 1] == strength[row]) {
                p.take(1, 0);
                end++;
            }
            if (end - row > Math.max(2, seed.gap() * .25f)) return null;
            p.take(1, 64);
            peaks.add(new Peak(first + (row + end) / 2, strength[row]));
            row = end;
        }
        int[] unique = null;
        float uniqueGap = 0;
        for (int a = 0; a + 4 < peaks.size(); a++)
            for (int z = a + 4; z < peaks.size(); z++) {
                p.take(1, 0);
                float gap = (peaks.get(z).row - peaks.get(a).row) / 4f;
                if (gap < 2.5f
                        || gap > Math.max(40f, height * .035f)
                        || gap < seed.gap() * .88f
                        || gap > seed.gap() * 1.12f
                        || Math.abs(peaks.get(z).row - seed.bottom()) > seed.gap() * .65f) continue;
                p.take(6, 64);
                int[] rows = {peaks.get(a).row, -1, -1, -1, peaks.get(z).row};
                int previous = a, total = peaks.get(a).strength + peaks.get(z).strength;
                float error = 0;
                boolean complete = true;
                for (int line = 1; line < 4; line++) {
                    int selected = -1;
                    float expected = rows[0] + line * gap;
                    for (int at = previous + 1; at < z; at++) {
                        p.take(1, 0);
                        if (Math.abs(peaks.get(at).row - expected) > Math.max(2, gap * .24f))
                            continue;
                        // No nearest/strongest fit may hide a competing observed rule.
                        if (selected >= 0) return null;
                        selected = at;
                    }
                    if (selected < 0) {
                        complete = false;
                        break;
                    }
                    rows[line] = peaks.get(selected).row;
                    previous = selected;
                    total += peaks.get(selected).strength;
                    error += Math.max(0, Math.abs(rows[line] - expected) - .5f);
                }
                if (!complete || total - error * minimum * .75f < minimum * 4.5f) continue;
                // Transport one flat phase only when its observed rows meet the existing broad-rule
                // law.
                boolean regular = true;
                for (int i = 0; i < 5; i++) {
                    p.take(1, 0);
                    if (Math.abs(rows[i] - (rows[4] - (4 - i) * gap)) > gap * .12f) regular = false;
                }
                if (!regular) continue;
                int radius = Math.max(1, Math.round(gap * .2f));
                p.take(7L * p.width * (2L * radius + 1) * 3, 64);
                if (!StaffPitchTrack.completePrintedSlurRules(
                        p.raw, p.width, p.height, new RawStaffLineDetector.StaffLines(rows, gap)))
                    continue;
                if (unique != null && !Arrays.equals(unique, rows)) return null;
                unique = rows;
                uniqueGap = gap;
            }
        if (unique == null) return null;
        p.take(16, 256);
        return StaffPitchTrack.fromVerifiedSamples(
                List.of(
                        new float[] {0, unique[4], uniqueGap},
                        new float[] {p.width - 1, unique[4], uniqueGap}));
    }

    private static List<ScoreExpressiveEvent> mergePageEdges(
            PrintedSlurPageCapture.Result first, PrintedSlurPageCapture.Result supplemental) {
        var merged = new LinkedHashMap<String, ScoreExpressiveEvent>();
        for (var capture : List.of(first, supplemental))
            for (var edge : capture.pageEdges()) {
                var prior = merged.putIfAbsent(edge.eventId(), edge);
                if (prior != null && !prior.equals(edge)) return List.of();
            }
        return List.copyOf(merged.values());
    }
}
