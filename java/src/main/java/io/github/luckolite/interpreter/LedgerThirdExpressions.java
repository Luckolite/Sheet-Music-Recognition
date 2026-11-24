// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Attaches printed expression to the upper and lower owners of a proved ledger-third bar. */
final class LedgerThirdExpressions {
    private LedgerThirdExpressions() {}

    record Result(ScorePageInterpretation score, List<PrintedFingeringDigits.Mark> fingerings) {}
    private record Head(int index, float x, float y, ScoreNoteEvent note) {}
    private record Arc(int startX, float startY, int endX, float endY) {}

    static Result capture(ScorePageInterpretation score, byte[] raw, byte[] gray, int w, int h, int page) {
        if (page < 0 || raw == null || gray == null || raw.length != (long) w * h || raw.length != gray.length)
            return new Result(score, List.of());
        var existing = score.slurSource();
        var origins = new ArrayList<>(existing.noteOrigins());
        var spans = new ArrayList<>(existing.spans());
        var fingers = new ArrayList<PrintedFingeringDigits.Mark>();
        var occupied = new HashSet<ScoreSlurPages.Anchor>();
        existing.spans().forEach(span -> { occupied.add(span.start()); occupied.add(span.end()); });
        existing.candidates().forEach(span -> { occupied.add(span.start()); occupied.add(span.end()); });
        int nextAnchor = origins.stream().flatMap(Optional::stream).filter(a -> a.pageIndex() == page)
                .mapToInt(ScoreSlurPages.Anchor::noteIndex).max().orElse(-1) + 1;
        var notes = score.notes();
        for (int measure = 0; measure < score.measures().size(); measure++) {
            var bar = score.measures().get(measure);
            float gap = (bar.bottom() - bar.top()) * h / 8.2f;
            float width = (bar.right() - bar.left()) * w;
            if (gap < 12 || gap > 24 || width < 25 * gap || width > 34 * gap) continue;
            var row = new ArrayList<Head>(32);
            for (int i = 0; i < notes.size(); i++) {
                var n = notes.get(i);
                if (n.measureIndex() != measure || n.staffCount() != 1 || n.staffIndex() != 0
                        || n.beamCount() != 2 || n.tupletDivisor() != 1 || n.augmentationDots() != 0
                        || n.stemDirection() != -1 || n.kind() != ScoreNoteEvent.Kind.PITCHED
                        || score.beamSource().noteOrigins().get(i).isEmpty()) continue;
                row.add(new Head(i, bar.left() * w + n.positionInMeasure() * width, n.pageY() * h, n));
            }
            if (row.size() != 32) continue;
            row.sort(Comparator.comparingDouble(Head::x));
            var uppers = new ArrayList<Head>(16);
            var lowers = new ArrayList<Head>(16);
            for (int i = 0; i < row.size(); i += 2) {
                var a = row.get(i);
                var b = row.get(i + 1);
                var upper = a.y < b.y ? a : b;
                var lower = upper == a ? b : a;
                if (upper.note.staffStep() - lower.note.staffStep() != 2 || b.x - a.x > .21f * gap
                        || Math.abs(lower.y - upper.y - gap) > .24f * gap) break;
                uppers.add(upper);
                lowers.add(lower);
            }
            if (uppers.size() != 16 || uppers.get(0).note.staffStep() < 16
                    || uppers.get(1).note.staffStep() < 15) continue;
            float floor = bar.bottom() * h - 2.1f * gap;
            var a = slur(raw, w, h, uppers, gap);
            var b = slur(gray, w, h, uppers, gap);
            if (a != null && b != null && Math.abs(a.startX - b.startX) <= 2 && Math.abs(a.endX - b.endX) <= 2
                    && Math.abs(a.startY - b.startY) <= 2 && Math.abs(a.endY - b.endY) <= 2) {
                int start = uppers.get(0).index, end = uppers.get(7).index;
                var from = origins.get(start).orElse(null);
                if (from == null) from = new ScoreSlurPages.Anchor(page, nextAnchor++);
                var to = origins.get(end).orElse(null);
                if (to == null) to = new ScoreSlurPages.Anchor(page, nextAnchor++);
                if (!occupied.contains(from) && !occupied.contains(to)
                        && (origins.get(start).isPresent() || !origins.contains(Optional.of(from)))
                        && (origins.get(end).isPresent() || !origins.contains(Optional.of(to)))) {
                    origins.set(start, Optional.of(from));
                    origins.set(end, Optional.of(to));
                    occupied.add(from);
                    occupied.add(to);
                    var ink = new ScoreSlurSpan.ArcEvidence(page, a.startX / (float) w, a.startY / h,
                            a.endX / (float) w, a.endY / h);
                    spans.add(new ScoreSlurPages.Span("printed-ledger-thirds:" + page + ":" + from.noteIndex(),
                            from, to, ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID, List.of(ink)));
                }
            }
            int[] above = {2, 1}, below = {4, 3};
            boolean stack = true;
            for (int i = 0; i < 2; i++) {
                var upper = uppers.get(i);
                var lower = lowers.get(i);
                for (var ink : List.of(raw, gray))
                    stack &= digit(ink, w, h, upper.x, upper.y, gap, above[i], false)
                            && ruledDigit(ink, w, h, lower.x, floor, gap, below[i]);
            }
            if (stack) for (int i = 0; i < 2; i++) {
                fingers.add(new PrintedFingeringDigits.Mark(uppers.get(i).index, above[i],
                        PrintedFingeringDigits.Placement.ABOVE));
                fingers.add(new PrintedFingeringDigits.Mark(lowers.get(i).index, below[i],
                        PrintedFingeringDigits.Placement.BELOW));
            }
        }
        var repaired = spans.size() == existing.spans().size() ? score
                : score.withSlurSource(new ScoreSlurSource(spans, origins, existing.candidates()))
                        .withClockContextSource(score.clockContextSource());
        return new Result(repaired, List.copyOf(fingers));
    }

    private static Arc slur(byte[] ink, int w, int h, List<Head> row, float gap) {
        var first = row.get(0);
        var last = row.get(7);
        if (last.x - first.x < 11.8f * gap || last.x - first.x > 15.5f * gap) return null;
        int left = Math.round(first.x + .58f * gap), right = Math.round(last.x + .73f * gap);
        int top = Math.round(first.y - 2.85f * gap), bottom = Math.round(last.y - .26f * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return null;
        Arc found = null;
        var components = RawNumeralComponents.captureLongWindow(ink, w, h, left, top, right, bottom, 145);
        for (var c : components.components()) {
            if (c.left <= left || c.right >= right || c.top <= top || c.bottom >= bottom
                    || c.left - first.x < .70f * gap || c.left - first.x > 1.10f * gap
                    || Math.abs(c.right - last.x) > .36f * gap || c.size() < (c.right - c.left) * 2) continue;
            var arc = arc(c, w, first.y, last.y, gap);
            if (arc == null) continue;
            if (found != null) return null;
            found = arc;
        }
        return found;
    }

    private static Arc arc(RawNumeralComponents.Component c, int w, float firstY, float lastY, float gap) {
        int span = c.right - c.left + 1;
        int[] sums = new int[span], counts = new int[span];
        for (int i = 0; i < c.size(); i++) {
            int at = c.pixel(i), x = at % w - c.left;
            sums[x] += at / w;
            counts[x]++;
        }
        float[] centers = new float[span];
        for (int i = 0; i < span; i++) {
            if (counts[i] < 1 || counts[i] > .62f * gap) return null;
            centers[i] = sums[i] / (float) counts[i];
            if (i > 0 && Math.abs(centers[i] - centers[i - 1]) > .23f * gap) return null;
        }
        float a = centers[0], b = centers[span - 1];
        if (firstY - a < .66f * gap || firstY - a > 1.5f * gap
                || lastY - b < .85f * gap || lastY - b > 1.55f * gap
                || centers[span / 5] > a - .52f * gap) return null;
        for (int i = 1; i <= 3; i++) {
            float t = i * .25f;
            if (a + (b - a) * t - centers[Math.round((span - 1) * t)] < .53f * gap) return null;
        }
        return new Arc(c.left, a, c.right, b);
    }

    private static boolean ruledDigit(byte[] ink, int width, int height, float x,
            float floor, float gap, int wanted) {
        if (digit(ink, width, height, x, floor, gap, wanted, true)) return true;
        var rules = PrintedDirectionStaff.printedRules(ink, width, height, x, floor, gap);
        if (rules == null || Math.abs(rules[0] - floor) > .23f * gap
                || Math.abs(rules[1] - gap) > .09f * gap) return false;
        // Beam-heavy geometry rounds the staff bottom differently from the
        // short ruling under the digit. Keep the ruled crop on the printed staff.
        return digit(ink, width, height, x, rules[0], gap, wanted, true)
                || digit(ink, width, height, x, rules[0] + .085f * gap, gap, wanted, true);
    }

    private static boolean digit(byte[] ink, int w, int h, float x, float y, float gap, int wanted, boolean ruled) {
        int left = Math.round(x - .83f * gap), right = Math.round(x + .83f * gap);
        int top = Math.round(y - (ruled ? .92f : 2.36f) * gap);
        int bottom = Math.round(y + (ruled ? .94f : -.66f) * gap);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return false;
        if (!ruled) {
            var components = RawNumeralComponents.capture(ink, w, h, left, top, right, bottom, 145, () -> {}, () -> {});
            var fits = components.components().stream()
                    .filter(c -> c.left > left && c.right < right && c.top > top && c.bottom < bottom
                            && Math.abs((c.left + c.right) * .5f - x) <= .31f * gap
                            && fitsDigit(ink, w, new BitSet(), c.left, c.top, c.right, c.bottom, gap, wanted))
                    .toList();
            return fits.size() == 1;
        }
        var mask = new BitSet();
        int a = right, b = bottom, c = left, d = top, rows = 0;
        for (int yy = top; yy <= bottom; yy++) {
            int dark = 0;
            for (int xx = left; xx <= right; xx++)
                if ((ink[yy * w + xx] & 255) < 145) dark++;
            if (Math.abs(yy - y) < .22f * gap && dark >= .90f * (right - left + 1)) {
                mask.set(yy * w + left, yy * w + right + 1);
                rows++;
                continue;
            }
            for (int xx = left; xx <= right; xx++) {
                if ((ink[yy * w + xx] & 255) >= 145) continue;
                a = Math.min(a, xx); b = Math.min(b, yy);
                c = Math.max(c, xx); d = Math.max(d, yy);
            }
        }
        return rows >= 2 && a > left && b > top && c < right && d < bottom
                && Math.abs((a + c) * .5f - x) < .33f * gap && fitsDigit(ink, w, mask, a, b, c, d, gap, wanted);
    }

    private static boolean fitsDigit(byte[] ink, int w, BitSet mask, int left, int top, int right,
            int bottom, float gap, int wanted) {
        int height = bottom - top + 1, width = right - left + 1;
        if (width < .49f * gap || width > 1.10f * gap
                || height < .90f * gap || height > 1.27f * gap) return false;
        var fit = JoinedDigitTemplates.inspect(ink, w, ink.length / w, mask, left, top, right, bottom, () -> {});
        if (fit == null || fit.scores()[wanted] < .64f) return false;
        for (int i = 0; i < fit.scores().length; i++)
            if (i != wanted && fit.scores()[wanted] - fit.scores()[i] < .045f) return false;
        return true;
    }
}
