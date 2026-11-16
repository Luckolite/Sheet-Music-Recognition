// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A hollow touch and its two beams can make a note shaft look like a staff divider. */
final class HarmonicBeamBarline {
    private HarmonicBeamBarline() {}

    static List<MeasureRegion> join(
            List<MeasureRegion> measures,
            byte[] labels,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        if (labels == null
                || gray == null
                || raw == null
                || width < 1
                || height < 1
                || gray.length != (long) width * height
                || labels.length != gray.length
                || raw.length != gray.length) return measures;
        List<MeasureRegion> joined = null;
        for (int i = 0; i < measures.size(); i++) {
            var first = measures.get(i);
            if (i + 1 < measures.size()) {
                var next = measures.get(i + 1);
                if (ownedShaft(labels, gray, width, height, first, next)
                        && (gray == raw || ownedShaft(labels, raw, width, height, first, next))) {
                    if (joined == null) joined = new ArrayList<>(measures.subList(0, i));
                    joined.add(
                            new MeasureRegion(
                                    first.left(),
                                    next.right(),
                                    Math.min(first.top(), next.top()),
                                    Math.max(first.bottom(), next.bottom())));
                    i++;
                    continue;
                }
            }
            if (joined != null) joined.add(first);
        }
        return joined == null ? measures : List.copyOf(joined);
    }

    private static boolean ownedShaft(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            MeasureRegion first,
            MeasureRegion next) {
        float gap = (first.bottom() - first.top()) * height / 8.2f;
        if (!Float.isFinite(gap)
                || gap < 9
                || gap > 25
                || first.cadenceQuarterBeats() != 0
                || next.cadenceQuarterBeats() != 0) return false;
        float left = first.left() * width, right = next.right() * width;
        float seamLeft = first.right() * width, seamRight = next.left() * width;
        float seamWidth = seamRight - seamLeft, center = (seamLeft + seamRight) * .5f;
        if (seamWidth < gap * .75f
                || seamWidth > gap * 1.4f
                || Math.abs(first.top() - next.top()) * height > gap * .25f
                || Math.abs(first.bottom() - next.bottom()) * height > gap * .25f
                || seamLeft - left < gap * 8
                || right - seamRight < gap * 8
                || center < gap * 3
                || center + gap * 3 >= width
                || right + gap * 1.6f >= width) return false;
        var lines =
                PrintedDirectionStaff.printedRules(
                        gray,
                        width,
                        height,
                        center + gap * 1.15f,
                        (first.bottom() + next.bottom()) * height * .5f - gap * 2.1f,
                        gap);
        if (lines == null) return false;
        gap = lines[1];
        int base = Math.round(lines[0]), top = Math.round(lines[0] - 4 * gap);
        if (top < gap * 2 || base + gap * 2 >= height) return false;
        int shaftLeft = -1, shaftRight = -1;
        for (int x = (int) Math.ceil(seamLeft); x <= (int) Math.floor(seamRight); x++) {
            int supported = 0;
            for (int y = top; y <= base; y++) if ((gray[y * width + x] & 255) < 155) supported++;
            if (supported < (base - top + 1) * .88f) continue;
            if (shaftLeft < 0) shaftLeft = x;
            else if (x != shaftRight + 1) return false;
            shaftRight = x;
        }
        if (shaftLeft < 0 || shaftRight - shaftLeft + 1 > gap * .35f) return false;
        int column = (shaftLeft + shaftRight) / 2;
        float head = column + gap * .62f;
        if (!ArtificialHarmonics.symbolDiamond(labels, gray, width, height, head, top, gap))
            return false;
        var shafts =
                PrintedBeamShafts.at(
                        gray, width, height, head, top, gap, new PrintedBeamShafts.Budget(100_000));
        if (shafts.size() != 1) return false;
        var shaft = shafts.get(0);
        if (shaft.direction() != 1
                || shaft.left() > shaftLeft
                || shaft.right() < shaftRight
                || shaft.endY() < base
                || shaft.endY() > base + gap * .8f) return false;
        int reach = Math.round(gap * 2.25f);
        if (!BeamedFlatBarline.closedDownstemPair(
                gray,
                width,
                height,
                new int[] {column - reach, shaft.endY(), 1},
                new int[] {column + reach, shaft.endY(), 1},
                gap)) return false;
        var staff = new PlayingTechniqueDetector.Staff(top, base, gap, 0, 1);
        return PrintedCadenceDurations.boundaryColumn(
                        gray, width, staff, right + gap * .55f, false, false)
                >= 0;
    }

    record TailRecovery(ScorePageInterpretation score, List<List<ScoreNoteEvent>> tails) {}

    static TailRecovery recoverTail(
            ScorePageInterpretation score,
            List<MeasureRegion> before,
            List<MeasureRegion> joined,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        if (before == joined) return new TailRecovery(score, List.of());
        var added = new java.util.HashSet<>(joined);
        added.removeAll(before);
        var notes = score.notes();
        List<ScoreNoteEvent> repaired = null;
        var tails = new ArrayList<List<ScoreNoteEvent>>();
        for (int m = 0; m < score.measures().size(); m++) {
            var region = score.measures().get(m);
            if (!added.contains(region)) continue;
            int measure = m;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            var owners =
                    java.util.stream.IntStream.range(0, notes.size())
                            .filter(i -> notes.get(i).measureIndex() == measure)
                            .boxed()
                            .toList();
            if (owners.size() < 7) continue;
            var terminal = owners.subList(owners.size() - 6, owners.size());
            var heads = terminal.stream().map(notes::get).toList();
            if (!closedTail(gray, width, height, region, heads)
                    || !closedTail(raw, width, height, region, heads)) continue;
            if (repaired == null) repaired = new ArrayList<>(notes);
            repaired.set(terminal.get(0), ClosingTripletRhythm.beamedValue(heads.get(0), 2));
            tails.add(terminal.stream().map(repaired::get).toList());
        }
        var revised =
                repaired == null
                        ? score
                        : score.withNotes(
                                repaired,
                                java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
        return new TailRecovery(revised, List.copyOf(tails));
    }

    private static boolean closedTail(
            byte[] gray, int width, int height, MeasureRegion region, List<ScoreNoteEvent> heads) {
        float left = region.left() * width, right = region.right() * width;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(6);
        float lastX = -1;
        for (int i = 0; i < heads.size(); i++) {
            var note = heads.get(i);
            int beams = i == 0 ? 0 : i == 5 ? 1 : 2;
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.staffCount() != 1
                    || note.stemDirection() != -1
                    || note.beamCount() != beams
                    || note.unbeamedDurationBeats() != (i == 0 ? 2 : 0)
                    || note.augmentationDots() != 0
                    || note.tupletDivisor() != 1
                    || note.leadingRestBeats() != 0
                    || note.followingRestBeats() != 0
                    || note.crossStaffBeam()
                    || note.tiedFromPrevious()
                    || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return false;
            float x = left + note.positionInMeasure() * (right - left), y = note.pageY() * height;
            if (i > 0 && (x - lastX < gap * 1.7f || x - lastX > gap * 3.2f)) return false;
            if (i == 0 && !hollow(gray, width, height, x, y, gap)) return false;
            var shaft = downstem(gray, width, height, x, y, gap);
            if (shaft == null) return false;
            shafts.add(shaft);
            lastX = x;
        }
        if (right - lastX < gap * 1.5f || right - lastX > gap * 4.5f) return false;
        int[] connections = {2, 2, 0, 2, 1};
        for (int i = 0; i < connections.length; i++) {
            if (!WrappedEighthTriplets.matchesBeamRails(
                    gray, width, height, shafts.get(i), shafts.get(i + 1), gap, connections[i]))
                return false;
        }
        var lines =
                PrintedDirectionStaff.printedRules(
                        gray,
                        width,
                        height,
                        lastX + gap * .6f,
                        region.bottom() * height - gap * 2.1f,
                        gap);
        if (lines == null) return false;
        var staff =
                new PlayingTechniqueDetector.Staff(
                        lines[0] - 4 * lines[1], lines[0], lines[1], 0, 1);
        return PrintedCadenceDurations.boundaryColumn(
                                gray, width, staff, right + gap * .55f, false, false)
                        >= 0
                && PrintedCadenceDurations.emptyOutsideChord(
                        gray, width, staff, lastX + gap * .5f, right, lastX, lastX, false);
    }

    private static boolean hollow(byte[] gray, int width, int height, float x, float y, float gap) {
        int l = Math.round(x - gap * .75f), r = Math.round(x + gap * .7f);
        int t = Math.round(y - gap * .75f), b = Math.round(y + gap * .75f);
        return l >= 0
                && r < width
                && t >= 0
                && b < height
                && WholeHeadPocketEvidence.hasEnclosedOpening(
                        gray,
                        width,
                        height,
                        l,
                        t,
                        r,
                        b,
                        x,
                        y,
                        gap,
                        Math.max(6, Math.round(gap * gap * .03125f)),
                        2);
    }

    private static PrintedBeamShafts.Shaft downstem(
            byte[] gray, int width, int height, float x, float y, float gap) {
        var budget = new PrintedBeamShafts.Budget(1_200_000);
        PrintedBeamShafts.Shaft best = null;
        for (float dy : new float[] {0, -.22f, .22f, -.36f, .36f}) {
            for (float dx : new float[] {0, -.21f, .21f}) {
                for (var shaft :
                        PrintedBeamShafts.at(
                                gray, width, height, x + gap * dx, y + gap * dy, gap, budget)) {
                    if (shaft.direction() != 1) continue;
                    if (best != null
                            && (shaft.left() > best.right() + 1
                                    || best.left() > shaft.right() + 1
                                    || Math.abs(best.endY() - shaft.endY()) > 2)) return null;
                    if (best == null || shaft.right() - shaft.left() > best.right() - best.left())
                        best = shaft;
                }
            }
        }
        return budget.refused() ? null : best;
    }

    static void placeTails(
            List<List<ScoreNoteEvent>> tails,
            List<ScoreNoteEvent> notes,
            List<Map<String, Object>> events,
            float[] beats,
            double[] starts) {
        for (var tail : tails) {
            int first = notes.indexOf(tail.get(0)), bar = tail.get(0).measureIndex();
            if (first < 0
                    || beats[bar] != 4
                    || first + tail.size() > events.size()
                    || !notes.subList(first, first + tail.size()).equals(tail)
                    || tail.stream().anyMatch(n -> notes.indexOf(n) != notes.lastIndexOf(n)))
                continue;
            // The six attacks have five sixteenths and a final eighth at the proved closing rule.
            double onset =
                    beats[bar]
                            - tail.stream().mapToDouble(n -> Math.scalb(1d, -n.beamCount())).sum();
            double limit = starts[bar] + onset;
            boolean blocked = false;
            for (int i = first - 1; i >= 0 && notes.get(i).measureIndex() == bar; i--) {
                var previous = events.get(i);
                double before = ((Number) previous.get("startBeat")).doubleValue();
                double duration = ((Number) previous.get("durationBeats")).doubleValue();
                if (before + duration > limit + 1e-6) blocked = true;
            }
            if (blocked) continue;
            for (int i = 0; i < tail.size(); i++) {
                var event = events.get(first + i);
                double duration = Math.scalb(1d, -tail.get(i).beamCount());
                event.put("startBeat", starts[bar] + onset);
                event.put(
                        "writtenPlacement",
                        new ScoreNoteTiming.WrittenPlacement(onset, duration, -1, -1, false, 0));
                onset += duration;
            }
        }
    }
}
