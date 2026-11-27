// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** Reads a cancellation natural and the distinct accidental to its right on the same attack. */
final class BeamedCancellationAccidental {
    private BeamedCancellationAccidental() {}

    static ScorePageInterpretation recover(ScorePageInterpretation score, byte[] raw, byte[] gray,
            int width, int height, Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (raw == null || gray == null || raw.length != (long) width * height
                || gray.length != raw.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 1; i + 1 < notes.size(); i++) {
            var first = notes.get(i);
            var second = notes.get(i + 1);
            var previous = notes.get(i - 1);
            int barIndex = first.measureIndex();
            var lower = first.pageY() > second.pageY() ? first : second;
            var upper = lower == first ? second : first;
            if (staggeredNatural(score, raw, gray, width, height, previous, lower, upper)) {
                printed.accept(new SourceLocalAccidental.ProvedMark(lower, false));
                continue;
            }
            if (barIndex != second.measureIndex() || barIndex != previous.measureIndex()
                    || !dottedAttack(first) || !dottedAttack(second)
                    || first.stemDirection() != second.stemDirection()
                    || first.clefBottomDiatonic() != second.clefBottomDiatonic()) continue;
            var bar = score.measures().get(barIndex);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 23) continue;
            float x1 = headX(bar, first, width), x2 = headX(bar, second, width);
            float prior = headX(bar, previous, width);
            float separation = Math.abs(first.pageY() - second.pageY()) * height;
            if (Math.abs(x1 - x2) > .16f * gap || separation < .92f * gap
                    || separation > 2.75f * gap || Math.min(x1, x2) - prior < 3.45f * gap
                    || Math.max(x1, x2) - prior > 4.65f * gap) continue;
            if (i + 2 < notes.size() && notes.get(i + 2).measureIndex() == barIndex
                    && Math.abs(headX(bar, notes.get(i + 2), width) - x2) < .33f * gap) continue;
            int firstSign = ownedSign(raw, gray, width, height, prior, x1, first.pageY() * height, gap);
            int secondSign = ownedSign(raw, gray, width, height, prior, x2, second.pageY() * height, gap);
            int absent = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
            if ((firstSign == absent) == (secondSign == absent)) continue;
            int owner = firstSign != absent ? i : i + 1;
            int sign = firstSign != absent ? firstSign : secondSign;
            var note = notes.get(owner);
            if (note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                    && note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) continue;
            var corrected = note.withWrittenAccidental(sign);
            if (edited == null) edited = new ArrayList<>(notes);
            edited.set(owner, corrected);
            printed.accept(new SourceLocalAccidental.ProvedMark(corrected, true));
        }
        return edited == null ? score : score.withNotes(edited, IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    /** A lower natural and the upper note's sharp are staggered before a shared downstem. */
    private static boolean staggeredNatural(ScorePageInterpretation score, byte[] raw, byte[] gray,
            int width, int height, ScoreNoteEvent previous, ScoreNoteEvent lower, ScoreNoteEvent upper) {
        int barIndex = lower.measureIndex();
        if (upper.measureIndex() != barIndex || previous.measureIndex() != barIndex
                || lower.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                || previous.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                || upper.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP
                || !dottedAttack(previous) || !undottedDownstem(lower) || !undottedDownstem(upper)
                || previous.diatonicPitchIdentity() != lower.diatonicPitchIdentity()
                || lower.clefBottomDiatonic() != upper.clefBottomDiatonic()
                || upper.staffStep() - lower.staffStep() != 2) return false;
        var bar = score.measures().get(barIndex);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 12 || gap > 23) return false;
        float x = headX(bar, lower, width), y = lower.pageY() * height;
        float highX = headX(bar, upper, width), highY = upper.pageY() * height;
        float prior = headX(bar, previous, width);
        if (Math.abs(highX - x) > .16f * gap || y - highY < .90f * gap || y - highY > 1.22f * gap
                || Math.abs(previous.pageY() * height - y) > .24f * gap
                || x - prior < 4.65f * gap || x - prior > 5.45f * gap) return false;
        if (!sharedDownstem(raw, width, height, x, y, highX, highY, gap)
                || !sharedDownstem(gray, width, height, x, y, highX, highY, gap)) return false;
        float prefixRight = x - .90f * gap;
        var natural = AccidentalSlurSpines.proveThinNaturalPrefix(raw, width, prior, prefixRight, y, gap);
        if (natural.size() != 2 || !natural.equals(
                AccidentalSlurSpines.proveThinNaturalPrefix(gray, width, prior, prefixRight, y, gap))) return false;
        int naturalEdge = natural.get(1).right();
        float naturalY = (natural.get(0).top() + natural.get(1).bottom()) * .5f;
        if (Math.abs(naturalY - y) > .26f * gap) return false;
        int sharpSign = ScoreNoteEvent.ACCIDENTAL_SHARP;
        float sharpWindow = naturalEdge - .16f * gap;
        var sharp = AccidentalSlurSpines.prove(raw, width, sharpWindow, highX, highY, gap, sharpSign);
        if (sharp.size() != 2 || !sharp.equals(
                AccidentalSlurSpines.prove(gray, width, sharpWindow, highX, highY, gap, sharpSign))) return false;
        int separation = sharp.get(0).left() - naturalEdge;
        float sharpY = (sharp.get(1).top() + sharp.get(0).bottom()) * .5f;
        return separation >= .30f * gap && separation <= .65f * gap && Math.abs(sharpY - highY) <= .26f * gap;
    }

    private static boolean sharedDownstem(
            byte[] gray, int width, int height, float x, float y, float highX, float highY, float gap) {
        var lower = PairedTremoloInk.stem(gray, width, height, x, y, gap, -1);
        var upper = PairedTremoloInk.stem(gray, width, height, highX, highY, gap, -1);
        return lower != null && upper != null && Math.abs(lower.x() - upper.x()) <= 2
                && Math.abs(lower.endY() - upper.endY()) <= 2;
    }

    private static boolean undottedDownstem(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.staffCount() == 1 && note.beamCount() == 3 && note.augmentationDots() == 0
                && note.stemDirection() == -1 && note.tupletDivisor() == 1 && note.octaveShift() == 0
                && note.harmonicSemitones() == 0 && !note.tiedFromPrevious();
    }

    private static int ownedSign(
            byte[] raw, byte[] gray, int width, int height, float previous, float x, float y, float gap) {
        int absent = ScoreNoteEvent.ACCIDENTAL_FROM_KEY;
        float prefixRight = x - .95f * gap;
        var natural = AccidentalSlurSpines.proveThinNaturalPrefix(raw, width, previous, prefixRight, y, gap);
        if (natural.size() != 2 || !natural.equals(
                AccidentalSlurSpines.proveThinNaturalPrefix(gray, width, previous, prefixRight, y, gap)))
            return absent;
        float naturalY = (natural.get(0).top() + natural.get(1).bottom()) * .5f;
        if (Math.abs(naturalY - y) > .26f * gap) return absent;
        int prefixEdge = natural.get(1).right();
        int sharpSign = ScoreNoteEvent.ACCIDENTAL_SHARP;
        float sharpLeft = x - 2.35f * gap;
        var sharp = AccidentalSlurSpines.prove(raw, width, sharpLeft, x, y, gap, sharpSign);
        var preparedSharp = AccidentalSlurSpines.prove(gray, width, sharpLeft, x, y, gap, sharpSign);
        if (!sharp.isEmpty() || !preparedSharp.isEmpty()) {
            if (sharp.size() != 2 || !sharp.equals(preparedSharp)) return absent;
            int separation = sharp.get(0).left() - prefixEdge;
            float sharpY = (sharp.get(1).top() + sharp.get(0).bottom()) * .5f;
            return separation >= .20f * gap && separation <= .58f * gap && Math.abs(sharpY - y) <= .22f * gap
                    ? sharpSign : absent;
        }
        int left = prefixEdge + 2, right = Math.round(x - .71f * gap);
        int top = Math.round(y - 1.72f * gap), bottom = Math.round(y + .74f * gap);
        return right - left >= .65f * gap && right - left <= 1.15f * gap
                && PrintedFlatGlyph.matches(raw, width, height, left, top, right, bottom, gap)
                && PrintedFlatGlyph.matches(gray, width, height, left, top, right, bottom, gap)
                ? ScoreNoteEvent.ACCIDENTAL_FLAT : absent;
    }

    private static boolean dottedAttack(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && note.staffCount() == 1 && note.beamCount() == 2 && note.augmentationDots() == 1
                && note.stemDirection() != 0 && note.tupletDivisor() == 1 && note.octaveShift() == 0
                && note.harmonicSemitones() == 0 && !note.tiedFromPrevious();
    }

    private static float headX(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }
}