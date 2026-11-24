// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Two upstem compound quarters with opposed touching positions and printed open fingerings. */
final class UpstemCompoundHarmonicQuarters {
    private UpstemCompoundHarmonicQuarters() {}

    private record Fingering(int base, int touch, String placement) {
        Map<String, Object> written() {
            return Map.of("base", base, "touch", touch, "placement", placement);
        }
    }

    private record Quarter(int source, ScoreNoteEvent note, int written, int artificial, int natural,
            int harmonic, float x, float naturalY, int shaft, Fingering stopped, Fingering open) {}

    static ScorePageInterpretation retain(
            ScorePageInterpretation score, List<Map<String, Object>> events,
            List<PlayingTechniqueDetector.Word> words, byte[] raw, byte[] normalized,
            int width, int height, float[] beats, double[] starts, int[] keys) {
        var notes = score.notes();
        var revised = new ArrayList<>(notes);
        boolean changed = false;
        for (var meter : score.meterChanges()) {
            int bar = meter.measureIndex();
            if (meter.numerator() != 2 || meter.denominator() != 4 || bar < 1
                    || bar + 1 >= score.measures().size() || beats[bar] != 2
                    || score.measures().get(bar).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) continue;
            var owners = IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == bar)
                    .boxed().sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            if (owners.size() != 2 || !ordinary(notes, events, owners.get(0), starts[bar], keys[bar], true)
                    || !ordinary(notes, events, owners.get(1), starts[bar] + 1, keys[bar], false))
                continue;
            var source = prove(score, owners, words, raw, width, height, bar, keys[bar]);
            if (source.isEmpty() || !source.equals(
                    prove(score, owners, words, normalized, width, height, bar, keys[bar])))
                continue;
            for (var q : source) {
                var event = events.get(q.source());
                event.put("writtenMidi", q.written());
                event.put("harmonicTouchMidi", q.artificial());
                event.put("midi", q.written() + q.harmonic());
                event.put(q.harmonic() == 19 ? "artificialHarmonicFifth" : "artificialHarmonicFourth", true);
                if (q.harmonic() == 24) event.put("compoundHarmonicFourth", true);
                event.put("compoundUpstemHarmonicBar", true);
                event.put("compoundHarmonicFingering", q.stopped().written());
                event.put("compoundNaturalHarmonicFingering", q.open().written());
                var touch = q.note().openStringTouch();
                if (touch == null)
                    touch = new ScoreNoteEvent.OpenStringTouch(q.natural(), q.naturalY() / height,
                            ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
                revised.set(q.source(), q.note().withOpenStringTouch(touch).withHarmonicSemitones(q.harmonic()));
            }
            changed = true;
        }
        if (!changed) return score;
        var ownership = IntStream.range(0, notes.size()).boxed().toList();
        return score.withNotes(revised, ownership).withClockContextSource(score.clockContextSource());
    }

    /** Retain the folded open-string touch above the first closed upstem chord. */
    static ScorePageInterpretation recoverOpeningTouchAlias(
            ScorePageInterpretation score, List<Map<String, Object>> events,
            List<PlayingTechniqueDetector.Word> words, byte[] raw, byte[] normalized,
            int width, int height, float[] beats, double[] starts, int[] keys) {
        var notes = score.notes();
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || notes.size() != events.size()) return score;
        var revised = new ArrayList<>(notes);
        for (var meter : score.meterChanges()) {
            int bar = meter.measureIndex();
            if (meter.numerator() != 2 || meter.denominator() != 4 || bar < 1 || bar + 1 >= score.measures().size()
                    || beats[bar] != 2 || score.measures().get(bar).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == bar)) continue;
            var owners = IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == bar)
                    .boxed().sorted(Comparator.<Integer>comparingDouble(i -> eventX(events.get(i)))).toList();
            if (owners.size() != 4) continue;
            int alias = owners.get(0), first = owners.get(1);
            if (notes.get(alias).staffStep() < notes.get(first).staffStep()) {
                int swap = first; first = alias; alias = swap;
            }
            int lower = owners.get(2), upper = owners.get(3);
            if (notes.get(lower).staffStep() > notes.get(upper).staffStep()) {
                int swap = lower; lower = upper; upper = swap;
            }
            var natural = notes.get(alias);
            var base = notes.get(first);
            var lo = notes.get(lower);
            var hi = notes.get(upper);
            if (natural.staffStep() - base.staffStep() != 5 || hi.staffStep() - lo.staffStep() != 5
                    || !openingOwner(natural, events.get(alias), alias, keys[bar], starts[bar], true)
                    || !openingOwner(base, events.get(first), first, keys[bar], starts[bar], false)
                    || !openingOwner(lo, events.get(lower), lower, keys[bar], starts[bar] + 1, false)
                    || !openingOwner(hi, events.get(upper), upper, keys[bar], starts[bar] + 1, false)) continue;
            int written = midi(natural.staffStep(), natural.writtenAccidental(), keys[bar]);
            var open = OpenStringHarmonics.sounding(written);
            if (open == null || open.sounding() - open.open() != 19
                    || List.of(base, lo, hi).stream().anyMatch(n -> midi(n.staffStep() + 3, n.writtenAccidental(), keys[bar])
                            - midi(n.staffStep(), n.writtenAccidental(), keys[bar]) != 5)) continue;

            var region = score.measures().get(bar);
            var following = score.measures().get(bar + 1);
            float x = eventX(events.get(first)), x2 = eventX(events.get(lower));
            float y = base.pageY() * height, y2 = lo.pageY() * height, outer = natural.pageY() * height;
            var source = openingInk(raw, width, height, region, following, words, x, x2, y, y2, outer,
                    eventX(events.get(alias)), eventX(events.get(upper)), hi.pageY() * height, base.staffStep());
            if (source == null || !source.equals(openingInk(normalized, width, height, region, following,
                    words, x, x2, y, y2, outer, eventX(events.get(alias)), eventX(events.get(upper)),
                    hi.pageY() * height, base.staffStep()))) continue;

            var touch = new ScoreNoteEvent.OpenStringTouch(natural.staffStep(), natural.pageY(),
                    natural.writtenAccidental());
            revised.set(alias, new ScoreNoteEvent(bar, base.positionInMeasure(), natural.staffStep(), 0, 1,
                    natural.pageY(), false, 0, 0, natural.writtenAccidental(), 1, 1, 0, 0,
                    natural.clefBottomDiatonic()).withStemDirection(1));
            var event = events.get(alias);
            event.put("x", x);
            event.put("midi", open.sounding());
            event.put("writtenMidi", written);
            event.put("harmonicTouchMidi", written);
            event.put("openStringMidi", open.open());
            event.put("durationBeats", 1.0);
            event.put("compoundHarmonicFingering", source.natural().written());
            retainStoppedFourth(revised, events, first, base.withOpenStringTouch(touch), keys[bar], source.first());
            events.get(first).put("compoundHarmonicFourth", true);
            retainStoppedFourth(revised, events, lower, lo, keys[bar], source.lower());
            retainStoppedFourth(revised, events, upper, hi, keys[bar], source.upper());
            events.get(lower).put("sharedUpstemHarmonicQuarter", true);
            events.get(upper).put("sharedUpstemHarmonicQuarter", true);
        }
        if (revised.equals(notes)) return score;
        return score.withNotes(revised, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    /** The lower touching head of the second chord may masquerade as an extra half note. */
    static ScorePageInterpretation recoverClosingTouchAlias(
            ScorePageInterpretation score, List<Map<String, Object>> events,
            List<PlayingTechniqueDetector.Word> words, byte[] raw, byte[] normalized,
            int width, int height, float[] beats, double[] starts, int[] keys) {
        var notes = score.notes();
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || notes.size() != events.size()) return score;
        var revised = new ArrayList<>(notes);
        var origins = new ArrayList<>(IntStream.range(0, notes.size()).boxed().toList());
        var replaced = new java.util.HashSet<Integer>();
        var clock = score.clockContextSource();
        for (var meter : score.meterChanges()) {
            int bar = meter.measureIndex();
            if (meter.numerator() != 2 || meter.denominator() != 4 || bar < 1 || bar + 1 >= score.measures().size()
                    || beats[bar] != 2 || score.measures().get(bar).cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == bar)) continue;
            var owners = IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == bar)
                    .boxed().sorted(Comparator.<Integer>comparingDouble(i -> eventX(events.get(i)))).toList();
            if (owners.size() != 4) continue;
            int first = owners.get(0);
            var closing = owners.subList(1, 4).stream()
                    .sorted(Comparator.comparingInt(i -> notes.get(i).staffStep())).toList();
            int lower = closing.get(0), alias = closing.get(1), upper = closing.get(2);
            var base = notes.get(first);
            var lo = notes.get(lower);
            var ghost = notes.get(alias);
            var hi = notes.get(upper);
            double onset = starts[bar];
            int key = keys[bar];
            if (ghost.staffStep() - lo.staffStep() != 2 || hi.staffStep() - lo.staffStep() != 4
                    || clock.voiceGroups().stream().anyMatch(group -> group.noteIndices().contains(alias))
                    || !quarterRead(base, events.get(first), first, key, onset, 1, 2, 1)
                    || !quarterRead(ghost, events.get(alias), alias, key, onset + 1, 2, 0, 1)
                    || !quarterRead(lo, events.get(lower), lower, key, onset + 1, 1, 0, 1)
                    || !quarterRead(hi, events.get(upper), upper, key, onset + 1, 1, 0, 0)) continue;
            int naturalStep = base.staffStep() - 2;
            int naturalMidi = midi(naturalStep, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
            var natural = OpenStringHarmonics.sounding(naturalMidi);
            int lowPitch = midi(lo.staffStep(), lo.writtenAccidental(), key);
            int lowTouch = midi(ghost.staffStep(), ghost.writtenAccidental(), key);
            if (natural == null || naturalMidi - natural.open() != 4 || lowTouch - lowPitch != 4
                    || !formsFourth(base, key) || !formsFourth(hi, key)) continue;
            var region = score.measures().get(bar);
            var following = score.measures().get(bar + 1);
            float x = eventX(events.get(first)), x2 = eventX(events.get(lower));
            var upperShape = new HeadGeometry(eventX(events.get(upper)), hi.pageY() * height);
            var aliasShape = new HeadGeometry(eventX(events.get(alias)), ghost.pageY() * height);
            var a = closingTouchInk(raw, width, height, region, following, words,
                    x, x2, base.pageY() * height, lo.pageY() * height, upperShape, aliasShape, base.staffStep(),
                    lo.staffStep(), hi.staffStep());
            if (a == null) continue;
            var b = closingTouchInk(normalized, width, height, region, following, words,
                    x, x2, base.pageY() * height, lo.pageY() * height, upperShape, aliasShape, base.staffStep(),
                    lo.staffStep(), hi.staffStep());
            if (b == null || Math.abs(a.naturalY() - b.naturalY()) > 1
                    || a.firstStem() != b.firstStem() || a.secondStem() != b.secondStem()) continue;
            var touch = new ScoreNoteEvent.OpenStringTouch(
                    naturalStep, a.naturalY() / height, ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
            revised.set(alias, new ScoreNoteEvent(bar, base.positionInMeasure(), naturalStep, 0, 1,
                    touch.pageY(), false, 0, 0, touch.writtenAccidental(), 1, 1, 0, 0,
                    base.clefBottomDiatonic()).withStemDirection(1));
            // The discarded half was the second chord's touch. The opening diamond has its own source position.
            origins.set(alias, -1);
            replaced.add(alias);
            var event = events.get(alias);
            event.put("x", x);
            event.put("y", a.naturalY());
            event.put("midi", natural.sounding());
            event.put("writtenMidi", naturalMidi);
            event.put("harmonicTouchMidi", naturalMidi);
            event.put("openStringMidi", natural.open());
            event.put("startBeat", onset);
            event.put("durationBeats", 1.0);
            retainStoppedFourth(revised, events, first, base.withOpenStringTouch(touch), key, a.firstFingers());
            events.get(first).put("compoundHarmonicFourth", true);
            events.get(first).put("openingLowerOpenFourth", true);
            retainStoppedFourth(revised, events, upper, hi.withStemDirection(1), key, a.secondFingers());
            events.get(upper).put("sharedUpstemHarmonicQuarter", true);
            var lowEvent = events.get(lower);
            lowEvent.put("midi", lowPitch + 28);
            lowEvent.put("writtenMidi", lowPitch);
            lowEvent.put("harmonicTouchMidi", lowTouch);
            lowEvent.put("artificialHarmonicThird", true);
            lowEvent.put("sharedUpstemHarmonicQuarter", true);
            revised.set(lower, lo.withHarmonicSemitones(28));
        }
        if (revised.equals(notes)) return score;
        return score.withNotes(revised, origins).withClockContextSource(clock.withoutNoteObservations(replaced));
    }

    private record HeadGeometry(float x, float y) {}

    private record ClosingTouch(float naturalY, int firstStem, int secondStem,
            Fingering firstFingers, Fingering secondFingers) {}

    private static ClosingTouch closingTouchInk(byte[] ink, int width, int height, MeasureRegion region,
            MeasureRegion following, List<PlayingTechniqueDetector.Word> words, float x, float x2, float y, float y2,
            HeadGeometry upper, HeadGeometry alias, int firstStep, int lowStep, int upperStep) {
        float nominal = (following.bottom() - following.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 24 || Math.abs(region.top() - following.top()) * height > .35f * nominal)
            return null;
        var frame = PrintedDirectionStaff.printedRules(ink, width, height,
                (x + x2) * .5f, upper.y() + upperStep * nominal * .5f, nominal);
        if (frame == null) return null;
        float gap = frame[1], floor = frame[0];
        if (x2 - x < 3.15f * gap || x2 - x > 4.5f * gap || Math.abs(upper.x() - x2) > .13f * gap
                || Math.abs(alias.x() - x2) > .21f * gap
                || Math.abs(alias.y() - (floor - (lowStep + 2) * gap * .5f)) > .2f * gap
                || Math.abs(y - (floor - firstStep * gap * .5f)) > .23f * gap
                || Math.abs(y2 - (floor - lowStep * gap * .5f)) > .23f * gap
                || Math.abs(upper.y() - (floor - upperStep * gap * .5f)) > .23f * gap
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, x, y, gap)
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, x2, y2, gap)
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, upper.x(), upper.y(), gap)) return null;
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        float separator = (region.right() + following.left()) * width * .5f;
        if (!ClosedSixteenthPairBeforeHalf.ownedSeparator(ink, width, height, staff, separator)
                || !PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, x - 1.65f * gap, separator, x, x2, false)) return null;
        float naturalY = floor - (firstStep - 2) * gap * .5f;
        float top = floor - (firstStep + 3) * gap * .5f;
        var first = openingShaft(ink, width, height, x, y, top, naturalY, staff);
        var candidates = PrintedBeamShafts.at(ink, width, height, x2, y2, gap, new PrintedBeamShafts.Budget(80_000));
        if (first == null || candidates.size() != 1 || candidates.get(0).direction() != -1) return null;
        var second = candidates.get(0);
        float upperTouch = floor - (upperStep + 3) * gap * .5f;
        for (var column : second.columns()) {
            int shaft = column.x(), end = column.endY();
            if (Math.abs(end - second.endY()) > 1
                    || !ClosedDoubleHarmonicQuarters.slantedTouchCounter(
                            ink, width, height, alias.x(), alias.y(), gap, shaft)
                    || !ArtificialHarmonics.shaftCrossedDiamond(
                            ink, width, height, upper.x(), upperTouch, gap, shaft, 1, false)
                    || !ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, shaft, upperTouch, upper.y(), end, 1, staff)) continue;
            var firstFingers = fingers(words, x, first.endY() - 3.2f * gap,
                    first.endY() + .42f * gap, gap, width, height, "above");
            var secondFingers = fingers(words, x2, end - 3.2f * gap,
                    end + .42f * gap, gap, width, height, "above");
            return new ClosingTouch(naturalY, first.left(), shaft,
                    firstFingers != null && firstFingers.base() > 0 ? firstFingers : null,
                    secondFingers != null && secondFingers.base() > 0 ? secondFingers : null);
        }
        return null;
    }

    private static boolean formsFourth(ScoreNoteEvent n, int key) {
        return midi(n.staffStep() + 3, n.writtenAccidental(), key)
                - midi(n.staffStep(), n.writtenAccidental(), key) == 5;
    }

    private record OpeningFingers(Fingering natural, Fingering first, Fingering lower, Fingering upper) {}

    private static OpeningFingers openingInk(byte[] ink, int width, int height, MeasureRegion region,
            MeasureRegion following, List<PlayingTechniqueDetector.Word> words,
            float x, float x2, float y, float y2, float outer, float aliasX, float upperX, float upperY, int step) {
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 24 || Math.abs(region.top() - following.top()) * height > .35f * nominal)
            return null;
        var frame = PrintedDirectionStaff.printedRules(ink, width, height,
                (region.left() + region.right()) * width * .5f, region.bottom() * height - 2.1f * nominal, nominal);
        if (frame == null) return null;
        float gap = frame[1], floor = frame[0];
        if (x2 - x < 3.5f * gap || x2 - x > 5.5f * gap || Math.abs(x - aliasX) > .14f * gap
                || Math.abs(x2 - upperX) > .14f * gap || Math.abs(y - (floor - step * gap * .5f)) > .23f * gap
                || Math.abs(outer - (floor - (step + 5) * gap * .5f)) > .22f * gap
                || Math.abs(upperY - (y2 - 2.5f * gap)) > .27f * gap
                || !filledCore(ink, width, height, x, y) || !filledCore(ink, width, height, x2, y2)
                || !filledCore(ink, width, height, upperX, upperY)) return null;
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        float separator = (region.right() + following.left()) * width * .5f;
        if (!ClosedSixteenthPairBeforeHalf.ownedSeparator(ink, width, height, staff, separator)
                || !PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, x - 1.65f * gap, separator, x, x2, false)) return null;
        var opening = openingShaft(ink, width, height, x, y, outer, floor - (step + 3) * gap * .5f, staff);
        var closing = openingShaft(ink, width, height, x2, y2, upperY - 1.5f * gap, y2 - 1.5f * gap, staff);
        if (opening == null || closing == null
                || !isolatedUpperZero(words, ink, width, height, x, outer, gap)) return null;
        var above = fingers(words, x, opening.endY() - 3.2f * gap, opening.endY() + .42f * gap, gap,
                width, height, "above");
        var below = fingers(words, x, y + .65f * gap, y + 4.4f * gap, gap, width, height, "below");
        var upper = fingers(words, x2, closing.endY() - 3.2f * gap, closing.endY() + .42f * gap, gap,
                width, height, "above");
        var lower = fingers(words, x2, y2 + .65f * gap, y2 + 4.4f * gap, gap, width, height, "below");
        return above != null && above.base() == 0 && below != null && below.base() > 0
                && upper != null && upper.base() > 0 && lower != null && lower.base() > 0
                ? new OpeningFingers(above, below, lower, upper) : null;
    }

    private static PrintedBeamShafts.Shaft openingShaft(byte[] ink, int width, int height,
            float x, float body, float upper, float middle, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        var shafts = PrintedBeamShafts.at(ink, width, height, x, body, gap, new PrintedBeamShafts.Budget(80_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -1) return null;
        var shaft = shafts.get(0);
        for (int col = shaft.left(); col <= shaft.right(); col++) {
            if (ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, upper, gap, col, 1, true)
                    && ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, middle, gap, col, 1, true)
                    && ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, col, upper, body, shaft.endY(), 1, staff))
                return shaft;
        }
        return null;
    }

    private static boolean filledCore(byte[] ink, int width, int height, float x, float y) {
        int cx = Math.round(x), cy = Math.round(y);
        if (cx < 2 || cy < 2 || cx + 2 >= width || cy + 2 >= height) return false;
        for (int row = cy - 2; row <= cy + 2; row++)
            for (int col = cx - 2; col <= cx + 2; col++)
                if ((ink[row * width + col] & 255) >= 165) return false;
        return true;
    }

    private static float eventX(Map<String, Object> event) {
        return event.get("x") instanceof Number x ? x.floatValue() : Float.NaN;
    }

    private static boolean openingOwner(ScoreNoteEvent n, Map<String, Object> e, int owner, int key,
            double onset, boolean alias) {
        return quarterRead(n, e, owner, key, onset, alias ? 4 : 1, alias ? 0 : 2, alias ? 0 : 1);
    }

    private static boolean quarterRead(ScoreNoteEvent n, Map<String, Object> e, int owner, int key,
            double onset, int duration, int octave, int stem) {
        int written = midi(n.staffStep(), n.writtenAccidental(), key);
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE && n.stemDirection() == stem
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.beamCount() == 0
                && n.unbeamedDurationBeats() == duration && n.augmentationDots() == 0 && n.tupletDivisor() == 1
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY && n.articulations() == 0
                && !n.tiedFromPrevious() && n.boundaryTies() == 0 && !n.crossStaffBeam()
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null && n.octaveShift() == octave
                && Integer.valueOf(owner).equals(e.get("sourceNoteIndex")) && Float.isFinite(eventX(e))
                && Integer.valueOf(written + 12 * octave).equals(e.get("midi"))
                && e.get("startBeat") instanceof Number start && start.doubleValue() == onset
                && e.get("durationBeats") instanceof Number time && time.doubleValue() == duration
                && Boolean.FALSE.equals(e.get("durationFallback")) && Boolean.FALSE.equals(e.get("clefInferred"))
                && !e.containsKey("writtenPlacement");
    }

    private static void retainStoppedFourth(List<ScoreNoteEvent> notes, List<Map<String, Object>> events,
            int owner, ScoreNoteEvent note, int key, Fingering fingers) {
        int written = midi(note.staffStep(), note.writtenAccidental(), key);
        var event = events.get(owner);
        event.remove("octaveShift");
        event.put("writtenMidi", written);
        event.put("harmonicTouchMidi", written + 5);
        event.put("artificialHarmonicFourth", true);
        event.put("midi", written + 24);
        if (fingers != null) event.put("compoundHarmonicFingering", fingers.written());
        notes.set(owner, note.withOctaveShift(0).withHarmonicSemitones(24));
    }

    private static boolean ordinary(List<ScoreNoteEvent> notes, List<Map<String, Object>> events,
            int owner, double onset, int key, boolean lowerNatural) {
        var n = notes.get(owner);
        var e = events.get(owner);
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE && n.stemDirection() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.beamCount() == 0
                && n.unbeamedDurationBeats() == 1 && n.augmentationDots() == 0 && n.tupletDivisor() == 1
                && n.articulations() == 0 && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0 && !n.crossStaffBeam()
                && n.octaveShift() == 0 && n.harmonicSemitones() == 0
                && (n.openStringTouch() != null) == lowerNatural
                && Integer.valueOf(owner).equals(e.get("sourceNoteIndex"))
                && Integer.valueOf(midi(n.staffStep(), n.writtenAccidental(), key)).equals(e.get("midi"))
                && Double.valueOf(onset).equals(e.get("startBeat")) && Main.completeCompoundQuarter(e, onset, onset + 1);
    }

    private static List<Quarter> prove(ScorePageInterpretation score, List<Integer> owners,
            List<PlayingTechniqueDetector.Word> words, byte[] ink, int width, int height,
            int bar, int key) {
        var region = score.measures().get(bar);
        var after = score.measures().get(bar + 1);
        float span = (region.right() - region.left()) * width;
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 24 || Math.abs(region.top() - after.top()) * height > nominal * .35f)
            return List.of();
        var frame = PrintedDirectionStaff.printedRules(ink, width, height, region.left() * width + span * .5f,
                region.bottom() * height - nominal * 2.1f, nominal);
        if (frame == null) return List.of();
        float floor = frame[0], gap = frame[1];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        if (!ClosedSixteenthPairBeforeHalf.ownedSeparator(ink, width, height, staff,
                (region.right() + after.left()) * width * .5f)) return List.of();
        var first = quarter(score.notes().get(owners.get(0)), owners.get(0), region,
                staff, words, ink, width, height, key, true);
        var second = quarter(score.notes().get(owners.get(1)), owners.get(1), region,
                staff, words, ink, width, height, key, false);
        if (first == null || second == null || second.x() - first.x() < gap * 3
                || second.x() - first.x() > gap * 6.5f) return List.of();
        return List.of(first, second);
    }

    private static Quarter quarter(ScoreNoteEvent n, int owner, MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff, List<PlayingTechniqueDetector.Word> words,
            byte[] ink, int width, int height, int key, boolean lowerNatural) {
        float gap = staff.gap();
        float x = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
        float y = n.pageY() * height;
        int step = n.staffStep(), naturalStep = step + (lowerNatural ? -2 : 5);
        int artificialStep = step + (lowerNatural ? 4 : 3);
        float naturalY = staff.bottom() - naturalStep * gap * .5f;
        float artificialY = staff.bottom() - artificialStep * gap * .5f;
        if (x < 3 * gap || x >= width - 3 * gap || naturalY < gap * 5f
                || y >= height - 5 * gap || Math.abs(y - (staff.bottom() - step * gap * .5f)) > gap * .23f)
            return null;
        var existing = n.openStringTouch();
        if (lowerNatural && (existing.staffStep() != naturalStep
                || Math.abs(existing.pageY() * height - naturalY) > gap * .23f)) return null;

        int center = Math.round(x), bodyY = Math.round(y);
        for (int row = bodyY - 2; row <= bodyY + 2; row++)
            for (int col = center - 2; col <= center + 2; col++)
                if ((ink[row * width + col] & 255) >= 165) return null;
        var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, new PrintedBeamShafts.Budget(80_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -1) return null;
        var shaft = shafts.get(0);
        float upper = Math.min(naturalY, artificialY);
        float lower = Math.max(naturalY, y);
        var above = fingers(words, x, shaft.endY() - 3.2f * gap, shaft.endY() + .42f * gap, gap,
                width, height, "above");
        var below = fingers(words, x, lower + .8f * gap, lower + 4.4f * gap, gap, width, height, "below");
        if (above == null || below == null || (above.base() == 0) == lowerNatural
                || (below.base() == 0) != lowerNatural
                || !lowerNatural && !isolatedUpperZero(words, ink, width, height, x, naturalY, gap))
            return null;

        int stoppedMidi = midi(step, n.writtenAccidental(), key);
        int artificial = midi(artificialStep, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int naturalMidi = midi(naturalStep, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int harmonic = lowerNatural ? 19 : 24;
        int sound = OpenStringHarmonics.uniqueSoundingMidi(naturalMidi);
        if (artificial - stoppedMidi != (lowerNatural ? 7 : 5) || sound < 0
                || stoppedMidi < 0 || stoppedMidi + harmonic > 127 || sound == stoppedMidi + harmonic)
            return null;
        for (int col = shaft.left(); col <= shaft.right(); col++) {
            if (!ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, naturalY, gap, col, 1, true)
                    || !artificialDiamond(ink, width, height, x, artificialY, gap, col, lowerNatural)
                    || !ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, col, upper, y, shaft.endY(), 1, staff)) continue;
            int gaps = 0;
            for (int row = Math.round(upper + .22f * gap); row <= Math.round(lower - .26f * gap); row++)
                if ((ink[row * width + col] & 255) >= 165) gaps++;
            if (gaps > 2) continue;
            return new Quarter(owner, n, stoppedMidi, artificial, naturalStep, harmonic, x,
                    lowerNatural ? existing.pageY() * height : naturalY, col,
                    lowerNatural ? above : below, lowerNatural ? below : above);
        }
        return null;
    }

    private static boolean artificialDiamond(byte[] ink, int width, int height, float x, float y,
            float gap, int shaft, boolean fifth) {
        if (ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, y, gap, shaft, 1, true))
            return true;
        if (fifth) return false;
        // A horizontal staff rule can close the lower counter of a touching head.
        return ArtificialHarmonics.shaftCrossedDiamond(
                ink, width, height, x, y - .24f * gap, gap, shaft, 1, true);
    }

    private static Fingering fingers(List<PlayingTechniqueDetector.Word> words, float x, float top,
            float bottom, float gap, int w, int h, String placement) {
        var nearby = words.stream().filter(word -> word.text() != null && word.text().matches("[0-4]{1,2}")
                        && Math.abs((word.left() + word.right()) * w * .5f - x) <= gap * .54f
                        && (word.right() - word.left()) * w <= gap * 2.4f
                        && (word.top() + word.bottom()) * h * .5f >= top
                        && (word.top() + word.bottom()) * h * .5f <= bottom)
                .sorted(Comparator.comparingDouble(PlayingTechniqueDetector.Word::top)).toList();
        var text = String.join("", nearby.stream().map(PlayingTechniqueDetector.Word::text).toList());
        if (!text.matches("[1-4][0-3]") || text.charAt(0) <= text.charAt(1)) return null;
        return new Fingering(text.charAt(1) - '0', text.charAt(0) - '0', placement);
    }

    private static boolean isolatedUpperZero(List<PlayingTechniqueDetector.Word> words, byte[] ink,
            int width, int height, float x, float diamondY, float gap) {
        for (var word : words) {
            if (word.text() == null || !word.text().matches("[1-4]0")
                    || Math.abs((word.left() + word.right()) * width * .5f - x) > gap * .54f) continue;
            int l = Math.round(word.left() * width), r = Math.round(word.right() * width);
            int top = Math.round((word.top() + word.bottom()) * height * .5f);
            int bottom = Math.round(word.bottom() * height);
            if (l < 0 || r >= width || r - l > gap * 2.4f || top < 0 || bottom >= height
                    || diamondY - bottom < gap * 2.0f || diamondY - bottom > gap * 3.3f
                    || bottom - top < gap * 1.3f || bottom - top > gap * 2.0f) continue;
            var box = new NaturalHarmonicRingProof.Box(l, top, r, bottom);
            var rings = NaturalHarmonicRingProof.rings(ink, width, height, box, 154, 6, 26,
                    new NaturalHarmonicRingProof.Budget(250_000, 250_000));
            if (rings.size() == 1 && rings.get(0).counterPixels() >= gap * gap * .19f
                    && Math.abs(rings.get(0).centerX() - x) <= gap * .34f) return true;
        }
        return false;
    }

    private static int midi(int step, int accidental, int key) {
        return ScoreTiePitchGuard.writtenMidi(ScoreNoteEvent.CLEF_TREBLE + step, accidental, key);
    }
}
