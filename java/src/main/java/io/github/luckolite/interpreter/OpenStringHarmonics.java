// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.regex.Pattern;

/** Recovers an omitted open-string touch head in a proved standard-violin chord. */
final class OpenStringHarmonics {
    private static final Pattern FINGERING = Pattern.compile("[1-4]{0,2}0");
    private static final int[] VIOLIN_STRINGS = {55, 62, 69, 76};

    private OpenStringHarmonics() {}

    private record Mark(float x, float y) {}
    private record Candidate(int owner, int step, ArtificialHarmonics.TouchHead head) {}
    record Pitch(int open, int sounding) {}
    record Recovery(int owner, ScoreNoteEvent note, int openMidi, int touchMidi, int soundingMidi) {}

    static ScorePageInterpretation capture(
            byte[] labels, byte[] gray, byte[] decoder, int width, int height,
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Word> words) {
        var marks = new LinkedHashSet<Mark>();
        for (var word : words) {
            var mark = zero(gray, width, height, word);
            if (mark != null) marks.add(mark);
        }
        if (marks.isEmpty() || score.notes().isEmpty()) return score;
        var staffs = OmrScoreInterpreter.techniqueStaffs(labels, decoder, width, height, score.measures());
        var byOwner = new LinkedHashMap<Integer, Candidate>();
        var ambiguous = new HashSet<Integer>();
        for (var mark : marks) {
            var candidates = candidates(labels, decoder, width, height, score, staffs, mark);
            if (candidates.isEmpty()) continue;
            candidates.sort(Comparator.comparingDouble(candidate -> Math.abs(candidate.head().y() - mark.y())));
            var selected = candidates.get(0);
            if (candidates.stream().anyMatch(candidate -> !sameColumn(selected, candidate))) continue;
            var previous = byOwner.putIfAbsent(selected.owner(), selected);
            if (previous != null && previous.step() != selected.step()) ambiguous.add(selected.owner());
        }
        var selected = byOwner.values().stream()
                .filter(candidate -> !ambiguous.contains(candidate.owner()))
                .sorted(Comparator.comparingInt(Candidate::owner)).toList();
        var accidentals = OmrScoreInterpreter.harmonicTouchAccidentals(
                labels, decoder, width, height, score, selected.stream().map(Candidate::head).toList());
        if (accidentals.isEmpty()) return score;
        var notes = new ArrayList<>(score.notes());
        for (var candidate : selected) {
            var head = candidate.head();
            Integer accidental = accidentals.get(head.stop());
            if (accidental == null) continue;
            var touch = new ScoreNoteEvent.OpenStringTouch(candidate.step(), head.y() / height, accidental);
            notes.set(candidate.owner(), head.stop().withOpenStringTouch(touch));
        }
        var owners = java.util.stream.IntStream.range(0, notes.size()).boxed().toList();
        return score.withNotes(notes, owners).withClockContextSource(score.clockContextSource());
    }

    /** Upgrades a proved open-string quarter with a literal stopped fourth on the same shaft. */
    static Map<Integer, ScoreNoteEvent> captureFourthPartners(
            ScorePageInterpretation score, byte[] raw, byte[] normalized, int width, int height, int[] keys,
            List<Recovery> recovered) {
        if (keys.length != score.measures().size() || raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return Map.of();
        var revised = new LinkedHashMap<Integer, ScoreNoteEvent>();
        var notes = score.notes();
        for (var natural : recovered) {
            var base = notes.get(natural.owner());
            var touch = base.openStringTouch();
            int bar = base.measureIndex(), direction = base.stemDirection();
            int written = ScoreTiePitchGuard.writtenMidi(
                    base.diatonicPitchIdentity(), base.writtenAccidental(), keys[bar]);
            int artificialTouch = ScoreTiePitchGuard.writtenMidi(
                    base.diatonicPitchIdentity() + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, keys[bar]);
            int separation = touch.staffStep() - base.staffStep();
            boolean terminalBelow = direction == -1 && separation == -2 && bar > 0
                    && bar + 1 == score.measures().size()
                    && score.measures().get(bar).cadenceQuarterBeats() == 1
                    && natural.touchMidi() < written;
            if (base.octaveShift() != 2 || base.harmonicSemitones() != 0 || direction == 0
                    || base.kind() != ScoreNoteEvent.Kind.PITCHED || base.staffIndex() != 0 || base.staffCount() != 1
                    || base.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE
                    || base.visualSize() != ScoreNoteEvent.VisualSize.FULL || base.beamCount() != 0
                    || base.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER
                    || base.augmentationDots() != 0 || base.tupletDivisor() != 1 || base.crossStaffBeam()
                    || base.articulations() != 0 || base.tiedFromPrevious() || base.boundaryTies() != 0
                    || !terminalBelow && (separation < 5 || separation > 6 || natural.touchMidi() <= artificialTouch)
                    || artificialTouch - written != 5
                    || written + 24 > 127 || natural.soundingMidi() == written + 24) continue;
            var region = score.measures().get(bar);
            float x = xAt(base, region, width);
            if (notes.stream().anyMatch(other -> other != base && other.measureIndex() == bar
                    && other.staffIndex() == base.staffIndex()
                    && Math.abs(xAt(other, region, width) - x) <= (region.bottom() - region.top()) * height * .055f))
                continue;
            int a = terminalBelow
                    ? terminalFourthShaft(raw, width, height, score.measures().get(bar - 1), region, base, x)
                    : fourthPartnerShaft(raw, width, height, region, base, x);
            int b = terminalBelow
                    ? terminalFourthShaft(normalized, width, height, score.measures().get(bar - 1), region, base, x)
                    : fourthPartnerShaft(normalized, width, height, region, base, x);
            if (a < 0 || b < 0 || Math.abs(a - b) > 1) continue;
            revised.put(natural.owner(), base.withOctaveShift(0).withHarmonicSemitones(24));
        }
        return revised;
    }

    private static int terminalFourthShaft(byte[] ink, int width, int height, MeasureRegion prior,
            MeasureRegion bar, ScoreNoteEvent base, float x) {
        var proof = TerminalCompoundHarmonicQuarter.prove(ink, width, height, prior, bar, x);
        return proof != null && proof.stoppedStep() == base.staffStep()
                && Math.abs(proof.lowerY() - base.openStringTouch().pageY() * height) <= .18f * proof.gap()
                ? proof.shaft() : -1;
    }

    static int fourthPartnerShaft(byte[] ink, int width, int height, MeasureRegion region,
            ScoreNoteEvent base, float x) {
        if (x < 40 || x > width - 40) return -1;
        float nominal = (region.bottom() - region.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 26) return -1;
        var rails = PrintedDirectionStaff.printedRules(
                ink, width, height, x, region.bottom() * height - 2.1f * nominal, nominal);
        if (rails == null) return -1;
        float gap = rails[1], y = base.pageY() * height;
        float middle = rails[0] - (base.staffStep() + 3) * gap * .5f;
        var touch = base.openStringTouch();
        float outer = touch.pageY() * height;
        if (outer < gap * 3 || y > height - gap * 3
                || Math.abs(y - (rails[0] - base.staffStep() * gap * .5f)) > gap * .25f
                || Math.abs(outer - (rails[0] - touch.staffStep() * gap * .5f)) > gap * .25f
                || !filledCenter(ink, width, x, y, gap)) return -1;
        var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, new PrintedBeamShafts.Budget(100_000));
        int direction = base.stemDirection();
        if (shafts.size() != 1 || shafts.get(0).direction() != -direction) return -1;
        var shaft = shafts.get(0);
        var staff = new PlayingTechniqueDetector.Staff(rails[0]-4*gap, rails[0], gap, 0, 1);
        for (int column = shaft.left(); column <= shaft.right(); column++) {
            if (continuous(ink, width, column, outer, y)
                    && ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, middle, gap, column, direction, true)
                    && ArtificialHarmonics.shaftCrossedDiamond(ink, width, height, x, outer, gap, column, direction, true)
                    && ArtificialHarmonics.unflaggedQuarterTip(
                        ink, width, height, column, outer, y, shaft.endY(), direction, staff)) return column;
        }
        return -1;
    }

    private static boolean continuous(byte[] ink, int width, int x, float from, float to) {
        for (int y = Math.round(from); y <= Math.round(to); y++)
            if ((ink[y * width + x] & 255) >= 180) return false;
        return true;
    }

    private static boolean filledCenter(byte[] ink, int width, float x, float y, float gap) {
        int row = Math.round(y), col = Math.round(x), dark = 0;
        int a = Math.round(.24f * gap), b = Math.round(.14f * gap);
        for (int dy = -b; dy <= b; dy++)
            for (int dx = -a; dx <= a; dx++)
                if ((ink[(row + dy) * width + col + dx] & 255) < 155) dark++;
        return dark >= (2 * a + 1) * (2 * b + 1) * .72f;
    }

    static List<Recovery> recover(ScorePageInterpretation score, int initialKey) {
        var recovered = new ArrayList<Recovery>();
        for (int owner = 0; owner < score.notes().size(); owner++) {
            var anchor = score.notes().get(owner);
            var proof = anchor.openStringTouch();
            if (proof == null) continue;
            int accidental = proof.writtenAccidental();
            if (accidental == ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                accidental = precedingAccidental(score.notes(), recovered, anchor, proof.staffStep());
            int fifths = initialKey;
            for (var key : score.keyChanges())
                if (key.measureIndex() <= anchor.measureIndex()) fifths = key.fifths();
            int touch = ScoreTiePitchGuard.writtenMidi(
                    anchor.clefBottomDiatonic() + proof.staffStep(), accidental, fifths);
            var pitch = sounding(touch);
            if (pitch == null) continue;
            var note = new ScoreNoteEvent(anchor.measureIndex(), anchor.positionInMeasure(),
                    proof.staffStep(), anchor.staffIndex(), anchor.staffCount(), proof.pageY(),
                    false, 0, 0, accidental, ScoreNoteEvent.DURATION_QUARTER, 1, 0, 0,
                    anchor.clefBottomDiatonic(), false, 0, false, 0, 0, 1, anchor.stemDirection());
            recovered.add(new Recovery(owner, note, pitch.open(), touch, pitch.sounding()));
        }
        return List.copyOf(recovered);
    }

    private static int precedingAccidental(
            List<ScoreNoteEvent> notes, List<Recovery> recovered, ScoreNoteEvent anchor, int step) {
        float latest = -1;
        int accidental = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        for (var prior : notes) {
            if (sameWrittenPitch(prior, anchor, step)
                    && prior.positionInMeasure() > latest) {
                latest = prior.positionInMeasure();
                accidental = prior.writtenAccidental();
            }
        }
        for (var prior : recovered) {
            var note = prior.note();
            if (sameWrittenPitch(note, anchor, step) && note.positionInMeasure() > latest) {
                latest = note.positionInMeasure();
                accidental = note.writtenAccidental();
            }
        }
        return accidental;
    }

    private static boolean sameWrittenPitch(ScoreNoteEvent note, ScoreNoteEvent anchor, int step) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.measureIndex() == anchor.measureIndex() && note.staffIndex() == anchor.staffIndex()
                && note.clefBottomDiatonic() == anchor.clefBottomDiatonic()
                && note.staffStep() == step && note.positionInMeasure() < anchor.positionInMeasure() - .005f
                && note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
    }

    static int uniqueSoundingMidi(int touch) {
        var pitch = sounding(touch);
        return pitch == null ? -1 : pitch.sounding();
    }

    static Pitch sounding(int touch) {
        Pitch pitch = null;
        for (int string : VIOLIN_STRINGS) {
            int partial = switch (touch - string) {
                case 4, 9 -> 28;
                case 5 -> 24;
                case 7 -> 19;
                default -> 0;
            };
            if (partial == 0) continue;
            if (pitch != null) return null;
            pitch = new Pitch(string, string + partial);
        }
        return pitch;
    }

    private static List<Candidate> candidates(
            byte[] labels, byte[] gray, int width, int height, ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Staff> staffs, Mark mark) {
        var candidates = new ArrayList<Candidate>();
        var notes = score.notes();
        for (int index = 0; index < notes.size(); index++) {
            var note = notes.get(index);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE || note.stemDirection() == 0
                    || note.tiedFromPrevious() || note.boundaryTies() != 0 || note.augmentationDots() != 0
                    || note.beamCount() != 0 || note.tupletDivisor() != 1
                    || (note.articulations() & NoteOrnament.GRACE) != 0
                    || note.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER) continue;
            var region = score.measures().get(note.measureIndex());
            float x = xAt(note, region, width), y = note.pageY() * height;
            if (Math.abs(x - mark.x()) > 25 || Math.abs(y - mark.y()) > 125) continue;
            var staff = NaturalHarmonicStaffBinding.uniqueForMeasure(
                    staffs, note.staffIndex(), note.staffCount(), region, height);
            if (staff == null || Math.abs(x - mark.x()) > staff.gap() * .52f
                    || Math.abs(y - mark.y()) > staff.gap() * 6.2f) continue;
            float[] rails = PrintedDirectionStaff.printedRules(gray, width, height, x, staff.bottom(), staff.gap());
            if (rails == null) continue;
            float gap = rails[1], toward = Math.signum(mark.y() - y);
            int firstStep = Math.max(-35, note.staffStep() - 8);
            int lastStep = Math.min(35, note.staffStep() + 8);
            for (int step = firstStep; step <= lastStep; step++) {
                float touchY = rails[0] - step * gap * .5f;
                if ((touchY - y) * toward < gap * .35f || (mark.y() - touchY) * toward < gap * .65f
                        || Math.abs(mark.y() - touchY) > 4.1f * gap || touchY < 0 || touchY >= height) continue;
                if (notes.stream().anyMatch(other -> other.measureIndex() == note.measureIndex()
                                && other.staffIndex() == note.staffIndex()
                                && Math.abs(xAt(other, region, width) - x) < gap * .45f
                                && Math.abs(other.pageY() * height - touchY) < gap * .24f)) continue;
                if (ArtificialHarmonics.symbolDiamond(labels, gray, width, height, x, touchY, gap)
                        && hasShaft(gray, width, x, y, touchY, gap, note.stemDirection())) {
                    var head = new ArtificialHarmonics.TouchHead(note, x, touchY, gap);
                    candidates.add(new Candidate(index, step, head));
                }
            }
        }
        return candidates;
    }

    private static boolean sameColumn(Candidate a, Candidate b) {
        return a.head().stop().measureIndex() == b.head().stop().measureIndex()
                && a.head().stop().staffIndex() == b.head().stop().staffIndex()
                && Math.abs(a.head().x() - b.head().x()) <= a.head().gap() * .35f;
    }

    private static float xAt(ScoreNoteEvent note, MeasureRegion region, int width) {
        return (region.left() + note.positionInMeasure() * (region.right() - region.left())) * width;
    }

    static boolean isFingering(String text) {
        return text != null && FINGERING.matcher(text.trim()).matches();
    }

    static boolean verifiedZeroBelow(List<PlayingTechniqueDetector.Word> words,
            byte[] original, byte[] normalized, int width, int height, float x, float y, float gap) {
        for (var word : words) {
            var a = zero(original, width, height, word);
            var b = zero(normalized, width, height, word);
            if (a == null || b == null || Math.abs(a.x - b.x) > .17f * gap
                    || Math.abs(a.y - b.y) > .17f * gap || Math.abs(a.x - x) > .55f * gap
                    || a.y - y < 1.55f * gap || a.y - y > 4.85f * gap) continue;
            return ArtificialHarmonics.enclosedCounter(original, width, height, a.x, a.y, .82f * gap, .13f * gap)
                    && ArtificialHarmonics.enclosedCounter(normalized, width, height, b.x, b.y, .82f * gap, .13f * gap);
        }
        return false;
    }

    private static Mark zero(byte[] gray, int width, int height, PlayingTechniqueDetector.Word word) {
        String text = word.text();
        if (!isFingering(text)) return null;
        text = text.trim();
        int left = Math.round(word.left() * width), right = Math.round(word.right() * width);
        int top = Math.round(word.top() * height), bottom = Math.round(word.bottom() * height);
        if (left < 0 || right >= width || top < 0 || bottom >= height || right <= left || bottom <= top
                || right - left > 100 || bottom - top > 160) return null;
        if (text.equals("0")) return new Mark((left + right) * .5f, (top + bottom) * .5f);
        var box = new NaturalHarmonicRingProof.Box(left, top, right, bottom);
        var rings = NaturalHarmonicRingProof.rings(gray, width, height, box, 180, 6, 26,
                        new NaturalHarmonicRingProof.Budget(1_000_000, 1_000_000)).stream()
                .filter(ring -> ring.counterPixels() >= .19f * ring.bounds().width() * ring.bounds().height())
                .toList();
        if (rings.size() != 1) return null;
        return new Mark(rings.get(0).centerX(), rings.get(0).centerY());
    }

    private static boolean hasShaft(
            byte[] gray, int width, float x, float headY, float touchY, float gap, int stem) {
        int height = gray.length / width;
        int top = Math.max(0, Math.round(Math.min(headY, touchY)));
        int bottom = Math.min(height - 1, Math.round(Math.max(headY, touchY)));
        int left = Math.max(0, Math.round(x + (stem > 0 ? .25f : -.85f) * gap));
        int right = Math.min(width - 1, Math.round(x + (stem > 0 ? .85f : -.25f) * gap));
        for (int col = left; col <= right; col++) {
            int row = top;
            while (row <= bottom && (gray[row * width + col] & 255) < 180) row++;
            if (row > bottom) return true;
        }
        return false;
    }
}
