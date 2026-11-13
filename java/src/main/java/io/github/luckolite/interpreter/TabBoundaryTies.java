// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Optical system shoulders with explicit string/fret/tuning identity. */
public final class TabBoundaryTies {
    private TabBoundaryTies() {}

    private record Edge(TablatureDecoder.Fret fret, int note, int directions) {}

    private record VoiceMeasure(int measure, int staff, int staffCount) {}

    private static final class EdgePositions {
        float firstNote = Float.POSITIVE_INFINITY;
        float lastNote = Float.NEGATIVE_INFINITY;
        float firstRest = Float.POSITIVE_INFINITY;
        float lastRest = Float.NEGATIVE_INFINITY;
        boolean hasRest;
    }

    public static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<TablatureDecoder.Staff> tabs,
            byte[] gray,
            int width,
            int height) {
        return apply(score, tabs, gray, width, height, null, 0);
    }

    public static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<TablatureDecoder.Staff> tabs,
            byte[] gray,
            int width,
            int height,
            int[] suppliedTuning,
            int capo) {
        if (score == null
                || tabs == null
                || tabs.isEmpty()
                || gray == null
                || width < 64
                || height < 64
                || gray.length != (long) width * height
                || score.notes().isEmpty()
                || capo < 0
                || capo > 12) return score;
        suppliedTuning = suppliedTuning == null ? null : suppliedTuning.clone();
        var systems = new ArrayList<>(tabs);
        systems.sort(Comparator.comparingDouble(TablatureDecoder.Staff::top));
        // Mixed or paired notation owns its own rhythmic/tie evidence.
        if (systems.stream().anyMatch(t -> t.standardTop() >= 0)) return score;
        byte[] clean = TabFretRaster.clean(gray, width, height, systems);
        var positions = edgePositions(score);
        var noteLocations = new NoteLocations(score, width, height);
        var incoming = new ArrayList<List<Edge>>();
        var outgoing = new ArrayList<List<Edge>>();
        for (var system : systems) {
            incoming.add(edges(score, system, clean, width, height, true, positions, noteLocations));
            outgoing.add(edges(score, system, clean, width, height, false, positions, noteLocations));
        }
        var notes = new ArrayList<>(score.notes());
        // Only actual adjacent systems can establish a within-page predecessor.
        for (int i = 1; i < systems.size(); i++) {
            var before = systems.get(i - 1);
            var after = systems.get(i);
            float ratio = after.gap() / before.gap();
            int[] beforeTuning = effectiveTuning(before, suppliedTuning, capo);
            int[] afterTuning = effectiveTuning(after, suppliedTuning, capo);
            if (before.stringCount() != after.stringCount()
                    || beforeTuning == null
                    || afterTuning == null
                    || !Arrays.equals(beforeTuning, afterTuning)
                    || ratio < .75f
                    || ratio > 1.33f
                    || after.top() - before.bottom() < before.gap()
                    || after.top() - before.bottom() > before.gap() * 50) continue;
            for (var end : incoming.get(i))
                for (var start : outgoing.get(i - 1)) {
                    if (start.fret().string() != end.fret().string()
                            || start.fret().fret() != end.fret().fret()
                            || beforeTuning[start.fret().string()] + start.fret().fret() > 127
                            || (start.directions() & end.directions()) == 0) continue;
                    var a = score.notes().get(start.note());
                    var b = score.notes().get(end.note());
                    // Edge collection already proved both sides free of intervening notes/rests.
                    if (samePitchVoice(a, b) && a.measureIndex() + 1 == b.measureIndex()
                            && noDirectionAt(score, b.measureIndex()))
                        notes.set(end.note(), tied(notes.get(end.note())));
                }
        }
        // Page-outer optical evidence retains string/count/fret/effective-open
        // identity. The page resolver must still prove the actual adjacent-page
        // predecessor, equal pitch/voice, and compatible independent shoulders.
        var first = systems.get(0);
        var last = systems.get(systems.size() - 1);
        int[] firstTuning = effectiveTuning(first, suppliedTuning, capo);
        int[] lastTuning = effectiveTuning(last, suppliedTuning, capo);
        for (var edge : incoming.get(0)) {
            var note = notes.get(edge.note());
            if (firstTuning != null && note.measureIndex() == 0 && noDirectionAt(score, 0))
                notes.set(edge.note(), withIdentity(note, edge, first, firstTuning, true));
        }
        for (var edge : outgoing.get(systems.size() - 1)) {
            var note = notes.get(edge.note());
            if (lastTuning != null
                    && note.measureIndex() == score.measures().size() - 1
                    && noDirectionAt(score, score.measures().size()))
                notes.set(edge.note(), withIdentity(note, edge, last, lastTuning, false));
        }
        return new ScorePageInterpretation(
                        score.measures(),
                        notes,
                        score.firstMeasureNumber(),
                        score.keyChanges(),
                        score.tempoChanges(),
                        score.meterChanges(),
                        score.rests(),
                        score.techniqueChanges(),
                        score.dynamicChanges(),
                        score.playbackDirections(),
                        score.expressiveEvents(),
                        score.slurSource(),
                        score.openingPickup(),
                        score.tremoloSource()).withBeamSource(score.beamSource())
                .carryMultiRestGroupsFrom(score);
    }

    private static List<Edge> edges(
            ScorePageInterpretation score,
            TablatureDecoder.Staff tab,
            byte[] clean,
            int width,
            int height,
            boolean incoming,
            Map<VoiceMeasure, EdgePositions> positions,
            NoteLocations noteLocations) {
        if (tab.frets().isEmpty() || tab.bars().size() < 2) return List.of();
        var bars = new ArrayList<>(tab.bars());
        bars.sort(Float::compare);
        float boundary = incoming ? bars.get(0) : bars.get(bars.size() - 1);
        float inner = incoming ? bars.get(1) : bars.get(bars.size() - 2);
        float extreme = incoming ? Float.MAX_VALUE : -Float.MAX_VALUE;
        for (var fret : tab.frets()) {
            if (incoming) extreme = Math.min(extreme, fret.x());
            else extreme = Math.max(extreme, fret.x());
        }
        var result = new ArrayList<Edge>();
        for (var fret : tab.frets()) {
            if (fret.string() < 0
                    || fret.string() >= tab.stringCount()
                    || fret.fret() < 0
                    || !allowedMarks(fret.marks(), incoming)
                    || incoming && fret.tied()
                    || Math.abs(fret.x() - extreme) > tab.gap() * .18f
                    || (incoming
                            ? fret.x() <= boundary || fret.x() >= inner
                            : fret.x() >= boundary || fret.x() <= inner)) continue;
            int mapped = noteLocations.find(fret, tab.gap() * .22f);
            if (mapped < 0) continue;
            var note = score.notes().get(mapped);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || !allowedMarks(note.articulations(), incoming)
                    || note.clefBottomDiatonic() == ScoreNoteEvent.CLEF_UNKNOWN
                    || !clearSide(positions, note, incoming)) continue;
            int directions = shoulders(clean, width, height, tab, fret, boundary, incoming);
            if (directions != 0) result.add(new Edge(fret, mapped, directions));
        }
        return List.copyOf(result);
    }

    private static boolean allowedMarks(int marks, boolean incoming) {
        // An incoming effect explicitly attacks the target. A pure outgoing
        // tap attacks the source at its start and may subsequently sustain;
        // bends, slides, grace notes and additional articulations remain barred.
        return marks == 0 || !incoming && marks == TabEffect.encode(TabEffect.TAP, 0);
    }

    private record NoteLocation(int index, float x, float y) {}

    private static final class NoteLocations {
        private final ScorePageInterpretation score;
        private final int width, height;
        private List<NoteLocation> byRow;
        private boolean finiteRows;

        NoteLocations(ScorePageInterpretation score, int width, int height) {
            this.score = score;
            this.width = width;
            this.height = height;
        }

        private void indexRows() {
            byRow = new ArrayList<>(score.notes().size());
            boolean finite = true;
            for (int i = 0; i < score.notes().size(); i++) {
                var note = score.notes().get(i);
                var measure = score.measures().get(note.measureIndex());
                float x = (measure.left()
                        + note.positionInMeasure() * (measure.right() - measure.left())) * width;
                float y = note.pageY() * height;
                byRow.add(new NoteLocation(i, x, y));
                finite &= Float.isFinite(y);
            }
            finiteRows = finite;
            if (finiteRows) byRow.sort(Comparator.comparingDouble(NoteLocation::y));
        }

        int find(TablatureDecoder.Fret fret, float tolerance) {
            if (byRow == null) indexRows();
            boolean bounded = finiteRows && Float.isFinite(fret.y()) && tolerance >= 0;
            int low = 0, high = bounded ? byRow.size() : 0;
            // Compare the original floating-point difference at both limits.
            while (low < high) {
                int middle = low + ((high - low) >>> 1);
                if (byRow.get(middle).y() - fret.y() < -tolerance) low = middle + 1;
                else high = middle;
            }
            int found = -1;
            for (int i = low; i < byRow.size(); i++) {
                var location = byRow.get(i);
                float dy = location.y() - fret.y();
                if (bounded && dy > tolerance) break;
                if (Math.abs(location.x() - fret.x()) > tolerance || Math.abs(dy) > tolerance)
                    continue;
                if (found >= 0) return -1;
                found = location.index();
            }
            return found;
        }
    }

    private static boolean samePitchVoice(ScoreNoteEvent a, ScoreNoteEvent b) {
        return a.kind() == ScoreNoteEvent.Kind.PITCHED
                && b.kind() == ScoreNoteEvent.Kind.PITCHED
                && a.staffIndex() == b.staffIndex()
                && a.staffCount() == b.staffCount()
                && a.diatonicPitchIdentity() == b.diatonicPitchIdentity()
                && a.writtenAccidental() == b.writtenAccidental()
                && a.octaveShift() == b.octaveShift();
    }

    private static ScoreNoteEvent withIdentity(
            ScoreNoteEvent note,
            Edge edge,
            TablatureDecoder.Staff tab,
            int[] tuning,
            boolean incoming) {
        int directions = incoming ? edge.directions() : edge.directions() << 2;
        int evidence;
        try {
            evidence =
                    TabTieIdentity.encode(
                            directions,
                            edge.fret().string(),
                            tab.stringCount(),
                            edge.fret().fret(),
                            tuning[edge.fret().string()]);
        } catch (IllegalArgumentException invalid) {
            return note;
        }
        int old = note.boundaryTies();
        if (old != 0) {
            // Never replace a previous identity or upgrade unrelated legacy
            // optical direction bits to a guessed tab identity.
            if (!TabTieIdentity.typed(old) || !TabTieIdentity.compatible(old, evidence))
                return note;
            evidence =
                    TabTieIdentity.encode(
                            (old | evidence) & TabTieIdentity.DIRECTIONS,
                            edge.fret().string(),
                            tab.stringCount(),
                            edge.fret().fret(),
                            tuning[edge.fret().string()]);
        }
        return note.withBoundaryTies(evidence);
    }

    private static boolean noDirectionAt(ScorePageInterpretation score, int boundary) {
        return score.playbackDirections().stream().noneMatch(d -> d.measureBoundary() == boundary);
    }

    private static int[] effectiveTuning(TablatureDecoder.Staff tab, int[] supplied, int capo) {
        int[] tuning;
        if (supplied != null) tuning = supplied.clone();
        else if (!tab.tuning().isEmpty())
            tuning = tab.tuning().stream().mapToInt(Integer::intValue).toArray();
        else
            tuning =
                    tab.stringCount() == 7
                            ? new int[] {64, 59, 55, 50, 45, 40, 35}
                            : new int[] {64, 59, 55, 50, 45, 40};
        if (tuning.length != tab.stringCount()) return null;
        for (int i = 0; i < tuning.length; i++) {
            if (tuning[i] < 0 || tuning[i] > 127 - capo) return null;
            tuning[i] += capo;
        }
        return tuning;
    }

    private static Map<VoiceMeasure, EdgePositions> edgePositions(ScorePageInterpretation score) {
        var positions = new HashMap<VoiceMeasure, EdgePositions>();
        for (var note : score.notes()) {
            float at = note.positionInMeasure();
            if (Float.isNaN(at)) continue;
            var key = new VoiceMeasure(note.measureIndex(), note.staffIndex(), note.staffCount());
            var edge = positions.computeIfAbsent(key, unused -> new EdgePositions());
            edge.firstNote = Math.min(edge.firstNote, at);
            edge.lastNote = Math.max(edge.lastNote, at);
        }
        for (var rest : score.rests()) {
            float at = rest.positionInMeasure();
            if (Float.isNaN(at)) continue;
            var key = new VoiceMeasure(rest.measureIndex(), rest.staffIndex(), rest.staffCount());
            var edge = positions.computeIfAbsent(key, unused -> new EdgePositions());
            edge.firstRest = Math.min(edge.firstRest, at);
            edge.lastRest = Math.max(edge.lastRest, at);
            edge.hasRest = true;
        }
        return positions;
    }

    private static boolean clearSide(
            Map<VoiceMeasure, EdgePositions> positions, ScoreNoteEvent note, boolean incoming) {
        if (incoming ? note.leadingRestBeats() > 0 : note.followingRestBeats() > 0) return false;
        float at = note.positionInMeasure();
        var edge = positions.get(new VoiceMeasure(note.measureIndex(), note.staffIndex(), note.staffCount()));
        if (edge == null) return true;
        return incoming
                ? !(edge.firstNote < at - .018f || edge.hasRest && edge.firstRest <= at + .018f)
                : !(edge.lastNote > at + .018f || edge.hasRest && edge.lastRest >= at - .018f);
    }

    private static ScoreNoteEvent tied(ScoreNoteEvent n) {
        return new ScoreNoteEvent(
                        n.measureIndex(),
                        n.positionInMeasure(),
                        n.staffStep(),
                        n.staffIndex(),
                        n.staffCount(),
                        n.pageY(),
                        true,
                        n.augmentationDots(),
                        n.beamCount(),
                        n.writtenAccidental(),
                        n.unbeamedDurationBeats(),
                        n.tupletDivisor(),
                        n.followingRestBeats(),
                        n.articulations(),
                        n.clefBottomDiatonic(),
                        n.crossStaffBeam(),
                        n.leadingRestBeats(),
                        n.compactOpening(),
                        n.octaveShift(),
                        n.boundaryTies(),
                        n.tupletNormalNotes(),
                        n.stemDirection(),
                        n.kind())
                .withVisualSize(n.visualSize());
    }

    private static int shoulders(
            byte[] clean,
            int width,
            int height,
            TablatureDecoder.Staff tab,
            TablatureDecoder.Fret fret,
            float boundary,
            boolean incoming) {
        float gap = tab.gap();
        float clear = fret.fret() >= 10 ? .78f : .47f;
        float left, right;
        if (incoming) {
            if (fret.x() - boundary > gap * 4 || fret.x() - boundary < gap * .8f) return 0;
            left = Math.max(boundary + gap * .15f, fret.x() - gap * 2.6f);
            right = fret.x() - gap * clear;
        } else {
            if (boundary - fret.x() > gap * 24 || boundary - fret.x() < gap * 1.3f) return 0;
            left = fret.x() + gap * clear;
            right = boundary - gap * .18f;
        }
        int result = 0;
        for (int direction : new int[] {-1, 1})
            if (bow(clean, width, height, left, right, fret.y(), gap, direction, incoming))
                result |= direction == -1 ? 1 : 2;
        return result;
    }

    /**
     * Require one connected bowed component. Its two ends return toward this
     * string and its middle travels away; a rule, parenthesis, sloping fragment,
     * or the opposite shoulder belonging to a neighboring string cannot qualify.
     * Incoming shoulder length is deliberately bounded beside the first fret;
     * outgoing ink must span from the last fret to the actual enclosing bar.
     */
    private static boolean bow(
            byte[] gray,
            int width,
            int height,
            float left,
            float right,
            float cy,
            float gap,
            int direction,
            boolean incoming) {
        // Pale glyphs may touch an otherwise independent dark curve. Keep each
        // component proof separate so added antialias coverage cannot merge
        // neighboring ink into and invalidate an already proven shoulder.
        return bow(gray, width, height, left, right, cy, gap, direction, incoming, false)
                || bow(gray, width, height, left, right, cy, gap, direction, incoming, true);
    }

    private static boolean bow(
            byte[] gray,
            int width,
            int height,
            float left,
            float right,
            float cy,
            float gap,
            int direction,
            boolean incoming,
            boolean coverage) {
        int x0 = Math.max(0, Math.round(left)), x1 = Math.min(width - 1, Math.round(right));
        int y0 = Math.max(0, Math.round(cy - gap * .92f)),
                y1 = Math.min(height - 1, Math.round(cy + gap * .92f));
        int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
        if (cw < 3 || ch < 3) return false;
        boolean[] foreground =
                coverage
                        ? inkRuns(gray, width, x0, x1, y0, y1, cy, gap, direction)
                        : darkInk(gray, width, x0, x1, y0, y1, cy, gap, direction);
        boolean[] seen = new boolean[cw * ch];
        int[] queue = new int[cw * ch];
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int seed = (y - y0) * cw + x - x0;
                if (seen[seed] || !foreground[seed]) continue;
                int begin = 0, end = 0;
                queue[end++] = seed;
                seen[seed] = true;
                int minX = x, maxX = x, minY = y, maxY = y;
                float[] centerline = new float[cw];
                Arrays.fill(centerline, Float.MAX_VALUE);
                float[] farthest = new float[cw];
                while (begin < end) {
                    int value = queue[begin++], px = x0 + value % cw, py = y0 + value / cw;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                    float distance = (py - cy) * direction;
                    centerline[px - x0] = Math.min(centerline[px - x0], distance);
                    farthest[px - x0] = Math.max(farthest[px - x0], distance);
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = px + dx, ny = py + dy;
                            if (nx < x0 || nx > x1 || ny < y0 || ny > y1) continue;
                            int next = (ny - y0) * cw + nx - x0;
                            if (!seen[next] && foreground[next]) {
                                seen[next] = true;
                                queue[end++] = next;
                            }
                        }
                }
                float span = maxX - minX, rise = maxY - minY;
                if (span < gap * (incoming ? .28f : .8f)
                        || rise < gap * .07f
                        || rise > gap * .68f
                        || rise > span * (incoming ? 1.2f : .7f)) continue;
                if (incoming) {
                    if (span > gap * 1.5f || maxX < x1 - gap * .9f) continue;
                } else if (minX > x0 + gap * .28f || maxX < x1 - gap * .28f) continue;
                // Filled/slightly thick glyphs can have a flat inner edge. Their
                // connected stroke center still describes the optical bow.
                for (int column = minX - x0; column <= maxX - x0; column++)
                    if (centerline[column] != Float.MAX_VALUE)
                        centerline[column] = (centerline[column] + farthest[column]) * .5f;
                int middleMargin = Math.max(1, Math.round(span * .2f));
                // Sample the actual tips, bounded by both bow span and string
                // spacing; wider windows include a long bow's raised shoulder.
                int endMargin =
                        Math.max(1, Math.min(Math.round(span * .1f), Math.round(gap * .1f)));
                float first = average(centerline, minX - x0, minX - x0 + endMargin);
                float last = average(centerline, maxX - x0 - endMargin, maxX - x0);
                float middle =
                        average(
                                centerline,
                                Math.round((minX + maxX) * .5f) - x0 - middleMargin / 2,
                                Math.round((minX + maxX) * .5f) - x0 + middleMargin / 2);
                if (Float.isFinite(first)
                        && Float.isFinite(last)
                        && Float.isFinite(middle)
                        && first < gap * .4f
                        && last < gap * .4f
                        && middle > Math.max(first, last) + gap * .075f) return true;
            }
        return false;
    }

    private static boolean[] darkInk(
            byte[] gray,
            int width,
            int x0,
            int x1,
            int y0,
            int y1,
            float cy,
            float gap,
            int direction) {
        int cw = x1 - x0 + 1;
        boolean[] result = new boolean[cw * (y1 - y0 + 1)];
        for (int y = y0; y <= y1; y++) {
            float distance = (y - cy) * direction;
            if (distance < gap * .08f || distance > gap * .92f) continue;
            for (int x = x0; x <= x1; x++)
                result[(y - y0) * cw + x - x0] = (gray[y * width + x] & 255) < 210;
        }
        return result;
    }

    /**
     * A thin stroke may share its coverage between adjacent pale pixels. Keep
     * only contiguous vertical runs with actual ink mass; white columns and
     * separate fragments remain disconnected. Geometry still owns the tie.
     */
    private static boolean[] inkRuns(
            byte[] gray,
            int width,
            int x0,
            int x1,
            int y0,
            int y1,
            float cy,
            float gap,
            int direction) {
        int cw = x1 - x0 + 1;
        boolean[] result = new boolean[cw * (y1 - y0 + 1)];
        for (int x = x0; x <= x1; x++) {
            int first = -1, mass = 0;
            for (int y = y0; y <= y1 + 1; y++) {
                float distance = (y - cy) * direction;
                int value =
                        y <= y1 && distance >= gap * .08f && distance <= gap * .92f
                                ? gray[y * width + x] & 255
                                : 255;
                if (value < 245) {
                    if (first < 0) first = y;
                    mass += 255 - value;
                } else if (first >= 0) {
                    if (mass > 45)
                        for (int row = first; row < y; row++)
                            result[(row - y0) * cw + x - x0] = true;
                    first = -1;
                    mass = 0;
                }
            }
        }
        return result;
    }

    private static float average(float[] values, int from, int to) {
        float total = 0;
        int count = 0;
        for (int i = Math.max(0, from); i <= Math.min(values.length - 1, to); i++)
            if (values[i] != Float.MAX_VALUE) {
                total += values[i];
                count++;
            }
        return count == 0 ? Float.NaN : total / count;
    }
}
