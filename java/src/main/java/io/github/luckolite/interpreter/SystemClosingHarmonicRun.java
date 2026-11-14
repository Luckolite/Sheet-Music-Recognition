// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

final class SystemClosingHarmonicRun {
    private SystemClosingHarmonicRun() {}

    static boolean provesTripletRun(byte[] gray, int width, int height,
            MeasureRegion run, MeasureRegion closing, List<ScoreNoteEvent> heads, ScoreNoteEvent tail) {
        if (gray == null || gray.length != width * (long) height || heads.size() != 12) return false;
        float gap = (run.bottom() - run.top()) * height / 8.2f;
        float cut = (run.right() + closing.left()) * width * .5f;
        float edge = closing.right() * width;
        if (gap < 6 || gap > 32 || Math.abs(run.top() - closing.top()) * height > gap * .35
                || (closing.left() - run.right()) * width > gap * 1.8f
                || (edge - cut) / gap < 4 || (edge - cut) / gap > 9
                || tail.beamCount() != 0 || tail.unbeamedDurationBeats() != 1
                || !simpleHarmonic(tail)) return false;
        float railGap = (closing.bottom() - closing.top()) * height / 8.2f;
        float hint = closing.bottom() * height - railGap * 2.1f;
        float[] rails = null;
        for (float inset : new float[] {2, 2.5f, 3, 4, 5, 6, 7, 8, 9}) {
            rails = PrintedDirectionStaff.printedRules(gray, width, height, edge - inset * railGap, hint, railGap);
            if (rails != null) break;
        }
        if (rails == null) return false;
        var staff = new PlayingTechniqueDetector.Staff(rails[0]-4*rails[1], rails[0], rails[1], 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(gray, width, staff, cut, true, false) < 0
                || PrintedCadenceDurations.boundaryColumn(gray, width, staff, edge, false, true) < 0)
            return false;
        float[] xs = new float[12], ys = new float[12];
        for (int i = 0; i < heads.size(); i++) {
            var n = heads.get(i);
            if (!simpleHarmonic(n) || n.beamCount() != 1 || n.unbeamedDurationBeats() != 0) return false;
            xs[i] = (run.left() + n.positionInMeasure() * (run.right() - run.left())) * width;
            ys[i] = n.pageY() * height;
            if (i == 0) continue;
            if (xs[i] - xs[i - 1] < gap * 1.2f || xs[i] - xs[i - 1] > gap * 5) return false;
            var prev = heads.get(i - 1);
            boolean sameDirection = prev.stemDirection() == n.stemDirection();
            boolean linked = sameDirection && PrintedTupletBeamOwner.connectedHeads(
                    gray, width, height, xs[i - 1], ys[i - 1], xs[i], ys[i], gap, n.stemDirection());
            if (linked != (i % 3 != 0)) return false;
        }
        for (int i = 0; i < xs.length; i += 3) {
            if (BoundedSecondaryBeam.proves(gray, width, height,
                    new float[] {xs[i], xs[i + 1], xs[i + 2]},
                    new float[] {ys[i], ys[i + 1], ys[i + 2]}, gap)) return false;
        }
        return true;
    }

    private static boolean simpleHarmonic(ScoreNoteEvent n) {
        return n.octaveShift() == 2 && n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0 && n.staffCount() == 1 && !n.crossStaffBeam()
                && n.tupletDivisor() == 1 && n.augmentationDots() == 0
                && n.stemDirection() != 0 && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.followingRestBeats() == 0 && n.leadingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score, ScoreMeterChange meter,
            byte[] gray, int width, int height) {
        var notes = score.notes();
        var owners = new ArrayList<List<Integer>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) owners.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) owners.get(notes.get(i).measureIndex()).add(i);
        var rests = new boolean[owners.size()];
        for (var rest : score.rests()) rests[rest.measureIndex()] = true;
        var output = new ArrayList<>(notes);
        boolean changed = false;
        for (int i = 0; i + 1 < owners.size(); i++) {
            var run = owners.get(i);
            var tail = owners.get(i + 1);
            var bar = score.measures().get(i);
            var closing = score.measures().get(i + 1);
            if (closing.cadenceQuarterBeats() != 1 || rests[i] || rests[i + 1]
                    || run.size() != 12 || tail.size() != 1) continue;
            ScoreMeterChange active = meter;
            for (var change : score.meterChanges()) {
                if (change.measureIndex() <= i) active = change;
            }
            if (active == null || active.numerator() != 4 || active.denominator() != 4) continue;
            float staffGap = (closing.bottom() - closing.top()) * height / 8.2f;
            if (i + 2 < owners.size() && Math.abs(
                    score.measures().get(i + 2).top() - closing.top()) * height < staffGap)
                continue;
            run.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            if (!provesTripletRun(gray, width, height, bar, closing,
                    run.stream().map(notes::get).toList(), notes.get(tail.get(0)))) continue;
            for (var owner : run) output.set(owner, notes.get(owner).withTupletRatio(3, 2));
            changed = true;
        }
        if (!changed) return score;
        return score.withNotes(output, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }
}
