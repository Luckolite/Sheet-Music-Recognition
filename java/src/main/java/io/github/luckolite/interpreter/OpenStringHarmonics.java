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
    private record Pitch(int open, int sounding) {}
    record Recovery(int owner, ScoreNoteEvent note, int openMidi, int touchMidi, int soundingMidi) {}

    static List<Recovery> recover(
            byte[] labels, byte[] gray, byte[] decoder, int width, int height,
            ScorePageInterpretation score, List<SheetInterpreter.Word> words, int initialKey) {
        var marks = new LinkedHashSet<Mark>();
        for (var word : words) {
            var mark = zero(gray, width, height, word);
            if (mark != null) marks.add(mark);
        }
        if (marks.isEmpty() || score.notes().isEmpty()) return List.of();
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
        var recovered = new ArrayList<Recovery>();
        for (var candidate : selected) {
            var head = candidate.head();
            var anchor = head.stop();
            Integer accidental = accidentals.get(anchor);
            if (accidental == null) continue;
            if (accidental == ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                accidental = precedingAccidental(score.notes(), recovered, candidate);
            int fifths = initialKey;
            for (var key : score.keyChanges())
                if (key.measureIndex() <= anchor.measureIndex()) fifths = key.fifths();
            int touch = ScoreTiePitchGuard.writtenMidi(
                    anchor.clefBottomDiatonic() + candidate.step(), accidental, fifths);
            var pitch = sounding(touch);
            if (pitch == null) continue;
            var note = new ScoreNoteEvent(anchor.measureIndex(), anchor.positionInMeasure(),
                    candidate.step(), anchor.staffIndex(), anchor.staffCount(), head.y() / height,
                    false, 0, 0, accidental, ScoreNoteEvent.DURATION_QUARTER, 1, 0, 0,
                    anchor.clefBottomDiatonic(), false, 0, false, 0, 0, 1, anchor.stemDirection());
            recovered.add(new Recovery(candidate.owner(), note, pitch.open(), touch, pitch.sounding()));
        }
        return List.copyOf(recovered);
    }

    private static int precedingAccidental(
            List<ScoreNoteEvent> notes, List<Recovery> recovered, Candidate candidate) {
        var anchor = candidate.head().stop();
        float latest = -1;
        int accidental = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        for (var prior : notes) {
            if (sameWrittenPitch(prior, anchor, candidate.step())
                    && prior.positionInMeasure() > latest) {
                latest = prior.positionInMeasure();
                accidental = prior.writtenAccidental();
            }
        }
        for (var prior : recovered) {
            var note = prior.note();
            if (sameWrittenPitch(note, anchor, candidate.step()) && note.positionInMeasure() > latest) {
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

    private static Pitch sounding(int touch) {
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
            for (int step = note.staffStep() - 8; step <= note.staffStep() + 8; step++) {
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

    private static Mark zero(byte[] gray, int width, int height, SheetInterpreter.Word word) {
        String text = word.text();
        if (!FINGERING.matcher(text).matches()) return null;
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
