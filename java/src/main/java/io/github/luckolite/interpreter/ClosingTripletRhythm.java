// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Recovers an unnumbered triplet cadence at the proved end of a single staff. */
final class ClosingTripletRhythm {
    private ClosingTripletRhythm() {}
    static ScorePageInterpretation apply(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] labels, byte[] gray, int width, int height) {
        int barIndex = score.measures().size() - 1;
        if (barIndex < 1) return score;
        ScoreMeterChange meter = inherited;
        for (var change : score.meterChanges())
            if (change.measureIndex() <= barIndex) meter = change;
        if (meter != null && (meter.numerator() != 4 || meter.denominator() != 4)
                || score.rests().stream().anyMatch(rest -> rest.measureIndex() == barIndex)) return score;
        var notes = score.notes();
        var indices = IntStream.range(0, notes.size())
                .filter(i -> notes.get(i).measureIndex() == barIndex).boxed()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
        var bar = score.measures().get(barIndex);
        if (indices.size() == 7)
            return closingHalf(score, meter, labels, gray, width, height, barIndex, bar, indices);
        if (indices.size() != 10) return score;
        float[] xs = new float[10], ys = new float[10];
        int direction = notes.get(indices.get(0)).stemDirection();
        if (Math.abs(direction) != 1) return score;
        int doubled = -1;
        for (int i = 0; i < 10; i++) {
            var n = notes.get(indices.get(i));
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffCount() != 1
                    || n.stemDirection() != direction
                    || n.tupletDivisor() != 1 || n.augmentationDots() != 0 || n.crossStaffBeam()
                    || n.leadingRestBeats() > 0 || n.followingRestBeats() > 0
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || (n.articulations() & NoteOrnament.GRACE) != 0) return score;
            if (i == 9) {
                if (n.beamCount() != 0 || n.unbeamedDurationBeats() != 1) return score;
            } else if (n.beamCount() == 2 && i % 3 == 0 && doubled < 0) doubled = i;
            else if (n.beamCount() != 1) return score;
            xs[i] = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
            ys[i] = n.pageY() * height;
        }
        var staffs = OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, score.measures());
        var owner = staffs.stream()
                .filter(s -> s.top() >= bar.top() * height && s.bottom() <= bar.bottom() * height)
                .min(Comparator.comparingDouble(s -> Math.abs((s.top()+s.bottom())/2
                        -(bar.top()+bar.bottom())*height/2))).orElse(null);
        if (owner == null) return score;
        if (meter == null && !printedCommonTime(score, barIndex, labels, gray, width, height, owner))
            return score;
        // A group break must survive staff-line removal; shared line ink can bridge
        // otherwise independent triplets.
        var track = track(gray, width, height, bar, owner);
        if (track == null || !printedEnd(gray, width, height, bar, track, owner.gap())) return score;
        float gap = owner.gap();
        for (int i = 1; i < 10; i++) {
            float dx = xs[i] - xs[i-1];
            if (dx < gap * 1.4f || dx > gap * 7) return score;
            boolean linked = PrintedTupletBeamOwner.connectedAwayFromRules(
                    gray, width, height, xs[i-1], ys[i-1], xs[i], ys[i], gap, direction, track);
            if (linked != (i % 3 != 0)) return score;
        }
        if (doubled >= 0 && hasSecondary(gray, width, height, xs, ys, gap, direction, doubled))
            return score;
        var result = new ArrayList<>(notes);
        for (int i = 0; i < 9; i++) {
            int index = indices.get(i);
            var note = result.get(index);
            if (i == doubled) note = singleBeam(note);
            result.set(index, note.withTupletRatio(3, 2));
        }
        return score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static ScorePageInterpretation closingHalf(
            ScorePageInterpretation score, ScoreMeterChange meter, byte[] labels, byte[] gray,
            int width, int height, int barIndex, MeasureRegion bar, List<Integer> indices) {
        if (gray == null || gray.length != (long) width * height) return score;
        var notes = score.notes();
        float[] x = new float[7], y = new float[7];
        for (int i = 0; i < indices.size(); i++) {
            var note = notes.get(indices.get(i));
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.crossStaffBeam() || note.beamCount() != (i < 6 ? 1 : 0)
                    || i == 6 && note.unbeamedDurationBeats() != 2
                    || note.augmentationDots() != 0 || note.tupletDivisor() != 1
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return score;
            x[i] = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            y[i] = note.pageY() * height;
        }
        float edge = bar.right() * width;
        for (var staff : RawStaffLineDetector.detect(gray, width, height)) {
            float gap = staff.gap();
            if (staff.top() < bar.top() * height - gap
                    || staff.bottom() > bar.bottom() * height + gap) continue;
            var owner = new PlayingTechniqueDetector.Staff(staff.top(), staff.bottom(), gap, 0, 1);
            if (meter == null && !printedCommonTime(score, barIndex, labels, gray, width, height, owner))
                continue;
            if (!doubleRule(gray, width, height, owner, edge)) continue;
            int first = tripletDirection(gray, width, height, x, y, gap, 0);
            int second = tripletDirection(gray, width, height, x, y, gap, 3);
            if (first == 0 || second == 0 || first == second
                    && !SeparateBeamGroups.between(gray, width, height, x[2], y[2], x[3], y[3], gap))
                continue;
            var result = new ArrayList<>(notes);
            for (int i : indices.subList(0, 6)) result.set(i, notes.get(i).withTupletRatio(3, 2));
            return score.withNotes(result, IntStream.range(0, notes.size()).boxed().toList())
                    .withClockContextSource(score.clockContextSource());
        }
        return score;
    }

    private static boolean doubleRule(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff, float edge) {
        float gap = staff.gap();
        for (float inset : new float[] {3, 2.5f, 4}) {
            float[] rails = PrintedDirectionStaff.printedRules(
                    gray, width, height, edge - inset * gap, staff.bottom(), gap);
            if (rails == null) continue;
            var local = new PlayingTechniqueDetector.Staff(
                    rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
            if (PrintedCadenceDurations.boundaryColumn(gray, width, local, edge, false, true) >= 0)
                return true;
        }
        return false;
    }

    private static int tripletDirection(
            byte[] gray, int width, int height, float[] x, float[] y, float gap, int start) {
        for (int direction : new int[] {-1, 1}) {
            if (PrintedTupletBeamOwner.connectedHeads(gray, width, height,
                    x[start], y[start], x[start + 1], y[start + 1], gap, direction)
                    && PrintedTupletBeamOwner.connectedHeads(gray, width, height,
                            x[start + 1], y[start + 1], x[start + 2], y[start + 2], gap, direction))
                return direction;
        }
        return 0;
    }

    private static boolean printedCommonTime(
            ScorePageInterpretation score, int barIndex, byte[] labels, byte[] gray,
            int width, int height, PlayingTechniqueDetector.Staff staff) {
        var first = score.measures().get(barIndex);
        for (int i = barIndex - 1; i >= 0; i--) {
            var earlier = score.measures().get(i);
            if (Math.abs(earlier.top() - first.top()) * height > staff.gap()) break;
            first = earlier;
        }
        float left = first.left() * width;
        return CommonTimeMeter.readHeader(
                labels, gray, width, height, Math.round(left), Math.round(staff.top()), staff.gap()) == 4;
    }

    /** All five rules must end together with no sustained continuation. */
    static boolean printedEnd(byte[] gray, int width, int height,
                              MeasureRegion bar, StaffPitchTrack track, float gap) {
        int edge = Math.round(bar.right() * width);
        int inset = Math.round(gap * 2.25f), outside = Math.round(gap * 2);
        int radius = Math.max(1, Math.round(gap * .15f));
        if (edge - inset < 0 || edge + outside >= width) return false;
        int firstEnd = width, lastEnd = -1;
        for (int line = 0; line < 5; line++) {
            int endpoint = -1, run = 0;
            for (int x = edge - inset; x <= edge + outside; x++) {
                float[] frame = track.at(Math.min(x, edge));
                int center = Math.round(frame[0] - line * frame[1]);
                if (center - radius < 0 || center + radius >= height) return false;
                boolean ink = false;
                for (int dy = -radius; dy <= radius; dy++)
                    if ((gray[(center + dy) * width + x] & 255) < 165) {
                        ink = true;
                        break;
                    }
                if (ink) {
                    run++;
                    if (run >= Math.round(gap * 1.4f)) endpoint = x;
                } else run = 0;
                // A continuing rule beyond the shared edge vetoes closure.
                if (x > edge + Math.round(gap * .55f) && run >= Math.max(2, Math.round(gap * .35f)))
                    return false;
            }
            if (Math.abs(endpoint - edge) > gap * .55f) return false;
            firstEnd = Math.min(firstEnd, endpoint);
            lastEnd = Math.max(lastEnd, endpoint);
        }
        return lastEnd - firstEnd <= Math.max(2, Math.round(gap * .28f));
    }
    private static StaffPitchTrack track(byte[] gray, int width, int height,
                                         MeasureRegion bar, PlayingTechniqueDetector.Staff owner) {
        float gap = owner.gap();
        if (gap < 6 || gap > 32) return null;
        int first = Math.round(bar.left() * width), last = Math.round(bar.right() * width);
        int top = Math.max(0, Math.round(owner.top() - gap * 1.5f));
        int bottom = Math.min(height - 1, Math.round(owner.bottom() + gap * 1.5f));
        int radius = Math.round(gap * 5.4f);
        var samples = new ArrayList<float[]>();
        for (int i = 0; i <= 12; i++) {
            int center = first + (last-first)*i/12;
            int left = Math.max(0, center-radius), right = Math.min(width-1,center+radius);
            int[] strength = new int[bottom-top+1];
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++)
                    if ((gray[y*width+x]&255) < 165) strength[y-top]++;
            var candidates = RawStaffLineDetector.detectFromStrength(
                    strength, Math.round((right-left+1)*.8f), strength.length);
            for (var candidate : candidates) {
                int base = candidate.bottom()+top;
                if (Math.abs(base-owner.bottom()) > gap*.32f
                        || Math.abs(candidate.gap()-gap) > gap*.06f) continue;
                samples.add(new float[]{center,base,candidate.gap()});
                break;
            }
        }
        return samples.size() < 5 ? null : StaffPitchTrack.fromVerifiedSamples(samples);
    }

    /** Preserve printed secondary rails and short, shaft-rooted beamlets. */
    private static boolean hasSecondary(byte[] gray, int width, int height, float[] xs, float[] ys,
                                        float gap, int direction, int first) {
        if (BoundedSecondaryBeam.proves(gray, width, height,
                Arrays.copyOfRange(xs, first, first+3),
                Arrays.copyOfRange(ys, first, first+3), gap)) return true;
        int center = Math.round(xs[first] + direction * gap * .47f);
        int y = Math.round(ys[first]);
        for (int x = center-Math.round(gap*.24f); x <= center+Math.round(gap*.24f); x++)
            for (int d = Math.round(gap*2); d <= gap*4; d++)
                if (StemOwnedBeamlet.proves(gray,width,height,new int[]{x,y-direction*d,-direction},gap))
                    return true;
        return false;
    }

    private static ScoreNoteEvent singleBeam(ScoreNoteEvent n) {
        return new ScoreNoteEvent(
                n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(), n.staffCount(),
                n.pageY(), n.tiedFromPrevious(), n.augmentationDots(), 1, n.writtenAccidental(), 0,
                n.tupletDivisor(), n.followingRestBeats(), n.articulations(), n.clefBottomDiatonic(),
                n.crossStaffBeam(), n.leadingRestBeats(), n.compactOpening(), n.octaveShift(),
                n.boundaryTies(), n.tupletNormalNotes(), n.stemDirection(), n.kind(), n.visualSize(),
                n.harmonicSemitones(), n.openStringTouch());
    }
}
