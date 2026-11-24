// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;

/** Matches printed annotations to the two distinct owners of a proved octave triplet column. */
final class ClosedOctavePrintedMarks {
    private ClosedOctavePrintedMarks() {}

    private record Head(int owner, float x, float y) {}
    private record Column(Head upper, Head lower) {}
    private record Group(List<Column> columns, float gap) {}

    static List<SourceLocalAccidental.PrintedMark> naturals(
            List<SourceLocalAccidental.PrintedMark> existing, ScorePageInterpretation score,
            byte[] raw, byte[] normalized, int width, int height) {
        var marks = new TreeMap<Integer, SourceLocalAccidental.PrintedMark>();
        for (var mark : existing) marks.put(mark.noteIndex(), mark);
        for (var group : groups(score, raw, normalized, width, height)) {
            for (int i = 1; i < group.columns.size(); i++) {
                var previous = group.columns.get(i - 1);
                var column = group.columns.get(i);
                var upper = marks.get(column.upper.owner);
                var lower = marks.get(column.lower.owner);
                // One admitted natural anchors the written spelling. The second is independently proved.
                if (upper != null && lower != null || upper == null && lower == null) continue;
                if ((upper != null ? upper : lower).accidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL)
                    continue;
                var a = naturalSpines(raw, normalized, width, previous.upper, column.upper, group.gap);
                var b = naturalSpines(raw, normalized, width, previous.lower, column.lower, group.gap);
                if (a.size() != 2 || b.size() != 2) continue;
                if (a.get(0).bottom() + 2 >= b.get(0).top()
                        || a.get(1).bottom() + 2 >= b.get(1).top()) continue;
                int missing = upper == null ? column.upper.owner : column.lower.owner;
                marks.put(missing, new SourceLocalAccidental.PrintedMark(missing, ScoreNoteEvent.ACCIDENTAL_NATURAL));
            }
        }
        return marks.size() == existing.size() ? existing : List.copyOf(marks.values());
    }

    private static List<AccidentalSlurSpines.Spine> naturalSpines(byte[] raw, byte[] normalized,
            int width, Head previous, Head target, float gap) {
        var a = AccidentalSlurSpines.prove(raw, width, previous.x, target.x, target.y, gap, 0);
        var b = AccidentalSlurSpines.prove(normalized, width, previous.x, target.x, target.y, gap, 0);
        return a.equals(b) ? a : List.of();
    }

    static List<PrintedFingeringDigits.Mark> fingerings(List<PrintedFingeringDigits.Mark> existing,
            ScorePageInterpretation score, byte[] raw, byte[] normalized, int width, int height,
            List<SheetInterpreter.Word> words) {
        var marks = new TreeMap<Integer, PrintedFingeringDigits.Mark>();
        for (var mark : existing) marks.put(mark.noteIndex(), mark);
        boolean changed = false;
        for (var group : groups(score, raw, normalized, width, height)) {
            var a = downstems(raw, width, height, group);
            var b = downstems(normalized, width, height, group);
            if (a.size() != 3 || b.size() != 3) continue;
            int[] digits = new int[3];
            for (int i = 0; i < 3; i++) {
                int first = fingering(raw, width, height, a, i, group.gap, words);
                int second = fingering(normalized, width, height, b, i, group.gap, words);
                if (first > 0 && first == second) digits[i] = first;
            }
            if (digits[0] == 0 || digits[1] == 0 || digits[2] == 0) continue;
            // Each independently read mark is below the common shaft of the octave.
            for (int i = 0; i < 3; i++) {
                var column = group.columns.get(i);
                var upper = marks.get(column.upper.owner);
                if (upper != null && upper.placement() == PrintedFingeringDigits.Placement.BELOW)
                    marks.remove(column.upper.owner);
                var lower = marks.get(column.lower.owner);
                if (lower == null || lower.placement() == PrintedFingeringDigits.Placement.BELOW)
                    marks.put(column.lower.owner, new PrintedFingeringDigits.Mark(column.lower.owner,
                            digits[i], PrintedFingeringDigits.Placement.BELOW));
                changed = true;
            }
        }
        return changed ? List.copyOf(marks.values()) : existing;
    }

    private static List<PrintedBeamShafts.Shaft> downstems(byte[] ink, int width, int height, Group group) {
        var result = new ArrayList<PrintedBeamShafts.Shaft>();
        var budget = new PrintedBeamShafts.Budget(80000);
        for (var pair : group.columns) {
            var found = PrintedBeamShafts.at(ink, width, height, pair.lower.x, pair.lower.y, group.gap, budget);
            if (found.size() != 1 || found.get(0).direction() != 1 || budget.refused()) return List.of();
            result.add(found.get(0));
        }
        return result;
    }

    private static int fingering(byte[] ink, int width, int height,
            List<PrintedBeamShafts.Shaft> stems, int i, float gap, List<SheetInterpreter.Word> words) {
        var at = stems.get(i);
        float x = axis(at);
        int end = at.endY();
        int left = Math.round(x - gap * .21f), right = Math.round(x + gap * .89f);
        int top = Math.round(end + gap * .10f), bottom = Math.round(end + gap * 2.34f);
        int flank = Math.round(gap * .42f);
        if (left - flank - 2 < 0 || right + flank + 2 >= width || top < 0 || bottom >= height) return 0;
        int stride = right - left + 1, rows = bottom - top + 1;
        byte[] crop = new byte[stride * rows];
        java.util.Arrays.fill(crop, (byte) 255);
        var mask = new BitSet();
        int a = right + 1, b = left - 1, c = bottom + 1, d = top - 1;
        for (int y = top; y <= bottom; y++) {
            int before = 0, after = 0;
            for (int k = 2; k < flank + 2; k++) {
                if ((ink[y * width + left - k] & 255) < 190) before++;
                if ((ink[y * width + right + k] & 255) < 190) after++;
            }
            if (Math.min(before, after) >= flank - 1) {
                mask.set((y - top) * stride, (y - top + 1) * stride);
                System.arraycopy(ink, y * width + left, crop, (y - top) * stride, stride);
                continue;
            }
            int first = right + 1, last = left - 1, dark = 0;
            for (int col = left; col <= right; col++) {
                if (y <= beamEdge(stems, i, col) + 1.2f || (ink[y * width + col] & 255) >= 202) continue;
                first = Math.min(first, col);
                last = Math.max(last, col);
                dark++;
            }
            if (dark < 3 || dark < (last - first + 1) * .48f) continue;
            if (first == left && last == right) return 0;
            for (int col = first; col <= last; col++) {
                if (y > beamEdge(stems, i, col) + 1.2f)
                    crop[(y - top) * stride + col - left] = ink[y * width + col];
            }
            a = Math.min(a, first);
            b = Math.max(b, last);
            c = Math.min(c, y);
            d = Math.max(d, y);
        }
        var glyph = digitIsland(crop, stride, rows, mask, gap);
        if (glyph == null) return 0;
        a = left + glyph.left; b = left + glyph.right;
        c = top + glyph.top; d = top + glyph.bottom;
        int glyphWidth = b - a + 1, glyphHeight = d - c + 1;
        if (glyphWidth < .5f * gap || glyphWidth > 1.12f * gap
                || glyphHeight < .88f * gap || glyphHeight > 1.40f * gap
                || c - end > 1.14f * gap || d >= bottom
                || Math.abs((a + b) * .5f - x) > .58f * gap) return 0;
        int l = a - left, t = c - top, r = b - left, z = d - top;
        var reading = JoinedDigitTemplates.inspect(crop, stride, rows, mask, l, t, r, z, () -> {});
        if (reading == null) return 0;
        if (reading.digit() >= 0 && reading.digit() <= 4) return reading.digit();
        if (mask.nextSetBit(t * stride) >= 0 && mask.nextSetBit(t * stride) < z * stride
                && reading.scores()[1] >= .56f && reading.scores()[1] >= reading.best() - .02f) {
            // A rising rail can leave a short left spur near the top of a stem-side one.
            for (int trim = 1; trim <= Math.round(gap * .22f); trim++) {
                var narrower = JoinedDigitTemplates.inspect(crop, stride, rows, mask, l + trim, t, r, z, () -> {});
                if (narrower != null && narrower.digit() == 1) return 1;
            }
        }
        if (mask.nextSetBit(t * stride) >= 0 && mask.nextSetBit(t * stride) < z * stride
                && reading.scores()[2] >= .60f && reading.scores()[2] - reading.runner() >= .048f
                && ownsOcrTwo(words, width, height, a, c, b, d, gap)) return 2;
        if (reading.scores()[2] >= .50f && reading.scores()[2] >= reading.best() - .018f
                && curledTwo(crop, stride, mask, l, t, r, z)) return 2;
        return 0;
    }

    private static boolean ownsOcrTwo(List<SheetInterpreter.Word> words, int width, int height,
            int left, int top, int right, int bottom, float gap) {
        int matches = 0;
        for (var word : words) {
            if (!"2".equals(word.text()) || word.left() * width > left || word.right() * width < right
                    || word.top() * height > top || word.bottom() * height < bottom) continue;
            if ((word.right() - word.left()) * width <= 1.9f * gap
                    && (word.bottom() - word.top()) * height <= 2.1f * gap) matches++;
        }
        return matches == 1;
    }

    private record Island(int left,int top,int right,int bottom,int count) {
        Island merge(Island other) {
            return new Island(Math.min(left,other.left), Math.min(top,other.top),
                    Math.max(right,other.right), Math.max(bottom,other.bottom), count+other.count);
        }
    }

    private static Island digitIsland(byte[] crop, int stride, int rows, BitSet mask, float gap) {
        var used = new BitSet();
        var pieces = new ArrayList<Island>();
        int[] queue = new int[crop.length];
        for (int seed = 0; seed < crop.length; seed++) {
            if (used.get(seed) || mask.get(seed) || (crop[seed] & 255) >= 195) continue;
            used.set(seed);
            queue[0] = seed;
            int size = 1, l = stride, r = -1, t = rows, b = -1;
            for (int i = 0; i < size; i++) {
                int x = queue[i] % stride, y = queue[i] / stride;
                l = Math.min(l,x); r = Math.max(r,x); t = Math.min(t,y); b = Math.max(b,y);
                for (int yy = Math.max(0,y-1); yy <= Math.min(rows-1,y+1); yy++) {
                    for (int xx = Math.max(0,x-1); xx <= Math.min(stride-1,x+1); xx++) {
                        int at = yy*stride+xx;
                        if (used.get(at) || mask.get(at) || (crop[at] & 255) >= 195) continue;
                        used.set(at);
                        queue[size++] = at;
                    }
                }
            }
            if (size >= gap * gap * .053f && b - t >= 2) pieces.add(new Island(l,t,r,b,size));
        }
        if (pieces.isEmpty()) return null;
        pieces.sort(java.util.Comparator.comparingInt(Island::top));
        Island glyph = pieces.get(0);
        for (int i = 1; i < pieces.size(); i++) {
            var next = pieces.get(i);
            int paper = next.top - glyph.bottom - 1, rules = 0;
            for (int y = glyph.bottom + 1; y < next.top; y++) {
                if (mask.get(y * stride)) rules++;
            }
            if (rules >= 2 && paper - rules <= 1 && paper <= 5
                    && Math.min(glyph.right,next.right) - Math.max(glyph.left,next.left) >= 2)
                glyph = glyph.merge(next);
            else return null;
        }
        return glyph;
    }

    private static boolean curledTwo(byte[] crop, int stride, BitSet mask, int l, int t, int r, int z) {
        int w = r - l + 1, h = z - t + 1;
        int upper = 0, diagonal = 0, foot = 0;
        for (int y = t + 2; y < z; y++) {
            if (mask.get(y * stride + l)) continue;
            float f = (y - t) / (float) (h - 1);
            int left = 0, right = 0, center = 0, total = 0;
            for (int x = l; x <= r; x++) {
                if ((crop[y * stride + x] & 255) >= 170) continue;
                total++;
                if (x < l + w * .33f) left++;
                else if (x > l + w * .66f) right++;
                else center++;
            }
            if (f >= .18f && f <= .48f && left >= 2 && right >= 2 && center <= w * .25f) upper++;
            if (f >= .46f && f <= .72f && left + center >= w * .27f && center >= 2
                    && total < w * .72f) diagonal++;
            if (f >= .65f && f <= .89f && total >= w * .80f) foot++;
        }
        return upper >= 1 && diagonal >= 1 && foot >= 2;
    }

    private static float beamEdge(List<PrintedBeamShafts.Shaft> stems, int i, int x) {
        var shaft = stems.get(i);
        float center = axis(shaft);
        int j = x < center && i > 0 ? i - 1 : x > center && i < 2 ? i + 1 : i;
        if (i == j) return shaft.endY();
        var next = stems.get(j);
        return shaft.endY() + (next.endY() - shaft.endY()) * (x - center) / (axis(next) - center);
    }

    private static float axis(PrintedBeamShafts.Shaft shaft) {
        return (shaft.left() + shaft.right()) * .5f;
    }

    private static List<Group> groups(ScorePageInterpretation score, byte[] raw, byte[] normalized,
            int width, int height) {
        long area = (long) width * height;
        if (raw == null || normalized == null || raw.length != area || normalized.length != area) return List.of();
        var result = new ArrayList<Group>();
        for (var group : score.beamSource().resolve().groups()) {
            if (!group.source().bridges().equals(List.of(1, 1)) || !group.source().hooks().isEmpty()
                    || group.source().tupletMark() != ScoreBeamSource.TupletMark.UNNUMBERED
                    || group.columns().size() != 3) continue;
            var columns = new ArrayList<Column>();
            float gap = 0;
            int measure = -1;
            for (var column : group.columns()) {
                if (column.ambiguous() || column.owners().size() != 2) break;
                int a = column.owners().get(0), b = column.owners().get(1);
                var n = score.notes().get(a);
                var m = score.notes().get(b);
                if (n.measureIndex() != m.measureIndex() || measure != -1 && measure != n.measureIndex()
                        || n.staffCount() != 1 || m.staffCount() != 1
                        || n.stemDirection() != -1 || m.stemDirection() != -1
                        || Math.abs(n.staffStep() - m.staffStep()) != 7) break;
                measure = n.measureIndex();
                var bar = score.measures().get(measure);
                gap = (bar.bottom() - bar.top()) * height / 8.2f;
                if (gap < 10 || gap > 24) break;
                var first = head(a, n, bar, width, height);
                var second = head(b, m, bar, width, height);
                columns.add(first.y < second.y ? new Column(first, second) : new Column(second, first));
            }
            if (columns.size() == 3) result.add(new Group(List.copyOf(columns), gap));
        }
        return result;
    }

    private static Head head(int owner, ScoreNoteEvent note, MeasureRegion bar, int width, int height) {
        return new Head(owner, (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width,
                note.pageY() * height);
    }
}
