// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Two complete downstem quarters each combine a stopped fourth with a lower open-string touch. */
final class ClosedDownstemCompoundQuarters {
    private ClosedDownstemCompoundQuarters() {}

    private record Head(int owner, ScoreNoteEvent note, float x, float y) {}
    private record Quarter(int shaft, float openY) {}

    static ScorePageInterpretation recover(ScorePageInterpretation score,
            byte[] normalized, byte[] raw, int width, int height, int initialKey) {
        if (normalized == null || raw == null || normalized.length != (long) width * height
                || raw.length != normalized.length) return score;
        var bars = score.measures();
        var notes = score.notes();
        var replacements = new HashMap<Integer, ScoreNoteEvent>();
        var removed = new BitSet();
        int meterIndex = 0;
        ScoreMeterChange meter = null;
        for (int bar = 1; bar + 1 < bars.size(); bar++) {
            while (meterIndex < score.meterChanges().size()
                    && score.meterChanges().get(meterIndex).measureIndex() <= bar)
                meter = score.meterChanges().get(meterIndex++);
            if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) continue;
            int measure = bar;
            var region = bars.get(bar);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float span = (region.right() - region.left()) * width;
            if (region.cadenceQuarterBeats() != 0 || gap < 12 || gap > 24
                    || span < 9.2f * gap || span > 12.5f * gap
                    || Math.abs(region.top() - bars.get(bar - 1).top()) * height > .2f * gap
                    || Math.abs(region.top() - bars.get(bar + 1).top()) * height > .2f * gap
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == measure)) continue;
            var owners = IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == measure)
                    .boxed().sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
            if (owners.size() != 4 || owners.stream().anyMatch(i -> !plainQuarter(notes.get(i)))) continue;
            var heads = new ArrayList<Head>(4);
            for (int i : owners) {
                var n = notes.get(i);
                heads.add(new Head(i, n, region.left() * width + span * n.positionInMeasure(), n.pageY() * height));
            }
            var firstTouch = heads.get(0);
            var firstStop = heads.get(1);
            var lastTouch = heads.get(2);
            var lastStop = heads.get(3);
            if (lastStop.x - firstStop.x < 3.7f * gap || lastStop.x - firstStop.x > 5.1f * gap
                    || !writtenFourth(firstTouch, firstStop, gap) || !writtenFourth(lastTouch, lastStop, gap)
                    || !unlinked(score, firstTouch.owner) || !unlinked(score, lastTouch.owner)) continue;
            int key = score.keyChanges().stream().filter(change -> change.measureIndex() <= measure)
                    .max(Comparator.comparingInt(ScoreKeyChange::measureIndex)).map(ScoreKeyChange::fifths)
                    .orElse(initialKey);
            if (!compatiblePitches(firstStop.note, key) || !compatiblePitches(lastStop.note, key)) continue;
            var a = print(normalized, width, height, region, bars.get(bar - 1), firstTouch, firstStop,
                    lastTouch, lastStop, gap);
            var b = print(raw, width, height, region, bars.get(bar - 1), firstTouch, firstStop,
                    lastTouch, lastStop, gap);
            if (a.size() != 2 || b.size() != 2) continue;
            for (int i = 0; i < 2; i++) {
                if (Math.abs(a.get(i).shaft - b.get(i).shaft) > 1
                        || Math.abs(a.get(i).openY - b.get(i).openY) > 1) {
                    b = List.of();
                    break;
                }
            }
            if (b.isEmpty()) continue;
            var keep = List.of(firstStop, lastStop);
            for (int i = 0; i < 2; i++) {
                var head = keep.get(i);
                var open = new ScoreNoteEvent.OpenStringTouch(head.note.staffStep() - 2,
                        b.get(i).openY / height, ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
                replacements.put(head.owner, head.note.withHarmonicSemitones(24).withOpenStringTouch(open));
            }
            removed.set(firstTouch.owner);
            removed.set(lastTouch.owner);
        }
        if (removed.isEmpty()) return score;
        var kept = new ArrayList<ScoreNoteEvent>();
        var owners = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            if (removed.get(i)) continue;
            kept.add(replacements.getOrDefault(i, notes.get(i)));
            owners.add(i);
        }
        return score.withNotes(kept, owners);
    }

    /** Two written downstem quarters: stopped third/fourth, then lower open and stopped fourth. */
    static ScorePageInterpretation recoverPenultimateMixed(
            ScorePageInterpretation score, List<Map<String, Object>> events, byte[] labels,
            byte[] raw, byte[] normalized, int width, int height, float[] beats, double[] starts, int[] keys,
            List<SourceLocalAccidental.PrintedMark> printed) throws java.io.IOException {
        var bars = score.measures();
        var notes = score.notes();
        int bar = bars.size() - 2;
        if (raw == null || normalized == null || labels == null || raw.length != (long) width * height
                || normalized.length != raw.length || labels.length != raw.length || bar < 1
                || notes.size() != events.size() || beats.length != bars.size() || beats[bar] != 2
                || beats[bar + 1] != 1 || bars.get(bar + 1).cadenceQuarterBeats() != 1
                || bars.get(bar).cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) return score;
        var meter = score.meterChanges().stream().filter(m -> m.measureIndex() <= bar)
                .reduce((a, b) -> b).orElse(null);
        if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) return score;
        var region = bars.get(bar);
        float span = (region.right() - region.left()) * width;
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        if (nominal < 12 || nominal > 24 || span < 9.2f * nominal || span > 12.5f * nominal
                || Math.abs(bars.get(bar - 1).top() - region.top()) * height > nominal * .25f
                || Math.abs(bars.get(bar + 1).top() - region.top()) * height > nominal * .25f) return score;
        var local = new ArrayList<Head>(4);
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.measureIndex() == bar)
                local.add(new Head(i, note, region.left() * width + span * note.positionInMeasure(),
                        note.pageY() * height));
        }
        if (local.size() != 4 || local.stream().anyMatch(h -> !unlinked(score, h.owner))) return score;
        local.sort(Comparator.comparingDouble(Head::x));
        var first = local.subList(0, 2).stream().sorted(Comparator.comparingDouble(Head::y)).toList();
        var second = local.subList(2, 4).stream().sorted(Comparator.comparingDouble(Head::y)).toList();
        var high = first.get(0); var low = first.get(1);
        var next = second.get(0); var alias = second.get(1);
        if (!stoppedFourthClock(high.note) || !stoppedFourthClock(next.note)
                || !plainQuarter(low.note.withHarmonicSemitones(0)
                        .withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FROM_KEY))
                || low.note.harmonicSemitones() != 28
                || low.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || high.note.staffStep() - low.note.staffStep() != 4
                || !stackedFingeringAlias(alias.note)
                || Math.abs(high.x - low.x) > .18f * nominal || Math.abs(next.x - alias.x) > .18f * nominal
                || next.x - high.x < 4 * nominal || next.x - high.x > 5.1f * nominal) return score;
        int key = keys[bar];
        int openStep = next.note.staffStep() - 2;
        int touchMidi = ScoreTiePitchGuard.writtenMidi(ScoreNoteEvent.CLEF_TREBLE + openStep,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        var pitch = OpenStringHarmonics.sounding(touchMidi);
        int lowerMidi = ScoreTiePitchGuard.writtenMidi(low.note.diatonicPitchIdentity(),
                low.note.writtenAccidental(), key);
        int lowerTouchMidi = ScoreTiePitchGuard.writtenMidi(low.note.diatonicPitchIdentity() + 2,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int highStep = high.note.diatonicPitchIdentity();
        int highMidi = ScoreTiePitchGuard.writtenMidi(highStep, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int highTouchMidi = ScoreTiePitchGuard.writtenMidi(highStep + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        if (pitch == null || lowerTouchMidi - lowerMidi != 4 || highTouchMidi - highMidi != 5
                || !compatiblePitches(next.note, key)) return score;
        var a = mixedInk(raw, labels, width, height, bars, bar, high, low, next, alias, nominal);
        var b = mixedInk(normalized, labels, width, height, bars, bar, high, low, next, alias, nominal);
        if (a == null || !a.equals(b)) return score;

        var open = new ScoreNoteEvent(bar, next.note.positionInMeasure(), openStep, 0, 1,
                a.openY / height, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                1, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE).withStemDirection(-1);
        var upper = next.note.withOctaveShift(0).withHarmonicSemitones(24)
                .withOpenStringTouch(new ScoreNoteEvent.OpenStringTouch(openStep, open.pageY(),
                        ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        var firstUpper = high.note.withOctaveShift(0).withHarmonicSemitones(24);
        var highIdentity = Main.noteIdentity(firstUpper, key);
        var nextIdentity = Main.noteIdentity(upper, key);
        if (!highIdentity.get("midi").equals(events.get(high.owner).get("midi"))
                || !Integer.valueOf(lowerMidi + 28).equals(events.get(low.owner).get("midi"))
                || !nextIdentity.get("midi").equals(events.get(next.owner).get("midi"))
                || !Boolean.TRUE.equals(highIdentity.get("artificialHarmonicFourth"))
                || !Boolean.TRUE.equals(nextIdentity.get("artificialHarmonicFourth"))
                || pitch.sounding() == ((Number)nextIdentity.get("midi")).intValue()) return score;
        for (var owner : local) {
            var event = events.get(owner.owner);
            if (!Integer.valueOf(owner.owner).equals(event.get("sourceNoteIndex"))
                    || event.get("writtenPlacement") != null) return score;
        }

        var revised = new ArrayList<>(notes);
        revised.set(high.owner, firstUpper);
        revised.set(next.owner, upper);
        revised.set(alias.owner, open);
        var leftHigh = events.get(high.owner);
        var leftLow = events.get(low.owner);
        var rightHigh = events.get(next.owner);
        var rightOpen = events.get(alias.owner);
        for (var event : List.of(leftHigh, leftLow, rightHigh)) {
            event.remove("octaveShift");
            event.put("durationBeats", 1.0);
            event.put("durationFallback", false);
            event.put("startBeat", event == rightHigh ? starts[bar] + 1 : starts[bar]);
        }
        leftHigh.putAll(highIdentity);
        leftLow.put("writtenMidi", lowerMidi);
        leftLow.put("artificialHarmonicThird", true);
        leftLow.put("harmonicTouchMidi", lowerTouchMidi);
        leftHigh.put("sharedDownstemHarmonicQuarter", true);
        leftLow.put("sharedDownstemHarmonicQuarter", true);
        rightHigh.putAll(nextIdentity);
        rightHigh.put("compoundHarmonicFourth", true);
        rightHigh.put("closedDownstemCompoundBar", true);
        rightOpen.clear();
        rightOpen.put("measureIndex", bar);
        rightOpen.put("staffIndex", 0);
        rightOpen.put("staffCount", 1);
        rightOpen.put("sourceNoteIndex", alias.owner);
        rightOpen.put("midi", pitch.sounding());
        rightOpen.put("writtenMidi", touchMidi);
        rightOpen.put("harmonicTouchMidi", touchMidi);
        rightOpen.put("openStringMidi", pitch.open());
        rightOpen.put("x", next.x);
        rightOpen.put("y", open.pageY() * height);
        rightOpen.put("startBeat", starts[bar] + 1);
        rightOpen.put("durationBeats", 1.0);
        rightOpen.put("durationFallback", false);
        rightOpen.put("clefInferred", false);
        rightOpen.put("tiedFromPrevious", false);

        var literals = OmrScoreInterpreter.harmonicTouchAccidentals(labels, normalized,
                raw, width, height, score,
                List.of(new ArtificialHarmonics.TouchHead(low.note, low.x, low.y, nominal)));
        if (Integer.valueOf(ScoreNoteEvent.ACCIDENTAL_FLAT).equals(literals.get(low.note)))
            printed.add(new SourceLocalAccidental.PrintedMark(low.owner, ScoreNoteEvent.ACCIDENTAL_FLAT));
        return score.withNotes(revised, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    /** The upper touching diamond of a stopped/open dyad can be admitted as a separate half head. */
    static ScorePageInterpretation recoverPenultimateUpperOpen(ScorePageInterpretation score,
            List<Map<String, Object>> events, byte[] labels, byte[] raw, byte[] normalized, int width, int height,
            float[] beats, double[] starts, int[] keys) throws java.io.IOException {
        var bars = score.measures();
        var notes = score.notes();
        int bar = bars.size() - 2;
        if (raw == null || normalized == null || labels == null || raw.length != (long) width * height
                || normalized.length != raw.length || labels.length != raw.length || bar < 1
                || notes.size() != events.size() || beats.length != bars.size()
                || beats[bar] != 2 || beats[bar + 1] != 1
                || bars.get(bar).cadenceQuarterBeats() != 0 || bars.get(bar + 1).cadenceQuarterBeats() != 1
                || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) return score;
        var meter = score.meterChanges().stream().filter(m -> m.measureIndex() <= bar)
                .reduce((a, b) -> b).orElse(null);
        if (meter == null || meter.numerator() != 2 || meter.denominator() != 4) return score;
        var region = bars.get(bar);
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        float span = (region.right() - region.left()) * width;
        if (gap < 12 || gap > 24 || span < 8.2f * gap || span > 11.5f * gap
                || Math.abs(bars.get(bar + 1).top() - region.top()) * height > .22f * gap
                || Math.abs(bars.get(bar - 1).top() - region.top()) * height > .22f * gap) return score;
        var row = new ArrayList<Head>();
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.measureIndex() == bar) row.add(new Head(i, n, region.left() * width + span * n.positionInMeasure(),
                    n.pageY() * height));
        }
        if (row.size() != 4 || row.stream().anyMatch(h -> !unlinked(score, h.owner))) return score;
        row.sort(Comparator.comparingDouble(Head::x));
        var marked = row.subList(2, 4).stream().sorted(Comparator.comparingDouble(Head::y)).toList();
        var alias = marked.get(0);
        var stopped = marked.get(1);
        var n = stopped.note;
        var a = alias.note;
        var opening = row.get(0);
        if (!stoppedFourthClock(n) || a.unbeamedDurationBeats() != 2 || a.beamCount() != 0
                || a.octaveShift() != 0 || a.harmonicSemitones() != 0 || a.openStringTouch() != null
                || a.kind() != ScoreNoteEvent.Kind.PITCHED || a.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || a.stemDirection() != -1 || a.staffCount() != 1 || a.staffIndex() != 0
                || a.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                || a.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || a.augmentationDots() != 0 || a.tupletDivisor() != 1 || a.tupletNormalNotes() != 1
                || a.articulations() != 0 || a.boundaryTies() != 0 || a.tiedFromPrevious()
                || a.crossStaffBeam() || a.leadingRestBeats() != 0 || a.followingRestBeats() != 0
                || a.staffStep() - n.staffStep() != 5 || stopped.y - alias.y < 2.2f * gap
                || stopped.y - alias.y > 2.7f * gap || Math.abs(stopped.x - alias.x) > .2f * gap
                || Math.abs(opening.x - row.get(1).x) > .15f * gap
                || stopped.x - opening.x < 3.6f * gap || stopped.x - opening.x > 4.7f * gap) return score;
        var baseEvent = events.get(stopped.owner);
        var openEvent = events.get(alias.owner);
        if (!atClock(baseEvent, stopped.owner, starts[bar] + 1, 1)
                || !atClock(openEvent, alias.owner, starts[bar] + 1, 2)
                || !atClock(events.get(row.get(0).owner), row.get(0).owner, starts[bar], 1)
                || !atClock(events.get(row.get(1).owner), row.get(1).owner, starts[bar], 1))
            return score;
        int key = keys[bar];
        int touchMidi = ScoreTiePitchGuard.writtenMidi(a.diatonicPitchIdentity(), a.writtenAccidental(), key);
        var natural = OpenStringHarmonics.sounding(touchMidi);
        var base = n.withOctaveShift(0).withHarmonicSemitones(24).withOpenStringTouch(
                new ScoreNoteEvent.OpenStringTouch(a.staffStep(), a.pageY(), a.writtenAccidental()));
        var identity = Main.noteIdentity(base, key);
        if (natural == null || !Integer.valueOf(touchMidi).equals(openEvent.get("midi"))
                || !Boolean.TRUE.equals(identity.get("artificialHarmonicFourth"))
                || !identity.get("midi").equals(baseEvent.get("midi"))
                || touchMidi <= ((Number)identity.get("harmonicTouchMidi")).intValue()
                || natural.sounding() == ((Number)identity.get("midi")).intValue()
                || !upperOpenInk(raw, labels, width, height, bars, bar, opening, alias, stopped, gap)
                || !upperOpenInk(normalized, labels, width, height, bars, bar, opening, alias, stopped, gap))
            return score;

        var written = new ScoreNoteEvent(bar, n.positionInMeasure(), a.staffStep(), 0, 1, a.pageY(), false,
                0, 0, a.writtenAccidental(), 1, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE).withStemDirection(-1);
        var revised = new ArrayList<>(notes);
        revised.set(stopped.owner, base);
        revised.set(alias.owner, written);
        baseEvent.remove("octaveShift");
        baseEvent.putAll(identity);
        baseEvent.put("compoundHarmonicFourth", true);
        if (openEvent.containsKey("writtenPlacement"))
            openEvent.put("writtenPlacement", new ScoreNoteTiming.WrittenPlacement(1, 1, -1, -1, false, 0));
        openEvent.put("midi", natural.sounding());
        openEvent.put("writtenMidi", touchMidi);
        openEvent.put("harmonicTouchMidi", touchMidi);
        openEvent.put("openStringMidi", natural.open());
        openEvent.put("durationBeats", 1.0);
        return score.withNotes(revised, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static boolean atClock(Map<String, Object> event, int owner, double onset, int quarters) {
        return Integer.valueOf(owner).equals(event.get("sourceNoteIndex"))
                && event.get("startBeat") instanceof Number time && time.doubleValue() == onset
                && event.get("durationBeats") instanceof Number length && length.doubleValue() == quarters
                && Boolean.FALSE.equals(event.get("durationFallback"))
                && Boolean.FALSE.equals(event.get("tiedFromPrevious"));
    }

    private static boolean upperOpenInk(byte[] ink, byte[] labels, int width, int height,
            List<MeasureRegion> bars, int bar, Head opening, Head alias, Head stopped, float nominal) {
        var region = bars.get(bar);
        float left = region.left() * width, right = region.right() * width;
        var f = PrintedDirectionStaff.printedRules(ink, width, height, (left + right) * .5f,
                region.bottom() * height - 2.1f * nominal, nominal);
        if (f == null || Math.abs(f[1] - nominal) > .13f * nominal) return false;
        float gap = f[1];
        var staff = new PlayingTechniqueDetector.Staff(f[0] - 4 * gap, f[0], gap, 0, 1);
        int begin = PrintedCadenceDurations.boundaryColumn(ink, width, staff,
                (bars.get(bar - 1).right() * width + left) * .5f, true, false);
        int end = PrintedCadenceDurations.boundaryColumn(ink, width, staff,
                (bars.get(bar + 1).left() * width + right) * .5f, true, false);
        if (begin < 0 || end < 0 || opening.x - begin < gap * 2.65f || end - stopped.x < gap * 2.65f
                || Math.abs(alias.y - (f[0] - alias.note.staffStep() * .5f * gap)) > gap * .33f
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, stopped.x, stopped.y, gap)
                || !ArtificialHarmonics.quarterTouchDiamond(labels, ink, width, height, stopped.x, stopped.y, gap)
                || !FingeringAnnotationFilter.detachedOpenZeroAbove(ink, width, height, alias.x, alias.y, gap))
            return false;
        var axes = PrintedBeamShafts.at(ink, width, height, stopped.x, stopped.y, gap,
                new PrintedBeamShafts.Budget(45_000));
        if (axes.size() != 1 || axes.get(0).direction() != 1) return false;
        var shaft = axes.get(0);
        float upper = f[0] - alias.note.staffStep() * .5f * gap;
        for (int col = shaft.left(); col <= shaft.right(); col++)
            if (ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, stopped.x, upper, gap, col, -1, true)
                    && joinedStem(ink, width, col, upper, stopped.y, gap)
                    && ArtificialHarmonics.unflaggedQuarterTip(ink, width, height, col,
                            stopped.y, stopped.y + .5f * gap, shaft.endY(), -1, staff)) return true;
        return false;
    }

    private static boolean stackedFingeringAlias(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0 && n.staffCount() == 1 && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.staffStep() >= -4 && n.staffStep() <= -2
                && n.stemDirection() == 0 && n.beamCount() == 3 && n.unbeamedDurationBeats() == 0
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && !n.crossStaffBeam()
                && n.articulations() == 0 && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0 && n.octaveShift() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null;
    }

    private static boolean stoppedFourthClock(ScoreNoteEvent n) {
        return n.octaveShift() == 2 && plainQuarter(n.withOctaveShift(0))
                || n.harmonicSemitones() == 24 && plainQuarter(n.withHarmonicSemitones(0));
    }

    private record MixedQuarter(int firstShaft, int secondShaft, float openY) {}

    private static MixedQuarter mixedInk(byte[] ink, byte[] labels, int width, int height,
            List<MeasureRegion> bars, int bar, Head high, Head low, Head next, Head alias, float nominal) {
        var region = bars.get(bar);
        float left = region.left() * width, right = region.right() * width;
        var f = PrintedDirectionStaff.printedRules(ink, width, height,
                (left + right) * .5f, region.bottom() * height - 2.1f * nominal, nominal);
        if (f == null || Math.abs(f[1] - nominal) > .12f * nominal) return null;
        float floor = f[0], gap = f[1];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        int open = PrintedCadenceDurations.boundaryColumn(
                ink, width, staff, (left + bars.get(bar - 1).right() * width) * .5f, true, false);
        int end = PrintedCadenceDurations.boundaryColumn(
                ink, width, staff, (right + bars.get(bar + 1).left() * width) * .5f, true, false);
        if (open < 0 || end < 0 || high.x - open < gap * 2.5f || end - next.x < gap * 2.7f
                || !PrintedCadenceDurations.emptyOutsideChord(ink, width, staff,
                        open, end, low.x - .7f * gap, next.x, true)) return null;
        var budget = new PrintedBeamShafts.Budget(180_000);
        var a = PrintedBeamShafts.at(ink, width, height, low.x, low.y, gap, budget);
        var b = PrintedBeamShafts.at(ink, width, height, next.x, next.y, gap, budget);
        if (a.size() != 1 || b.size() != 1 || a.get(0).direction() != 1 || b.get(0).direction() != 1
                || !PrintedBeamShafts.at(ink, width, height, alias.x, alias.y, gap, budget).isEmpty()
                || budget.refused() || ArtificialHarmonics.filledQuarterCore(ink, width, height, alias.x, alias.y, gap)
                || !FingeringAnnotationFilter.stackedOpenFingering(
                        ink, width, height, alias.x, alias.y, b.get(0).endY(), gap)
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, high.x, high.y, gap)
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, low.x, low.y, gap)
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, next.x, next.y, gap)
                || !ArtificialHarmonics.quarterTouchDiamond(labels, ink, width, height, high.x, high.y, gap))
            return null;
        float openY = floor - (next.note.staffStep() - 2) * .5f * gap;
        int firstShaft = mixedShaft(ink, width, height, staff, a.get(0), high.y - 1.5f * gap,
                low.x, low.y - gap, low.y);
        int secondShaft = mixedShaft(ink, width, height, staff, b.get(0), next.y - 1.5f * gap,
                next.x, openY, openY);
        if (firstShaft < 0 || secondShaft < 0
                || !ArtificialHarmonics.shaftCrossedDiamond(
                        ink, width, height, next.x, next.y - 1.5f * gap, gap, secondShaft, -1, true))
            return null;
        return new MixedQuarter(firstShaft, secondShaft, openY);
    }

    private static int mixedShaft(byte[] ink, int width, int height, PlayingTechniqueDetector.Staff staff,
            PrintedBeamShafts.Shaft a, float top, float x, float touchY, float bottom) {
        float gap = staff.gap();
        for (int col = a.left(); col <= a.right(); col++)
            if (ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, touchY, gap, col, -1, true)
                    && joinedStem(ink, width, col, top, bottom, gap)
                    && ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, col, bottom, bottom + .5f * gap, a.endY(), -1, staff))
                return col;
        return -1;
    }

    static void retainRoles(ScorePageInterpretation score, List<Map<String, Object>> events, double[] starts) {
        var notes = score.notes();
        var byMeasure = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < notes.size(); i++)
            byMeasure.computeIfAbsent(notes.get(i).measureIndex(), unused -> new ArrayList<>()).add(i);
        for (var entry : byMeasure.entrySet()) {
            int bar = entry.getKey();
            var row = entry.getValue();
            if (row.size() != 2 || starts[bar + 1] - starts[bar] != 2) continue;
            row.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
            boolean paired = true;
            for (int slot = 0; slot < 2; slot++) {
                int owner = row.get(slot);
                var n = notes.get(owner);
                var open = n.openStringTouch();
                var e = events.get(owner);
                if (n.stemDirection() != -1 || n.harmonicSemitones() != 24 || open == null
                        || n.staffStep() - open.staffStep() != 2
                        || !Boolean.TRUE.equals(e.get("artificialHarmonicFourth"))
                        || !Main.completeCompoundQuarter(e, starts[bar] + slot, starts[bar] + slot + 1)) paired = false;
            }
            if (paired)
                for (int owner : row) {
                    events.get(owner).put("compoundHarmonicFourth", true);
                    events.get(owner).put("closedDownstemCompoundBar", true);
                }
        }
    }

    private static boolean plainQuarter(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffIndex() == 0 && n.staffCount() == 1 && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.stemDirection() == -1 && n.beamCount() == 0 && n.unbeamedDurationBeats() == 1
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && !n.crossStaffBeam()
                && n.articulations() == 0 && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0 && n.octaveShift() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null;
    }

    private static boolean writtenFourth(Head upper, Head stop, float gap) {
        return upper.note.staffStep() - stop.note.staffStep() == 3
                && stop.x - upper.x >= 0 && stop.x - upper.x <= .43f * gap
                && stop.y - upper.y >= 1.45f * gap && stop.y - upper.y <= 1.77f * gap;
    }

    private static boolean compatiblePitches(ScoreNoteEvent note, int key) {
        int step = note.diatonicPitchIdentity();
        int midi = ScoreTiePitchGuard.writtenMidi(step, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int upper = ScoreTiePitchGuard.writtenMidi(step + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int lower = ScoreTiePitchGuard.writtenMidi(step - 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        int sound = OpenStringHarmonics.uniqueSoundingMidi(lower);
        return upper - midi == 5 && midi + 24 <= 127 && sound >= 0 && sound != midi + 24;
    }

    private static boolean unlinked(ScorePageInterpretation score, int owner) {
        var beams = score.beamSource();
        var ref = beams.noteOrigins().get(owner).orElse(null);
        return ref == null || beams.groups().stream().flatMap(group -> group.columns().stream())
                .noneMatch(column -> column.members().contains(ref))
                && beams.detachedColumns().stream().noneMatch(column -> column.members().contains(ref));
    }

    private static List<Quarter> print(byte[] ink, int width, int height, MeasureRegion bar,
            MeasureRegion previous, Head firstTouch, Head firstStop, Head lastTouch, Head lastStop, float nominal) {
        float left = bar.left() * width, right = bar.right() * width;
        var f = PrintedDirectionStaff.printedRules(
                ink, width, height, (left + right) * .5f, bar.bottom() * height - 2.1f * nominal, nominal);
        if (f == null) return List.of();
        float floor = f[0], gap = f[1];
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        int a = PrintedCadenceDurations.boundaryColumn(
                ink, width, staff, (left + previous.right() * width) * .5f, true, false);
        int b = PrintedCadenceDurations.boundaryColumn(ink, width, staff, right, true, false);
        if (a < 0 || b < 0 || firstStop.x - a < 1.65f * gap || b - lastStop.x < 2.3f * gap
                || !PrintedCadenceDurations.emptyOutsideChord(
                        ink, width, staff, a, b, firstStop.x, lastStop.x, false))
            return List.of();
        var first = quarter(ink, width, height, staff, firstTouch, firstStop);
        var last = quarter(ink, width, height, staff, lastTouch, lastStop);
        return first == null || last == null ? List.of() : List.of(first, last);
    }

    private static Quarter quarter(byte[] ink, int width, int height,
            PlayingTechniqueDetector.Staff staff, Head top, Head stop) {
        float x = stop.x, y = stop.y, gap = staff.gap();
        float expected = staff.bottom() - stop.note.staffStep() * .5f * gap;
        float openingY = expected + gap;
        if (Math.abs(y - expected) > .17f * gap
                || !ArtificialHarmonics.filledQuarterCore(ink, width, height, x, y, gap)) return null;
        var found = PrintedBeamShafts.at(ink, width, height, x, y, gap, new PrintedBeamShafts.Budget(40_000));
        if (found.size() != 1 || found.get(0).direction() != 1) return null;
        var shaft = found.get(0);
        var zero = DetachedHarmonicZero.below(ink, width, height, x, shaft.endY(), gap);
        if (zero == null)
            zero = DetachedHarmonicZero.below(ink, width, height, x - .16f * gap, shaft.endY() + .38f * gap, gap);
        if (zero == null || Math.abs(zero.x() - x) > .39f * gap) return null;
        for (int col = shaft.left(); col <= shaft.right(); col++) {
            if (ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, top.x, top.y, gap, col, -1, true)
                    && (ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, openingY, gap, col, -1, true)
                        || shaftAndStaffSplitCounter(ink, width, height, staff, x, openingY, col))
                    && ArtificialHarmonics.unflaggedQuarterTip(
                            ink, width, height, col, openingY, openingY + .5f * gap, shaft.endY(), -1, staff)
                    && joinedStem(ink, width, col, top.y, openingY, gap))
                return new Quarter(col, openingY);
        }
        return null;
    }

    private static boolean joinedStem(byte[] ink, int width, int col, float top, float lower, float gap) {
        int gaps = 0;
        for (int row = Math.round(top + .22f * gap); row <= Math.round(lower + .42f * gap); row++)
            if ((ink[row * width + col] & 255) >= 168) gaps++;
        return gaps <= 2;
    }

    /** Staff ink through the diamond can leave just its upper paper pocket and pointed lower side. */
    private static boolean shaftAndStaffSplitCounter(byte[] ink, int width, int height,
            PlayingTechniqueDetector.Staff staff, float x, float y, int col) {
        float gap = staff.gap();
        float part = (staff.bottom() - y) / gap;
        int line = Math.round(part);
        if (line < 1 || line > 4 || Math.abs(part - line) > .12f) return false;
        int across = Math.round(y);
        for (int i = Math.round(x - 1.02f * gap); i <= Math.round(x + 1.02f * gap); i++)
            if ((ink[across * width + i] & 255) >= 160) return false;
        if (!ArtificialHarmonics.enclosedCounter(
                ink, width, height, x - .43f * gap, y - .36f * gap, .47f * gap, .13f * gap))
            return false;
        int start = Math.round(x), limit = Math.round(x + .78f * gap);
        int first = -1, last = -1, rows = 0;
        for (int row = Math.round(y + .12f * gap); row <= y + .71f * gap; row++) {
            int tip = limit;
            while (tip >= start && (ink[row * width + tip] & 255) >= 162) tip--;
            if (tip < start) break;
            if (rows > 0 && tip > last + 1 || (ink[row * width + col] & 255) >= 162) return false;
            if (rows++ == 0) first = tip;
            last = tip;
        }
        return rows >= gap * .39f && first - last >= gap * .31f;
    }
}
