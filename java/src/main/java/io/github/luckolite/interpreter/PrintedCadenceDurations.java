// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** A narrow, complete written cadence can be shorter than its surrounding meter. */
final class PrintedCadenceDurations {
    private PrintedCadenceDurations() {}

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] labels, byte[] gray, int width, int height) {
        if (score.notes().isEmpty()) return score;
        var staffs = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, score.measures());
        return capture(score, gray, width, height, staffs);
    }

    static ScorePageInterpretation capture(
            ScorePageInterpretation score, byte[] gray, int width, int height,
            List<PlayingTechniqueDetector.Staff> staffs) {
        int count = score.measures().size();
        var notes = new ArrayList<List<ScoreNoteEvent>>(count);
        for (int i = 0; i < count; i++) notes.add(new ArrayList<>());
        for (var note : score.notes()) notes.get(note.measureIndex()).add(note);
        var rests = new boolean[count];
        for (var rest : score.rests()) rests[rest.measureIndex()] = true;
        var meter = new boolean[count];
        for (var change : score.meterChanges()) {
            if (change.measureIndex() < meter.length) meter[change.measureIndex()] = true;
        }
        var regions = new ArrayList<>(score.measures());
        boolean changed = false;
        for (int i = 0; i < count; i++) {
            var chord = notes.get(i);
            if (rests[i] || chord.isEmpty()) continue;
            float duration = chord.get(0).unbeamedDurationBeats();
            if (duration != 1 && duration != 2) continue;
            if (!chord.stream().allMatch(note -> exactCadenceNote(note, duration))) continue;
            var measure = score.measures().get(i);
            var next = i + 1 < count ? score.measures().get(i + 1) : null;
            if (!proved(gray, width, height, measure, next, staffs, chord,
                    next != null && meter[i + 1])) continue;
            regions.set(i, new MeasureRegion(measure.left(), measure.right(), measure.top(),
                    measure.bottom(), (int) duration));
            changed = true;
        }
        if (!changed) return score;
        return new ScorePageInterpretation(regions, score.notes(), score.firstMeasureNumber(),
                score.keyChanges(), score.tempoChanges(), score.meterChanges(), score.rests(),
                score.techniqueChanges(), score.dynamicChanges(), score.playbackDirections(),
                score.expressiveEvents(), score.slurSource(), score.openingPickup(), score.tremoloSource(),
                score.multiRestGroups(), score.beamSource(), score.clockContextSource());
    }

    static boolean[] apply(ScorePageInterpretation score, float[] beats) {
        var shortened = new boolean[beats.length];
        for (int i = 0; i < beats.length; i++) {
            int duration = score.measures().get(i).cadenceQuarterBeats();
            if (duration > 0 && duration < beats[i]) {
                beats[i] = duration;
                shortened[i] = true;
            }
        }
        return shortened;
    }

    private static boolean exactCadenceNote(ScoreNoteEvent note, float duration) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffIndex() == 0 && note.staffCount() == 1
                && note.unbeamedDurationBeats() == duration
                && note.beamCount() == 0 && note.augmentationDots() == 0
                && note.tupletDivisor() == 1 && !note.crossStaffBeam()
                && !note.tiedFromPrevious() && note.boundaryTies() == 0
                && note.leadingRestBeats() == 0 && note.followingRestBeats() == 0
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean proved(
            byte[] gray, int width, int height, MeasureRegion measure, MeasureRegion next,
            List<PlayingTechniqueDetector.Staff> staffs, List<ScoreNoteEvent> chord,
            boolean nextHasMeter) {
        for (var staff : staffs) {
            float gap = staff.gap();
            if (staff.count() != 1 || staff.top() < 0 || staff.top() + gap * 4 >= height
                    || staff.bottom() >= height || staff.top() < measure.top() * height - gap
                    || staff.bottom() > measure.bottom() * height + gap) continue;
            float span = (measure.right() - measure.left()) * width;
            if (span > gap * 8) return false;
            double first = chord.stream().mapToDouble(ScoreNoteEvent::positionInMeasure).min().orElseThrow();
            double last = chord.stream().mapToDouble(ScoreNoteEvent::positionInMeasure).max().orElseThrow();
            if (first < .15 || last > .8 || (last - first) * span > gap * .4) return false;
            float left = measure.left() * width, right = measure.right() * width;
            boolean sameSystem = next != null && Math.abs(next.top() - measure.top()) * height < gap;
            float boundary = sameSystem ? (right + next.left() * width) * .5f : right;
            double firstX = left + first * span, lastX = left + last * span;
            boolean hasAccidental = chord.stream().anyMatch(
                    note -> note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
            if (completeCadence(gray, width, staff, left, right, firstX, lastX, boundary,
                    sameSystem && nextHasMeter, false, hasAccidental))
                return true;
            // Near a sloping edge the page-wide rails can be several pixels off.
            // Reuse a local five-rule reading before allowing an interrupted stroke.
            float[] rails = PrintedDirectionStaff.printedRules(
                    gray, width, height, left + span * .25f, staff.bottom(), gap);
            if (rails == null) return false;
            float localTop = rails[0] - rails[1] * 4;
            if (localTop < 0 || rails[0] >= height) return false;
            var local = new PlayingTechniqueDetector.Staff(
                    localTop, rails[0], rails[1], staff.index(), staff.count());
            return completeCadence(gray, width, local, left, right, firstX, lastX, boundary,
                    sameSystem && nextHasMeter, !sameSystem, hasAccidental);
        }
        return false;
    }

    private static boolean completeCadence(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            float left, float right, double first, double last,
            float boundary, boolean nextHasMeter, boolean terminal, boolean hasAccidental) {
        int stroke = boundaryColumn(gray, width, staff, boundary, nextHasMeter, terminal);
        // A thick terminal bar may be bounded by its right edge in the measure geometry.
        // Start the exclusion at the first proved stroke, before counting any interior ink.
        return stroke >= 0 && emptyOutsideChord(gray, width, staff, left,
                Math.min(right, stroke + staff.gap() * .45f), first, last, hasAccidental);
    }

    private static boolean emptyOutsideChord(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            float left, float right, double first, double last, boolean hasAccidental) {
        float gap = staff.gap();
        for (int x = Math.max(0, Math.round(left + gap * .75f));
                x < Math.min(width, Math.round(right - gap * .75f)); x++) {
            double accidentalReach = hasAccidental ? 1.5 : 1.2;
            if (x >= first - gap * accidentalReach && x <= last + gap * 1.2) continue;
            int ink = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++)
                if (betweenRules(y, staff) && (gray[y * width + x] & 255) < 155) ink++;
            if (ink >= gap * .55f) return false;
        }
        return true;
    }

    private static boolean betweenRules(int y, PlayingTechniqueDetector.Staff staff) {
        float row = (y - staff.top()) / staff.gap();
        return Math.abs(row - Math.round(row)) > .17f;
    }

    static int boundaryColumn(
            byte[] gray, int width, PlayingTechniqueDetector.Staff staff,
            float boundary, boolean nextHasMeter, boolean terminal) {
        float gap = staff.gap();
        int left = Math.max(0, Math.round(boundary - gap));
        int right = Math.min(width - 1, Math.round(boundary + gap));
        var darkness = new float[right - left + 1];
        var strokes = new boolean[darkness.length];
        var interrupted = new boolean[darkness.length];
        for (int x = left; x <= right; x++) {
            int samples = 0, sum = 0;
            int spaces = 0;
            for (int space = 0; space < 4; space++) {
                int local = 0, dark = 0;
                for (int y = Math.round(staff.top() + (space + .2f) * gap);
                        y <= Math.round(staff.top() + (space + .8f) * gap); y++) {
                    int value = gray[y * width + x] & 255;
                    sum += value;
                    local++;
                    if (value < 155) dark++;
                }
                samples += local;
                if (dark >= local * .7f) spaces |= 1 << space;
            }
            boolean crossesStaff = spaces == 0b1111;
            darkness[x - left] = sum / (float) samples;
            if (nextHasMeter && crossesStaff && Math.abs(x - boundary) <= gap * .65f) return x;
            strokes[x - left] = crossesStaff && darkness[x - left] < 95;
            interrupted[x - left] = terminal && (spaces == 0b1101 || spaces == 0b1011)
                    && darkness[x - left] < 95;
        }
        // Two thin rules can touch at the staff lines. Compare the raw space profile
        // for a lighter valley separating both full-height strokes.
        for (int a = 0; a < darkness.length; a++) {
            if (!strokes[a] && !interrupted[a]) continue;
            for (int b = a + Math.max(3, Math.round(gap * .24f));
                    b < darkness.length && b - a <= gap * .85f; b++) {
                if (!(strokes[a] && (strokes[b] || interrupted[b]) || interrupted[a] && strokes[b])
                        || Math.abs(left + (a + b) * .5f - boundary) > gap * .65f) continue;
                for (int x = a + 1; x < b; x++)
                    if (darkness[x] - Math.max(darkness[a], darkness[b]) >= 24) return left + a;
            }
        }
        return -1;
    }
}
