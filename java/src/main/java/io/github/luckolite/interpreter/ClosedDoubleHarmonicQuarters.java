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
        return apply(score, gray, null, width, height);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] gray, byte[] original, int width, int height) {
        var contracted = pairedAttacks(score, gray, original, width, height);
        var opening = openingHarmonicOffsets(score, gray, original, width, height);
        if (contracted.isEmpty() && opening.isEmpty()) return score;
        var renewed = new ArrayList<>(score.notes());
        for (var attack : contracted) {
            for (var owner : List.of(attack.upper(), attack.lower())) {
                var n = owner.note();
                if (!quarter(n) || n.stemDirection() == 0)
                    renewed.set(owner.index(), withQuarter(n, attack.direction()));
            }
        }
        for (int owner : opening.keySet()) {
            var note = score.notes().get(owner);
            if (!quarter(note)) renewed.set(owner, withQuarter(note, note.stemDirection()));
        }
        return renewed.equals(score.notes())
                ? score
                : score.withNotes(renewed, IntStream.range(0, renewed.size()).boxed().toList());
    }


    /**
     * A shaft, two stopped quarters and their touch heads can merge at the
     * first major-third diamond. Remove its unstemmed eighth-note alias.
     */
    static ScorePageInterpretation recoverMergedThirdTouch(
            ScorePageInterpretation score, byte[] labels, byte[] normalized, byte[] raw,
            int width, int height, Integer inheritedKey) {
        if (raw == null || normalized == null || labels == null || raw.length != (long) width * height
                || normalized.length != raw.length || labels.length != raw.length) return score;
        var bars = score.measures();
        var notes = new ArrayList<>(score.notes());
        var removed = new java.util.HashSet<Integer>();
        ScoreMeterChange meter = null;
        int section = 0;
        for (int measure = 1; measure + 1 < bars.size(); measure++) {
            while (section < score.meterChanges().size()
                    && score.meterChanges().get(section).measureIndex() <= measure)
                meter = score.meterChanges().get(section++);
            if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) continue;
            var region = bars.get(measure);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float span = (region.right() - region.left()) * width;
            final int index = measure;
            if (gap < 12 || gap > 23 || span < 7.8f * gap || span > 10.2f * gap
                    || region.cadenceQuarterBeats() != 0
                    || Math.abs(region.top() - bars.get(measure - 1).top()) * height > .32f * gap
                    || Math.abs(region.top() - bars.get(measure + 1).top()) * height > .32f * gap
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            var local = local(score, measure, width, height);
            if (local.size() != 5) continue;
            var alias = local.get(0);
            var a = local.get(1);
            var b = local.get(2);
            var c = local.get(3);
            var d = local.get(4);
            var up = a.y() < b.y() ? a : b;
            var low = up == a ? b : a;
            var down = c.y() < d.y() ? c : d;
            var bottom = down == c ? d : c;
            var n = alias.note();
            if (!ordinary(n) || n.stemDirection() != 0 || n.beamCount() != 1
                    || n.unbeamedDurationBeats() != 0 || n.articulations() != 0
                    || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || n.harmonicSemitones() != 0 || n.octaveShift() != 0 || n.openStringTouch() != null
                    || n.staffStep() != low.note().staffStep() + 2
                    || low.x() - alias.x() < .14f * gap || low.x() - alias.x() > .44f * gap
                    || low.y() - alias.y() < .97f * gap || low.y() - alias.y() > 1.35f * gap
                    || hasNoteSourceControl(score, alias.index())
                    || !compoundQuarterPair(up, low, 1, gap) || !compoundQuarterPair(down, bottom, -1, gap)
                    || bottom.x() - low.x() < 3.75f * gap || bottom.x() - low.x() > 4.85f * gap) continue;
            int key = keyAt(score, measure, inheritedKey == null ? 0 : inheritedKey);
            if (!printedMajorThird(low.note(), key) || !printedMajorThird(bottom.note(), key)
                    || !printedFourth(up.note(), key) || !printedFourth(down.note(), key)) continue;
            if (!mergedTouchThirds(labels, raw, width, height, bars, measure, gap, alias, up, low, down, bottom)
                    || !mergedTouchThirds(labels, normalized, width, height, bars, measure,
                            gap, alias, up, low, down, bottom)) continue;
            removed.add(alias.index());
            notes.set(up.index(), harmonicQuarter(up.note(), 1, 24));
            notes.set(low.index(), harmonicQuarter(low.note(), 1, 28));
            notes.set(down.index(), harmonicQuarter(down.note(), -1, 24));
            notes.set(bottom.index(), harmonicQuarter(bottom.note(), -1, 28));
        }
        if (removed.isEmpty()) return score;
        var owners = IntStream.range(0, notes.size()).filter(i -> !removed.contains(i)).boxed().toList();
        return score.withNotes(owners.stream().map(notes::get).toList(), owners);
    }

    private static boolean compoundQuarterPair(Owner upper, Owner lower, int direction, float gap) {
        if (upper.note().staffStep() - lower.note().staffStep() != 4
                || Math.abs(upper.x() - lower.x()) > .16f * gap
                || lower.y() - upper.y() < 1.85f * gap || lower.y() - upper.y() > 2.28f * gap) return false;
        for (var owner : List.of(upper, lower)) {
            var note = owner.note();
            if (!ordinary(note) || !quarter(note) || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || note.stemDirection() != direction || note.articulations() != 0
                    || note.openStringTouch() != null || note.harmonicSemitones() != 0
                            && note.harmonicSemitones() != (owner == upper ? 24 : 28)) return false;
        }
        return true;
    }

    private static boolean mergedTouchThirds(byte[] labels, byte[] ink, int width, int height,
            List<MeasureRegion> bars, int measure, float nominal, Owner alias,
            Owner firstUpper, Owner firstLower, Owner lastUpper, Owner lastLower) {
        var bar = bars.get(measure);
        var fit = PrintedDirectionStaff.printedRules(ink, width, height,
                (bar.left() + bar.right()) * width * .5f, bar.bottom() * height - 2.1f * nominal, nominal);
        if (fit == null) return false;
        float floor = fit[0], gap = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        if (!interiorRule(ink, width, height, staff, bars.get(measure - 1), bar)
                || !interiorRule(ink, width, height, staff, bar, bars.get(measure + 1))
                || filledStaffHead(ink, width, height, alias, staff)
                || List.of(firstUpper, firstLower, lastUpper, lastLower).stream()
                        .anyMatch(head -> !filledStaffHead(ink, width, height, head, staff))) return false;
        var up = compoundQuarterShaft(labels, ink, width, height, staff, firstUpper, firstLower, 1, alias);
        var down = compoundQuarterShaft(labels, ink, width, height, staff, lastUpper, lastLower, -1, null);
        return up != null && down != null && paperSeparatesAttacks(ink, width, height, up, down, staff)
                && PrintedCadenceDurations.emptyOutsideChord(ink, width, staff, bar.left() * width,
                        bar.right() * width, firstLower.x(), lastLower.x(), false);
    }

    private static Attack compoundQuarterShaft(byte[] labels, byte[] ink, int width, int height,
            PlayingTechniqueDetector.Staff staff, Owner upper, Owner lower, int direction, Owner alias) {
        float gap = staff.gap();
        var tip = direction > 0 ? upper : lower;
        var shafts = PrintedBeamShafts.at(ink, width, height, tip.x(), tip.y(), gap,
                new PrintedBeamShafts.Budget(72_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -direction) return null;
        var shaft = shafts.get(0);
        float highY = upper.y() - 1.5f * gap;
        float lowY = alias == null ? lower.y() - gap : alias.y();
        if (!labeledTouch(labels, ink, width, height, upper.x(), highY, gap)
                || alias == null && !labeledTouch(labels, ink, width, height, lower.x(), lowY, gap)) return null;
        for (int col = shaft.left(); col <= shaft.right(); col++) {
            if (!headJoinsShaft(ink, width, upper, col, gap) || !headJoinsShaft(ink, width, lower, col, gap)
                    || !darkSpine(ink, width, height, col, Math.round(upper.y()), Math.round(lower.y()), .94f)
                    || alias != null && !mergedCounter(ink, width, height, lower.x(), alias.y(), gap, col)
                    || !ArtificialHarmonics.unflaggedQuarterTip(ink, width, height, col, highY,
                            tip.y(), shaft.endY(), direction, staff)) continue;
            return new Attack(upper, lower, col, shaft.endY(), direction);
        }
        return null;
    }

    static void retainOpposedThirdRoles(ScorePageInterpretation score, List<Map<String, Object>> events,
            int width, int height, float[] beats, double[] starts, int[] keys) {
        if (score.notes().size() != events.size()) return;
        var bars = score.measures();
        for (int measure = 1; measure + 1 < bars.size(); measure++) {
            if (beats[measure] != 2) continue;
            var region = bars.get(measure);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            if (gap < 12 || gap > 23) continue;
            var row = local(score, measure, width, height);
            if (row.size() != 4) continue;
            var a = row.get(0);
            var b = row.get(1);
            var c = row.get(2);
            var d = row.get(3);
            var up = a.y() < b.y() ? a : b;
            var low = up == a ? b : a;
            var down = c.y() < d.y() ? c : d;
            var bottom = down == c ? d : c;
            if (!compoundQuarterPair(up, low, 1, gap) || !compoundQuarterPair(down, bottom, -1, gap)
                    || bottom.x() - low.x() < 3.75f * gap || bottom.x() - low.x() > 4.85f * gap
                    || !provedThirdRole(up, events, starts[measure], 24)
                    || !provedThirdRole(low, events, starts[measure], 28)
                    || !provedThirdRole(down, events, starts[measure] + 1, 24)
                    || !provedThirdRole(bottom, events, starts[measure] + 1, 28)) continue;
            int key = keys[measure];
            if (!printedFourth(up.note(), key) || !printedMajorThird(low.note(), key)
                    || !printedFourth(down.note(), key) || !printedMajorThird(bottom.note(), key)) continue;
            for (var first : List.of(up, low))
                writeStackRole(events.get(first.index()), first, starts[measure], key, 1, false);
            for (var last : List.of(down, bottom))
                writeStackRole(events.get(last.index()), last, starts[measure] + 1, key, -1, false);
        }
    }

    private static boolean provedThirdRole(
            Owner owner, List<Map<String, Object>> events, double onset, int harmonics) {
        var event = events.get(owner.index());
        return owner.note().harmonicSemitones() == harmonics && owner.note().octaveShift() == 0
                && Integer.valueOf(owner.index()).equals(event.get("sourceNoteIndex"))
                && Double.valueOf(onset).equals(event.get("startBeat"))
                && Double.valueOf(1).equals(event.get("durationBeats"))
                && Boolean.FALSE.equals(event.get("durationFallback"));
    }

    private static boolean labeledTouch(byte[] labels, byte[] ink, int width, int height,
            float x, float y, float gap) {
        return ArtificialHarmonics.symbolDiamond(labels, ink, width, height, x, y, gap)
                && ArtificialHarmonics.enclosedCounter(ink, width, height, x, y, .69f * gap, .23f * gap);
    }

    private static boolean mergedCounter(byte[] ink, int width, int height, float x, float y, float gap, int stem) {
        if (!ArtificialHarmonics.enclosedCounter(ink, width, height, x, y, .69f * gap, .23f * gap))
            return false;
        // The measured counter slants toward the upper shaft as it drops.
        float top = boundedPaperCenter(ink, width, x, Math.round(y - .10f * gap), gap, stem);
        float bottom = boundedPaperCenter(ink, width, x, Math.round(y + .10f * gap), gap, stem);
        return Float.isFinite(top) && Float.isFinite(bottom)
                && bottom - top >= .17f * gap && bottom - top <= .39f * gap;
    }

    private record HeaderedStack(Attack first, Attack second, Owner alias) {}

    /** A late 2/4 header can contain two complete upstem harmonic pairs after the clef and meter. */
    static ScorePageInterpretation recoverHeaderedStack(
            ScorePageInterpretation score, byte[] labels, byte[] normalized, byte[] raw,
            int width, int height, int initialKey) {
        int bar = stackedHeaderBar(score);
        if (bar < 0) return score;
        var stack = headeredStack(score, labels, normalized, raw, width, height, bar, true);
        if (stack == null || !headeredStackPitches(stack, keyAt(score, bar, initialKey))) return score;
        var notes = new ArrayList<>(score.notes());
        for (var member : List.of(stack.first().upper(), stack.first().lower(), stack.second().upper()))
            notes.set(member.index(), harmonicQuarter(member.note(), 1, 24));
        var lower = stack.second().lower();
        notes.set(lower.index(), harmonicQuarter(
                lower.note().withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_DOUBLE_FLAT), 1, 28));
        var owners = IntStream.range(0, notes.size()).filter(i -> i != stack.alias().index()).boxed().toList();
        return score.withNotes(owners.stream().map(notes::get).toList(), owners);
    }

    static void retainHeaderedStackRoles(
            ScorePageInterpretation score, List<Map<String, Object>> events, List<PlayingTechniqueDetector.Word> words,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height,
            float[] beats, double[] starts, int[] keys, List<SourceLocalAccidental.PrintedMark> marks) {
        int bar = stackedHeaderBar(score);
        if (bar < 0 || beats[bar] != 2 || events.size() != score.notes().size()) return;
        var stack = headeredStack(score, labels, normalized, raw, width, height, bar, false);
        if (stack == null || !headeredStackPitches(stack, keys[bar])) return;
        var a = stack.first();
        var b = stack.second();
        if (List.of(a.upper(), a.lower(), b.upper(), b.lower()).stream().anyMatch(owner ->
                !Integer.valueOf(owner.index()).equals(events.get(owner.index()).get("sourceNoteIndex")))) return;
        for (var chord : List.of(a, b)) {
            for (var owner : List.of(chord.upper(), chord.lower()))
                writeStackRole(events.get(owner.index()), owner, starts[bar] + (chord == a ? 0 : 1),
                        keys[bar], 1, chord == a);
        }
        marks.add(new SourceLocalAccidental.PrintedMark(b.lower().index(), ScoreNoteEvent.ACCIDENTAL_DOUBLE_FLAT));
        float gap = (score.measures().get(bar).bottom() - score.measures().get(bar).top()) * height / 8.2f;
        for (var chord : List.of(a, b)) {
            float x = chord.lower().x();
            var upper = PrintedFingeringDigits.harmonicStack(words, raw, normalized, width, height, x,
                    chord.end() - gap * 3.45f, chord.end() + gap * .45f, gap);
            var lower = quarterFingers(words, raw, normalized, width, height, gap, chord, false);
            if (upper != null && upper.provedTouch())
                events.get(chord.upper().index()).put("compoundHarmonicFingering", upper.fingering("above"));
            if (lower != null && (lower.provedTouch() || chord == b && matchesFollowingThree(
                    score, words, raw, normalized, width, height, gap, bar + 1, lower)))
                events.get(chord.lower().index()).put("compoundHarmonicFingering", lower.fingering("below"));
        }
    }

    private static void writeStackRole(Map<String, Object> event, Owner owner,
            double onset, int key, int direction, boolean opposite) {
        int semitones = owner.note().harmonicSemitones();
        int base = ScoreTiePitchGuard.writtenMidi(owner.note().diatonicPitchIdentity(),
                owner.note().writtenAccidental(), key);
        event.remove("octaveShift");
        event.remove("writtenPlacement");
        event.put("midi", base + semitones);
        event.put("writtenMidi", base);
        event.put("harmonicTouchMidi", base + (semitones == 28 ? 4 : 5));
        event.put(semitones == 28 ? "artificialHarmonicThird" : "artificialHarmonicFourth", true);
        event.put(direction > 0 ? "sharedUpstemHarmonicQuarter" : "sharedDownstemHarmonicQuarter", true);
        event.put("startBeat", onset);
        event.put("durationBeats", 1.0);
        event.put("durationFallback", false);
        if (opposite) event.put("oppositeShaftHarmonicQuarter", true);
    }


    static ScorePageInterpretation recoverMixedStemStack(
            ScorePageInterpretation score, byte[] labels, byte[] normalized, byte[] raw,
            int width, int height, int initialKey) {
        int header = stackedHeaderBar(score);
        if (header < 0) return score;
        var notes = new ArrayList<>(score.notes());
        for (int bar = header + 1; bar + 1 < score.measures().size(); bar++) {
            var stack = mixedStemStack(score, labels, normalized, raw, width, height, bar, true);
            if (stack.isEmpty()) continue;
            int key = keyAt(score, bar, initialKey);
            if (!mixedStemPitches(stack, key)) continue;
            for (var chord : stack) {
                var high = chord.upper();
                var low = chord.lower();
                notes.set(high.index(), harmonicQuarter(high.note(), chord.direction(), 24));
                notes.set(low.index(), harmonicQuarter(low.note(), chord.direction(), chord.direction() > 0 ? 28 : 24));
            }
        }
        if (notes.equals(score.notes())) return score;
        return score.withNotes(notes, IntStream.range(0, notes.size()).boxed().toList());
    }

    static void retainMixedStemStackRoles(
            ScorePageInterpretation score, List<Map<String, Object>> events, List<PlayingTechniqueDetector.Word> words,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height,
            float[] beats, double[] starts, int[] keys) {
        int header = stackedHeaderBar(score);
        if (header < 0 || events.size() != score.notes().size()) return;
        for (int bar = header + 1; bar + 1 < score.measures().size(); bar++) {
            if (beats[bar] != 2) continue;
            var stack = mixedStemStack(score, labels, normalized, raw, width, height, bar, false);
            if (stack.isEmpty() || !mixedStemPitches(stack, keys[bar])) continue;
            var region = score.measures().get(bar);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            if (stack.stream().flatMap(chord -> java.util.stream.Stream.of(chord.upper(), chord.lower()))
                    .anyMatch(owner -> !Integer.valueOf(owner.index()).equals(events.get(owner.index()).get("sourceNoteIndex"))))
                continue;
            PrintedFingeringDigits.HarmonicStack firstUpper = null;
            for (int i = 0; i < stack.size(); i++) {
                var chord = stack.get(i);
                for (var owner : List.of(chord.upper(), chord.lower()))
                    writeStackRole(events.get(owner.index()), owner, starts[bar] + i, keys[bar], chord.direction(), false);
                float edge = chord.direction() > 0 ? chord.end() : chord.upper().y() - 1.5f * gap;
                var high = PrintedFingeringDigits.harmonicStack(words, raw, normalized, width, height, chord.upper().x(),
                        edge - gap * (chord.direction() > 0 ? 3.45f : 4.55f), edge + gap * .42f, gap);
                if (i == 0) firstUpper = high;
                if (i == 1 && high == null)
                    high = PrintedFingeringDigits.matchingHarmonicStack(raw, normalized, width, height,
                            chord.upper().x(), edge - 4.35f * gap, edge - .72f * gap, gap, firstUpper);
                var low = quarterFingers(words, raw, normalized, width, height, gap, chord, false);
                if (high != null && high.provedTouch())
                    events.get(chord.upper().index()).put("compoundHarmonicFingering", high.fingering("above"));
                if (low != null && low.provedTouch())
                    events.get(chord.lower().index()).put("compoundHarmonicFingering", low.fingering("below"));
            }
        }
    }

    private static List<Attack> mixedStemStack(ScorePageInterpretation score, byte[] labels,
            byte[] normalized, byte[] raw, int width, int height, int barIndex, boolean beforeRecovery) {
        if (raw == null || normalized == null || labels == null || raw.length != (long) width * height
                || normalized.length != raw.length || labels.length != raw.length) return List.of();
        var bars = score.measures();
        var bar = bars.get(barIndex);
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        if (bar.cadenceQuarterBeats() != 0 || nominal < 10 || nominal > 25
                || span < nominal * 8.3f || span > nominal * 11.8f
                || Math.abs(bar.top() - bars.get(barIndex - 1).top()) * height > nominal * .35f
                || Math.abs(bar.top() - bars.get(barIndex + 1).top()) * height > nominal * .35f
                || score.rests().stream().anyMatch(rest -> rest.measureIndex() == barIndex)) return List.of();
        var members = local(score, barIndex, width, height);
        if (members.size() != 4) return List.of();
        var pair = new ArrayList<Owner>(4);
        for (int i = 0; i < 4; i += 2) {
            var a = members.get(i);
            var b = members.get(i + 1);
            var high = a.y() < b.y() ? a : b;
            var low = high == a ? b : a;
            if (Math.abs(high.x() - low.x()) > nominal * .16f
                    || high.note().staffStep() - low.note().staffStep() != 5) return List.of();
            pair.add(high);
            pair.add(low);
        }
        float distance = pair.get(2).x() - pair.get(0).x();
        if (distance < nominal * 3.8f || distance > nominal * 5.6f) return List.of();
        for (int i = 0; i < pair.size(); i++) {
            var owner = pair.get(i);
            var n = owner.note();
            if (!ordinary(withQuarter(n, n.stemDirection()))
                    || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE || n.stemDirection() != (i < 2 ? 1 : -1)
                    || n.openStringTouch() != null || n.articulations() != 0
                    || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || hasNoteSourceControl(score, owner.index())) return List.of();
            if (beforeRecovery) {
                if (n.octaveShift() != 2 || n.harmonicSemitones() != 0
                        || (i < 3 ? !quarter(n) : n.beamCount() != 3 || n.unbeamedDurationBeats() != 0))
                    return List.of();
            } else if (!quarter(n) || n.octaveShift() != 0 || n.harmonicSemitones() != (i == 1 ? 28 : 24))
                return List.of();
        }
        var printed = proveMixedStemStack(raw, width, height, bars, barIndex, nominal, pair);
        var corrected = proveMixedStemStack(normalized, width, height, bars, barIndex, nominal, pair);
        return printed.equals(corrected) ? printed : List.of();
    }

    /** Recover the exact printed roles after the closing measure's clocks have been assigned. */
    static ScorePageInterpretation retainReverseTerminalStackRoles(
            ScorePageInterpretation score, List<Map<String, Object>> events,
            List<PlayingTechniqueDetector.Word> words, byte[] raw, byte[] normalized,
            int width, int height, float[] beats, double[] starts, int[] keys) {
        int index = score.measures().size() - 2;
        int header = stackedHeaderBar(score);
        if (header < 0 || index <= header || index >= beats.length || beats[index] != 2
                || events.size() != score.notes().size()) return score;
        var stack = reverseTerminalStack(score, normalized, raw, width, height, index);
        if (stack.isEmpty()) return score;
        int key = keys[index];
        var bar = score.measures().get(index);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        for (var chord : stack) {
            int upperMarks = chord.upper().note().articulations();
            int lowerMarks = chord.lower().note().articulations();
            if ((upperMarks | lowerMarks) != 0
                    && (chord.direction() != 1 || upperMarks != NoteArticulation.STACCATO
                            || lowerMarks != NoteArticulation.STACCATO
                            || !printedFingeringClearsStaccato(raw, width, height, chord.lower(), gap)
                            || !printedFingeringClearsStaccato(normalized, width, height, chord.lower(), gap)))
                return score;
            if (!printedFourth(chord.upper().note(), key)
                    || !(chord.direction() > 0 ? printedMajorThird(chord.lower().note(), key)
                            : printedFourth(chord.lower().note(), key))) return score;
            for (var owner : List.of(chord.upper(), chord.lower())) {
                var event = events.get(owner.index());
                if (!Integer.valueOf(owner.index()).equals(event.get("sourceNoteIndex"))
                        || !Integer.valueOf(index).equals(event.get("measureIndex"))
                        || !(event.get("durationBeats") instanceof Number duration)
                        || duration.doubleValue() != 1
                        || Boolean.TRUE.equals(event.get("durationFallback"))) return score;
            }
        }
        var notes = new ArrayList<>(score.notes());
        for (int i = 0; i < stack.size(); i++) {
            var chord = stack.get(i);
            for (var owner : List.of(chord.upper(), chord.lower())) {
                int semitones = chord.direction() > 0 && owner == chord.lower() ? 28 : 24;
                var note = harmonicQuarter(owner.note().withArticulations(0), chord.direction(), semitones);
                notes.set(owner.index(), note);
                writeStackRole(events.get(owner.index()), new Owner(owner.index(), note, owner.x(), owner.y()),
                        starts[index] + i, key, chord.direction(), false);
            }
            float edge = chord.direction() > 0 ? chord.end() : chord.upper().y() - 1.5f * gap;
            var upper = PrintedFingeringDigits.harmonicStack(words, raw, normalized, width, height, chord.upper().x(),
                    edge - gap * (chord.direction() > 0 ? 3.45f : 4.55f), edge + gap * .42f, gap);
            var lower = quarterFingers(words, raw, normalized, width, height, gap, chord, false);
            if (upper != null && upper.provedTouch())
                events.get(chord.upper().index()).put("compoundHarmonicFingering", upper.fingering("above"));
            if (lower != null && lower.provedTouch())
                events.get(chord.lower().index()).put("compoundHarmonicFingering", lower.fingering("below"));
        }
        var retained = IntStream.range(0, notes.size()).boxed().toList();
        // These source values were already literal quarters. The rhythm observations still apply.
        return score.withNotes(notes, retained).withClockContextSource(score.clockContextSource());
    }

    private static boolean printedFingeringClearsStaccato(
            byte[] ink, int width, int height, Owner lower, float gap) {
        int left = Math.round(lower.x() - .95f * gap);
        int right = Math.round(lower.x() + .95f * gap);
        int top = Math.round(lower.y() + .63f * gap);
        int bottom = Math.round(lower.y() + 4.15f * gap);
        var window = RawNumeralComponents.capture(
                ink, width, height, left, top, right, bottom, 175, () -> {}, () -> {});
        if (window == null || window.components().size() != 2) return false;
        var digits = window.components().stream()
                .sorted(Comparator.comparingInt(glyph -> glyph.top)).toList();
        var first = digits.get(0);
        var second = digits.get(1);
        // Require paper before two complete tall glyphs. Any third component, including
        // a compact staccato dot between the head and fingerings, keeps the original mark.
        return first.top - top >= .35f * gap
                && second.top - first.bottom >= .20f * gap
                && second.top - first.bottom <= .52f * gap
                && digits.stream().allMatch(glyph -> glyph.left > left && glyph.right < right
                        && glyph.bottom < bottom && glyph.bottom - glyph.top + 1 >= .85f * gap
                        && glyph.bottom - glyph.top + 1 <= 1.4f * gap
                        && glyph.right - glyph.left + 1 <= .96f * gap
                        && Math.abs((glyph.left + glyph.right) * .5f - lower.x()) <= .45f * gap);
    }

    /** The closing 2/4 can put two full downstem fourths before an upstem third and fourth. */
    private static List<Attack> reverseTerminalStack(
            ScorePageInterpretation score, byte[] normalized, byte[] raw,
            int width, int height, int index) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || index + 2 != score.measures().size())
            return List.of();
        var bars = score.measures();
        var bar = bars.get(index);
        var after = bars.get(index + 1);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        if (bar.cadenceQuarterBeats() != 0 || after.cadenceQuarterBeats() != 1 || gap < 10 || gap > 25
                || span < 8.15f * gap || span > 10.1f * gap
                || Math.abs(bar.top() - bars.get(index - 1).top()) * height > .35f * gap
                || Math.abs(bar.top() - after.top()) * height > .35f * gap
                || score.rests().stream().anyMatch(r -> r.measureIndex() == index)) return List.of();
        var members = local(score, index, width, height);
        if (members.size() != 4) return List.of();
        var owners = new ArrayList<Owner>(4);
        for (int i = 0; i < 4; i += 2) {
            var a = members.get(i);
            var b = members.get(i + 1);
            var high = a.y() < b.y() ? a : b;
            var low = high == a ? b : a;
            if (Math.abs(high.x() - low.x()) > gap * (i == 0 ? .28f : .17f)
                    || high.note().staffStep() - low.note().staffStep() != (i == 0 ? 5 : 4))
                return List.of();
            owners.add(high);
            owners.add(low);
        }
        float distance = owners.get(3).x() - owners.get(1).x();
        if (distance < 3.4f * gap || distance > 4.15f * gap) return List.of();
        for (int i = 0; i < 4; i++) {
            var owner = owners.get(i);
            var n = owner.note();
            int direction = i < 2 ? -1 : 1;
            if (!ordinary(n) || !quarter(n) || n.stemDirection() != direction
                    || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE || n.openStringTouch() != null
                    || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || (n.articulations() & ~NoteArticulation.STACCATO) != 0
                    || hasNoteSourceControl(score, owner.index())) return List.of();
            int shifted = i == 1 || i == 2 ? 2 : 0;
            if (n.octaveShift() != shifted || n.harmonicSemitones() != 0) return List.of();
        }
        var original = proveReverseTerminalStack(raw, width, height, bars, index, gap, owners);
        var corrected = proveReverseTerminalStack(normalized, width, height, bars, index, gap, owners);
        return original.equals(corrected) ? original : List.of();
    }

    private static List<Attack> proveReverseTerminalStack(
            byte[] ink, int width, int height, List<MeasureRegion> bars,
            int index, float nominal, List<Owner> owners) {
        var bar = bars.get(index);
        var fit = PrintedDirectionStaff.printedRules(ink, width, height,
                (bar.left() + bar.right()) * width * .5f, bar.bottom() * height - 2.1f * nominal, nominal);
        if (fit == null) return List.of();
        float gap = fit[1], floor = fit[0];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        if (!interiorRule(ink, width, height, staff, bars.get(index - 1), bar)
                || !interiorRule(ink, width, height, staff, bar, bars.get(index + 1))
                || owners.stream().anyMatch(head -> !filledStaffHead(ink, width, height, head, staff)))
            return List.of();
        var high = owners.get(0);
        var low = owners.get(1);
        var down = alignedHarmonicQuarter(ink, width, height, staff, high, low, -1);
        if (down == null && high.x() > low.x() + .12f * gap && high.x() <= low.x() + .28f * gap) {
            // The far filled lobe can inherit the ledger edge. Test the same head on the
            // lower owner's horizontal axis and require the complete shared printed shaft.
            var aligned = new Owner(high.index(), high.note(), low.x(), high.y());
            if (!filledStaffHead(ink, width, height, aligned, staff)) return List.of();
            var proved = alignedHarmonicQuarter(ink, width, height, staff, aligned, low, -1);
            if (proved != null) down = new Attack(high, low, proved.stem(), proved.end(), proved.direction());
        }
        var up = alignedHarmonicQuarter(ink, width, height, staff, owners.get(2), owners.get(3), 1);
        if (down == null || up == null || !paperSeparatesAttacks(ink, width, height, down, up, staff)
                || !PrintedCadenceDurations.emptyOutsideChord(ink, width, staff, bar.left() * width,
                        bar.right() * width, down.lower().x(), up.lower().x(), false)) return List.of();
        return List.of(down, up);
    }

    private static List<Attack> proveMixedStemStack(byte[] ink, int width, int height,
            List<MeasureRegion> bars, int index, float nominal, List<Owner> heads) {
        var bar = bars.get(index);
        var fit = PrintedDirectionStaff.printedRules(ink, width, height,
                (bar.left() + bar.right()) * width * .5f, bar.bottom() * height - nominal * 2.1f, nominal);
        if (fit == null) return List.of();
        float gap = fit[1], floor = fit[0];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        if (!interiorRule(ink, width, height, staff, bars.get(index - 1), bar)
                || !interiorRule(ink, width, height, staff, bar, bars.get(index + 1))
                || heads.stream().anyMatch(head -> !filledStaffHead(ink, width, height, head, staff))) return List.of();
        var up = alignedHarmonicQuarter(ink, width, height, staff, heads.get(0), heads.get(1), 1);
        var down = alignedHarmonicQuarter(ink, width, height, staff, heads.get(2), heads.get(3), -1);
        if (up == null || down == null || !paperSeparatesAttacks(ink, width, height, up, down, staff)
                || !PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, bar.left() * width, bar.right() * width,
                        up.lower().x(), down.lower().x(), false)) return List.of();
        return List.of(up, down);
    }

    private static Attack alignedHarmonicQuarter(byte[] ink, int width, int height,
            PlayingTechniqueDetector.Staff staff, Owner upper, Owner lower, int direction) {
        float gap = staff.gap(), floor = staff.bottom();
        var budget = new PrintedBeamShafts.Budget(72_000);
        var high = PrintedBeamShafts.at(ink, width, height, upper.x(), upper.y(), gap, budget);
        var low = PrintedBeamShafts.at(ink, width, height, lower.x(), lower.y(), gap, budget);
        if (budget.refused() || high.size() != 1 || low.size() != 1) return null;
        var a = high.get(0);
        var b = low.get(0);
        if (a.direction() != -direction || b.direction() != -direction
                || Math.abs(a.endY() - b.endY()) > gap * .13f) return null;
        float hiTouch = floor - (upper.note().staffStep() + 3) * gap * .5f;
        float loTouch = floor - (lower.note().staffStep() + (direction > 0 ? 2 : 3)) * gap * .5f;
        int tip = direction > 0 ? a.endY() : b.endY();
        for (int col = Math.max(a.left(), b.left()); col <= Math.min(a.right(), b.right()); col++) {
            if (!darkSpine(ink, width, height, col, Math.round(upper.y()), Math.round(lower.y()), .94f)
                    || !ArtificialHarmonics.shaftCrossedDiamond(
                            ink, width, height, upper.x(), hiTouch, gap, col, direction, true)
                    || !ArtificialHarmonics.shaftCrossedDiamond(
                            ink, width, height, lower.x(), loTouch, gap, col, direction, true)) continue;
            var tipOwner = direction > 0 ? upper : lower;
            if (ArtificialHarmonics.unflaggedQuarterTip(ink, width, height, col, hiTouch,
                    tipOwner.y(), tip, direction, staff)) return new Attack(upper, lower, col, tip, direction);
        }
        return null;
    }

    private static boolean mixedStemPitches(List<Attack> stack, int key) {
        var up = stack.get(0);
        var down = stack.get(1);
        return printedFourth(up.upper().note(), key) && printedMajorThird(up.lower().note(), key)
                && printedFourth(down.upper().note(), key) && printedFourth(down.lower().note(), key);
    }
    private static boolean matchesFollowingThree(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Word> words,
            byte[] raw, byte[] normalized, int width, int height, float gap, int bar,
            PrintedFingeringDigits.HarmonicStack target) {
        if (target.touch() != 3) return false;
        var next = local(score, bar, width, height);
        if (next.size() != 4) return false;
        var low = next.get(2).y() > next.get(3).y() ? next.get(2) : next.get(3);
        var high = low == next.get(2) ? next.get(3) : next.get(2);
        if (Math.abs(high.x() - low.x()) > .16f * gap
                || high.note().staffStep() - low.note().staffStep() != 5) return false;
        var match = PrintedFingeringDigits.harmonicStack(words, raw, normalized, width, height, low.x(),
                low.y() + .8f * gap, low.y() + 4.4f * gap, gap);
        return match != null && PrintedFingeringDigits.repeatedHarmonicThree(target, match, width);
    }

    private static int stackedHeaderBar(ScorePageInterpretation score) {
        var meters = score.meterChanges();
        if (meters.isEmpty()) return -1;
        var meter = meters.get(meters.size() - 1);
        return meter.numerator() == 2 && meter.denominator() == 4
                && meter.measureIndex() > 0 && meter.measureIndex() + 1 < score.measures().size()
                ? meter.measureIndex() : -1;
    }

    private static HeaderedStack headeredStack(
            ScorePageInterpretation score, byte[] labels, byte[] normalized, byte[] raw,
            int width, int height, int bar, boolean beforeRecovery) {
        if (raw == null || normalized == null || labels == null || raw.length != (long) width * height
                || normalized.length != raw.length || labels.length != raw.length) return null;
        var region = score.measures().get(bar);
        var next = score.measures().get(bar + 1);
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        float span = (region.right() - region.left()) * width;
        if (nominal < 10 || nominal > 25 || span < 22 * nominal || span > 38 * nominal
                || Math.abs(next.top() - region.top()) * height > nominal * .35f
                || region.cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(rest -> rest.measureIndex() == bar)) return null;
        var members = local(score, bar, width, height);
        if (members.size() != (beforeRecovery ? 5 : 4)) return null;
        var aLower = members.get(0);
        var aUpper = members.get(1);
        var second = members.subList(beforeRecovery ? 3 : 2, members.size())
                .stream().sorted(Comparator.comparingDouble(Owner::y)).toList();
        var bUpper = second.get(0);
        var bLower = second.get(1);
        var alias = beforeRecovery ? members.get(2) : null;
        if (aLower.note().positionInMeasure() < .57f || bLower.note().positionInMeasure() < .77f
                || aUpper.note().staffStep() - aLower.note().staffStep() != 4
                || bUpper.note().staffStep() != aUpper.note().staffStep()
                || bLower.note().staffStep() != aLower.note().staffStep()
                || aLower.note().staffStep() >= 0
                || aUpper.x() - aLower.x() < nominal * 1.06f || aUpper.x() - aLower.x() > nominal * 1.52f
                || Math.abs(bUpper.x() - bLower.x()) > nominal * .16f
                || bLower.x() - aLower.x() < nominal * 4.7f || bLower.x() - aLower.x() > nominal * 6.7f)
            return null;
        for (var owner : List.of(aLower, aUpper, bUpper, bLower)) {
            var n = owner.note();
            if (!ordinary(n) || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || n.articulations() != 0 || n.openStringTouch() != null || hasNoteSourceControl(score, owner.index()))
                return null;
            if (beforeRecovery && (n.harmonicSemitones() != 0
                    || !quarter(n) && (owner != aUpper || n.beamCount() != 1 || n.unbeamedDurationBeats() != 0)))
                return null;
            if (!beforeRecovery && (!quarter(n) || n.stemDirection() != 1 || n.octaveShift() != 0
                    || n.harmonicSemitones() != (owner == bLower ? 28 : 24))) return null;
        }
        if (beforeRecovery && (!ordinary(withQuarter(alias.note(), 1))
                || alias.note().beamCount() != 0 || alias.note().unbeamedDurationBeats() != 2
                || alias.note().octaveShift() != 0 || alias.note().harmonicSemitones() != 0
                || alias.note().stemDirection() != 1 || alias.note().articulations() != 0
                || alias.note().staffStep() - bLower.note().staffStep() != 2
                || hasNoteSourceControl(score, alias.index())
                || Math.abs(alias.x() - bLower.x()) > nominal * .30f)) return null;

        var printed = proveHeaderStack(labels, raw, width, height, region, next, nominal,
                aUpper, aLower, bUpper, bLower, alias);
        var corrected = proveHeaderStack(labels, normalized, width, height, region, next, nominal,
                aUpper, aLower, bUpper, bLower, alias);
        return printed != null && printed.equals(corrected) ? printed : null;
    }

    private static HeaderedStack proveHeaderStack(byte[] labels, byte[] gray, int width, int height,
            MeasureRegion bar, MeasureRegion next, float nominal,
            Owner aUpper, Owner aLower, Owner bUpper, Owner bLower, Owner alias) {
        var fit = PrintedDirectionStaff.printedRules(gray, width, height,
                (bar.left() + bar.right()) * width * .5f, bar.bottom() * height - nominal * 2.1f, nominal);
        if (fit == null) return null;
        float gap = fit[1], floor = fit[0];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        int top = Math.round(staff.top());
        var metric = MeterFontMatcher.readCrop(gray, width, height, new MeterChangeDetector.Crop(
                Math.round(aLower.x() - 4.25f * gap), Math.round(top - .32f * gap),
                Math.round(aLower.x() - 2.15f * gap), Math.round(floor + .52f * gap), top, gap));
        if (!metric.text().equals("2/4") || metric.topScore() < .86f || metric.bottomScore() < .86f
                || !interiorRule(gray, width, height, staff, bar, next)) return null;
        for (var owner : List.of(aUpper, aLower, bUpper, bLower))
            if (Math.abs(owner.y() - (floor - owner.note().staffStep() * gap * .5f)) > gap * .28f
                    || !ArtificialHarmonics.filledQuarterCore(gray, width, height, owner.x(), owner.y(), gap)) return null;

        var a = stackedUpstem(labels, gray, width, height, staff, aUpper, aLower, null, true);
        var b = stackedUpstem(labels, gray, width, height, staff, bUpper, bLower, alias, false);
        return a != null && b != null && paperSeparatesAttacks(gray, width, height, a, b, staff)
                && twoDetachedFlats(gray, width, height, bLower, floor, gap)
                ? new HeaderedStack(a, b, alias) : null;
    }

    private static Attack stackedUpstem(byte[] labels, byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, Owner upper, Owner lower, Owner alias, boolean offset) {
        float gap = staff.gap(), floor = staff.bottom();
        var budget = new PrintedBeamShafts.Budget(72_000);
        var shafts = PrintedBeamShafts.at(gray, width, height, lower.x(), lower.y(), gap, budget);
        if (budget.refused() || shafts.size() != 1) return null;
        var shared = shafts.get(0);
        if (shared.direction() != -1) return null;
        float highTouch = floor - (upper.note().staffStep() + 3) * gap * .5f;
        float lowTouch = floor - (lower.note().staffStep() + (offset ? 3 : 2)) * gap * .5f;
        float touchX = alias == null ? lower.x() - .20f * gap : alias.x();
        float touchY = alias == null ? lowTouch + .055f * gap : alias.y();
        if (!offset && Math.abs(touchY - lowTouch) > .18f * gap) return null;
        for (var column : shared.columns()) {
            int x = column.x(), end = column.endY(), side = offset ? -1 : 1;
            if (Math.abs(shared.endY() - end) > 1
                    || !darkSpine(gray, width, height, x, end, Math.round(lower.y() - .13f * gap), .96f)
                    || !headJoinsShaft(gray, width, upper, x, gap)
                    || !ArtificialHarmonics.shaftCrossedDiamond(
                            gray, width, height, upper.x(), highTouch, gap, x, side, true)
                    || !ArtificialHarmonics.unflaggedQuarterTip(
                            gray, width, height, x, highTouch, upper.y(), end, 1, staff))
                continue;
            if (offset ? LedgerCrossedHarmonicDiamond.proved(
                            labels, gray, width, height, lower.x(), lowTouch, gap, x, 1)
                    : slantedTouchCounter(gray, width, height, touchX, touchY, gap, x))
                return new Attack(upper, lower, x, end, 1);
        }
        return null;
    }

    private static boolean headJoinsShaft(byte[] gray, int width, Owner owner, int stem, float gap) {
        int center = Math.round(owner.x());
        if (Math.abs(center - stem) < .40f * gap || Math.abs(center - stem) > .90f * gap) return false;
        int y = Math.round(owner.y());
        for (int dy = -2; dy <= 2; dy++) {
            int ink = rowInk(gray, width, Math.min(stem, center), Math.max(stem, center), y + dy);
            if (ink == Math.abs(center - stem) + 1) return true;
        }
        return false;
    }

    /** The ledger and two filled heads conceal the outer corners, but leave a diagonal closed counter. */
    static boolean slantedTouchCounter(
            byte[] gray, int width, int height, float x, float y, float gap, int stem) {
        if (stem - x < .55f * gap || stem - x > .93f * gap
                || !ArtificialHarmonics.enclosedCounter(gray, width, height, x, y, .70f * gap, .23f * gap))
            return false;
        float top = boundedPaperCenter(gray, width, x, Math.round(y - .16f * gap), gap, stem);
        float bottom = boundedPaperCenter(gray, width, x, Math.round(y + .16f * gap), gap, stem);
        return Float.isFinite(top) && Float.isFinite(bottom)
                && bottom - top > .15f * gap && bottom - top < .45f * gap;
    }

    private static float boundedPaperCenter(byte[] gray, int width, float x, int row, float gap, int stem) {
        int left = Math.round(x - .50f * gap), right = stem - 2;
        int run = -1;
        for (int col = left; col <= right; col++) {
            if ((gray[row * width + col] & 255) >= 185) {
                if (run < 0) run = col;
            } else if (run >= 0) {
                int length = col - run;
                if (run > left && length >= .17f * gap && length <= .49f * gap)
                    return (run + col - 1) * .5f;
                run = -1;
            }
        }
        return Float.NaN;
    }

    private static boolean twoDetachedFlats(byte[] gray, int width, int height, Owner owner, float floor, float gap) {
        int left = Math.round(owner.x() - 3.58f * gap), right = Math.round(owner.x() - .98f * gap);
        int top = Math.round(floor + .20f * gap), bottom = Math.round(owner.y() + .88f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height || top >= bottom) return false;
        var graph = RawNumeralComponents.captureLongWindow(gray, width, height, left, top, right, bottom, 154);
        var glyphs = graph.components().stream().filter(g -> g.left > left && g.right < right
                && g.right - g.left >= .65f * gap && g.right - g.left <= 1.17f * gap
                && g.top <= top + .10f * gap && g.bottom < bottom).sorted(Comparator.comparingInt(g -> g.left)).toList();
        if (glyphs.size() != 2) return false;
        var first = glyphs.get(0);
        var second = glyphs.get(1);
        if (second.left - first.left < .86f * gap || second.left - first.left > 1.36f * gap
                || Math.abs(first.bottom - second.bottom) > .22f * gap) return false;
        for (var g : glyphs) {
            boolean proved = false;
            for (int dl = 0; dl <= Math.round(.20f * gap); dl++)
                for (int dr = 0; dr <= Math.round(.20f * gap); dr++)
                    if (PrintedFlatGlyph.matches(gray, width, height, g.left + dl,
                            Math.round(floor - .40f * gap), g.right - dr,
                            Math.round(owner.y() + .70f * gap), gap)) proved = true;
            if (!proved) return false;
        }
        return true;
    }

    private static boolean headeredStackPitches(HeaderedStack stack, int key) {
        var lower = stack.second().lower().note();
        int inherited = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        return stack.first().upper().note().writtenAccidental() == inherited
                && stack.first().lower().note().writtenAccidental() == inherited
                && stack.second().upper().note().writtenAccidental() == inherited
                && (lower.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                        || lower.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_DOUBLE_FLAT)
                && printedFourth(stack.first().upper().note(), key)
                && printedFourth(stack.first().lower().note(), key)
                && printedFourth(stack.second().upper().note(), key)
                && printedMajorThird(lower.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_DOUBLE_FLAT), key);
    }

    private static int keyAt(ScorePageInterpretation score, int bar, int fallback) {
        int key = fallback;
        for (var change : score.keyChanges()) if (change.measureIndex() <= bar) key = change.fifths();
        return key;
    }

    private static ScoreNoteEvent harmonicQuarter(ScoreNoteEvent note, int stem, int semitones) {
        return withQuarter(note, stem).withOctaveShift(0).withHarmonicSemitones(semitones);
    }
    /** Engrave two proved upstem chords without changing the retained sounded quarter clocks. */
    static ScorePageInterpretation retainUpstemRoles(
            ScorePageInterpretation score, List<Map<String, Object>> events,
            List<PlayingTechniqueDetector.Word> words, byte[] raw,
            byte[] normalized, int width, int height, float[] beats, double[] starts, int[] keys) {
        var bars = score.measures();
        var notes = score.notes();
        var updated = new ArrayList<>(notes);
        boolean found = false;
        for (int bar = 1; bar + 1 < bars.size(); bar++) {
            if (beats[bar] != 2 || bars.get(bar).cadenceQuarterBeats() != 0) continue;
            int measure = bar;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == measure)) continue;
            var members = local(score, bar, width, height);
            if (members.size() != 4 || members.stream().anyMatch(member ->
                    !quarter(member.note()) || member.note().stemDirection() != 1
                    || member.note().openStringTouch() != null
                    || member.note().clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || member.note().articulations() != 0
                    || !Integer.valueOf(member.index()).equals(events.get(member.index()).get("sourceNoteIndex"))))
                continue;
            var first = upstemRoles(raw, width, height, bars, bar, members);
            var second = upstemRoles(normalized, width, height, bars, bar, members);
            if (!first.equals(second) || first.size() != 2) continue;
            var a = first.get(0);
            var b = first.get(1);
            if (!quarterRoleIdentity(a.lower(), events, starts[bar], keys[bar], false)
                    || !quarterRoleIdentity(a.upper(), events, starts[bar], keys[bar], false)
                    || !quarterRoleIdentity(b.lower(), events, starts[bar] + 1, keys[bar], true)
                    || !quarterRoleIdentity(b.upper(), events, starts[bar] + 1, keys[bar], false)) continue;
            writeUpstemRole(a.lower(), updated, events, keys[bar], false);
            writeUpstemRole(a.upper(), updated, events, keys[bar], false);
            writeUpstemRole(b.lower(), updated, events, keys[bar], true);
            writeUpstemRole(b.upper(), updated, events, keys[bar], false);
            var region = bars.get(bar);
            retainQuarterFingerings(events, words, raw, normalized, width, height,
                    (region.bottom() - region.top()) * height / 8.2f, a, b);
            found = true;
        }
        if (!found) return score;
        var owners = IntStream.range(0, notes.size()).boxed().toList();
        return score.withNotes(updated, owners).withClockContextSource(score.clockContextSource());
    }

    private static void retainQuarterFingerings(List<Map<String, Object>> events,
            List<PlayingTechniqueDetector.Word> words, byte[] raw, byte[] normalized,
            int width, int height, float gap, Attack a, Attack b) {
        var aUpper = quarterFingers(words, raw, normalized, width, height, gap, a, true);
        var bUpper = quarterFingers(words, raw, normalized, width, height, gap, b, true);
        var aLower = quarterFingers(words, raw, normalized, width, height, gap, a, false);
        var bLower = quarterFingers(words, raw, normalized, width, height, gap, b, false);
        if (aUpper != null && aUpper.provedTouch())
            events.get(a.upper().index()).put("compoundHarmonicFingering", aUpper.fingering("above"));
        if (bUpper != null && bUpper.provedTouch())
            events.get(b.upper().index()).put("compoundHarmonicFingering", bUpper.fingering("above"));
        boolean repeated = aLower != null && bLower != null
                && PrintedFingeringDigits.repeatedHarmonicThree(aLower, bLower, width);
        if (aLower != null && (aLower.provedTouch() || repeated))
            events.get(a.lower().index()).put("compoundHarmonicFingering", aLower.fingering("below"));
        if (bLower != null && (bLower.provedTouch() || repeated))
            events.get(b.lower().index()).put("compoundHarmonicFingering", bLower.fingering("below"));
    }

    private static PrintedFingeringDigits.HarmonicStack quarterFingers(
            List<PlayingTechniqueDetector.Word> words, byte[] raw, byte[] normalized,
            int width, int height, float gap, Attack attack, boolean above) {
        float x = above ? attack.upper().x() : attack.lower().x();
        float edge = above ? attack.end() : attack.lower().y();
        return PrintedFingeringDigits.harmonicStack(words, raw, normalized, width, height, x,
                edge + gap * (above ? -3.4f : .8f), edge + gap * (above ? .42f : 4.4f), gap);
    }

    private static List<Attack> upstemRoles(byte[] gray, int width, int height,
            List<MeasureRegion> bars, int bar, List<Owner> members) {
        var region = bars.get(bar);
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 25
                || Math.abs(region.top() - bars.get(bar - 1).top()) * height > nominal * .36f
                || Math.abs(region.top() - bars.get(bar + 1).top()) * height > nominal * .36f) return List.of();
        var rules = PrintedDirectionStaff.printedRules(gray, width, height,
                (region.left() + region.right()) * width * .5f,
                region.bottom() * height - 2.1f * nominal, nominal);
        if (rules == null) return List.of();
        float gap = rules[1];
        var staff = new PlayingTechniqueDetector.Staff(rules[0] - 4 * gap, rules[0], gap, 0, 1);
        if (!interiorRule(gray, width, height, staff, bars.get(bar - 1), region)
                || !interiorRule(gray, width, height, staff, region, bars.get(bar + 1))
                || members.stream().anyMatch(n -> !filledStaffHead(gray, width, height, n, staff)))
            return List.of();
        var a = attack(members, 0, gray, width, height, staff);
        var b = attack(members, 2, gray, width, height, staff);
        if (a == null || b == null || a.direction() != 1 || b.direction() != 1
                || !paperSeparatesAttacks(gray, width, height, a, b, staff)
                || !provedLowerTouch(gray, width, height, a, gap, 3)
                || !provedLowerTouch(gray, width, height, b, gap, 2)) return List.of();
        return List.of(a, b);
    }

    private static boolean provedLowerTouch(
            byte[] gray, int width, int height, Attack attack, float gap, int steps) {
        var lower = attack.lower();
        return ArtificialHarmonics.shaftCrossedDiamond(gray, width, height, lower.x(),
                lower.y() - steps * gap * .5f, gap, attack.stem(), 1, true);
    }

    private static boolean quarterRoleIdentity(
            Owner owner, List<Map<String, Object>> events, double onset, int key, boolean third) {
        var n = owner.note();
        var event = events.get(owner.index());
        int written = ScoreTiePitchGuard.writtenMidi(n.diatonicPitchIdentity(), n.writtenAccidental(), key);
        int touch = ScoreTiePitchGuard.writtenMidi(n.diatonicPitchIdentity() + (third ? 2 : 3),
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        return n.octaveShift() == (third ? 0 : 2) && n.harmonicSemitones() == (third ? 28 : 0)
                && touch - written == (third ? 4 : 5)
                && Integer.valueOf(written + (third ? 28 : 24)).equals(event.get("midi"))
                && Double.valueOf(onset).equals(event.get("startBeat"))
                && Double.valueOf(1).equals(event.get("durationBeats"))
                && Boolean.FALSE.equals(event.get("durationFallback"))
                && Boolean.FALSE.equals(event.get("clefInferred"))
                && !event.containsKey("writtenPlacement");
    }

    private static void writeUpstemRole(Owner owner, List<ScoreNoteEvent> notes,
            List<Map<String, Object>> events, int key, boolean third) {
        var n = owner.note();
        var event = events.get(owner.index());
        int written = ScoreTiePitchGuard.writtenMidi(n.diatonicPitchIdentity(), n.writtenAccidental(), key);
        event.remove("octaveShift");
        event.put("writtenMidi", written);
        event.put("harmonicTouchMidi", written + (third ? 4 : 5));
        event.put(third ? "artificialHarmonicThird" : "artificialHarmonicFourth", true);
        event.put("sharedUpstemHarmonicQuarter", true);
        notes.set(owner.index(), n.withOctaveShift(0).withHarmonicSemitones(third ? 28 : 24));
    }

    /**
     * Recover the unflagged shared downstems before a closing upstem cadence.
     * A ledger can fill a diamond while the ordinary label records the shaft as a flag.
     */
    static ScorePageInterpretation retainClosingDownstemRoles(
            ScorePageInterpretation score, List<Map<String, Object>> events, byte[] labels,
            byte[] raw, byte[] normalized, int width, int height, float[] beats, double[] starts, int[] keys,
            List<SourceLocalAccidental.PrintedMark> printedMarks) {
        var bars = score.measures();
        int last = bars.size() - 1, first = last - 2;
        if (first < 1 || beats[first] != 2 || beats[first + 1] != 2 || beats[last] != 1
                || bars.get(last).cadenceQuarterBeats() != 1) return score;
        var meters = score.meterChanges();
        if (meters.isEmpty()) return score;
        var meter = meters.get(meters.size() - 1);
        if (meter.measureIndex() > first || meter.numerator() != 2 || meter.denominator() != 4
                || score.rests().stream().anyMatch(rest -> rest.measureIndex() >= first)) return score;
        var a = local(score, first, width, height);
        var b = local(score, first + 1, width, height);
        var terminal = local(score, last, width, height);
        if (!closingOwners(a, events, starts[first], true)
                || !closingOwners(b, events, starts[first + 1], false)
                || terminal.size() != 2 || terminal.stream().anyMatch(n -> !quarter(n.note())
                        || n.note().stemDirection() != 1 || n.note().octaveShift() != 2)) return score;
        var source = closingDownstems(bars, first, a, b, terminal, labels, raw, width, height);
        if (source.size() != 3 || !source.equals(
                closingDownstems(bars, first, a, b, terminal, labels, normalized, width, height))) return score;
        if (!closedDownstemPitches(source.get(0), keys[first], true)
                || !closedDownstemPitches(source.get(1), keys[first], false)
                || !closedDownstemPitches(source.get(2), keys[first + 1], false)) return score;
        var notes = new ArrayList<>(score.notes());
        for (int i = 0; i < source.size(); i++) {
            var chord = source.get(i);
            int key = keys[first + i / 2];
            double onset = starts[first] + i;
            writeClosingDownstem(chord.lower(), notes, events, key, onset, i == 0);
            writeClosingDownstem(chord.upper(), notes, events, key, onset, false);
        }
        var firstLower = source.get(0).lower();
        var region = bars.get(first);
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        var query = new ArtificialHarmonics.TouchHead(firstLower.note(), firstLower.x(), firstLower.y(), gap);
        var literals = OmrScoreInterpreter.harmonicTouchAccidentals(
                labels, normalized, raw, width, height, score, List.of(query));
        if (firstLower.note().writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                && Integer.valueOf(ScoreNoteEvent.ACCIDENTAL_FLAT).equals(literals.get(firstLower.note())))
            printedMarks.add(new SourceLocalAccidental.PrintedMark(firstLower.index(), ScoreNoteEvent.ACCIDENTAL_FLAT));
        var owners = IntStream.range(0, notes.size()).boxed().toList();
        return score.withNotes(notes, owners).withClockContextSource(score.clockContextSource());
    }


    /** Resolves two complete unflagged downstem quarters when a folded shaft edge was read as a flag. */
    static ScorePageInterpretation retainClosedFourthPairs(
            ScorePageInterpretation score, List<Map<String, Object>> events, byte[] labels, byte[] raw,
            byte[] normalized, int width, int height, float[] beats, double[] starts, int[] keys) {
        var meters = score.meterChanges();
        if (meters.isEmpty() || raw == null || normalized == null || labels == null
                || raw.length != (long) width * height || normalized.length != raw.length
                || labels.length != raw.length || events.size() != score.notes().size()) return score;
        var meter = meters.get(meters.size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4) return score;
        var revised = new ArrayList<>(score.notes());
        var bars = score.measures();
        for (int bar = Math.max(1, meter.measureIndex() + 1); bar + 1 < bars.size(); bar++) {
            final int measure = bar;
            if (beats[bar] != 2 || bars.get(bar).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == measure)) continue;
            var owners = local(score, bar, width, height);
            if (!foldedQuarterOwners(owners, events, starts[bar])) continue;
            var first = opticalFourthPairs(bars, bar, owners, labels, raw, width, height);
            if (first.isEmpty()) continue;
            var second = opticalFourthPairs(bars, bar, owners, labels, normalized, width, height);
            if (second.size() != first.size()) continue;
            boolean agrees = true;
            for (int i = 0; i < first.size(); i++) {
                var a = first.get(i);
                var b = second.get(i);
                if (Math.abs(a.stem() - b.stem()) > 1 || Math.abs(a.end() - b.end()) > 2
                        || !closedDownstemPitches(a, keys[bar], false)) agrees = false;
            }
            if (!agrees) continue;
            for (int i = 0; i < 2; i++) {
                var chord = first.get(i);
                writeClosingDownstem(chord.lower(), revised, events, keys[bar], starts[bar] + i, false);
                writeClosingDownstem(chord.upper(), revised, events, keys[bar], starts[bar] + i, false);
            }
        }
        if (revised.equals(score.notes())) return score;
        return score.withNotes(revised, IntStream.range(0, revised.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static boolean foldedQuarterOwners(List<Owner> owners, List<Map<String, Object>> events,
            double firstBeat) {
        if (owners.size() != 4) return false;
        var upperLast = owners.get(2).y() < owners.get(3).y() ? owners.get(2) : owners.get(3);
        for (int i = 0; i < owners.size(); i++) {
            var owner = owners.get(i);
            var note = owner.note();
            var event = events.get(owner.index());
            boolean folded = owner == upperLast;
            if (!ordinary(note) || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || note.stemDirection() != -1 || note.octaveShift() != 2 || note.harmonicSemitones() != 0
                    || note.openStringTouch() != null || note.articulations() != 0
                    || (folded ? note.beamCount() != 1 || note.unbeamedDurationBeats() != 0 : !quarter(note))
                    || !Integer.valueOf(owner.index()).equals(event.get("sourceNoteIndex"))
                    || !Boolean.FALSE.equals(event.get("durationFallback")) || event.containsKey("writtenPlacement")
                    || !Double.valueOf(firstBeat + i / 2 + .25).equals(event.get("startBeat"))
                    || !Double.valueOf(folded ? .5 : 1).equals(event.get("durationBeats"))) return false;
        }
        return true;
    }

    private static List<Attack> opticalFourthPairs(List<MeasureRegion> bars, int index, List<Owner> owners,
            byte[] labels, byte[] ink, int width, int height) {
        var region = bars.get(index);
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 25) return List.of();
        var rails = PrintedDirectionStaff.printedRules(ink, width, height,
                (region.left() + region.right()) * width * .5f,
                region.bottom() * height - 2.1f * nominal, nominal);
        if (rails == null || Math.abs(rails[1] - nominal) > nominal * .12f) return List.of();
        float gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - gap * 4, rails[0], gap, 0, 1);
        for (int i = index - 1; i <= index; i++) {
            if (Math.abs(bars.get(i).top() - bars.get(i + 1).top()) * height > gap * .36f
                    || !interiorRule(ink, width, height, staff, bars.get(i), bars.get(i + 1))) return List.of();
        }
        var a = closingDownstem(owners, 0, 3, labels, ink, width, height, staff);
        var b = foldedDownstem(owners.get(2), owners.get(3), labels, ink, width, height, staff);
        if (a == null || b == null || b.stem() - a.stem() < gap * 3.2f || b.stem() - a.stem() > gap * 7.5f
                || a.upper().note().staffStep() - a.lower().note().staffStep() != 5
                || b.upper().note().staffStep() - b.lower().note().staffStep() != 5
                || a.upper().note().staffStep() - b.upper().note().staffStep()
                        != a.lower().note().staffStep() - b.lower().note().staffStep()
                || !paperSeparatesAttacks(ink, width, height, a, b, staff)) return List.of();
        return List.of(a, b);
    }

    private static Attack foldedDownstem(Owner one, Owner two, byte[] labels, byte[] ink,
            int width, int height, PlayingTechniqueDetector.Staff staff) {
        var upper = one.y() < two.y() ? one : two;
        var lower = upper == one ? two : one;
        float gap = staff.gap();
        if (Math.abs(upper.x() - lower.x()) > gap * .17f || !filledStaffHead(ink, width, height, upper, staff)
                || !filledStaffHead(ink, width, height, lower, staff)) return null;
        float lowTouch = staff.bottom() - (lower.note().staffStep() + 3) * gap * .5f;
        float highTouch = staff.bottom() - (upper.note().staffStep() + 3) * gap * .5f;
        if (!ArtificialHarmonics.symbolDiamond(labels, ink, width, height, lower.x(), lowTouch, gap)
                || !ArtificialHarmonics.symbolDiamond(labels, ink, width, height, upper.x(), highTouch, gap))
            return null;
        var budget = new PrintedBeamShafts.Budget(85_000);
        var top = PrintedBeamShafts.at(ink, width, height, upper.x(), upper.y(), gap, budget);
        var base = PrintedBeamShafts.at(ink, width, height, lower.x(), lower.y(), gap, budget);
        if (budget.refused() || top.size() != 1 || base.size() != 1
                || top.get(0).direction() != 1 || base.get(0).direction() != 1) return null;
        var upperStem = top.get(0);
        var lowerStem = base.get(0);
        int end = lowerStem.endY();
        if (end - upperStem.endY() < gap * .26f || end - upperStem.endY() > gap * .66f) return null;
        for (int stem = Math.max(upperStem.left(), lowerStem.left());
                stem <= Math.min(upperStem.right(), lowerStem.right()); stem++) {
            if (darkSpine(ink, width, height, stem, Math.round(highTouch + .24f * gap), end - 2, .93f)
                    && ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, upper.x(), highTouch,
                            gap, stem, -1, true)
                    && ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, lower.x(), lowTouch,
                            gap, stem, -1, true)
                    && ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, stem, lowTouch, lower.y(), end, -1, staff))
                return new Attack(upper, lower, stem, end, -1);
        }
        return null;
    }

    private static boolean closingOwners(
            List<Owner> notes, List<Map<String, Object>> events, double firstBeat, boolean aliasBar) {
        if (notes.size() != 4) return false;
        for (int i = 0; i < notes.size(); i++) {
            var owner = notes.get(i);
            var note = owner.note();
            boolean flaggedAlias = aliasBar && i >= 2;
            int stem = !aliasBar && i >= 2 ? 1 : -1;
            var event = events.get(owner.index());
            if (!ordinary(note) || note.stemDirection() != stem || note.openStringTouch() != null
                    || note.articulations() != 0 || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || (flaggedAlias ? note.beamCount() != 1 || note.unbeamedDurationBeats() != 0 : !quarter(note))
                    || !Integer.valueOf(owner.index()).equals(event.get("sourceNoteIndex"))
                    || !Boolean.FALSE.equals(event.get("durationFallback"))
                    || !Boolean.FALSE.equals(event.get("clefInferred"))
                    || event.containsKey("writtenPlacement")
                    || !Double.valueOf(firstBeat + i / 2 + (aliasBar ? .5 : 0)).equals(event.get("startBeat"))
                    || !Double.valueOf(flaggedAlias ? .5 : 1).equals(event.get("durationBeats")))
                return false;
        }
        return true;
    }

    private static List<Attack> closingDownstems(
            List<MeasureRegion> bars, int first, List<Owner> a, List<Owner> b, List<Owner> terminal,
            byte[] labels, byte[] ink, int width, int height) {
        var region = bars.get(first);
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 25) return List.of();
        var rails = PrintedDirectionStaff.printedRules(ink, width, height,
                (region.left() + region.right()) * width * .5f,
                region.bottom() * height - 2.1f * nominal, nominal);
        if (rails == null) return List.of();
        float gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - gap * 4, rails[0], gap, 0, 1);
        for (int i = first - 1; i < first + 2; i++)
            if (Math.abs(bars.get(i).top() - bars.get(i + 1).top()) * height > gap * .36f
                    || !interiorRule(ink, width, height, staff, bars.get(i), bars.get(i + 1))) return List.of();
        var firstQuarter = closingDownstem(a, 0, 2, labels, ink, width, height, staff);
        var secondQuarter = closingDownstem(a, 2, 3, labels, ink, width, height, staff);
        var nextQuarter = closingDownstem(b, 0, 3, labels, ink, width, height, staff);
        var nextUp = attack(b, 2, ink, width, height, staff);
        if (firstQuarter == null || secondQuarter == null || nextQuarter == null || nextUp == null
                || nextUp.direction() != 1 || attack(terminal, 0, ink, width, height, staff) == null
                || !paperSeparatesAttacks(ink, width, height, firstQuarter, secondQuarter, staff)) return List.of();
        return List.of(firstQuarter, secondQuarter, nextQuarter);
    }

    private static Attack closingDownstem(List<Owner> owners, int offset, int lowerSteps,
            byte[] labels, byte[] ink, int width, int height, PlayingTechniqueDetector.Staff staff) {
        var one = owners.get(offset);
        var two = owners.get(offset + 1);
        var upper = one.y() < two.y() ? one : two;
        var lower = upper == one ? two : one;
        float gap = staff.gap();
        int separation = upper.note().staffStep() - lower.note().staffStep();
        if (separation < 4 || separation > 5 || Math.abs(upper.x() - lower.x()) > gap * .16f
                || !filledStaffHead(ink, width, height, upper, staff)
                || !filledStaffHead(ink, width, height, lower, staff)) return null;
        float lowTouch = staff.bottom() - (lower.note().staffStep() + lowerSteps) * gap * .5f;
        float highTouch = staff.bottom() - (upper.note().staffStep() + 3) * gap * .5f;
        if (!ArtificialHarmonics.symbolDiamond(labels, ink, width, height, lower.x(), lowTouch, gap))
            return null;
        var budget = new PrintedBeamShafts.Budget(85_000);
        var a = PrintedBeamShafts.at(ink, width, height, upper.x(), upper.y(), gap, budget);
        var b = PrintedBeamShafts.at(ink, width, height, lower.x(), lower.y(), gap, budget);
        if (a.size() != 1 || b.size() != 1 || a.get(0).direction() != 1 || b.get(0).direction() != 1
                || Math.abs(a.get(0).endY() - b.get(0).endY()) > gap * .12f) return null;
        int end = b.get(0).endY();
        boolean clearHigh = ArtificialHarmonics.symbolDiamond(labels, ink, width, height, upper.x(), highTouch, gap);
        for (int stem = Math.max(a.get(0).left(), b.get(0).left());
                stem <= Math.min(a.get(0).right(), b.get(0).right()); stem++) {
            boolean high = clearHigh;
            if (!high && highTouch < staff.top() - gap * .5f)
                for (int delta = -2; delta <= 2; delta++)
                    high |= LedgerCrossedHarmonicDiamond.proved(
                            labels, ink, width, height, upper.x(), highTouch + delta, gap, stem, -1);
            if (high
                    && darkSpine(ink, width, height, stem, Math.round(highTouch + gap * .22f), end, .96f)
                    && ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, stem, lowTouch, lower.y(), end, -1, staff))
                return new Attack(upper, lower, stem, end, -1);
        }
        return null;
    }

    private static boolean closedDownstemPitches(Attack attack, int key, boolean lowerThird) {
        return printedFourth(attack.upper().note(), key)
                && (lowerThird ? printedMajorThird(attack.lower().note(), key) : printedFourth(attack.lower().note(), key));
    }

    private static boolean printedMajorThird(ScoreNoteEvent note, int key) {
        int stopped = ScoreTiePitchGuard.writtenMidi(note.diatonicPitchIdentity(), note.writtenAccidental(), key);
        int touch = ScoreTiePitchGuard.writtenMidi(
                note.diatonicPitchIdentity() + 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        return stopped >= 0 && stopped + 28 <= 127 && touch - stopped == 4;
    }

    private static void writeClosingDownstem(Owner owner, List<ScoreNoteEvent> notes,
            List<Map<String, Object>> events, int key, double onset, boolean third) {
        var note = owner.note();
        var event = events.get(owner.index());
        int written = ScoreTiePitchGuard.writtenMidi(note.diatonicPitchIdentity(), note.writtenAccidental(), key);
        int harmonic = third ? 28 : 24;
        event.remove("octaveShift");
        event.put("writtenMidi", written);
        event.put("harmonicTouchMidi", written + (third ? 4 : 5));
        event.put("midi", written + harmonic);
        event.put("startBeat", onset);
        event.put("durationBeats", 1.0);
        event.put(third ? "artificialHarmonicThird" : "artificialHarmonicFourth", true);
        event.put("sharedDownstemHarmonicQuarter", true);
        notes.set(owner.index(), withQuarter(note, -1).withOctaveShift(0).withHarmonicSemitones(harmonic));
    }

    /** Proves the short final quarter from both stopped heads and their touch diamonds. */
    static ScorePageInterpretation closeTerminalPair(
            ScorePageInterpretation score, byte[] normalized, byte[] original, int width, int height) {
        if (width <= 0 || height <= 0 || original == null || normalized == null
                || original.length != (long) width * height || normalized.length != original.length
                || score.measures().size() < 3 || score.meterChanges().isEmpty()) return score;
        var bars = score.measures();
        int last = bars.size() - 1;
        var meters = score.meterChanges();
        var meter = meters.get(meters.size() - 1);
        var bar = bars.get(last);
        var previous = bars.get(last - 1);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        if (meter.numerator() != 2 || meter.denominator() != 4 || meter.measureIndex() >= last
                || bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 25
                || span < gap * 5.4f || span > gap * 7.5f
                || Math.abs(bar.top() - previous.top()) * height > gap * .34f
                || score.rests().stream().anyMatch(r -> r.measureIndex() == last)) return score;
        var heads = local(score, last, width, height);
        if (heads.size() != 2 || heads.stream().noneMatch(o -> o.note().beamCount() != 0)
                || heads.stream().anyMatch(o -> !terminalAlias(o.note()))) return score;
        var upper = heads.get(0).y() < heads.get(1).y() ? heads.get(0) : heads.get(1);
        var lower = upper == heads.get(0) ? heads.get(1) : heads.get(0);
        if (lower.note().boundaryTies() != 0
                || upper.note().boundaryTies() != 0 && upper.note().boundaryTies() != 8) return score;
        var untied = new Owner(upper.index(), upper.note().withBoundaryTies(0), upper.x(), upper.y());
        var pair = List.of(untied, lower);
        int a = terminalStem(normalized, width, height, previous, bar, pair, gap);
        int b = terminalStem(original, width, height, previous, bar, pair, gap);
        if (a < 0 || b < 0 || Math.abs(a - b) > gap * .12f) return score;
        var notes = new ArrayList<>(score.notes());
        Integer key = null;
        for (var change : score.keyChanges()) {
            if (change.measureIndex() > last) break;
            key = change.fifths();
        }
        boolean fourths = key != null && printedFourth(upper.note(), key) && printedFourth(lower.note(), key);
        for (var owner : pair) {
            var quarter = withQuarter(owner.note(), -1);
            notes.set(owner.index(), fourths ? quarter.withOctaveShift(0).withHarmonicSemitones(24) : quarter);
        }
        var revised = score.withNotes(notes, IntStream.range(0, notes.size()).boxed().toList());
        var regions = new ArrayList<>(bars);
        regions.set(last, new MeasureRegion(bar.left(), bar.right(), bar.top(), bar.bottom(), 1));
        return new ScorePageInterpretation(
                regions, revised.notes(), revised.firstMeasureNumber(), revised.keyChanges(),
                revised.tempoChanges(), revised.meterChanges(), revised.rests(), revised.techniqueChanges(),
                revised.dynamicChanges(), revised.playbackDirections(), revised.expressiveEvents(),
                revised.slurSource(), revised.openingPickup(), revised.tremoloSource(),
                revised.multiRestGroups(), revised.beamSource(), score.clockContextSource());
    }

    private static boolean printedFourth(ScoreNoteEvent note, int fifths) {
        int diatonic = note.diatonicPitchIdentity();
        int stopped = ScoreTiePitchGuard.writtenMidi(diatonic, note.writtenAccidental(), fifths);
        int touch = ScoreTiePitchGuard.writtenMidi(diatonic + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, fifths);
        return stopped >= 0 && stopped + 24 <= 127 && touch - stopped == 5;
    }

    private static boolean terminalAlias(ScoreNoteEvent note) {
        return note.octaveShift() == 2 && note.harmonicSemitones() == 0
                && note.openStringTouch() == null && note.stemDirection() == -1
                && note.articulations() == 0 && !note.compactOpening()
                && note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN
                && (quarter(note) || note.beamCount() > 0 && note.unbeamedDurationBeats() == 0);
    }

    private static int terminalStem(
            byte[] gray, int width, int height, MeasureRegion previous, MeasureRegion bar,
            List<Owner> pair, float nominal) {
        float left = bar.left() * width;
        float right = bar.right() * width;
        var fit = PrintedDirectionStaff.printedRules(gray, width, height, right - 3.4f * nominal,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (fit == null || Math.abs(fit[1] - nominal) > nominal * .12f) return -1;
        float gap = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
        int open = PrintedCadenceDurations.boundaryColumn(gray, width, staff,
                (previous.right() * width + left) * .5f, true, false);
        int end = PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true);
        var upper = pair.get(0);
        var lower = pair.get(1);
        if (open < 0 || end < 0 || upper.x() - open < gap * 2f || upper.x() - open > gap * 3.4f
                || end - lower.x() < gap * 3.2f || end - lower.x() > gap * 5.15f
                || !PrintedCadenceDurations.emptyOutsideChord(
                        gray, width, staff, open, end, upper.x(), lower.x(), true)
                || !clearDeparture(gray, width, height, staff, upper, end)) return -1;
        var printed = attack(pair, 0, gray, width, height, staff);
        return printed != null && printed.direction() == -1
                        && ArtificialHarmonics.shaftCrossedDiamond(gray, width, height, lower.x(),
                                lower.y() - gap * 1.5f, gap, printed.stem(), -1, true)
                ? printed.stem() : -1;
    }

    /** An ordinary tie or slur leaves ink between the head and the closing rules. */
    private static boolean clearDeparture(byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, Owner head, int end) {
        float gap = staff.gap();
        int left = Math.round(head.x() + 1.12f * gap);
        int right = Math.round(end - .55f * gap);
        int top = Math.round(head.y() - 1.30f * gap);
        int bottom = Math.round(head.y() + 1.30f * gap);
        if (left < 0 || right >= width || right - left < gap * 1.35f || top < 0 || bottom >= height)
            return false;
        for (int x = left; x <= right; x++) {
            for (int y = top; y <= bottom; y++) {
                float rule = (y - staff.top()) / gap;
                int nearest = Math.round(rule);
                if (nearest >= 0 && nearest <= 4 && Math.abs(rule - nearest) <= .21f) continue;
                if ((gray[y * width + x] & 255) < 155) return false;
            }
        }
        return true;
    }

    static ScorePageInterpretation removeOwnedTouchHalves(
            ScorePageInterpretation score, byte[] normalized, byte[] original, int width, int height) {
        if (normalized == null || original == null || normalized.length != (long) width * height
                || original.length != normalized.length || score.meterChanges().isEmpty()) return score;
        var meters = score.meterChanges();
        var meter = meters.get(meters.size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4) return score;
        var bars = score.measures();
        var updates = new HashMap<Integer, ScoreNoteEvent>();
        var removed = new java.util.HashSet<Integer>();
        for (int i = meter.measureIndex() + 1; i + 1 < bars.size(); i++) {
            var bar = bars.get(i);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            if (bar.cadenceQuarterBeats() != 0 || nominal < 10 || nominal > 26
                    || span < nominal * 6.4f || span > nominal * 8.15f
                    || Math.abs(bar.top() - bars.get(i - 1).top()) * height > nominal * .35f
                    || Math.abs(bar.top() - bars.get(i + 1).top()) * height > nominal * .35f) continue;
            final int index = i;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            var notes = local(score, i, width, height);
            if (notes.size() != 6 || notes.get(2).x() - notes.get(0).x() > nominal * .18f
                    || notes.get(5).x() - notes.get(3).x() > nominal * .18f
                    || notes.get(3).x() - notes.get(2).x() < nominal * 3.0f
                    || notes.get(3).x() - notes.get(2).x() > nominal * 3.85f
                    || notes.subList(0, 3).stream().anyMatch(o -> !ordinary(o.note()) || !quarter(o.note())))
                continue;
            var later = notes.subList(3, 6).stream().sorted(Comparator.comparingDouble(Owner::y)).toList();
            var upper = later.get(0);
            var touch = later.get(1);
            var lower = later.get(2);
            var head = touch.note();
            if (!quarter(upper.note()) || !quarter(lower.note())
                    || upper.note().stemDirection() != -1 || lower.note().stemDirection() != -1
                    || upper.note().octaveShift() != 2 || lower.note().octaveShift() != 0
                    || upper.note().harmonicSemitones() != 0 || lower.note().harmonicSemitones() != 0
                    || head.beamCount() != 0 || head.unbeamedDurationBeats() != 2
                    || head.stemDirection() != -1 || head.articulations() != 0
                    || head.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || head.harmonicSemitones() != 0 || head.octaveShift() != 0
                    || touch.note().staffStep() - lower.note().staffStep() != 3
                    || hasNoteSourceControl(score, touch.index())
                    || !ordinary(withQuarter(head, -1))) continue;
            int a = ownedTouchHalf(normalized, width, height, bars, i, upper, touch, lower, nominal);
            int b = ownedTouchHalf(original, width, height, bars, i, upper, touch, lower, nominal);
            if (a < 0 || b < 0 || Math.abs(a - b) > nominal * .12f) continue;
            removed.add(touch.index());
            updates.put(upper.index(), upper.note().withOctaveShift(0).withHarmonicSemitones(24));
            updates.put(lower.index(), lower.note().withHarmonicSemitones(24));
        }
        if (removed.isEmpty()) return score;
        var owners = IntStream.range(0, score.notes().size()).filter(i -> !removed.contains(i)).boxed().toList();
        return score.withNotes(owners.stream().map(i -> updates.getOrDefault(i, score.notes().get(i))).toList(),
                owners);
    }

    private static boolean hasNoteSourceControl(ScorePageInterpretation score, int index) {
        var origin = score.beamSource().noteOrigins().get(index);
        return origin.isPresent() && (score.beamSource().groups().stream().flatMap(g -> g.columns().stream())
                .anyMatch(c -> c.members().contains(origin.get()))
                || score.beamSource().detachedColumns().stream().anyMatch(c -> c.members().contains(origin.get())));
    }

    /** The half is a diamond on the quarter's established shaft and has no separate stem. */
    private static int ownedTouchHalf(
            byte[] gray, int width, int height, List<MeasureRegion> bars, int index,
            Owner upper, Owner touch, Owner lower, float nominal) {
        var bar = bars.get(index);
        var fit = PrintedDirectionStaff.printedRules(
                gray, width, height, (bar.left() + bar.right()) * width * .5f,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (fit == null) return -1;
        float gap = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
        if (!interiorRule(gray, width, height, staff, bars.get(index - 1), bar)
                || !interiorRule(gray, width, height, staff, bar, bars.get(index + 1))) return -1;
        var printed = attack(List.of(upper, lower), 0, gray, width, height, staff);
        if (printed == null || printed.direction() != -1
                || Math.abs(lower.y() - touch.y() - 1.5f * gap) > .30f * gap
                || !ArtificialHarmonics.shaftCrossedDiamond(
                        gray, width, height, touch.x(), touch.y(), gap, printed.stem(), -1, true)) return -1;
        var shafts = PrintedBeamShafts.at(gray, width, height, touch.x(), touch.y(), gap,
                new PrintedBeamShafts.Budget(48_000));
        if (shafts.size() != 1) return -1;
        var shaft = shafts.get(0);
        return shaft.direction() == 1 && shaft.left() <= printed.stem() && shaft.right() >= printed.stem()
                ? printed.stem() : -1;
    }

    static Map<Integer, Integer> writtenOnsets(
            ScorePageInterpretation score, byte[] gray, int width, int height, float[] beats) {
        return writtenOnsets(score, gray, null, width, height, beats);
    }

    static Map<Integer, Integer> writtenOnsets(
            ScorePageInterpretation score, byte[] gray, byte[] original,
            int width, int height, float[] beats) {
        var attacks = pairedAttacks(score, gray, original, width, height);
        var placed = new HashMap<Integer, Integer>();
        openingHarmonicOffsets(score, gray, original, width, height).forEach((index, beat) -> {
            if (beats[score.notes().get(index).measureIndex()] == 2) placed.put(index, beat);
        });
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

    private static Map<Integer, Integer> openingHarmonicOffsets(
            ScorePageInterpretation score, byte[] normalized, byte[] raw, int w, int h) {
        if (normalized == null || raw == null || normalized.length != (long) w * h
                || raw.length != normalized.length || score.meterChanges().isEmpty()) return Map.of();
        var meter = score.meterChanges().get(score.meterChanges().size() - 1);
        int index = meter.measureIndex();
        var bars = score.measures();
        if (meter.numerator() != 2 || meter.denominator() != 4 || index < 1 || index + 1 >= bars.size()
                || score.rests().stream().anyMatch(r -> r.measureIndex() == index)) return Map.of();
        var previous = bars.get(index - 1);
        var opening = bars.get(index);
        var after = bars.get(index + 1);
        float gap = (opening.bottom() - opening.top()) * h / 8.2f;
        float span = (opening.right() - opening.left()) * w;
        if (gap < 10 || gap > 25 || span < gap * 10.2f || span > gap * 13.5f
                || opening.cadenceQuarterBeats() != 0
                || opening.top() - previous.bottom() < gap * 2 / h
                || Math.abs(opening.top() - after.top()) * h > gap * .3f) return Map.of();
        var owners = local(score, index, w, h);
        if (owners.size() != 3 || owners.stream().anyMatch(o -> !ordinary(o.note())))
            return Map.of();
        var a = owners.get(0);
        var b = owners.get(1);
        var upper = a.y() < b.y() ? a : b;
        var lower = a == upper ? b : a;
        var next = owners.get(2);
        if (upper.note().stemDirection() != 1 || lower.note().stemDirection() != 1
                || next.note().stemDirection() != -1 || !quarter(upper.note()) || !quarter(lower.note())
                || !quarter(next.note()) && (next.note().beamCount() != 1
                        || next.note().unbeamedDurationBeats() != 0)
                || upper.note().staffStep() - lower.note().staffStep() != 4
                || next.note().staffStep() - upper.note().staffStep() != 2
                || lower.note().staffStep() >= 0
                || Math.abs(upper.x() - lower.x()) > gap * .16f
                || next.x() - upper.x() < gap * 3.6f || next.x() - upper.x() > gap * 4.75f)
            return Map.of();
        if (!openingHarmonicQuarters(normalized, w, h, opening, after, upper, lower, next, gap)
                || !openingHarmonicQuarters(raw, w, h, opening, after, upper, lower, next, gap))
            return Map.of();
        return Map.of(upper.index(), 0, lower.index(), 0, next.index(), 1);
    }

    private static boolean openingHarmonicQuarters(byte[] gray, int w, int h, MeasureRegion bar,
            MeasureRegion nextBar, Owner upper, Owner lower, Owner next, float nominal) {
        float left = bar.left() * w;
        var fit = PrintedDirectionStaff.printedRules(gray, w, h, (bar.left() + bar.right()) * w * .5f,
                bar.bottom() * h - nominal * 2.1f, nominal);
        if (fit == null || Math.abs(fit[1] - nominal) > nominal * .10f) return false;
        float g = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - g * 4, fit[0], g, 0, 1);
        int right = Math.round(upper.x() - g * 1.22f);
        int top = Math.round(staff.top());
        if (right - left < g * 2.8f || right - left > g * 3.6f
                || !interiorRule(gray, w, h, staff, bar, nextBar)
                || !filledStaffHead(gray, w, h, upper, staff)
                || !filledStaffHead(gray, w, h, lower, staff)
                || !filledStaffHead(gray, w, h, next, staff)
                || !PrintedCadenceDurations.emptyOutsideChord(
                        gray, w, staff, right, bar.right() * w, upper.x(), next.x(), true)) return false;
        var reading = MeterFontMatcher.readCrop(gray, w, h,
                new MeterChangeDetector.Crop(Math.round(left), Math.round(top - g * .32f),
                        right, Math.round(staff.bottom() + g * .52f), top, g));
        if (!reading.text().equals("2/4") || reading.topScore() < .86f || reading.bottomScore() < .86f)
            return false;
        var budget = new PrintedBeamShafts.Budget(72_000);
        var rising = PrintedBeamShafts.at(gray, w, h, lower.x(), lower.y(), g, budget);
        var falling = PrintedBeamShafts.at(gray, w, h, next.x(), next.y(), g, budget);
        if (budget.refused() || rising.size() != 1 || falling.size() != 1) return false;
        var first = rising.get(0);
        var last = falling.get(0);
        if (first.direction() != -1 || last.direction() != 1
                || staff.top() - first.endY() < g * 1.02f
                || last.endY() - staff.bottom() < g * 1.35f) return false;
        int up = (first.left() + first.right()) / 2, down = (last.left() + last.right()) / 2;
        float upperTouch = upper.y() - g * 1.5f;
        float lowerTouch = lower.y() - g;
        // The lowest diamond on the second chord occupies the next staff rule.
        // Check its narrowing shoulders before measuring the exposed tail below it.
        float extra = staff.bottom() - g;
        if (!darkSpine(gray, w, h, up, first.endY(), Math.round(lower.y()), .95f)
                || !darkSpine(gray, w, h, down, Math.round(next.y() - g * 1.15f), last.endY(), .95f)
                || !ArtificialHarmonics.shaftCrossedDiamond(gray, w, h, upper.x(), upperTouch, g, up, 1, true)
                || !ArtificialHarmonics.shaftCrossedDiamond(gray, w, h, lower.x(), lowerTouch, g, up, 1, true)
                || !ArtificialHarmonics.shaftCrossedDiamond(
                        gray, w, h, next.x(), next.y() - g * 1.5f, g, down, -1, true)
                || !taperedLowerHead(gray, w, h, down, next.x(), extra, g)
                || !ArtificialHarmonics.unflaggedQuarterTip(
                        gray, w, h, up, upperTouch, upper.y(), first.endY(), 1, staff)
                || !ArtificialHarmonics.unflaggedQuarterTip(
                        gray, w, h, down, extra, extra, last.endY(), -1, staff)) return false;
        return paperSeparatesAttacks(gray, w, h,
                new Attack(upper, lower, up, first.endY(), 1),
                new Attack(next, next, down, last.endY(), -1), staff);
    }

    private static boolean taperedLowerHead(
            byte[] gray, int w, int h, int stem, float x, float y, float gap) {
        int left = stem + Math.round(gap * .34f), right = Math.round(x + gap * .83f);
        int top = Math.round(y + gap * .23f), bottom = Math.round(y + gap * .8f);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return false;
        int shoulder = rowInk(gray, w, left, right, top);
        int taper = rowInk(gray, w, left, right, Math.round(y + gap * .52f));
        int tip = rowInk(gray, w, left, right, bottom);
        return shoulder >= gap * .73f && taper >= gap * .24f
                && taper <= shoulder - gap * .18f && tip <= taper - gap * .17f;
    }

    private static int rowInk(byte[] gray, int width, int left, int right, int row) {
        int count = 0;
        for (int x = left; x <= right; x++) if ((gray[row * width + x] & 255) < 155) count++;
        return count;
    }

    private static List<Attack> pairedAttacks(
            ScorePageInterpretation score, byte[] gray, byte[] original, int width, int height) {
        var legacy = provedAttacks(score, gray, width, height);
        var bars = score.measures();
        var meters = score.meterChanges();
        if (gray == null || original == null || gray.length != (long) width * height
                || original.length != gray.length || meters.isEmpty()) return legacy;
        var meter = meters.get(meters.size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4) return legacy;
        var attacks = new ArrayList<>(legacy);
        for (int i = meter.measureIndex() + 1; i + 1 < bars.size(); i++) {
            int barIndex = i;
            var before = bars.get(i - 1);
            var bar = bars.get(i);
            var after = bars.get(i + 1);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            if (nominal < 9 || nominal > 25 || span < nominal * 8.4f || span > nominal * 12.5f
                    || bar.cadenceQuarterBeats() != 0
                    || Math.abs(bar.top() - before.top()) * height > nominal * .36f
                    || Math.abs(bar.top() - after.top()) * height > nominal * .36f
                    || legacy.stream().anyMatch(a -> a.upper().note().measureIndex() == barIndex)
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == barIndex)) continue;
            var owners = local(score, i, width, height);
            if (owners.size() != 4 || owners.stream().anyMatch(o ->
                    !ordinary(o.note()) || o.note().stemDirection() != -1
                            || !quarter(o.note()) && o.note().beamCount() == 0)) continue;
            var normalized = denseDownstemPair(gray, width, height, before, bar, after, owners, nominal);
            if (normalized.isEmpty()) continue;
            var source = denseDownstemPair(original, width, height, before, bar, after, owners, nominal);
            if (source.isEmpty()) continue;
            boolean matches = true;
            for (int j = 0; j < normalized.size(); j++) {
                if (Math.abs(normalized.get(j).stem() - source.get(j).stem()) > 1
                        || Math.abs(normalized.get(j).end() - source.get(j).end()) > nominal * .19f) {
                    matches = false;
                    break;
                }
            }
            if (matches) attacks.addAll(normalized);
        }
        return attacks;
    }

    private static List<Attack> denseDownstemPair(
            byte[] gray, int width, int height, MeasureRegion before, MeasureRegion bar,
            MeasureRegion after, List<Owner> owners, float nominal) {
        var fit = PrintedDirectionStaff.printedRules(
                gray, width, height, (bar.left() + bar.right()) * width * .5f,
                bar.bottom() * height - nominal * 2.1f, nominal);
        if (fit == null || Math.abs(fit[1] - nominal) > nominal * .12f) return List.of();
        float gap = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
        if (!interiorRule(gray, width, height, staff, before, bar)
                || !interiorRule(gray, width, height, staff, bar, after)) return List.of();
        var first = denseDownstem(gray, width, height, owners.get(0), owners.get(1), staff);
        var second = denseDownstem(gray, width, height, owners.get(2), owners.get(3), staff);
        if (first == null || second == null) return List.of();
        float spacing = second.upper().x() - first.upper().x();
        return spacing >= gap * 3.2f && spacing <= gap * 6.6f
                        && paperSeparatesAttacks(gray, width, height, first, second, staff)
                ? List.of(first, second) : List.of();
    }

    /**
     * The upper owner's immediate ledger and diamond ink can conceal the shoulder of a real
     * downstem. Find the narrow shaft below the lower owner and follow that same shaft upward.
     */
    private static Attack denseDownstem(
            byte[] gray, int width, int height, Owner a, Owner b, PlayingTechniqueDetector.Staff staff) {
        var upper = a.y() < b.y() ? a : b;
        var lower = a == upper ? b : a;
        float gap = staff.gap();
        if (upper.note().staffStep() < 9 || lower.note().staffStep() < 5
                || upper.note().staffStep() - lower.note().staffStep() != 4
                || Math.abs(upper.x() - lower.x()) > gap * .16f
                || !filledStaffHead(gray, width, height, upper, staff)
                || !filledStaffHead(gray, width, height, lower, staff)) return null;
        var budget = new PrintedBeamShafts.Budget(48_000);
        var lowerShafts = PrintedBeamShafts.at(gray, width, height, lower.x(), lower.y(), gap, budget);
        var upperShafts = PrintedBeamShafts.at(gray, width, height, upper.x(), upper.y(), gap, budget);
        if (budget.refused() || upperShafts.stream().anyMatch(s -> s.direction() < 0)
                || lowerShafts.stream().anyMatch(s ->
                        s.direction() < 0 && s.endY() < upper.y() - gap * 1.2f)) return null;
        for (var shaft : lowerShafts) {
            if (shaft.direction() < 0 || shaft.endY() < staff.bottom() - gap * .2f
                    || shaft.endY() > staff.bottom() + gap || shaft.endY() - lower.y() > gap * 4.2f)
                continue;
            int first = (shaft.left() + shaft.right()) / 2;
            for (int stem = first; stem <= shaft.right(); stem++) {
                int rowsStart = Math.round(upper.y() - gap * .28f);
                int end = shaft.endY();
                if (darkSpine(gray, width, height, stem, rowsStart, end, .97f)
                        && darkSpine(gray, width, height, stem + 1, Math.round(lower.y() + gap * .56f),
                                end - 2, .91f)
                        && ArtificialHarmonics.unflaggedQuarterTip(
                                gray, width, height, stem, lower.y() - gap, lower.y(), end, -1, staff))
                    return new Attack(upper, lower, stem, end, -1);
            }
        }
        return null;
    }

    record QuarterShaft(int column, int end) {}

    static QuarterShaft stoppedQuarterShaft(byte[] ink, int width, int height,
            ScoreNoteEvent lower, ScoreNoteEvent upper, float lowerX, float upperX,
            PlayingTechniqueDetector.Staff staff) {
        var attack = denseDownstem(ink, width, height,
                new Owner(0, lower, lowerX, lower.pageY() * height),
                new Owner(1, upper, upperX, upper.pageY() * height), staff);
        return attack == null ? null : new QuarterShaft(attack.stem(), attack.end());
    }

    private static boolean filledStaffHead(
            byte[] gray, int width, int height, Owner owner, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        float gridY = staff.bottom() - owner.note().staffStep() * gap * .5f;
        if (Math.abs(owner.y() - gridY) > gap * .23f) return false;
        int x = Math.round(owner.x()), y = Math.round(owner.y());
        int rx = Math.round(gap * .28f), ry = Math.round(gap * .16f);
        if (x - rx < 0 || x + rx >= width || y - ry < 0 || y + ry >= height) return false;
        int ink = 0;
        for (int row = y - ry; row <= y + ry; row++)
            for (int col = x - rx; col <= x + rx; col++)
                if ((gray[row * width + col] & 255) < 165) ink++;
        return ink >= (2 * rx + 1) * (2 * ry + 1) * .9f;
    }

    private static List<Attack> provedAttacks(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        var fullRun = closedAttacks(score, gray, width, height);
        if (!fullRun.isEmpty() || gray == null || gray.length != (long) width * height)
            return fullRun;
        var bars = score.measures();
        var meters = score.meterChanges();
        if (meters.isEmpty()) return List.of();
        var meter = meters.get(meters.size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4) return List.of();
        var claimed = new ArrayList<Attack>();
        for (int i = meter.measureIndex() + 1; i + 1 < bars.size(); i++) {
            var bar = bars.get(i);
            var previous = bars.get(i - 1);
            var next = bars.get(i + 1);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            if (bar.cadenceQuarterBeats() != 0 || nominal < 9 || nominal > 25
                    || span < nominal * 6.4f || span > nominal * 8.15f
                    || Math.abs(bar.top() - previous.top()) * height > nominal * .36f
                    || Math.abs(bar.top() - next.top()) * height > nominal * .36f) continue;
            int index = i;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            var owners = local(score, i, width, height);
            if (owners.size() != 4 || owners.stream().filter(o -> quarter(o.note())).count() < 3
                    || owners.stream().anyMatch(o -> !quarter(o.note()) && o.note().beamCount() == 0))
                continue;
            var fit = PrintedDirectionStaff.printedRules(
                    gray, width, height, (bar.left() + bar.right()) * width * .5f,
                    bar.bottom() * height - nominal * 2.1f, nominal);
            if (fit == null) continue;
            float gap = fit[1];
            var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
            if (!interiorRule(gray, width, height, staff, previous, bar)
                    || !interiorRule(gray, width, height, staff, bar, next)) continue;
            var first = attack(owners, 0, gray, width, height, staff);
            var second = attack(owners, 2, gray, width, height, staff);
            if (first == null || second == null || first.direction() != second.direction()
                    || second.upper().x() - first.upper().x() < gap * 3.15f
                    || second.upper().x() - first.upper().x() > gap * 3.85f
                    || !paperSeparatesAttacks(gray, width, height, first, second, staff)) continue;
            claimed.add(first);
            claimed.add(second);
        }
        return claimed;
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

    static ScoreNoteEvent withQuarter(ScoreNoteEvent n, int direction) {
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
