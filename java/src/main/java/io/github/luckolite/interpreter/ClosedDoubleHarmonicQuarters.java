// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Reads a closed 2/4 run with two stopped heads and matching touch diamonds on each shaft. */
final class ClosedDoubleHarmonicQuarters {
    private ClosedDoubleHarmonicQuarters() {}

    private record Owner(int index, ScoreNoteEvent note, float x, float y) {}

    private record Attack(Owner upper, Owner lower, int stem, int end, int direction) {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        var contracted = closedAttacks(score, gray, width, height);
        if (contracted.isEmpty()) return score;
        var renewed = new ArrayList<>(score.notes());
        for (var attack : contracted) {
            for (var owner : List.of(attack.upper(), attack.lower())) {
                var n = owner.note();
                if (!quarter(n) || n.stemDirection() == 0)
                    renewed.set(owner.index(), withQuarter(n, attack.direction()));
            }
        }
        return renewed.equals(score.notes())
                ? score
                : score.withNotes(renewed, IntStream.range(0, renewed.size()).boxed().toList());
    }

    static Map<Integer, Integer> writtenOnsets(
            ScorePageInterpretation score, byte[] gray, int width, int height, float[] beats) {
        var attacks = closedAttacks(score, gray, width, height);
        if (attacks.isEmpty()) return Map.of();
        var placed = new HashMap<Integer, Integer>();
        for (int i = 0; i < attacks.size(); i += 2) {
            int bar = attacks.get(i).upper().note().measureIndex();
            if (beats[bar] != 2) continue;
            for (int j = 0; j < 2; j++) {
                var attack = attacks.get(i + j);
                placed.put(attack.upper().index(), j);
                placed.put(attack.lower().index(), j);
            }
        }
        return placed;
    }

    private static List<Attack> closedAttacks(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height) return List.of();
        var bars = score.measures();
        int end = bars.size() - 1;
        if (end < 3 || bars.get(end).cadenceQuarterBeats() > 1 || score.meterChanges().isEmpty())
            return List.of();
        var meter = score.meterChanges().get(score.meterChanges().size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4 || meter.measureIndex() > end - 2)
            return List.of();

        var terminal = bars.get(end);
        float nominal = (terminal.bottom() - terminal.top()) * height / 8.2f;
        if (nominal < 9 || nominal > 25) return List.of();
        var finale = local(score, end, width, height);
        float terminalWidth = (terminal.right() - terminal.left()) * width;
        if (terminalWidth < nominal * 4.3f
                || terminalWidth > nominal * 6.1f
                || finale.size() != 2
                || finale.stream().anyMatch(o -> !quarter(o.note()))
                || score.rests().stream().anyMatch(r -> r.measureIndex() == end)) return List.of();
        var fit =
                PrintedDirectionStaff.printedRules(
                        gray,
                        width,
                        height,
                        finale.get(0).x(),
                        terminal.bottom() * height - nominal * 2.1f,
                        nominal);
        if (fit == null) return List.of();
        float g = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * g, fit[0], g, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(
                        gray, width, staff, terminal.right() * width, false, false)
                < 0) return List.of();
        if (attack(finale, 0, gray, width, height, staff) == null) return List.of();

        List<Attack> contracted = new ArrayList<>();
        for (int i = end - 1; i >= meter.measureIndex(); i--) {
            var bar = bars.get(i);
            float span = (bar.right() - bar.left()) * width;
            if (bar.cadenceQuarterBeats() != 0
                    || span < g * 4.65f
                    || span > g * 7.15f
                    || Math.abs(bar.top() - terminal.top()) * height > g * .36f
                    || !interiorRule(gray, width, height, staff, bar, bars.get(i + 1))) break;
            final int barIndex = i;
            var notes = local(score, i, width, height);
            if (notes.size() != 4
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == barIndex)) break;
            var a = attack(notes, 0, gray, width, height, staff);
            var b = attack(notes, 2, gray, width, height, staff);
            if (a == null
                    || b == null
                    || a.direction() != b.direction()
                    || b.upper().x() - a.upper().x() < g * 2.30f
                    || b.upper().x() - a.upper().x() > g * 3.40f
                    || !paperSeparatesAttacks(gray, width, height, a, b, staff)) break;
            contracted.add(a);
            contracted.add(b);
        }
        if (contracted.size() < 4) return List.of();

        return contracted;
    }

    private static List<Owner> local(
            ScorePageInterpretation score, int bar, int width, int height) {
        var bound = score.measures().get(bar);
        var notes = new ArrayList<Owner>();
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (note.measureIndex() != bar) continue;
            float x =
                    (bound.left() + note.positionInMeasure() * (bound.right() - bound.left()))
                            * width;
            notes.add(new Owner(i, note, x, note.pageY() * height));
        }
        notes.sort(Comparator.comparingDouble(Owner::x));
        return notes;
    }

    private static boolean quarter(ScoreNoteEvent n) {
        return n.beamCount() == 0 && n.unbeamedDurationBeats() == 1;
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffCount() == 1
                && n.staffIndex() == 0
                && n.beamCount() <= 2
                && n.unbeamedDurationBeats() <= 1
                && n.augmentationDots() == 0
                && n.tupletDivisor() == 1
                && n.tupletNormalNotes() == 1
                && n.boundaryTies() == 0
                && !n.tiedFromPrevious()
                && !n.crossStaffBeam()
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static Attack attack(
            List<Owner> notes,
            int offset,
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff) {
        var first = notes.get(offset);
        var second = notes.get(offset + 1);
        var upper = first.y() < second.y() ? first : second;
        var lower = upper == first ? second : first;
        float gap = staff.gap();
        if (!ordinary(upper.note())
                || !ordinary(lower.note())
                || upper.note().staffStep() - lower.note().staffStep() < 4
                || upper.note().staffStep() - lower.note().staffStep() > 5
                || Math.abs(upper.x() - lower.x()) > gap * .16f
                || lower.y() - upper.y() < gap * 1.98f
                || lower.y() - upper.y() > gap * 2.78f) return null;

        var shafts =
                PrintedBeamShafts.at(
                        gray,
                        width,
                        height,
                        upper.x(),
                        upper.y(),
                        gap,
                        new PrintedBeamShafts.Budget(48_000));
        if (shafts.size() != 1) return null;
        var shaft = shafts.get(0);
        int direction = -shaft.direction();
        if (upper.note().stemDirection() != direction
                || lower.note().stemDirection() != 0 && lower.note().stemDirection() != direction)
            return null;

        for (int stem = shaft.left() - (direction < 0 ? 2 : 0); stem <= shaft.right(); stem++) {
            int end =
                    direction < 0
                            ? continuedLowerStemEnd(gray, width, height, stem, lower.y(), gap)
                            : shaft.endY();
            if (end < 0
                    || !ArtificialHarmonics.shaftCrossedDiamond(
                            gray,
                            width,
                            height,
                            upper.x(),
                            upper.y() - gap * 1.5f,
                            gap,
                            stem,
                            direction,
                            true)
                    || !lowerTouch(gray, width, height, lower, stem, direction, gap)
                    || !darkSpine(
                            gray,
                            width,
                            height,
                            stem,
                            Math.round(upper.y()),
                            Math.round(lower.y()),
                            .86f)) continue;
            var tipOwner = direction > 0 ? upper : lower;
            if (ArtificialHarmonics.unflaggedQuarterTip(
                    gray,
                    width,
                    height,
                    stem,
                    tipOwner.y() - gap * 1.5f,
                    tipOwner.y(),
                    end,
                    direction,
                    staff)) return new Attack(upper, lower, stem, end, direction);
        }
        return null;
    }

    private static boolean lowerTouch(
            byte[] gray, int width, int height, Owner note, int stem, int direction, float gap) {
        return ArtificialHarmonics.shaftCrossedDiamond(
                        gray,
                        width,
                        height,
                        note.x(),
                        note.y() - gap * 1.5f,
                        gap,
                        stem,
                        direction,
                        true)
                || ArtificialHarmonics.shaftCrossedDiamond(
                        gray, width, height, note.x(), note.y() - gap, gap, stem, direction, true);
    }

    private static boolean darkSpine(
            byte[] gray, int width, int height, int x, int start, int end, float fraction) {
        if (x < 0 || x >= width || start < 0 || end >= height || end < start) return false;
        int ink = 0;
        for (int y = start; y <= end; y++) if ((gray[y * width + x] & 255) < 180) ink++;
        return ink >= (end - start + 1) * fraction;
    }

    private static int continuedLowerStemEnd(
            byte[] gray, int width, int height, int stem, float stoppedY, float gap) {
        int first = Math.round(stoppedY + gap * .32f), last = Math.round(stoppedY + gap * 2.75f);
        if (stem <= 0 || stem + 1 >= width || first < 0 || last + 1 >= height) return -1;
        int y = first;
        while (y <= last && (gray[y * width + stem] & 255) < 180) y++;
        int end = y - 1;
        if (end - stoppedY < gap * 1.08f || y > last) return -1;
        int lowerStart = Math.round(stoppedY + gap * .58f);
        return darkSpine(gray, width, height, stem - 1, lowerStart, end - 2, .90f)
                        || darkSpine(gray, width, height, stem + 1, lowerStart, end - 2, .90f)
                ? end
                : -1;
    }

    private static boolean paperSeparatesAttacks(
            byte[] gray,
            int width,
            int height,
            Attack a,
            Attack b,
            PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int left = a.stem() + Math.round(gap * .88f), right = b.stem() - Math.round(gap * .88f);
        int top = Math.min(a.end(), b.end()) - Math.round(gap * .42f);
        int bottom = Math.max(a.end(), b.end()) + Math.round(gap * .42f);
        if (left < 0 || right >= width || top < 0 || bottom >= height || right - left < gap * .38f)
            return false;
        int clear = 0;
        for (int x = left; x <= right; x++) {
            int ink = 0;
            for (int y = top; y <= bottom; y++) {
                float phase = (staff.bottom() - y) / gap;
                int rule = Math.round(phase);
                if (rule >= 0 && rule <= 4 && Math.abs(phase - rule) <= .16f) continue;
                if ((gray[y * width + x] & 255) < 170) ink++;
            }
            clear = ink > gap * .14f ? 0 : clear + 1;
            if (clear >= gap * .43f) return true;
        }
        return false;
    }

    private static boolean interiorRule(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            MeasureRegion before,
            MeasureRegion after) {
        float expected = (before.right() + after.left()) * width * .5f;
        return ClosedSixteenthPairBeforeHalf.ownedSeparator(gray, width, height, staff, expected);
    }

    private static ScoreNoteEvent withQuarter(ScoreNoteEvent n, int direction) {
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                n.augmentationDots(),
                0,
                n.writtenAccidental(),
                1,
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
                direction,
                n.kind(),
                n.visualSize(),
                n.harmonicSemitones(),
                n.openStringTouch());
    }
}
