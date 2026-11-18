// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;

/** Two unflagged down-stem harmonic quarters between printed rules in an explicit 2/4 bar. */
final class ClosedMixedHarmonicQuarters {
    private ClosedMixedHarmonicQuarters() {}

    record Head(float x, float y, int staffStep, int midi, int openString, int soundingMidi, int accidental) {}
    record Recovery(int bar, int owner, Head firstLower, Head firstUpper, Head secondUpper, int thirdMidi) {}
    private record Shaft(int x, int tip) {}

    static List<Recovery> capture(ScorePageInterpretation score, byte[] labels, byte[] raw,
            byte[] normalized, int w, int h, float[] beats, int[] keys) {
        var bars = score.measures();
        if (raw == null || normalized == null || labels == null || raw.length != (long) w * h
                || normalized.length != raw.length || labels.length != raw.length || beats.length != bars.size())
            return List.of();
        var found = new ArrayList<Recovery>();
        var owners = new int[bars.size()];
        var counts = new int[bars.size()];
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            counts[note.measureIndex()]++;
            owners[note.measureIndex()] = i;
        }
        ScoreMeterChange active = null;
        int nextMeter = 0;
        var meters = score.meterChanges();
        for (int i = 1; i + 1 < bars.size(); i++) {
            while (nextMeter < meters.size() && meters.get(nextMeter).measureIndex() <= i)
                active = meters.get(nextMeter++);
            if (active == null || active.numerator() != 2 || active.denominator() != 4 || beats[i] != 2
                    || counts[i] != 1 || bars.get(i).cadenceQuarterBeats() != 0) continue;
            int bar = i;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) continue;
            int owner = owners[i];
            var note = score.notes().get(owner);
            if (!stopped(note) || hasBeamOwner(score, owner)) continue;
            var a = prove(note, owner, bars.get(i - 1), bars.get(i), bars.get(i + 1),
                    labels, raw, w, h, keys[i]);
            if (a == null) continue;
            var b = prove(note, owner, bars.get(i - 1), bars.get(i), bars.get(i + 1),
                    labels, normalized, w, h, keys[i]);
            if (a.equals(b)) found.add(a);
        }
        return List.copyOf(found);
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score, List<Map<String, Object>> events,
            List<Recovery> found, double[] starts, int width, int height) {
        if (found.isEmpty()) return score;
        var notes = new ArrayList<>(score.notes());
        var owners = new ArrayList<>(IntStream.range(0, notes.size()).boxed().toList());
        for (var recovery : found) {
            var note = notes.get(recovery.owner);
            var upper = recovery.secondUpper;
            var touch = new ScoreNoteEvent.OpenStringTouch(
                    upper.staffStep, upper.y / height, upper.accidental);
            notes.set(recovery.owner, quarter(note.measureIndex(), note.positionInMeasure(), note.staffStep(),
                    note.pageY(), 0).withHarmonicSemitones(28).withOpenStringTouch(touch));
            var stopped = events.get(recovery.owner);
            stopped.put("startBeat", starts[recovery.bar] + 1);
            stopped.put("durationBeats", 1.0);
            stopped.put("durationFallback", false);
            stopped.put("writtenMidi", recovery.thirdMidi - 4);
            stopped.put("harmonicTouchMidi", recovery.thirdMidi);
            stopped.put("midi", recovery.thirdMidi + 24);
            stopped.put("artificialHarmonicThird", true);
            stopped.put("mixedHarmonicQuarter", true);
            stopped.remove("writtenPlacement");
            for (var head : List.of(recovery.firstLower, recovery.firstUpper)) {
                var bar = score.measures().get(recovery.bar);
                float position = (head.x / width - bar.left()) / (bar.right() - bar.left());
                var written = quarter(recovery.bar, position, head.staffStep, head.y / height, head.accidental);
                var event = new LinkedHashMap<String, Object>();
                event.put("measureIndex", recovery.bar);
                event.put("staffIndex", 0);
                event.put("staffCount", 1);
                event.put("sourceNoteIndex", notes.size());
                event.put("midi", head.soundingMidi);
                event.put("writtenMidi", head.midi);
                event.put("harmonicTouchMidi", head.midi);
                event.put("openStringMidi", head.openString);
                event.put("clefInferred", false);
                event.put("startBeat", starts[recovery.bar]);
                event.put("durationBeats", 1.0);
                event.put("durationFallback", false);
                event.put("tiedFromPrevious", false);
                event.put("mixedHarmonicQuarter", true);
                event.put("x", head.x);
                event.put("y", head.y);
                events.add(event);
                notes.add(written);
                owners.add(-1);
            }
        }
        return score.withNotes(notes, owners).withClockContextSource(
                score.clockContextSource().withNoteCount(notes.size()));
    }

    private static ScoreNoteEvent quarter(int measure, float position, int step, float y, int accidental) {
        return new ScoreNoteEvent(measure, position, step, 0, 1, y, false, 0, 0, accidental,
                ScoreNoteEvent.DURATION_QUARTER, 1, 0, 0, ScoreNoteEvent.CLEF_TREBLE)
                .withStemDirection(-1);
    }

    private static boolean stopped(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.clefBottomDiatonic() == ScoreNoteEvent.CLEF_TREBLE
                && n.staffIndex() == 0 && n.staffCount() == 1 && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.writtenAccidental() == 0 && n.stemDirection() == -1 && n.staffStep() >= 4 && n.staffStep() <= 6
                && n.unbeamedDurationBeats() == 0 && n.beamCount() == 1 && n.tupletDivisor() == 1
                && n.augmentationDots() == 0 && n.harmonicSemitones() == 0 && n.openStringTouch() == null
                && n.boundaryTies() == 0 && !n.tiedFromPrevious() && !n.crossStaffBeam() && n.articulations() == 0
                && n.octaveShift() == 0 && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0;
    }

    private static boolean hasBeamOwner(ScorePageInterpretation score, int index) {
        var beams = score.beamSource();
        var anchor = beams.noteOrigins().get(index);
        return anchor.isPresent() && beams.groups().stream().flatMap(g -> g.columns().stream())
                .anyMatch(c -> c.members().contains(anchor.get()));
    }

    private static Recovery prove(ScoreNoteEvent n, int owner, MeasureRegion previous, MeasureRegion bar,
            MeasureRegion next, byte[] labels, byte[] gray, int w, int h, int key) {
        float nominal = (bar.bottom() - bar.top()) * h / 8.2f;
        float left = bar.left() * w;
        float right = bar.right() * w;
        float span = right - left;
        if (nominal < 12 || nominal > 23 || span < nominal * 9.5f || span > nominal * 12.5f
                || Math.abs(bar.bottom() - previous.bottom()) * h > nominal * .24f
                || Math.abs(bar.bottom() - next.bottom()) * h > nominal * .24f
                || n.positionInMeasure() < .62f || n.positionInMeasure() > .74f) return null;
        var frame = PrintedDirectionStaff.printedRules(
                gray, w, h, right - nominal * 1.8f, bar.bottom() * h - nominal * 2.1f, nominal);
        if (frame == null || Math.abs(frame[1] - nominal) > nominal * .1f) return null;
        float bottom = frame[0], gap = frame[1];
        var staff = new PlayingTechniqueDetector.Staff(bottom - 4 * gap, bottom, gap, 0, 1);
        int opening = PrintedCadenceDurations.boundaryColumn(
                gray, w, staff, (previous.right() * w + left) * .5f, true, false);
        int closing = PrintedCadenceDurations.boundaryColumn(
                gray, w, staff, (right + next.left() * w) * .5f, true, false);
        if (opening < 0 || closing - opening < gap * 11 || closing - opening > gap * 13.3f) return null;
        float y = n.pageY() * h;
        int step = n.staffStep();
        float x2 = left + n.positionInMeasure() * span;
        float lowerY = bottom - step * gap * .5f;
        if (Math.abs(lowerY - y) > gap * .23f) return null;
        var a = shaft(gray, w, h, opening + gap * 2.05f, opening + gap * 3.15f, lowerY, staff);
        var b = shaft(gray, w, h, x2 - gap * .77f, x2 - gap * .32f, lowerY, staff);
        if (a == null || b == null || b.x - a.x < gap * 4.55f || b.x - a.x > gap * 5.55f) return null;
        float x1 = a.x + gap * .46f;
        float y1Upper = lowerY - gap * 2.5f;
        float y2Middle = lowerY - gap;
        float y2Upper = lowerY - gap * 3.5f;
        if (!ArtificialHarmonics.shaftCrossedDiamond(gray, w, h, x1, lowerY, gap, a.x, -1, true)
                || !ArtificialHarmonics.shaftCrossedDiamond(gray, w, h, x2, y2Middle, gap, b.x, -1, true)
                || !LedgerCrossedHarmonicDiamond.proved(labels, gray, w, h, x1, y1Upper, gap, a.x, -1)
                || !LedgerCrossedHarmonicDiamond.proved(labels, gray, w, h, x2, y2Upper, gap, b.x, -1)
                || !filledCore(gray, w, x2, y, gap)
                || !PrintedCadenceDurations.emptyOutsideChord(gray, w, staff, opening, closing, x1, x2, true)
                || !clearBetween(gray,w,staff,x1 + gap * 1.35f,x2 - gap * 2.17f)) return null;
        if (DetachedHarmonicZero.above(gray, w, h, x1, y1Upper, gap, false) == null
                || DetachedHarmonicZero.below(gray, w, h, x1, a.tip, gap) == null
                || DetachedHarmonicZero.above(gray, w, h, x2, y2Upper, gap, true) == null
                || DetachedHarmonicZero.below(gray, w, h, x2, b.tip, gap) != null) return null;
        if (!OmrScoreInterpreter.printedTerminalDyadSharp(gray, w, h, x2, y2Upper, gap, false)
                || !OmrScoreInterpreter.provesNaturalMeterInk(gray, w, h,
                        Math.round(x2 - gap * 2.27f), Math.round(x2 - gap * .98f), y, gap,
                        Math.round(staff.top()))) return null;
        int inherited = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        var first = natural(x1, lowerY, step, inherited, key);
        var second = natural(x1, y1Upper, step + 5, inherited, key);
        var third = natural(x2, y2Upper, step + 7, ScoreNoteEvent.ACCIDENTAL_SHARP, key);
        int base = ScoreTiePitchGuard.writtenMidi(n.diatonicPitchIdentity(), 0, key);
        int middle = ScoreTiePitchGuard.writtenMidi(n.diatonicPitchIdentity() + 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
        if (first == null || second == null || third == null || first.openString == second.openString
                || first.midi != base + 1 || third.openString != second.openString
                || middle - base != 4 || base + 28 > 127) return null;
        return new Recovery(n.measureIndex(), owner, first, second, third, middle);
    }

    private static Head natural(float x, float y, int step, int accidental, int key) {
        int midi = ScoreTiePitchGuard.writtenMidi(
                ScoreNoteEvent.CLEF_TREBLE + step, accidental, key);
        int sound = OpenStringHarmonics.uniqueSoundingMidi(midi);
        if (sound < 0) return null;
        for (int open : new int[]{55, 62, 69, 76})
            if (switch (midi - open) {
                case 4, 9 -> sound == open + 28;
                case 5 -> sound == open + 24;
                case 7 -> sound == open + 19;
                default -> false;
            }) return new Head(x, y, step, midi, open, sound, accidental);
        return null;
    }

    private static Shaft shaft(byte[] gray, int w, int h, float from, float to,
            float lowerY, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap(), floor = staff.bottom();
        int top = Math.round(lowerY - gap * 1.6f);
        int limit = Math.round(floor + gap * 1.02f);
        if (from < 0 || to >= w || top < 0 || limit >= h) return null;
        int first = -1, last = -1;
        for (int x = (int)Math.ceil(from); x <= (int)Math.floor(to); x++) {
            int y = top;
            while (y <= limit && (gray[y * w + x] & 255) < 155) y++;
            if (y < floor + gap * .11f || y > floor + gap * .78f) continue;
            if (first < 0) first = x;
            if (last >= 0 && x != last + 1) return null;
            last = x;
        }
        if (first < 0 || last - first + 1 > gap * .32f) return null;
        int x = (first + last) / 2;
        int y = top;
        while (y <= limit && (gray[y * w + x] & 255) < 155) y++;
        if (!ArtificialHarmonics.unflaggedQuarterTip(
                gray, w, h, x, lowerY - gap, lowerY, y - 1, -1, staff)) return null;
        return new Shaft(x, y - 1);
    }

    private static boolean filledCore(byte[] gray, int w, float x, float y, float gap) {
        int cx = Math.round(x), cy = Math.round(y), across = Math.round(gap * .22f);
        for (int row = cy - 2; row <= cy + 2; row++)
            for (int col = cx - across; col <= cx + across; col++)
                if ((gray[row * w + col] & 255) >= 155) return false;
        return true;
    }

    private static boolean clearBetween(byte[] gray, int w, PlayingTechniqueDetector.Staff staff,
            float from, float to) {
        float gap = staff.gap();
        if (to - from < gap * 1.2f) return false;
        for (int x = Math.round(from); x <= Math.round(to); x++) {
            int count = 0;
            for (int y = Math.round(staff.top()); y <= Math.round(staff.bottom()); y++) {
                float rail = (staff.bottom() - y) / gap;
                if (Math.abs(rail - Math.round(rail)) <= .22f) continue;
                if ((gray[y * w + x] & 255) < 155 && ++count > gap * .20f) return false;
            }
        }
        return true;
    }
}
