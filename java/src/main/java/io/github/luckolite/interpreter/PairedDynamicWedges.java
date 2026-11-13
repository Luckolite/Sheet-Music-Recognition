// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

/** Retains stacked, opposing printed alternatives as notation evidence. */
final class PairedDynamicWedges {
    static final String SOURCE = "printed-alternative-hairpin";

    private PairedDynamicWedges() {}

    static void retain(List<ScoreExpressiveEvent> events, List<PrintedHairpinArrival.Level> levels,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes,
            byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height) return;
        for (var pair : find(levels, measures, notes, gray, width, height)) {
            var measure = measures.get(pair.measure());
            float x = measure.left() + pair.start() * (measure.right() - measure.left());
            add(events, pair, x, 0, pair.upperDirection(), pair.upperDepth());
            add(events, pair, x, 1, -pair.upperDirection(), pair.lowerDepth());
        }
    }

    private static void add(List<ScoreExpressiveEvent> events, Pair pair, float x,
            int row, int direction, float depth) {
        var kind = direction > 0 ? ScoreExpressiveEvent.Kind.CRESCENDO
                : ScoreExpressiveEvent.Kind.DIMINUENDO;
        var id = SOURCE + ":" + pair.measure() + ":" + row;
        String geometry = pair.measure() + ":" + pair.start() + ":" + pair.end()
                + ":" + row + ":" + depth;
        events.add(new ScoreExpressiveEvent(id, kind, Optional.empty(), Optional.empty(),
                ScoreExpressiveEvent.Scope.UNRESOLVED, 0, 1, Optional.empty(),
                ScoreExpressiveEvent.Strength.UNSPECIFIED, geometry,
                List.of(new ScoreExpressiveEvent.Evidence(SOURCE, 0, x, 0, 1, ""))));
    }

    static String remap(ScoreExpressiveEvent event, IntUnaryOperator measures) {
        var text = event.qualifierText();
        if (!event.eventId().startsWith(SOURCE + ":")
                || event.evidence().stream().noneMatch(e -> e.sourceId().equals(SOURCE))) return text;
        int end = text.indexOf(':');
        if (end <= 0) return text;
        try {
            int measure = Integer.parseInt(text.substring(0, end));
            return measure < 0 ? text : measures.applyAsInt(measure) + text.substring(end);
        } catch (NumberFormatException invalid) {
            return text;
        }
    }

    private record Pair(int measure, float start, float end, int upperDirection,
                float upperDepth, float lowerDepth) {}

    private static List<Pair> find(List<PrintedHairpinArrival.Level> levels, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] gray, int width, int height) {
        var pairs = new ArrayList<Pair>();
        var openingLevels = levels.stream()
                .filter(level -> level.owner().count() == 1
                        && ("p".equals(level.word().text()) || "f".equals(level.word().text())))
                .collect(java.util.stream.Collectors.groupingBy(PrintedHairpinArrival.Level::measure));
        for (int m = 0; m < measures.size(); m++) {
            var localLevels = openingLevels.get(m);
            if (localLevels == null || localLevels.size() < 2) continue;
            var measure = measures.get(m);
            int index = m;
            var onsets = notes.stream().filter(n -> n.measureIndex() == index && n.staffCount() == 1
                    && n.kind() == ScoreNoteEvent.Kind.PITCHED
                    && (n.articulations() & NoteOrnament.GRACE) == 0)
                    .map(ScoreNoteEvent::positionInMeasure).distinct().sorted().toList();
            if (onsets.size() < 3) continue;
            float start = onsets.get(0), end = onsets.get(onsets.size() - 1);
            int left = Math.round((measure.left() + start * (measure.right() - measure.left())) * width);
            int right = Math.round((measure.left() + end * (measure.right() - measure.left())) * width);
            for (var upper : localLevels) {
                if (upper.measure() != m || upper.owner().count() != 1) continue;
                var staff = upper.owner();
                float gap = staff.gap(), y1 = center(upper, height);
                if (right - left < gap * 9 || y1 < staff.bottom() + gap * .8f
                        || y1 > staff.bottom() + gap * 4 || !opensAt(upper, left, width, gap)) continue;
                for (var lower : localLevels) {
                    if (!staff.equals(lower.owner())
                            || !opensAt(lower, left, width, gap)
                            || Math.abs(upper.word().left() - lower.word().left()) * width > gap * .8f) continue;
                    float y2 = center(lower, height);
                    if (y2 - y1 < gap * 1.5f || y2 - y1 > gap * 3) continue;
                    int direction = switch (upper.word().text() + "/" + lower.word().text()) {
                        case "p/f" -> 1;
                        case "f/p" -> -1;
                        default -> 0;
                    };
                    if (direction != 0 && prove(gray, width, height, left, right, y1, gap, direction)
                            && prove(gray, width, height, left, right, y2, gap, -direction)) {
                        var pair = new Pair(m, start, end, direction,
                                (y1 - staff.bottom()) / gap, (y2 - staff.bottom()) / gap);
                        if (pairs.stream().noneMatch(other -> other.measure() == index)) pairs.add(pair);
                    }
                }
            }
        }
        return pairs;
    }

    private static boolean opensAt(PrintedHairpinArrival.Level level, int x, int width, float gap) {
        float distance = x - level.word().right() * width;
        return distance >= gap * .25f && distance <= gap * 3;
    }

    private static float center(PrintedHairpinArrival.Level level, int height) {
        return (level.word().top() + level.word().bottom()) * height * .5f;
    }

    private static boolean prove(byte[] gray, int width, int height, int left, int right,
            float y, float gap, int direction) {
        int top = Math.round(y - gap * .88f), bottom = Math.round(y + gap * .88f);
        int radius = Math.round(gap / 3);
        if (top < 0 || bottom >= height || left < radius || right + radius >= width) return false;
        var first = bands(gray, width, left + Math.round((right - left) * .42f), top, bottom, radius, gap);
        var second = bands(gray, width, left + Math.round((right - left) * .58f), top, bottom, radius, gap);
        if (first.size() != 2 || second.size() != 2
                || Math.abs(first.get(0) - second.get(0)) > gap * .22f
                || Math.abs(first.get(1) - second.get(1)) > gap * .22f) return false;
        float opening = (first.get(1) - first.get(0) + second.get(1) - second.get(0)) * .5f;
        if (opening < gap * .66f || opening > gap * 1.55f) return false;
        float center = (first.get(0) + first.get(1) + second.get(0) + second.get(1)) * .25f;
        if (!tip(gray, width, left, right, top, bottom, radius, gap, direction, center, .02f)
                && !tip(gray, width, left, right, top, bottom, radius, gap, direction, center, .05f)) return false;
        int body = 0;
        boolean taper = false;
        for (int i = 2; i <= 19; i++) {
            int x = sample(left, right, direction, i / 20f);
            var arms = bands(gray, width, x, top, bottom, radius, gap);
            if (arms.size() != 2 || Math.abs((arms.get(0) + arms.get(1)) * .5f - center) > gap * .26f)
                continue;
            float separation = arms.get(1) - arms.get(0);
            if (i >= 5 && Math.abs(separation - opening) <= gap * .22f) body++;
            if (i <= 4 && separation >= gap * .2f && separation <= opening - gap * .12f) taper = true;
        }
        return taper && body >= 14;
    }

    private static boolean tip(byte[] gray, int width, int left, int right, int top, int bottom,
            int radius, float gap, int direction, float center, float fraction) {
        var bands = bands(gray, width, sample(left, right, direction, fraction), top, bottom, radius, gap);
        return bands.size() == 1 && Math.abs(bands.get(0) - center) <= gap * .23f;
    }

    private static int sample(int left, int right, int direction, float fraction) {
        return left + Math.round((right - left) * (direction > 0 ? fraction : 1 - fraction));
    }

    private static List<Float> bands(byte[] gray, int width, int x, int top, int bottom,
            int radius, float gap) {
        var bands = new ArrayList<Float>();
        int start = -1;
        for (int y = top; y <= bottom + 1; y++) {
            int ink = 0;
            if (y <= bottom)
                for (int c = x - radius; c <= x + radius; c++)
                    if ((gray[y * width + c] & 255) < 170) ink++;
            if (ink >= radius) {
                if (start < 0) start = y;
            } else if (start >= 0) {
                if (y - start <= gap * .45f) bands.add((start + y - 1) * .5f);
                start = -1;
            }
        }
        return bands;
    }
}
