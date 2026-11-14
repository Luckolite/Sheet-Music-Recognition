// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Reads independently visible half-rest rectangles beside a physically proved note shaft. */
final class PolyphonicHalfRests {
    record Candidate(
            int noteIndex,
            int left,
            int right,
            int top,
            int bottom,
            float gap,
            int staffIndex,
            int staffCount,
            int measureIndex,
            float position,
            float pageY) {}

    record Shaft(int left, int right, int direction) {}

    private static final class ProjectedNote {
        private final ScoreNoteEvent note;
        private final float x;
        private final float y;
        private float shaftGap;
        private Shaft shaft;
        private BeamContinuation continuation;

        ProjectedNote(ScoreNoteEvent note, float x, float y) {
            this.note = note;
            this.x = x;
            this.y = y;
        }

        Shaft shaft(byte[] gray, int w, int h, float gap) {
            // Staff gaps are positive. Zero marks an unchecked shaft, including on paper.
            if (shaftGap != gap) {
                shaft = PolyphonicHalfRests.shaft(gray, w, h, x, y, gap);
                shaftGap = gap;
                continuation = null;
            }
            return shaft;
        }

        boolean continuesToHead(byte[] gray, int width, int top) {
            if (continuation == null) continuation = new BeamContinuation(y, shaftGap);
            return continuation.proves(gray, width, shaft, top);
        }
    }

    static SixteenthRestDetector.Detection detectWithDots(
            byte[] gray,
            int w,
            int h,
            List<MeasureRegion> measures,
            List<SixteenthRestDetector.Staff> staffs,
            List<ScoreNoteEvent> notes) {
        var result = new ArrayList<ScoreRestEvent>();
        var dots = new ArrayList<SixteenthRestDetector.RestDot>();
        if (gray == null
                || w <= 0
                || h <= 0
                || (long) w * h != gray.length
                || measures == null
                || staffs == null
                || notes == null) return new SixteenthRestDetector.Detection(List.of(), List.of());
        var plain =
                staffs.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(
                                s ->
                                        new PlayingTechniqueDetector.Staff(
                                                s.top(), s.bottom(), s.gap(), s.index(), s.count()))
                        .toList();
        for (var c : find(gray, w, h, new ScorePageInterpretation(measures, notes), plain)) {
            // The dot sits in the space above the supporting rule, independent of plate thickness.
            float virtualTop = c.bottom() + 1 - c.gap() * 2;
            var dotStaff =
                    new SixteenthRestDetector.Staff(
                            virtualTop,
                            virtualTop + 4 * c.gap(),
                            c.gap(),
                            c.staffIndex(),
                            c.staffCount());
            var inkDots =
                    SixteenthRestDetector.augmentationDots(
                            gray,
                            w,
                            h,
                            dotStaff,
                            c.right(),
                            measures.get(c.measureIndex()),
                            notes,
                            c.measureIndex());
            var rest =
                    new ScoreRestEvent(
                            c.measureIndex(),
                            c.position(),
                            c.pageY(),
                            (c.bottom() - c.top() + 1f) / h,
                            c.staffIndex(),
                            c.staffCount(),
                            inkDots.size() == 2 ? 3.5 : inkDots.size() == 1 ? 3 : 2);
            result.add(rest);
            for (var dot : inkDots)
                dots.add(new SixteenthRestDetector.RestDot(dot.x(), dot.y(), rest));
        }
        return new SixteenthRestDetector.Detection(List.copyOf(result), List.copyOf(dots));
    }

    static List<Candidate> find(
            byte[] gray,
            int w,
            int h,
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Staff> staffs) {
        var result = new ArrayList<Candidate>();
        if (gray == null
                || w <= 0
                || h <= 0
                || (long) w * h != gray.length
                || score == null
                || staffs == null) return result;
        var measureNotes = new HashMap<Integer, List<ProjectedNote>>();
        for (int ni = 0; ni < score.notes().size(); ni++) {
            var n = score.notes().get(ni);
            if (n == null
                    || n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.staffIndex() < 0
                    || n.staffCount() <= 0
                    || n.staffIndex() >= n.staffCount()
                    || n.measureIndex() < 0
                    || n.measureIndex() >= score.measures().size()
                    || !Float.isFinite(n.positionInMeasure())
                    || !Float.isFinite(n.pageY())) continue;
            var m = score.measures().get(n.measureIndex());
            if (!BeamedHeadQuarterRests.validMeasure(m)) continue;
            float measureWidth = m.right() - m.left();
            float hx = (m.left() + n.positionInMeasure() * measureWidth) * w,
                    hy = n.pageY() * h;
            if (hx < 0 || hx >= w || hy < 0 || hy >= h) continue;
            for (var s : staffs) {
                if (s == null) continue;
                float g = s.gap();
                if (s.count() <= 0
                        || s.index() < 0
                        || s.index() >= s.count()
                        || !(s.bottom() > s.top())
                        || s.index() != n.staffIndex()
                        || s.count() != n.staffCount()
                        || !Float.isFinite(g)
                        || g < 6
                        || !Float.isFinite(s.top())
                        || !Float.isFinite(s.bottom())
                        || hy < s.top() - 3 * g
                        || hy > s.bottom() + 4 * g) continue;
                int span = Math.max(4, Math.round(g * 1.25f));
                int firstColumn = Math.max(span, firstMeasureColumn(m.left() * w, w, true));
                int lastColumn = Math.min(w - 1 - span, firstMeasureColumn(m.right() * w, w, false) - 1);
                if (firstColumn > lastColumn) continue;
                int minimumWidth = Math.max(5, Math.round(g * .85f));
                int maximumWidth = Math.min(lastColumn - firstColumn + 1, Math.round(g * 1.65f));
                if (maximumWidth < minimumWidth) continue;
                Shaft shaft = shaft(gray, w, h, hx, hy, g);
                if (shaft == null || !head(gray, w, h, hx, hy, g)) continue;
                var continuation = n.beamCount() >= 2 && shaft.direction() == 1
                        ? new BeamContinuation(hy, g) : null;

                int widths = maximumWidth - minimumWidth + 1;
                int[] firstLeft = new int[widths], lastLeft = new int[widths];
                int[] plateWidths = new int[widths];
                float[] bodyMinimums = new float[widths];
                int legalWidths = 0;
                int firstSample = w, lastSample = -1;
                for (int i = 0; i < widths; i++) {
                    int width = minimumWidth + i;
                    float leftAtHead = hx - width * .5f;
                    int left = Math.max(firstColumn, Math.round(leftAtHead - g * .25f));
                    int last = Math.min(lastColumn - width + 1, Math.round(leftAtHead + g * .25f));
                    if (left > last) continue;
                    firstLeft[legalWidths] = left;
                    lastLeft[legalWidths] = last;
                    plateWidths[legalWidths] = width;
                    bodyMinimums[legalWidths++] = width * .9f;
                    firstSample = Math.min(firstSample, left - span);
                    lastSample = Math.max(lastSample, last + width - 1 + span);
                }
                if (firstSample > lastSample) continue;
                int[] ruleInk = new int[lastSample - firstSample + 2];
                PlateRows plateRows = null;
                int[] nextLeft = new int[ruleInk.length - 2 * span];
                nextLeft[nextLeft.length - 1] = nextLeft.length - 1;
                float minimumFlank = span * .9f;
                // An eligible rule needs disjoint ink in both flanks and in the plate.
                long minimumTotal = 2L * countLowerBound(minimumFlank)
                        + countLowerBound(minimumWidth * .9f);
                int minimumRows = (int) Math.max(3, Math.ceil(g * .3f));
                float measureTop = m.top() * h, measureBottom = m.bottom() * h;
                float verticalTolerance = g / h;
                for (int offset = -5; offset <= 9; offset++) {
                    float expected = s.top() + offset * g;
                    if (Math.abs(expected - hy) > g * 2.2f) continue;
                    for (int rule = Math.max(2, Math.round(expected - g * .3f));
                            rule <= Math.min(h - 3, Math.round(expected + g * .3f));
                            rule++) {
                        int latestTop = rule - minimumRows;
                        if (latestTop < 1 || latestTop < measureTop) continue;
                        fillInkPrefix(gray, rule * w + firstSample, ruleInk);
                        if (ruleInk[ruleInk.length - 1] < minimumTotal) continue;
                        for (int x = nextLeft.length - 2; x >= 0; x--) {
                            int l = x + span;
                            nextLeft[x] = ruleInk[l] - ruleInk[x] >= minimumFlank
                                    ? x : nextLeft[x + 1];
                        }
                        for (int i = 0; i < legalWidths; i++) {
                            int width = plateWidths[i];
                            int start = firstLeft[i] - firstSample - span;
                            int end = lastLeft[i] - firstSample - span;
                            for (int pos = nextLeft[start]; pos <= end; pos = nextLeft[pos + 1]) {
                                int l = pos + span, r = l + width;
                                if (ruleInk[r + span] - ruleInk[r] < minimumFlank
                                        || ruleInk[r] - ruleInk[l] < bodyMinimums[i]) continue;
                                int left = firstSample + l, right = firstSample + r - 1;
                                if (plateRows == null)
                                    plateRows = new PlateRows(gray, w, firstSample, ruleInk.length);
                                int[] bounds = rectangleOverRule(
                                        gray, w, left, right, rule, g, shaft, hx, hy, span,
                                        minimumRows, plateRows);
                                if (bounds == null
                                        || hy >= bounds[0] - g * .2f && hy <= bounds[1] + g * .2f
                                        || bounds[0] < measureTop
                                        || bounds[1] > measureBottom) continue;
                                float cy = (bounds[0] + bounds[1]) * .5f / h,
                                        cx = (left + right) * .5f / w;
                                float position = (cx - m.left()) / measureWidth;
                                if (represented(result, s, n.measureIndex(), position, cy, verticalTolerance)
                                        || beamlet(gray, w, h, left, right, bounds[0], g, shaft))
                                    continue;
                                var peers = measureNotes.computeIfAbsent(n.measureIndex(),
                                        key -> projectNotes(score.notes(), key, m.left(), measureWidth, w, h));
                                if (noteBeamOwnsPlate(gray, w, h, peers, n,
                                        hx, hy, shaft, continuation, g, left, right, bounds)) continue;
                                boolean otherHead = false;
                                for (var peer : peers) {
                                    var note = peer.note;
                                    if (note == n || !Float.isFinite(note.positionInMeasure())
                                            || !Float.isFinite(note.pageY())) continue;
                                    if (peer.x >= left - g * .25f
                                            && peer.x <= right + g * .25f
                                            && peer.y >= bounds[0] - g * .6f
                                            && peer.y <= bounds[1] + g * .6f) {
                                        otherHead = true;
                                        break;
                                    }
                                }
                                if (otherHead) continue;
                                result.add(new Candidate(
                                        ni, left, right, bounds[0], bounds[1], g,
                                        s.index(), s.count(), n.measureIndex(), position, cy));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean represented(
            List<Candidate> candidates, PlayingTechniqueDetector.Staff staff,
            int measure, float position, float pageY, float verticalTolerance) {
        for (Candidate old : candidates) {
            if (old.staffIndex() == staff.index()
                    && old.staffCount() == staff.count()
                    && old.measureIndex() == measure
                    && Math.abs(old.position() - position) < .025f
                    && Math.abs(old.pageY() - pageY) < verticalTolerance) return true;
        }
        return false;
    }

    private static int firstMeasureColumn(float boundary, int width, boolean inclusive) {
        if (width <= (1 << 24)) {
            double first = inclusive ? Math.ceil(boundary) : Math.floor(boundary) + 1;
            return (int) Math.max(0, Math.min(width, first));
        }
        // At large widths adjacent integer columns can compare equal after float conversion.
        int left = 0, right = width;
        while (left < right) {
            int center = left + (right - left) / 2;
            if (inclusive ? center < boundary : center <= boundary) left = center + 1;
            else right = center;
        }
        return left;
    }

    private static int countLowerBound(float minimum) {
        // Very large integer counts round on comparison with the original float threshold.
        return minimum <= (1 << 24) ? (int) Math.ceil(minimum) : (int) Math.nextDown(minimum);
    }

    private static List<ProjectedNote> projectNotes(
            List<ScoreNoteEvent> notes, int measure, float left, float width, int w, int h) {
        var result = new ArrayList<ProjectedNote>();
        for (var note : notes) {
            if (note.measureIndex() == measure) {
                result.add(new ProjectedNote(note,
                        (left + note.positionInMeasure() * width) * w, note.pageY() * h));
            }
        }
        return result;
    }

    private static boolean noteBeamOwnsPlate(
            byte[] gray, int w, int h, List<ProjectedNote> notes, ScoreNoteEvent owner,
            float headX, float headY, Shaft shaft, BeamContinuation continuation, float gap,
            int left, int right, int[] bounds) {
        float margin = gap * .2f;
        if (continuation == null || shaft.left() < right - margin
                || bounds[1] - bounds[0] < 2)
            return false;
        int center = (bounds[0] + bounds[1]) / 2;
        if (!continuation.proves(gray, w, shaft, center)) return false;
        float reach = gap * 3, verticalReach = gap * 1.4f, peerRight = left + margin;
        for (var projected : notes) {
            var peer = projected.note;
            if (peer == owner
                    || peer.staffIndex() != owner.staffIndex() || peer.staffCount() != owner.staffCount()
                    || peer.beamCount() < 2 || peer.kind() != ScoreNoteEvent.Kind.PITCHED) continue;
            float distance = headX - projected.x;
            if (distance < gap || distance > reach || Math.abs(projected.y - headY) > verticalReach)
                continue;
            Shaft other = projected.shaft(gray, w, h, gap);
            if (other == null || other.direction() != 1 || other.right() > peerRight
                    || !projected.continuesToHead(gray, w, center)) continue;
            int joined = 0, length = shaft.left() - other.right() + 1;
            float minimumInk = length * .95f;
            for (int row = bounds[0]; row <= bounds[1]; row++) {
                if (hasInk(gray, row * w + other.right(), 1, length, minimumInk)
                        && ++joined >= 3) return true;
                if (bounds[1] - row < 3 - joined) break;
            }
        }
        return false;
    }

    private static final class BeamContinuation {
        private final int bottom;
        private final float gap;
        private int firstProved = Integer.MAX_VALUE;
        private int lastRefused = Integer.MIN_VALUE;

        BeamContinuation(float headY, float gap) {
            bottom = Math.round(headY - gap * .45f);
            this.gap = gap;
        }

        boolean proves(byte[] gray, int width, Shaft shaft, int top) {
            if (bottom - top < gap || top <= lastRefused) return false;
            if (top >= firstProved) return true;
            // Shortening a continuous interval preserves it. Extending a broken one cannot repair it.
            for (int x = shaft.left(); x <= shaft.right(); x++) {
                int row = top, pixel = top * width + x;
                while (row <= bottom && (gray[pixel] & 255) < 170) {
                    row++;
                    pixel += width;
                }
                if (row > bottom) {
                    firstProved = top;
                    return true;
                }
            }
            lastRefused = top;
            return false;
        }
    }

    static Shaft shaft(byte[] gray, int w, int h, float x, float y, float g) {
        int direction = PrintedStemDirection.detect(gray, w, h, x, y, g);
        if (direction == 0) return null;
        int top = Math.round(direction == 1 ? y - 2.4f * g : y + .65f * g),
                bottom = Math.round(direction == 1 ? y - .65f * g : y + 2.4f * g);
        if (top < 0 || bottom >= h) return null;
        int a = Math.max(0, Math.round(x + (direction == 1 ? .4f : -.9f) * g)),
                b = Math.min(w - 1, Math.round(x + (direction == 1 ? .9f : -.4f) * g));
        int first = -1, last = -1;
        int length = bottom - top + 1;
        float minimum = length * .95f;
        float maximumWidth = Math.max(3, g * .22f);
        for (int col = a; col <= b; col++) {
            if (hasInk(gray, top * w + col, w, length, minimum)) {
                if (first < 0) first = col;
                if (col - first + 1 > maximumWidth) return null;
                last = col;
            } else if (first >= 0) break;
        }
        return first < 0 ? null : new Shaft(first, last, direction);
    }

    static boolean head(byte[] gray, int w, int h, float x, float y, float g) {
        int l = Math.round(x - .4f * g),
                r = Math.round(x + .4f * g),
                t = Math.round(y - .22f * g),
                b = Math.round(y + .22f * g);
        if (l < 0 || r >= w || t < 0 || b >= h) return false;
        int filled = 0, width = r - l + 1;
        float minimumColumns = width * .8f, minimumRows = (b - t + 1) * .8f;
        for (int row = t; row <= b; row++) {
            if (hasInk(gray, row * w + l, 1, width, minimumColumns)
                    && ++filled >= minimumRows) return true;
            if (filled + (b - row) < minimumRows) return false;
        }
        return filled >= minimumRows;
    }

    static int[] rectangle(
            byte[] gray,
            int w,
            int h,
            int left,
            int right,
            int rule,
            float g,
            Shaft shaft,
            float hx,
            float hy) {
        int span = Math.max(4, Math.round(g * 1.25f));
        if (left - span < 0 || right + span >= w || !support(gray, w, left, right, rule, span))
            return null;
        return rectangleOverRule(gray, w, left, right, rule, g, shaft, hx, hy, span,
                Math.max(3, Math.ceil(g * .3f)), null);
    }

    private static int[] rectangleOverRule(
            byte[] gray,
            int w,
            int left,
            int right,
            int rule,
            float g,
            Shaft shaft,
            float hx,
            float hy,
            int span,
            double minimumRows,
            PlateRows plateRows) {
        int width = right - left + 1;
        int last = rule - 1;
        float bodyMinimum = width * .85f;
        while (last > rule - g * .3f
                && (plateRows == null
                        ? support(gray, w, left, right, last, span)
                        : plateRows.support(left, right, last, span))) last--;
        int first = last;
        while (first > 0
                && last - first < g * .8f
                && (plateRows == null
                        ? hasInk(gray, first * w + left, 1, width, bodyMinimum)
                        : plateRows.ink(left, right, first) >= bodyMinimum)) first--;
        first++;
        int rows = last - first + 1;
        if (rows < minimumRows || rows > g * .65f || first < 1) return null;
        if (excessInkOutsideOwner(gray, w, left, right, first - 1, shaft, hx, hy, g))
            return null;
        int margin = Math.max(2, Math.round(g * .22f));
        if (left - margin < 0 || right + margin >= w) return null;
        for (int row = first; row <= last; row++) {
            if (!dark(gray, w, left, row) && !dark(gray, w, left + 1, row)
                    || !dark(gray, w, right, row) && !dark(gray, w, right - 1, row)) return null;
            if (plateRows == null) {
                if (excessInkOutsideShaft(gray, w, left - margin, left - 1, row, shaft)
                        || excessInkOutsideShaft(gray, w, right + 1, right + margin, row, shaft))
                    return null;
            } else if (plateRows.hasMarginInk(left, right, margin, row, shaft)) {
                return null;
            }
        }
        return new int[] {first, last};
    }

    /** Recent rows shared by rectangles above the rule currently being enumerated. */
    private static final class PlateRows {
        private static final int SLOTS = 16;

        private final byte[] gray;
        private final int width, firstColumn, prefixLength;
        private final int[][] prefixes = new int[SLOTS][];
        private final int[] identities = new int[SLOTS];

        PlateRows(byte[] gray, int width, int firstColumn, int prefixLength) {
            this.gray = gray;
            this.width = width;
            this.firstColumn = firstColumn;
            this.prefixLength = prefixLength;
        }

        private int[] prefix(int row) {
            int slot = row & (SLOTS - 1);
            int[] prefix = prefixes[slot];
            if (prefix == null) {
                prefix = new int[prefixLength];
                prefixes[slot] = prefix;
            } else if (identities[slot] == row) {
                return prefix;
            }
            fillInkPrefix(gray, row * width + firstColumn, prefix);
            identities[slot] = row;
            return prefix;
        }

        int ink(int left, int right, int row) {
            int[] prefix = prefix(row);
            return prefix[right - firstColumn + 1] - prefix[left - firstColumn];
        }

        boolean support(int left, int right, int row, int span) {
            int[] prefix = prefix(row);
            int l = left - firstColumn, r = right - firstColumn + 1;
            float minimumFlank = span * .9f;
            return prefix[l] - prefix[l - span] >= minimumFlank
                    && prefix[r + span] - prefix[r] >= minimumFlank
                    && prefix[r] - prefix[l] >= (right - left + 1) * .9f;
        }

        boolean hasMarginInk(int left, int right, int margin, int row, Shaft shaft) {
            int[] prefix = prefix(row);
            int l = left - firstColumn, r = right - firstColumn + 1;
            int shaftLeft = shaft.left() - firstColumn, shaftRight = shaft.right() - firstColumn + 1;
            return inkOutsideShaft(prefix, l - margin, l, shaftLeft, shaftRight) > 1
                    || inkOutsideShaft(prefix, r, r + margin, shaftLeft, shaftRight) > 1;
        }

        private static int inkOutsideShaft(int[] prefix, int left, int right, int shaftLeft, int shaftRight) {
            int coveredLeft = Math.max(left, shaftLeft), coveredRight = Math.min(right, shaftRight);
            int ink = prefix[right] - prefix[left];
            return coveredLeft < coveredRight ? ink - (prefix[coveredRight] - prefix[coveredLeft]) : ink;
        }
    }

    static boolean beamlet(
            byte[] gray, int w, int h, int left, int right, int top, float g, Shaft shaft) {
        if (shaft.direction() != 1) return false;
        int extension = Math.max(4, Math.round(g));
        int a = left - extension, b = shaft.right();
        if (a < 0 || b >= w || shaft.left() < right - g * .35f) return false;
        int run = 0, width = b - a + 1;
        int first = Math.max(0, Math.round(top - g * 1.5f));
        int end = Math.max(0, Math.round(top - g * .3f));
        int minimumRun = Math.max(3, Math.round(g * .22f));
        float minimumInk = width * .95f;
        for (int row = first; row < end; row++) {
            if (hasInk(gray, row * w + a, 1, width, minimumInk)) {
                if (++run >= minimumRun) return true;
            } else run = 0;
            if (run + (end - row - 1) < minimumRun) return false;
        }
        return false;
    }

    static boolean support(byte[] gray, int w, int l, int r, int y, int span) {
        int row = y * w, width = r - l + 1;
        float minimumFlank = span * .9f;
        return hasInk(gray, row + l - span, 1, span, minimumFlank)
                && hasInk(gray, row + r + 1, 1, span, minimumFlank)
                && hasInk(gray, row + l, 1, width, width * .9f);
    }

    private static void fillInkPrefix(byte[] gray, int pixel, int[] prefix) {
        int ink = 0;
        for (int i = 1; i < prefix.length; i++, pixel++) {
            if ((gray[pixel] & 255) < 170) ink++;
            prefix[i] = ink;
        }
    }

    private static boolean hasInk(
            byte[] gray, int pixel, int stride, int length, float minimum) {
        if (minimum <= 0) return true;
        if (!(length >= minimum)) return false;
        int hits = 0;
        int possible = length;
        for (int i = 0; i < length; i++, pixel += stride) {
            if ((gray[pixel] & 255) < 170) {
                if (++hits >= minimum) return true;
            } else if (--possible < minimum) return false;
        }
        return false;
    }

    private static boolean excessInkOutsideShaft(
            byte[] gray, int w, int left, int right, int y, Shaft shaft) {
        int count = 0;
        for (int x = left; x <= right; x++)
            if ((x < shaft.left() || x > shaft.right()) && dark(gray, w, x, y) && ++count > 1)
                return true;
        return false;
    }

    private static boolean excessInkOutsideOwner(
            byte[] gray, int w, int left, int right, int y, Shaft shaft,
            float hx, float hy, float gap) {
        int count = 0;
        float limit = (right - left + 1) * .25f;
        boolean nearHead = Math.abs(y - hy) < gap * .5f;
        float headReach = gap * .8f;
        for (int x = left; x <= right; x++)
            if ((x < shaft.left() || x > shaft.right())
                    && !(nearHead && Math.abs(x - hx) < headReach)
                    && dark(gray, w, x, y)
                    && ++count > limit) return true;
        return count > limit;
    }

    static boolean dark(byte[] gray, int w, int x, int y) {
        return (gray[y * w + x] & 255) < 170;
    }
}
