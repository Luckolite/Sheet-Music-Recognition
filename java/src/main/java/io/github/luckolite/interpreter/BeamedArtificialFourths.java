// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Retain independently printed fourth touches in a closed run of beamed stopped notes. */
final class BeamedArtificialFourths {
    private BeamedArtificialFourths() {}

    private record ProvedRun(
            List<ArtificialHarmonics.TouchHead> touches, List<List<Integer>> groups, boolean unnumbered) {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, List<Map<String, Object>> events,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height,
            double[] starts, int[] keys, int physicalPage,
            List<OctaveMarkDetector.StaffSpan> printedOctaves,
            List<SourceLocalAccidental.PrintedMark> stoppedAccidentals) {
        var notes = score.notes();
        if (notes.size() != events.size()) return score;
        var byMeasure = new ArrayList<List<Integer>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        var changed = new ArrayList<>(notes);
        var beams = score.beamSource();
        boolean found = false;
        for (int bar = 0; bar < byMeasure.size(); bar++) {
            var owners = byMeasure.get(bar);
            double beats = starts[bar + 1] - starts[bar];
            if ((owners.size() < 8 || owners.size() > 64) || owners.size() * .25 != beats || bar >= keys.length
                    || !qualifies(notes, events, owners, starts[bar])
                    || hasOtherSource(score, printedOctaves, bar)) continue;
            var proved = provedRun(score.measures().get(bar), notes, owners,
                    labels, raw, normalized, width, height, 2, null, false);
            if (proved == null) continue;
            var literals = OmrScoreInterpreter.harmonicTouchAccidentals(
                    labels, normalized, raw, width, height, score, proved.touches());
            if (!closedFourthSpelling(notes, events, owners, literals, keys[bar])) continue;
            for (int owner : owners) {
                var note = notes.get(owner);
                var event = events.get(owner);
                int stopped = ((Number) event.get("midi")).intValue() - 24;
                event.remove("octaveShift");
                event.put("writtenMidi", stopped);
                event.put("harmonicTouchMidi", stopped + 5);
                event.put("artificialHarmonicFourth", true);
                event.put("beamedArtificialHarmonicFourth", true);
                int literal = literals.get(note);
                if (literal != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                    event.put("printedHarmonicTouchAccidental", literal);
                changed.set(owner, note.withOctaveShift(0).withHarmonicSemitones(24));
            }
            if (notes.get(owners.get(owners.size() - 1)).stemDirection() == 1) {
                retainUpstemNaturals(proved.touches(), score, labels, normalized, raw,
                        width, height, owners, stoppedAccidentals);
            }
            beams = projectBeams(beams, physicalPage, proved.groups());
            found = true;
        }
        if (!found) return score;
        var identity = java.util.stream.IntStream.range(0, notes.size()).boxed().toList();
        return score.withBeamSource(beams).withNotes(changed, identity)
                .withClockContextSource(score.clockContextSource());
    }

    /** A complete printed compact run can contain one diamond segmented as a second attack. */
    static ScorePageInterpretation removeCompactTripletTouchAlias(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] labels,
            byte[] raw, byte[] normalized, int width, int height) {
        var notes = score.notes();
        var bars = score.measures();
        var byMeasure = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        var removed = new HashSet<Integer>();
        var restored = new HashSet<Integer>();
        ScoreMeterChange meter = inherited;
        int meterIndex = 0;
        for (int i = 0; i + 1 < bars.size(); i++) {
            while (meterIndex < score.meterChanges().size()
                    && score.meterChanges().get(meterIndex).measureIndex() <= i)
                meter = score.meterChanges().get(meterIndex++);
            var local = byMeasure.get(i);
            var following = byMeasure.get(i + 1);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || local.size() != 13 || following.size() != 1
                    || hasOtherSource(score, List.of(), i)) continue;
            var bar = bars.get(i);
            var tail = notes.get(following.get(0));
            if (!provesCompactClosure(bar, bars.get(i + 1), tail, raw, normalized, width, height)) continue;
            if (local.stream().filter(j -> notes.get(j).octaveShift() == 2).count() < 10) continue;
            for (int extra : local) {
                var alias = notes.get(extra);
                if (!simpleTripletStop(alias) || alias.octaveShift() != 0
                        || alias.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        || alias.articulations() != 0) continue;
                var owners = local.stream().filter(j -> j != extra).toList();
                if (!owners.stream().allMatch(j -> simpleTripletStop(notes.get(j)))) continue;
                var proof = provedRun(bar, notes, owners, labels, raw, normalized, width, height, 1, null, true);
                if (proof == null) continue;
                float x = xAt(alias, bar, width), y = alias.pageY() * height;
                var candidates = proof.touches().stream().filter(t ->
                        Math.abs(t.x() - x) <= .21f * t.gap() && Math.abs(t.y() - y) <= .34f * t.gap()
                                && alias.stemDirection() == t.stop().stemDirection()
                                && alias.staffStep() == t.stop().staffStep() + 3).toList();
                if (candidates.size() != 1) continue;
                var touch = candidates.get(0);
                if (!sameTouchShaft(raw, width, height, x, y, touch)
                        || !sameTouchShaft(normalized, width, height, x, y, touch)) continue;
                removed.add(extra);
                owners.stream().filter(j -> notes.get(j).octaveShift() == 0).forEach(restored::add);
                break;
            }
        }
        if (removed.isEmpty()) return score;
        var owners = java.util.stream.IntStream.range(0, notes.size())
                .filter(i -> !removed.contains(i)).boxed().toList();
        return score.withNotes(owners.stream()
                .map(i -> restored.contains(i) ? notes.get(i).withOctaveShift(2) : notes.get(i)).toList(), owners);
    }

    private static boolean simpleTripletStop(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && (n.octaveShift() == 2 || n.octaveShift() == 0) && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE && n.stemDirection() != 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.beamCount() == 1
                && n.unbeamedDurationBeats() == 0 && n.augmentationDots() == 0 && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0;
    }

    private static boolean sameTouchShaft(byte[] gray, int width, int height, float x, float y,
            ArtificialHarmonics.TouchHead touch) {
        var budget = new PrintedBeamShafts.Budget(70_000);
        var extra = PrintedBeamShafts.at(gray, width, height, x, y, touch.gap(), budget);
        var stop = PrintedBeamShafts.at(gray, width, height, touch.x(),
                touch.stop().pageY() * height, touch.gap(), budget);
        if (extra.size() != 1 || stop.size() != 1) return false;
        var a = extra.get(0);
        var b = stop.get(0);
        return a.direction() == b.direction() && a.left() <= b.right() && b.left() <= a.right()
                && Math.abs(a.endY() - b.endY()) <= 2;
    }

    private static boolean provesCompactClosure(MeasureRegion bar, MeasureRegion next, ScoreNoteEvent tail,
            byte[] raw, byte[] normalized, int width, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        if (gap < 10 || gap > 25 || span < 20 * gap || span > 28 * gap
                || Math.abs(bar.top() - next.top()) * height > .23f * gap
                || Math.abs(bar.bottom() - next.bottom()) * height > .23f * gap
                || bar.cadenceQuarterBeats() != 0 || next.cadenceQuarterBeats() > 1
                || tail.octaveShift() != 2 || tail.beamCount() != 0 || tail.unbeamedDurationBeats() != 1
                || tail.augmentationDots() != 0 || tail.stemDirection() == 0) return false;
        float x = xAt(tail, next, width), y = tail.pageY() * height;
        return printedCompactCadence(raw, width, height, next, x, y, gap, tail.stemDirection())
                && printedCompactCadence(normalized, width, height, next, x, y, gap, tail.stemDirection());
    }

    private static boolean printedCompactCadence(byte[] gray, int width, int height,
            MeasureRegion bar, float x, float y, float nominal, int direction) {
        if (!ArtificialHarmonics.joinedQuarterStem(gray, width, height, x, y, nominal, direction)) return false;
        var frame = PrintedDirectionStaff.printedRules(gray, width, height, x,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (frame == null) return false;
        float gap = frame[1];
        var staff = new PlayingTechniqueDetector.Staff(frame[0] - 4 * gap, frame[0], gap, 0, 1);
        int end = PrintedCadenceDurations.boundaryColumn(gray, width, staff, bar.right() * width, false, true);
        return end >= 0 && end - x > 2.4f * gap && end - x < 5.2f * gap;
    }

    private static float xAt(ScoreNoteEvent note, MeasureRegion bar, int width) {
        return (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
    }

    /** Export fourth touches in a physically closed bar of four eighth-note triplets. */
    static ScorePageInterpretation retainClosedTripletFourths(
            ScorePageInterpretation score, List<Map<String, Object>> events,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height,
            double[] starts, int[] keys, int physicalPage, List<OctaveMarkDetector.StaffSpan> printedOctaves,
            List<PlayingTechniqueDetector.Word> words) {
        var notes = score.notes();
        if (notes.size() != events.size()) return score;
        var byMeasure = new ArrayList<List<Integer>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) byMeasure.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        List<ScoreNoteEvent> changed = null;
        var clearBars = new ArrayList<List<Integer>>();
        for (int bar = 1; bar + 1 < byMeasure.size(); bar++) {
            var owners = byMeasure.get(bar);
            var following = byMeasure.get(bar + 1);
            if (owners.size() != 12 || following.size() != 1 || bar >= keys.length
                    || starts[bar + 1] - starts[bar] != 4 || starts[bar + 2] - starts[bar + 1] != 1
                    || hasOtherSource(score, printedOctaves, bar)) continue;
            var prior = score.measures().get(bar - 1);
            var region = score.measures().get(bar);
            var next = score.measures().get(bar + 1);
            var cadence = notes.get(following.get(0));
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float span = (region.right() - region.left()) * width;
            boolean compact = provesCompactClosure(region, next, cadence, raw, normalized, width, height);
            boolean wide = gap >= 10 && gap <= 25 && span >= 39 * gap && span <= 45 * gap
                    && (region.top() - prior.bottom()) * height >= 2.2f * gap
                    && region.top() == next.top() && region.bottom() == next.bottom();
            if ((!compact && !wide)
                    || region.cadenceQuarterBeats() != 0 || next.cadenceQuarterBeats() != 1
                    || cadence.octaveShift() != 2 || cadence.beamCount() != 0
                    || cadence.unbeamedDurationBeats() != 1
                    || !qualifiesTripletClock(notes, events, owners, starts[bar])) continue;
            var rows = UnnumberedTripletInk.sourceRows(region, score.measures(), height);
            var proved = provedRun(region, notes, owners, labels, raw, normalized, width, height, 1, rows, compact);
            if (proved == null) continue;
            var literals = OmrScoreInterpreter.harmonicTouchAccidentals(
                    labels, normalized, raw, width, height, score, proved.touches());
            if (!closedFourthSpelling(notes, events, owners, literals, keys[bar])) continue;
            if (changed == null) changed = new ArrayList<>(notes);
            if (proved.unnumbered()) clearBars.add(owners);
            for (int slot = 0; slot < owners.size(); slot++) {
                int owner = owners.get(slot);
                var note = notes.get(owner);
                var event = events.get(owner);
                retainFourth(event);
                event.put("beamedArtificialHarmonicFourth", true);
                event.put("tupletActualNotes", 3);
                event.put("tupletNormalNotes", 2);
                int literal = literals.get(note);
                if (literal != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                    event.put("printedHarmonicTouchAccidental", literal);
                changed.set(owner, note.withOctaveShift(0).withHarmonicSemitones(24).withTupletRatio(3, 2));
                if (compact && note.stemDirection() == -1) {
                    var touch = proved.touches().get(slot);
                    var fingers = PrintedFingeringDigits.harmonicStack(words,
                            raw, normalized, width, height, touch.x(), touch.y() - 4.2f * touch.gap(),
                            touch.y() - .1f * touch.gap(), touch.gap());
                    if (fingers != null && fingers.provedTouch() && fingers.stopped() < fingers.touch())
                        event.put("compoundHarmonicFingering", fingers.fingering("above"));
                }
            }
            if (compact && bar + 2 < byMeasure.size() && !hasOtherSource(score, printedOctaves, bar + 1)) {
                int owner = following.get(0);
                var event = events.get(owner);
                var end = new MeasureRegion(next.left(), next.right(), next.top(), next.bottom(), 0);
                if (Double.valueOf(starts[bar + 1]).equals(event.get("startBeat"))
                        && Double.valueOf(1).equals(event.get("durationBeats"))
                        && !Boolean.TRUE.equals(event.get("durationFallback"))
                        && !Boolean.TRUE.equals(event.get("tiedFromPrevious"))
                        && ClosedHarmonicQuarterCadence.afterCompactTriplets(
                                region, end, score.measures().get(bar + 2), cadence,
                                labels, normalized, raw, width, height)) {
                    float localGap = (next.bottom() - next.top()) * height / 8.2f;
                    var touch = new ArtificialHarmonics.TouchHead(cadence, xAt(cadence, next, width),
                            cadence.pageY() * height - 1.5f * localGap, localGap);
                    var signs = OmrScoreInterpreter.harmonicTouchAccidentals(
                            labels, normalized, raw, width, height, score, List.of(touch));
                    if (Integer.valueOf(ScoreNoteEvent.ACCIDENTAL_FROM_KEY).equals(signs.get(cadence))
                            && closedFourthSpelling(notes, events, following, signs, keys[bar + 1])) {
                        retainFourth(event);
                        event.put("closingHarmonicQuarter", true);
                        changed.set(owner, cadence.withOctaveShift(0).withHarmonicSemitones(24));
                    }
                }
            }
        }
        if (changed == null) return score;
        var identity = java.util.stream.IntStream.range(0, notes.size()).boxed().toList();
        var literal = new ClosedHarmonicTriplets.Capture(notes, clearBars).projectBeams(score, physicalPage);
        return literal.withNotes(changed, identity).withClockContextSource(score.clockContextSource());
    }

    private static void retainFourth(Map<String, Object> event) {
        int stopped = ((Number) event.get("midi")).intValue() - 24;
        event.remove("octaveShift");
        event.put("writtenMidi", stopped);
        event.put("harmonicTouchMidi", stopped + 5);
        event.put("artificialHarmonicFourth", true);
    }

    private static boolean qualifiesTripletClock(List<ScoreNoteEvent> notes,
            List<Map<String, Object>> events, List<Integer> owners, double start) {
        float previous = -1;
        for (int slot = 0; slot < owners.size(); slot++) {
            int i = owners.get(slot);
            var n = notes.get(i);
            var e = events.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.octaveShift() != 2 || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE || Math.abs(n.stemDirection()) != 1
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.beamCount() != 1
                    || n.crossStaffBeam() || n.augmentationDots() != 0 || n.unbeamedDurationBeats() != 0
                    || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || n.followingRestBeats() != 0 || n.leadingRestBeats() != 0
                    || n.positionInMeasure() <= previous
                    || !Integer.valueOf(i).equals(e.get("sourceNoteIndex"))
                    || !Boolean.FALSE.equals(e.get("durationFallback"))
                    || !Boolean.FALSE.equals(e.get("tiedFromPrevious"))
                    || !(e.get("midi") instanceof Integer midi) || midi < 24 || midi > 127
                    || !(e.get("startBeat") instanceof Number onset)
                    || !(e.get("durationBeats") instanceof Number duration)
                    || Math.abs(onset.doubleValue() - start - slot / 3.0) > 1e-6
                    || Math.abs(duration.doubleValue() - 1 / 3.0) > 1e-6) return false;
            previous = n.positionInMeasure();
        }
        return true;
    }

    /** Export both written pitches of a proved beamed fourth, including a separately read accidental. */
    static ScorePageInterpretation retainPairedTouches(
            ScorePageInterpretation score, List<Map<String, Object>> events, byte[] labels,
            byte[] raw, byte[] normalized, int width, int height, int[] keys,
            List<ArtificialHarmonics.BeamedTouch> touches) {
        if (touches.isEmpty() || score.notes().size() != events.size()) return score;
        var changed = new ArrayList<>(score.notes());
        var glyphs = touches.stream().map(ArtificialHarmonics.BeamedTouch::glyph).toList();
        var marks = OmrScoreInterpreter.harmonicTouchAccidentals(
                labels, normalized, raw, width, height, score, glyphs);
        for (var touch : touches) {
            var source = touch.glyph().stop();
            if (!Integer.valueOf(touch.accidental()).equals(marks.get(source))) continue;
            int i = matchingStop(score.notes(), source);
            if (i < 0) continue;
            var n = score.notes().get(i);
            var e = events.get(i);
            if (n.octaveShift() != 2 || n.harmonicSemitones() != 0 || n.beamCount() != 2
                    || !Integer.valueOf(i).equals(e.get("sourceNoteIndex"))) continue;
            int key = keys[n.measureIndex()];
            int written = ScoreTiePitchGuard.writtenMidi(n.diatonicPitchIdentity(), n.writtenAccidental(), key);
            int raised = ScoreTiePitchGuard.writtenMidi(touch.touchDiatonic(), touch.accidental(), key);
            if (raised - written != 5 || !Integer.valueOf(written + 24).equals(e.get("midi"))) continue;
            e.remove("octaveShift");
            e.put("writtenMidi", written);
            e.put("harmonicTouchMidi", raised);
            e.put("artificialHarmonicFourth", true);
            e.put("beamedArtificialHarmonicFourth", true);
            if (touch.accidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                e.put("printedHarmonicTouchAccidental", touch.accidental());
            changed.set(i, n.withOctaveShift(0).withHarmonicSemitones(24));
        }
        if (changed.equals(score.notes())) return score;
        var identity = java.util.stream.IntStream.range(0, changed.size()).boxed().toList();
        return score.withNotes(changed, identity).withClockContextSource(score.clockContextSource());
    }

    private static int matchingStop(List<ScoreNoteEvent> notes, ScoreNoteEvent source) {
        int owner = -1;
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.measureIndex() != source.measureIndex() || n.staffIndex() != source.staffIndex()
                    || n.staffCount() != source.staffCount() || n.staffStep() != source.staffStep()
                    || n.positionInMeasure() != source.positionInMeasure() || n.pageY() != source.pageY()) continue;
            if (owner >= 0) return -1;
            owner = i;
        }
        return owner;
    }

    private static void retainUpstemNaturals(
            List<ArtificialHarmonics.TouchHead> touches, ScorePageInterpretation score,
            byte[] labels, byte[] normalized, byte[] raw, int width, int height,
            List<Integer> owners, List<SourceLocalAccidental.PrintedMark> marks) {
        var queries = new ArrayList<ArtificialHarmonics.TouchHead>();
        for (var touch : touches) {
            var stop = touch.stop();
            if (stop.stemDirection() == 1
                    && stop.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL)
                queries.add(new ArtificialHarmonics.TouchHead(stop, touch.x(),
                        stop.pageY() * height, touch.gap()));
        }
        var literals = OmrScoreInterpreter.harmonicTouchAccidentals(
                labels, normalized, raw, width, height, score, queries);
        for (int i : owners)
            if (Integer.valueOf(ScoreNoteEvent.ACCIDENTAL_NATURAL).equals(literals.get(score.notes().get(i))))
                marks.add(new SourceLocalAccidental.PrintedMark(i, ScoreNoteEvent.ACCIDENTAL_NATURAL));
    }

    private static boolean hasOtherSource(ScorePageInterpretation score,
            List<OctaveMarkDetector.StaffSpan> printedOctaves, int bar) {
        return score.rests().stream().anyMatch(r -> r.measureIndex() == bar)
                || printedOctaves.stream().anyMatch(s -> s.staffIndex() == 0 && s.staffCount() == 1
                        && s.firstMeasureIndex() <= bar && s.lastMeasureIndex() >= bar);
    }

    private static boolean qualifies(List<ScoreNoteEvent> notes, List<Map<String, Object>> events,
            List<Integer> owners, double start) {
        // A stem reversal is admitted from the downstem group into the upstem group.
        if (notes.get(owners.get(0)).stemDirection() != -1) return false;
        int direction = -1;
        float previous = -1;
        for (int slot = 0; slot < owners.size(); slot++) {
            int i = owners.get(slot);
            var n = notes.get(i);
            var e = events.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.octaveShift() != 2 || n.harmonicSemitones() != 0 || n.openStringTouch() != null
                    || n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE || n.stemDirection() == 0
                    || n.stemDirection() < direction
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.beamCount() != 2
                    || n.unbeamedDurationBeats() != 0 || n.augmentationDots() != 0 || n.tupletDivisor() != 1
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0 || n.crossStaffBeam()
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.articulations() != 0
                    || !(n.positionInMeasure() > previous) || !Integer.valueOf(i).equals(e.get("sourceNoteIndex"))
                    || !Boolean.FALSE.equals(e.get("clefInferred")) || !Boolean.FALSE.equals(e.get("durationFallback"))
                    || !Double.valueOf(start + slot * .25).equals(e.get("startBeat"))
                    || !Double.valueOf(.25).equals(e.get("durationBeats"))
                    || !(e.get("midi") instanceof Integer midi) || midi < 24 || midi > 127)
                return false;
            previous = n.positionInMeasure();
            direction = n.stemDirection();
        }
        return true;
    }

    private static ProvedRun provedRun(
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> owners,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height, int rails,
            UnnumberedTripletInk.Rows rows, boolean compact) {
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 26) return null;
        boolean mixed = compact || notes.get(owners.get(owners.size() - 1)).stemDirection() == 1;
        var hits = new ArrayList<ArtificialHarmonics.TouchHead>();
        var rawShafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var proofShafts = new ArrayList<PrintedBeamShafts.Shaft>();
        var groups = new ArrayList<List<Integer>>();
        int first = 0;
        var budget = new PrintedBeamShafts.Budget(2_000_000);
        for (int i : owners) {
            var note = notes.get(i);
            float x = xAt(note, bar, width), y = note.pageY() * height;
            var frame = PrintedDirectionStaff.printedRules(normalized, width, height, x,
                    bar.bottom() * height - 2.1f * nominal, nominal);
            float gap = frame == null ? nominal : frame[1];
            float touch = y - 1.5f * gap;
            int stem = notes.get(i).stemDirection();
            var source = joinedShaft(raw, width, height, x, y, touch, gap, stem, budget);
            var proof = joinedShaft(normalized, width, height, x, y, touch, gap, stem, budget);
            if (source == null || proof == null || Math.abs(source.left() - proof.left()) > 2
                    || !printedTouch(labels, raw, normalized, width, height, x, y, touch, gap,
                            stem, mixed, compact, source, proof)) return null;
            rawShafts.add(source);
            proofShafts.add(proof);
            hits.add(new ArtificialHarmonics.TouchHead(notes.get(i), x, touch, gap));
        }
        if (rails == 1) {
            if (!closedTripletRails(raw, width, height, rawShafts, nominal)
                    || !closedTripletRails(normalized, width, height, proofShafts, nominal)) return null;
            if (compact && (!WrappedMeasureBoundaries.confirmsClosedTripletBar(
                        raw, width, height, bar, hits.get(0).x(), hits.get(hits.size() - 1).x())
                    || !WrappedMeasureBoundaries.confirmsClosedTripletBar(
                        normalized, width, height, bar, hits.get(0).x(), hits.get(hits.size() - 1).x()))) return null;
            boolean unnumbered = rows != null && (compact
                    ? clearsCompactTripletMarks(bar, raw, width, height, hits, rawShafts, rows, nominal)
                        && clearsCompactTripletMarks(bar, normalized, width, height, hits, proofShafts, rows, nominal)
                    : clearsWideTripletMarks(bar, raw, width, height, hits, rawShafts, rows, nominal)
                        && clearsWideTripletMarks(bar, normalized, width, height, hits, proofShafts, rows, nominal));
            return new ProvedRun(hits, List.of(), unnumbered);
        }
        for (int i = 1; i < owners.size(); i++) {
            var ra = rawShafts.get(i - 1);
            var rb = rawShafts.get(i);
            var pa = proofShafts.get(i - 1);
            var pb = proofShafts.get(i);
            if (ra.direction() == rb.direction()
                    && WrappedEighthTriplets.matchesBeamRails(raw, width, height, ra, rb, nominal, 2)
                    && WrappedEighthTriplets.matchesBeamRails(normalized, width, height, pa, pb, nominal, 2))
                continue;
            if (i - first < 2
                    || !WrappedEighthTriplets.matchesBeamRails(raw, width, height, ra, rb, nominal, 0)
                    || !WrappedEighthTriplets.matchesBeamRails(normalized, width, height, pa, pb, nominal, 0))
                return null;
            groups.add(List.copyOf(owners.subList(first, i)));
            first = i;
        }
        if (owners.size() - first < 2) return null;
        groups.add(List.copyOf(owners.subList(first, owners.size())));
        return new ProvedRun(hits, groups, false);
    }

    private static boolean closedTripletRails(byte[] gray, int width, int height,
            List<PrintedBeamShafts.Shaft> shafts, float gap) {
        for (int i = 1; i < shafts.size(); i++)
            if (SystemQuarterCadence.directHarmonicRail(gray, width, height, shafts.get(i - 1), shafts.get(i), gap)
                    != (i % 3 != 0)) return false;
        return true;
    }

    private static boolean clearsCompactTripletMarks(MeasureRegion bar, byte[] gray, int width, int height,
            List<ArtificialHarmonics.TouchHead> owners, List<PrintedBeamShafts.Shaft> shafts,
            UnnumberedTripletInk.Rows rows, float gap) {
        var track = StaffPitchTrack.forPrintedSlurs(gray, width, height,
                bar.top() * height + 2.1f * gap, bar.bottom() * height - 2.1f * gap, gap);
        for (int i = 0; i < owners.size(); i += 3) {
            float[] x = new float[3], stop = new float[3], touch = new float[3];
            for (int j = 0; j < 3; j++) {
                var owner = owners.get(i + j);
                x[j] = owner.x();
                stop[j] = owner.stop().pageY() * height;
                touch[j] = owner.y();
            }
            var a = shafts.get(i);
            var b = shafts.get(i + 1);
            var c = shafts.get(i + 2);
            float minHead = Math.min(touch[0], Math.min(touch[1], touch[2]));
            float maxHead = Math.max(stop[0], Math.max(stop[1], stop[2]));
            if (BoundedSecondaryBeam.proves(gray, width, height, x, stop, gap)
                    || ClosedHarmonicTriplets.containsAdditionalRail(gray, width, height, a, b, gap)
                    || ClosedHarmonicTriplets.containsAdditionalRail(gray, width, height, b, c, gap)
                    || UnnumberedTripletInk.bracketBesideBeam(gray, width, height, gap,
                        x[0], x[2], minHead, maxHead, b.endY(), rows, track, true)) return false;
            float center = (x[0] + x[2]) * .5f;
            int direction = b.direction();
            if (hasCenteredThree(gray, width, height, center, b.endY(), direction, .16f, 3, gap, rows, track)
                    || hasCenteredThree(gray, width, height, center, direction > 0 ? minHead : maxHead,
                            -direction, .23f, 4.85f, gap, rows, track)) return false;
        }
        return true;
    }

    private static boolean hasCenteredThree(byte[] gray, int width, int height, float x, float origin,
            int direction, float near, float far, float gap,
            UnnumberedTripletInk.Rows rows, StaffPitchTrack track) {
        float a = origin + direction * near * gap, b = origin + direction * far * gap;
        return UnnumberedTripletInk.centeredThree(gray, width, height, x,
                Math.min(a, b), Math.max(a, b), gap, rows, track);
    }

    private static boolean clearsWideTripletMarks(MeasureRegion bar, byte[] gray, int width, int height,
            List<ArtificialHarmonics.TouchHead> owners, List<PrintedBeamShafts.Shaft> shafts,
            UnnumberedTripletInk.Rows rows, float gap) {
        var track = StaffPitchTrack.forPrintedSlurs(gray, width, height,
                bar.top() * height + 2.1f * gap, bar.bottom() * height - 2.1f * gap, gap);
        if (track == null) return false;
        for (int start = 0; start < owners.size(); start += 3) {
            float[] x = new float[3], y = new float[3];
            for (int j = 0; j < 3; j++) {
                var owner = owners.get(start + j);
                x[j] = owner.x();
                y[j] = owner.stop().pageY() * height;
            }
            if (BoundedSecondaryBeam.proves(gray, width, height, x, y, gap)
                    || ClosedHarmonicTriplets.containsAdditionalRail(
                            gray, width, height, shafts.get(start), shafts.get(start + 1), gap)
                    || ClosedHarmonicTriplets.containsAdditionalRail(
                            gray, width, height, shafts.get(start + 1), shafts.get(start + 2), gap)
                    || !UnnumberedTripletInk.clearWideHarmonic(
                            gray, width, height, gap, x, y, shafts.subList(start, start + 3), rows, track))
                return false;
        }
        return true;
    }

    private static boolean printedTouch(
            byte[] labels, byte[] raw, byte[] normalized, int width, int height,
            float x, float y, float touch, float gap, int stem, boolean mixed, boolean compact,
            PrintedBeamShafts.Shaft source, PrintedBeamShafts.Shaft proof) {
        if (ArtificialHarmonics.symbolDiamond(labels, raw, width, height, x, touch, gap)
                && ArtificialHarmonics.symbolDiamond(labels, normalized, width, height, x, touch, gap))
            return true;
        if (!mixed) return false;
        if (stem == -1 && ArtificialHarmonics.beamedLedgerFourth(
                labels, normalized, raw, width, height, x, y, gap)) return true;
        if (stem == -1 && !compact) return false;
        for (int col = Math.max(source.left(), proof.left()); col <= Math.min(source.right(), proof.right()); col++)
            if (ArtificialHarmonics.shaftCrossedDiamond(raw, width, height, x, touch, gap, col, stem, true)
                    && ArtificialHarmonics.shaftCrossedDiamond(
                            normalized, width, height, x, touch, gap, col, stem, true)) return true;
        if (compact && printedDottedTouch(labels, raw, normalized, width, height, x, y, gap, stem))
            return true;
        if (!compact || stem != 1
                || !ArtificialHarmonics.filledQuarterCore(raw, width, height, x, y, gap)
                || !ArtificialHarmonics.filledQuarterCore(normalized, width, height, x, y, gap)) return false;
        int search = Math.round(gap * .16f);
        for (int col = Math.max(source.left(), proof.left()); col <= Math.min(source.right(), proof.right()); col++) {
            for (int dx = -search; dx <= search; dx++) for (int dy = -search; dy <= search; dy++) {
                if (LedgerCrossedHarmonicDiamond.compactStemmedTouch(
                            labels, raw, width, height, x + dx, touch + dy, gap, col)
                        && LedgerCrossedHarmonicDiamond.compactStemmedTouch(
                            labels, normalized, width, height, x + dx, touch + dy, gap, col)) return true;
            }
        }
        return false;
    }

    private static PrintedBeamShafts.Shaft joinedShaft(byte[] gray, int width, int height,
            float x, float y, float touch, float gap, int stem, PrintedBeamShafts.Budget budget) {
        var shafts = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
        if (shafts.size() != 1 || shafts.get(0).direction() != -stem) return null;
        var shaft = shafts.get(0);
        int top = Math.round(touch + gap * .22f), bottom = Math.round(y - gap * .32f);
        if (top < 0 || bottom >= height || shaft.left() <= 0 || shaft.right() >= width - 1)
            return null;
        for (int col = shaft.left(); col <= shaft.right(); col++) {
            boolean connected = true;
            for (int row = top; row <= bottom; row++) {
                int p = row * width + col;
                if ((gray[p - 1] & 255) >= 170 && (gray[p] & 255) >= 170 && (gray[p + 1] & 255) >= 170) {
                    connected = false;
                    break;
                }
            }
            if (connected) return shaft;
        }
        return null;
    }

    private static ScoreBeamSource projectBeams(
            ScoreBeamSource source, int page, List<List<Integer>> proved) {
        return projectBeams(source, page, proved, false);
    }

    private static ScoreBeamSource projectBeams(
            ScoreBeamSource source, int page, List<List<Integer>> proved, boolean dotted) {
        if (page < 0) return source;
        var groups = new ArrayList<>(source.groups());
        var origins = new ArrayList<>(source.noteOrigins());
        var used = new HashSet<ScoreBeamSource.Anchor>();
        var ids = new HashSet<String>();
        for (var origin : origins) origin.ifPresent(used::add);
        for (var group : groups) {
            ids.add(group.id());
            for (var column : group.columns()) used.addAll(column.members());
        }
        for (var column : source.detachedColumns()) used.addAll(column.members());
        for (var run : proved) {
            var columns = new ArrayList<ScoreBeamSource.Column>(run.size());
            String id = "beam-page:" + page + ":" + run.get(0);
            if (!ids.add(id)) return source;
            for (int owner : run) {
                var anchor = new ScoreBeamSource.Anchor(page, owner);
                if (origins.get(owner).isPresent() || !used.add(anchor)) return source;
                origins.set(owner, Optional.of(anchor));
                columns.add(new ScoreBeamSource.Column(List.of(anchor)));
            }
            var hooks = dotted ? List.of(
                    new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                    new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD))
                    : List.<ScoreBeamSource.Hook>of();
            groups.add(new ScoreBeamSource.Group(id, columns,
                    java.util.Collections.nCopies(columns.size() - 1, 3), hooks));
        }
        return new ScoreBeamSource(groups, source.detachedColumns(), origins);
    }

    /** Preserve the two main rails and the eight individual third-rail hooks of a closed run. */
    static ScorePageInterpretation retainDottedBeams(ScorePageInterpretation score,
            List<Map<String, Object>> events, byte[] labels, byte[] raw, byte[] normalized,
            int width, int height, double[] starts, int[] keys, int physicalPage,
            List<OctaveMarkDetector.StaffSpan> printedOctaves,
            List<SourceLocalAccidental.PrintedMark> stoppedMarks) {
        if (physicalPage < 0 || raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || events.size() != score.notes().size()) return score;
        var byMeasure = new ArrayList<List<Integer>>(score.measures().size());
        for (int i = 0; i < score.measures().size(); i++) byMeasure.add(new ArrayList<>());
        var notes = score.notes();
        for (int i = 0; i < notes.size(); i++) byMeasure.get(notes.get(i).measureIndex()).add(i);
        var beams = score.beamSource();
        List<ScoreNoteEvent> revised = null;
        for (int measure = 0; measure < byMeasure.size(); measure++) {
            var owners = byMeasure.get(measure);
            if (starts[measure + 1] - starts[measure] != 4) continue;
            var region = score.measures().get(measure);
            int terminal = -1;
            if (owners.size() == 9 && !hasOtherSource(score, printedOctaves, measure)
                    && measure < keys.length) {
                int last = owners.get(owners.size() - 1);
                if (!closedHalfFourth(notes.get(last), events.get(last), starts[measure] + 2,
                        labels, raw, normalized, width, height, region)) continue;
                terminal = last;
                owners = owners.subList(0, 8);
            }
            if ((owners.size() != 16 && terminal < 0)
                    || !dottedHarmonicRun(notes, events, owners, starts[measure])) continue;
            if (!printedDottedBeams(raw, width, height, region, notes, owners)
                    || !printedDottedBeams(normalized, width, height, region, notes, owners)
                    || !printedMissingDots(raw, normalized, width, height, region, notes, owners)) continue;
            List<List<Integer>> groups = new ArrayList<>(4);
            for (int i = 0; i < owners.size(); i += 4) groups.add(owners.subList(i, i + 4));
            var captured = projectBeams(beams, physicalPage, groups, true);
            if (captured == beams) continue;
            beams = captured;
            if (revised == null) revised = new ArrayList<>(notes);
            for (int i = 0; i < owners.size(); i++) {
                int index = owners.get(i);
                boolean dotted = i % 2 == 0;
                int beamsExpected = dotted ? 2 : 3, dotsExpected = dotted ? 1 : 0;
                var note = notes.get(index);
                if (note.beamCount() != beamsExpected || note.augmentationDots() != dotsExpected)
                    revised.set(index, ClosedDottedHookCadence.rhythm(note, beamsExpected, dotsExpected));
            }
            if (!hasOtherSource(score, printedOctaves, measure) && measure < keys.length) {
                retainDottedFourthPitches(score, revised, events, owners, labels, raw, normalized,
                        width, height, region, keys[measure], stoppedMarks);
                if (terminal >= 0)
                    retainClosingHalf(score, revised, events, terminal, labels, raw, normalized,
                            width, height, region, keys[measure]);
            }
        }
        if (revised == null) return score;
        var indices = java.util.stream.IntStream.range(0, notes.size()).boxed().toList();
        return score.withBeamSource(beams).withNotes(revised, indices)
                .withClockContextSource(score.clockContextSource());
    }

    private static void retainDottedFourthPitches(ScorePageInterpretation score,
            List<ScoreNoteEvent> revised, List<Map<String, Object>> events, List<Integer> owners,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height, MeasureRegion bar,
            int key, List<SourceLocalAccidental.PrintedMark> stoppedMarks) {
        if (labels == null || labels.length != raw.length) return;
        var touches = new ArrayList<ArtificialHarmonics.TouchHead>(owners.size());
        for (int index : owners) {
            var note = score.notes().get(index);
            float x = xAt(note, bar, width), y = note.pageY() * height;
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            var staff = PrintedDirectionStaff.printedRules(
                    normalized, width, height, x, bar.bottom() * height - 2.1f * nominal, nominal);
            float gap = staff == null ? nominal : staff[1];
            if (!printedDottedTouch(labels, raw, normalized, width, height, x, y, gap,
                    note.stemDirection())) return;
            touches.add(new ArtificialHarmonics.TouchHead(note, x, y - 1.5f * gap, gap));
        }
        var literals = OmrScoreInterpreter.harmonicTouchAccidentals(
                labels, normalized, raw, width, height, score, touches);
        var carry = new HashMap<Integer, Integer>();
        var pitchPairs = new ArrayList<int[]>(owners.size());
        for (int index : owners) {
            var note = score.notes().get(index);
            int base = note.diatonicPitchIdentity(), raised = base + 3;
            Integer mark = literals.get(note);
            int writtenAccidental = note.writtenAccidental();
            if (mark == null || mark < -1 || mark > ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || writtenAccidental < -1 || writtenAccidental > ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || note.harmonicSemitones() != 0 && note.harmonicSemitones() != 24
                    || note.octaveShift() != 0 && note.octaveShift() != 2) return;
            if (writtenAccidental != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) carry.put(base, writtenAccidental);
            if (mark != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) carry.put(raised, mark);
            int stopped = ScoreTiePitchGuard.writtenMidi(
                    base, carry.getOrDefault(base, ScoreNoteEvent.ACCIDENTAL_FROM_KEY), key);
            int touched = ScoreTiePitchGuard.writtenMidi(
                    raised, carry.getOrDefault(raised, ScoreNoteEvent.ACCIDENTAL_FROM_KEY), key);
            int sounding = stopped + 24;
            int stored = note.harmonicSemitones() == 24 || note.octaveShift() == 2 ? sounding : stopped;
            if (touched - stopped != 5 || sounding > 127
                    || !Integer.valueOf(stored).equals(events.get(index).get("midi"))) return;
            pitchPairs.add(new int[]{stopped, touched, sounding, mark});
        }
        for (int i = 0; i < owners.size(); i++) {
            int index = owners.get(i);
            var event = events.get(index);
            int[] pitch = pitchPairs.get(i);
            event.remove("octaveShift");
            event.put("writtenMidi", pitch[0]);
            event.put("harmonicTouchMidi", pitch[1]);
            event.put("midi", pitch[2]);
            event.put("artificialHarmonicFourth", true);
            event.put("beamedArtificialHarmonicFourth", true);
            if (pitch[3] != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                event.put("printedHarmonicTouchAccidental", pitch[3]);
            revised.set(index, revised.get(index).withOctaveShift(0).withHarmonicSemitones(24));
        }
        retainDottedStoppedSharps(touches, score, labels, raw, normalized, width, height, owners, stoppedMarks);
    }

    static void retainDottedStoppedSharps(List<ArtificialHarmonics.TouchHead> touches,
            ScorePageInterpretation score, byte[] labels, byte[] raw, byte[] normalized,
            int width, int height, List<Integer> owners, List<SourceLocalAccidental.PrintedMark> marks) {
        // Query the filled stopped heads separately from their touching diamonds.
        var queries = new ArrayList<ArtificialHarmonics.TouchHead>();
        for (var touch : touches) {
            var stop = touch.stop();
            float y = stop.pageY() * height;
            if (stop.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                    && OmrScoreInterpreter.printedStoppedHarmonicSharp(
                            raw, width, height, touch.x(), y, touch.gap())
                    && OmrScoreInterpreter.printedStoppedHarmonicSharp(
                            normalized, width, height, touch.x(), y, touch.gap()))
                queries.add(new ArtificialHarmonics.TouchHead(stop, touch.x(), y, touch.gap()));
        }
        var literals = OmrScoreInterpreter.harmonicTouchAccidentals(
                labels, normalized, raw, width, height, score, queries);
        for (int index : owners)
            if (Integer.valueOf(ScoreNoteEvent.ACCIDENTAL_SHARP).equals(literals.get(score.notes().get(index))))
                marks.add(new SourceLocalAccidental.PrintedMark(index, ScoreNoteEvent.ACCIDENTAL_SHARP));
    }

    private static boolean printedDottedTouch(byte[] labels, byte[] raw, byte[] normalized,
            int width, int height, float x, float y, float gap, int direction) {
        var a = dottedShaft(raw, width, height, x, y, gap, direction);
        var b = dottedShaft(normalized, width, height, x, y, gap, direction);
        if (a == null || b == null
                || !ArtificialHarmonics.filledQuarterCore(raw, width, height, x, y, gap)
                || !ArtificialHarmonics.filledQuarterCore(normalized, width, height, x, y, gap))
            return false;
        for (int col = Math.max(a.left(), b.left()); col <= Math.min(a.right(), b.right()); col++) {
            int first = Math.round(y - gap * 1.22f), last = Math.round(y - gap * .12f);
            if (!touchBridge(raw, width, col, first, last, gap)
                    || !touchBridge(normalized, width, col, first, last, gap)) continue;
            int dx = Math.round(gap * .27f), dy = Math.round(gap * .26f);
            for (int sx = -dx; sx <= dx; sx++) for (int sy = -dy; sy <= dy; sy++) {
                float tx = x + sx, ty = y - 1.5f * gap + sy;
                if (LedgerCrossedHarmonicDiamond.compact(labels, raw, width, height, tx, ty, gap, col, direction)
                        && LedgerCrossedHarmonicDiamond.compact(
                                labels, normalized, width, height, tx, ty, gap, col, direction))
                    return true;
                boolean symbol = ArtificialHarmonics.symbolDiamond(labels, raw, width, height, tx, ty, gap)
                        && ArtificialHarmonics.symbolDiamond(labels, normalized, width, height, tx, ty, gap);
                if (symbol && direction == -1
                        && shoulderJoinsShaft(raw, width, col, ty, gap)
                        && shoulderJoinsShaft(normalized, width, col, ty, gap)) return true;
                if (ArtificialHarmonics.shaftCrossedDiamond(raw, width, height, tx, ty, gap, col, direction, true)
                        && ArtificialHarmonics.shaftCrossedDiamond(
                                normalized, width, height, tx, ty, gap, col, direction, true)
                        && (symbol || ArtificialHarmonics.enclosedCounter(
                                raw, width, height, tx, ty, gap * .81f, gap * .27f)
                                && ArtificialHarmonics.enclosedCounter(
                                        normalized, width, height, tx, ty, gap * .81f, gap * .27f)))
                    return true;
            }
        }
        return false;
    }

    private static PrintedBeamShafts.Shaft dottedShaft(byte[] ink, int width, int height,
            float x, float y, float gap, int direction) {
        var budget = new PrintedBeamShafts.Budget(90_000);
        var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        if (shafts.isEmpty())
            shafts = PrintedBeamShafts.at(ink, width, height, x,
                    y + (direction > 0 ? -1.5f : 1f) * gap, gap, budget);
        if (budget.refused() || shafts.size() != 1) return null;
        var shaft = shafts.get(0);
        return shaft.direction() == -direction ? shaft : null;
    }

    private static boolean touchBridge(byte[] ink, int width, int column, int first, int last, float gap) {
        int start = -1;
        if (column < 1 || column + 1 >= width || first < 0 || last >= ink.length / width)
            return false;
        for (int row = first; row <= last; row++) {
            int p = row * width + column;
            if ((ink[p - 1] & 255) < 155 || (ink[p] & 255) < 155 || (ink[p + 1] & 255) < 155)
                continue;
            if (start < 0) start = row;
            if (row - start + 1 > Math.round(gap * .4f)) return false;
        }
        return true;
    }

    private static boolean shoulderJoinsShaft(byte[] ink, int width, int col, float y, float gap) {
        int center = Math.round(y), a = center - Math.round(gap * .28f), b = center + Math.round(gap * .37f);
        if (col < 0 || col + 2 >= width || a < 0 || b >= ink.length / width) return false;
        int support = 0;
        for (int row = a; row <= b; row++) {
            int p = row * width + col;
            if ((ink[p] & 255) < 155 && (ink[p + 1] & 255) < 155 && (ink[p + 2] & 255) < 155)
                support++;
        }
        return support >= Math.round(gap * .50f);
    }

    private static boolean closedHalfFourth(ScoreNoteEvent note, Map<String, Object> event, double onset,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height, MeasureRegion bar) {
        if (labels == null || labels.length != raw.length || note.kind() != ScoreNoteEvent.Kind.PITCHED
                || note.staffIndex() != 0 || note.staffCount() != 1
                || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || note.stemDirection() != 1 || note.beamCount() != 0 || note.augmentationDots() != 0
                || note.tupletDivisor() != 1 || note.unbeamedDurationBeats() != 2
                || note.articulations() != 0 || note.crossStaffBeam()
                || note.tiedFromPrevious() || note.boundaryTies() != 0
                || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                || note.octaveShift() != 0 || note.harmonicSemitones() != 0
                || note.openStringTouch() != null || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || !(event.get("startBeat") instanceof Number at) || at.doubleValue() != onset
                || !(event.get("durationBeats") instanceof Number length) || length.doubleValue() != 2)
            return false;
        float x = xAt(note, bar, width), y = note.pageY() * height;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25 || (bar.right() * width - x) / gap < 1) return false;
        var a = PrintedBeamShafts.at(raw, width, height, x, y, gap, new PrintedBeamShafts.Budget(90_000));
        var b = PrintedBeamShafts.at(
                normalized, width, height, x, y, gap, new PrintedBeamShafts.Budget(90_000));
        if (a.size() != 1 || b.size() != 1 || a.get(0).direction() != -1 || b.get(0).direction() != -1
                || Math.abs(a.get(0).endY() - b.get(0).endY()) > 2
                || !halfCounterAndStem(raw, width, height, x, y, gap)
                || !halfCounterAndStem(normalized, width, height, x, y, gap)) return false;
        int radius = Math.round(gap * .26f);
        for (int col = Math.max(a.get(0).left(), b.get(0).left());
                col <= Math.min(a.get(0).right(), b.get(0).right()); col++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) {
                float tx = x + dx, ty = y - 1.5f * gap + dy;
                if (LedgerCrossedHarmonicDiamond.proved(labels, raw, width, height, tx, ty, gap, col, 1)
                        && LedgerCrossedHarmonicDiamond.proved(
                                labels, normalized, width, height, tx, ty, gap, col, 1)) return true;
            }
        }
        return false;
    }

    private static boolean halfCounterAndStem(byte[] ink, int width, int height, float x, float y, float gap) {
        return !ArtificialHarmonics.filledQuarterCore(ink, width, height, x, y, gap)
                && ArtificialHarmonics.enclosedCounter(ink, width, height, x, y, gap * .8f, gap * .29f)
                && ArtificialHarmonics.joinedQuarterStem(ink, width, height, x, y, gap, 1);
    }

    private static void retainClosingHalf(ScorePageInterpretation score,
            List<ScoreNoteEvent> revised, List<Map<String, Object>> events, int index,
            byte[] labels, byte[] raw, byte[] normalized, int width, int height, MeasureRegion bar, int key) {
        var note = score.notes().get(index);
        var event = events.get(index);
        if (!Integer.valueOf(index).equals(event.get("sourceNoteIndex"))) return;
        float x = xAt(note, bar, width), y = note.pageY() * height;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var touch = new ArtificialHarmonics.TouchHead(note, x, y - 1.5f * gap, gap);
        var marks = OmrScoreInterpreter.harmonicTouchAccidentals(
                labels, normalized, raw, width, height, score, List.of(touch));
        // A closed half inheriting the signature must not consume another accidental.
        if (!Integer.valueOf(ScoreNoteEvent.ACCIDENTAL_FROM_KEY).equals(marks.get(note))) return;
        int base = note.diatonicPitchIdentity();
        int stopped = ScoreTiePitchGuard.writtenMidi(base, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int touching = ScoreTiePitchGuard.writtenMidi(base + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        if (touching - stopped != 5 || stopped + 24 > 127
                || !Integer.valueOf(stopped).equals(event.get("midi"))) return;
        event.put("writtenMidi", stopped);
        event.put("harmonicTouchMidi", touching);
        event.put("midi", stopped + 24);
        event.put("artificialHarmonicFourth", true);
        event.put("closingHarmonicHalf", true);
        revised.set(index, note.withHarmonicSemitones(24));
    }

    private static boolean dottedHarmonicRun(List<ScoreNoteEvent> notes, List<Map<String, Object>> events,
            List<Integer> owners, double start) {
        int harmonics = 0;
        float prior = -1;
        for (int i = 0; i < owners.size(); i++) {
            int index = owners.get(i);
            var note = notes.get(index);
            var event = events.get(index);
            boolean dotted = i % 2 == 0;
            double onset = start + i / 2 * .5 + (dotted ? 0 : .375);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffIndex() != 0 || note.staffCount() != 1
                    || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE || note.stemDirection() == 0
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL || note.crossStaffBeam()
                    || note.augmentationDots() > (dotted ? 1 : 0) || note.beamCount() < 2
                    || note.beamCount() > (dotted ? 2 : 3) || note.unbeamedDurationBeats() != 0
                    || note.tupletDivisor() != 1 || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || note.positionInMeasure() <= prior
                    || (!(event.get("startBeat") instanceof Number at) || at.doubleValue() != onset)
                    || (!(event.get("durationBeats") instanceof Number length)
                            || length.doubleValue() != (dotted ? .375 : .125))
                    || !Integer.valueOf(index).equals(event.get("sourceNoteIndex"))) return false;
            prior = note.positionInMeasure();
            if (note.octaveShift() == 2 || note.harmonicSemitones() == 24) harmonics++;
        }
        return harmonics * 4 >= owners.size() * 3;
    }

    private static boolean printedMissingDots(byte[] raw, byte[] normalized, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> notes, List<Integer> owners) {
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        for (int i = 0; i < owners.size(); i += 2) {
            var note = notes.get(owners.get(i));
            if (note.augmentationDots() == 1) continue;
            float x = xAt(note, bar, width), y = note.pageY() * height;
            float nextX = xAt(notes.get(owners.get(i + 1)), bar, width);
            var staff = PrintedDirectionStaff.printedRules(normalized, width, height, x,
                    bar.bottom() * height - 2.1f * nominal, nominal);
            float gap = staff == null ? nominal : staff[1];
            var first = WrappedTripletDotInk.uniqueCore(raw, width, height, x, y, nextX, gap);
            var second = WrappedTripletDotInk.uniqueCore(normalized, width, height, x, y, nextX, gap);
            if (first == null || second == null
                    || Math.abs(first.x() - second.x()) > 1 || Math.abs(first.y() - second.y()) > 1
                    || WrappedEighthTriplets.headConnectedLedger(raw, width, height, x, y, first, gap, .38f)
                    || WrappedEighthTriplets.headConnectedLedger(normalized, width, height, x, y, second, gap, .38f))
                return false;
        }
        return true;
    }

    private static boolean printedDottedBeams(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners) {
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 25) return false;
        var budget = new PrintedBeamShafts.Budget(650_000);
        PrintedBeamShafts.Shaft previous = null;
        float priorGap = 0;
        for (int i = 0; i < owners.size(); i++) {
            var note = notes.get(owners.get(i));
            float x = xAt(note, bar, width), y = note.pageY() * height;
            var staff = PrintedDirectionStaff.printedRules(ink, width, height, x,
                    bar.bottom() * height - 2.1f * nominal, nominal);
            float gap = staff == null ? nominal : staff[1];
            var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (shafts.isEmpty() && (note.octaveShift() == 2 || note.harmonicSemitones() == 24
                    || note.stemDirection() > 0)) {
                // A diamond may interrupt the sampled shaft above or below the filled base.
                float alternative = note.stemDirection() > 0 ? y - 1.5f * gap : y + gap;
                shafts = PrintedBeamShafts.at(ink, width, height, x, alternative, gap, budget);
            }
            if (budget.refused() || shafts.size() != 1) return false;
            var shaft = shafts.get(0);
            if (shaft.direction() != -note.stemDirection()
                    || shaft.direction() * (shaft.endY() - y) < gap * 2.4f) return false;
            if (previous != null) {
                boolean split = i % 4 == 0;
                float meanGap = (priorGap + gap) * .5f;
                float span = (shaft.left() + shaft.right() - previous.left() - previous.right()) * .5f;
                if (span < meanGap * 1.25f || span > meanGap * 4f) return false;
                if (previous.direction() != shaft.direction()) {
                    if (!split || Math.abs(previous.endY() - shaft.endY()) < 3f * meanGap)
                        return false;
                } else if (!WrappedEighthTriplets.matchesBeamRails(
                        ink, width, height, previous, shaft, meanGap, split ? 0 : 2)) return false;
            }
            boolean backward = OwnedTertiaryBeamlets.hasShortHook(ink, width, height, shaft, gap, -1);
            if (i % 2 == 1 && !backward)
                backward = ClosedDottedHookCadence.staffJoinedBackwardHook(ink, width, height, shaft, gap);
            if (backward != (i % 2 == 1)
                    || OwnedTertiaryBeamlets.hasShortHook(ink, width, height, shaft, gap, 1)) return false;
            previous = shaft;
            priorGap = gap;
        }
        return true;
    }

    private static boolean closedFourthSpelling(List<ScoreNoteEvent> notes, List<Map<String, Object>> events,
            List<Integer> owners, Map<ScoreNoteEvent, Integer> literals, int key) {
        var carry = new HashMap<Integer, Integer>();
        for (int i : owners) {
            var n = notes.get(i);
            Integer touch = literals.get(n);
            if (touch == null || touch < -1 || touch > ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return false;
            int base = n.diatonicPitchIdentity(), fourth = base + 3;
            int stopped = ScoreTiePitchGuard.writtenMidi(base, n.writtenAccidental(), key);
            if (!Integer.valueOf(stopped + 24).equals(events.get(i).get("midi"))) return false;
            if (n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                carry.put(base, n.writtenAccidental());
            if (touch != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) carry.put(fourth, touch);
            int touched = ScoreTiePitchGuard.writtenMidi(fourth,
                    carry.getOrDefault(fourth, ScoreNoteEvent.ACCIDENTAL_FROM_KEY), key);
            if (touched - stopped != 5) return false;
        }
        return true;
    }
}
