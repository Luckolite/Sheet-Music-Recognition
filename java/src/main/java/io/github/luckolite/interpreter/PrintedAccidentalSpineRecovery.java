// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Retains literal naturals and sharps proved independently of their already decoded pitch. */
final class PrintedAccidentalSpineRecovery {
    private PrintedAccidentalSpineRecovery() {}

    /** Reconcile literal signs and their unprinted carries in single-note ledger runs. */
    static ScorePageInterpretation reconcileLedgerSigns(
            ScorePageInterpretation score, byte[] raw, byte[] normalized, int width, int height,
            java.util.function.Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 1; i < notes.size(); i++) {
            var note = notes.get(i);
            var prior = notes.get(i - 1);
            if (!ledgerHead(note) || !ledgerHead(prior) || note.measureIndex() != prior.measureIndex()) continue;
            var bar = score.measures().get(note.measureIndex());
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = headX(bar, note, width), previousX = headX(bar, prior, width);
            float y = note.pageY() * height;
            if (gap < 10 || gap > 24 || y > bar.top() * height + (note.octaveShift() == 1 ? .86f : .45f) * gap
                    || x - previousX < 1.8f * gap || x - previousX > 4.15f * gap) continue;

            int current = note.writtenAccidental();
            int accidental = current;
            boolean literal = false;
            if (current == ScoreNoteEvent.ACCIDENTAL_SHARP && note.octaveShift() == 0
                    && agreedSign(raw, normalized, width, previousX, x, y, gap, ScoreNoteEvent.ACCIDENTAL_NATURAL)) {
                accidental = ScoreNoteEvent.ACCIDENTAL_NATURAL;
                literal = true;
            } else if (current == ScoreNoteEvent.ACCIDENTAL_FROM_KEY && note.octaveShift() == 0
                    && prior.octaveShift() == 0 && prior.beamCount() == 2
                    && agreedSharp(raw, normalized, width, previousX, x, y, gap)) {
                accidental = ScoreNoteEvent.ACCIDENTAL_SHARP;
                literal = true;
            } else if (current == ScoreNoteEvent.ACCIDENTAL_FROM_KEY && note.octaveShift() == 1
                    && prior.octaveShift() == 0 && coveredOpeningQuartet(notes, i)) {
                if (sharpAtSoundingEntry(notes, i, bar, raw, normalized, width, height, gap))
                    accidental = ScoreNoteEvent.ACCIDENTAL_SHARP;
            }
            if (accidental == current) continue;
            if (edited == null) edited = new ArrayList<>(notes);
            var corrected = note.withWrittenAccidental(accidental);
            edited.set(i, corrected);
            if (literal) printed.accept(new SourceLocalAccidental.ProvedMark(corrected, accidental, false));

            if (literal && accidental == ScoreNoteEvent.ACCIDENTAL_NATURAL) {
                for (int j = i + 1; j < notes.size(); j++) {
                    var repeated = notes.get(j);
                    if (repeated.measureIndex() != note.measureIndex()) break;
                    if (repeated.kind() != ScoreNoteEvent.Kind.PITCHED) continue;
                    if (repeated.diatonicPitchIdentity() != note.diatonicPitchIdentity()) continue;
                    if (!ledgerHead(repeated) || repeated.octaveShift() != note.octaveShift()
                            || repeated.writtenAccidental() != current) break;
                    float repeatX = headX(bar, repeated, width);
                    float prevX = headX(bar, notes.get(j - 1), width);
                    float repeatY = repeated.pageY() * height;
                    if (anySharp(raw, normalized, width, prevX, repeatX, repeatY, gap)) break;
                    edited.set(j, repeated.withWrittenAccidental(accidental));
                }
            }
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
    }

    /** A narrow printed 1 above the preceding head can be misread as a flat on the next head. */
    static ScorePageInterpretation rejectPriorFingeringFlats(ScorePageInterpretation score,
            byte[] labels, byte[] raw, byte[] gray, int width, int height) {
        if (labels == null || raw == null || gray == null || raw.length != (long) width * height
                || raw.length != labels.length || raw.length != gray.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 1; i < notes.size(); i++) {
            var note = notes.get(i);
            var prior = notes.get(i - 1);
            int index = note.measureIndex();
            if (note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT || index != prior.measureIndex()
                    || index < 0 || index >= score.measures().size()
                    || !fingeringFlatHead(note) || !fingeringFlatHead(prior)
                    || prior.staffStep() < 9 || note.staffStep() != prior.staffStep() + 1
                    || prior.clefBottomDiatonic() != note.clefBottomDiatonic()) continue;
            var bar = score.measures().get(index);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = headX(bar, note, width), px = headX(bar, prior, width);
            float y = note.pageY() * height, py = prior.pageY() * height;
            if (gap < 11 || gap > 24 || x - px < gap * 1.85f || x - px > gap * 2.8f
                    || py - y < gap * .38f || py - y > gap * .65f
                    || !priorFingeringOne(raw, labels, width, height, px, py, x, y, gap)
                    || !priorFingeringOne(gray, labels, width, height, px, py, x, y, gap)) continue;
            if (edited == null) edited = new ArrayList<>(notes);
            edited.set(i, note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static boolean fingeringFlatHead(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.beamCount() == 1 && n.unbeamedDurationBeats() == 0 && n.stemDirection() == -1
                && n.octaveShift() == 0 && n.augmentationDots() == 0 && n.tupletDivisor() == 1
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null && !n.crossStaffBeam()
                && (n.articulations() & NoteOrnament.GRACE) == 0 && !n.tiedFromPrevious()
                && n.boundaryTies() == 0 && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0;
    }

    private static boolean priorFingeringOne(byte[] ink, byte[] labels, int width, int height,
            float px, float py, float x, float y, float gap) {
        int left = Math.round(px - .57f * gap), right = Math.round(px + .57f * gap);
        int top = Math.round(py - 2.38f * gap), bottom = Math.round(py - .69f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        if (PrintedFlatGlyph.matchesOwnedInk(labels, ink, width, height, x, y, gap)
                || PrintedFlatGlyph.matches(ink, width, height, Math.round(x - 1.85f * gap),
                        Math.round(y - 1.8f * gap), Math.round(x - .72f * gap),
                        Math.round(y + .85f * gap), gap)) return false;

        var digits = RawNumeralComponents.capture(ink, width, height, left, top, right, bottom,
                () -> {}, () -> {}).components();
        if (digits.size() != 1) return false;
        var one = digits.get(0);
        int w = one.right - one.left + 1, h = one.bottom - one.top + 1;
        if (one.left <= left || one.right >= right || one.top <= top || one.bottom >= bottom
                || w < .48f * gap || w > .84f * gap || h < .95f * gap || h > 1.36f * gap
                || Math.abs((one.left + one.right) * .5f - px) > .22f * gap
                || Math.abs(y - one.bottom) > .51f * gap
                || py - one.bottom < .76f * gap || py - one.bottom > 1.40f * gap
                || !TripletRhythmDetector.fingerOne(ink, width, one)) return false;
        // Unlike a flat bowl, the lower middle of the numeral stays narrow.
        for (int row = one.top + Math.round(h * .47f); row <= one.top + Math.round(h * .72f); row++) {
            int black = 0;
            for (int col = one.left; col <= one.right; col++)
                if ((ink[row * width + col] & 255) <= 165) black++;
            if (black > .67f * w) return false;
        }

        var budget = new PrintedBeamShafts.Budget(28_000);
        var first = PrintedBeamShafts.at(ink, width, height, px, py, gap, budget);
        var second = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        if (budget.refused() || first.size() != 1 || second.size() != 1
                || first.get(0).direction() != 1 || second.get(0).direction() != 1) return false;
        var a = first.get(0);
        var b = second.get(0);
        if (!(WrappedEighthTriplets.matchesBeamRails(ink, width, height, a, b, gap, 1)
                || WrappedEighthTriplets.matchesBeamRails(ink, width, height, a, b, gap, 2))) return false;
        // The joined shafts must carry a thick beam, even when nearby staff ink mimics a second rail.
        int start = a.right() + Math.round(.43f * gap), end = b.left() - Math.round(.43f * gap);
        int thick = 0, depth = Math.round(.50f * gap);
        for (int col = start; col <= end; col++) {
            float t = (col - a.right()) / (float)(b.left() - a.right());
            int tip = Math.round(a.endY() + t * (b.endY() - a.endY()));
            if (tip < depth || tip >= height - 2) return false;
            int count = 0;
            for (int row = tip - depth; row <= tip + 2; row++)
                if ((ink[row * width + col] & 255) <= 155) count++;
            if (count >= .31f * gap) thick++;
        }
        return end > start && thick >= .85f * (end - start + 1);
    }

    /** Replace a decoded natural only when the repeat's printed shoulder proves another sign. */
    static ScorePageInterpretation reconcileRepeatedMonophonicSigns(
            ScorePageInterpretation score, byte[] labels, byte[] raw, byte[] normalized,
            int width, int height, java.util.function.Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (labels == null || raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || labels.length != raw.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 1; i < notes.size(); i++) {
            var note = notes.get(i);
            var past = notes.get(i - 1);
            int measure = note.measureIndex();
            if (measure != past.measureIndex() || measure < 0 || measure >= score.measures().size()
                    || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL
                    || !regularDyadHead(note) || !regularDyadHead(past)
                    || note.diatonicPitchIdentity() != past.diatonicPitchIdentity()
                    || note.octaveShift() != past.octaveShift()
                    || past.tiedFromPrevious()) continue;
            var bar = score.measures().get(measure);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = headX(bar, note, width), previous = headX(bar, past, width);
            float y = note.pageY() * height;
            if (gap < 10 || gap > 24 || x - previous < 1.85f * gap || x - previous > 3.3f * gap) continue;
            if (i + 1 < notes.size() && notes.get(i + 1).measureIndex() == measure
                    && headX(bar, notes.get(i + 1), width) - x < gap * 1.65f) continue;
            if (!AccidentalSlurSpines.prove(raw, width, previous, x, y, gap, ScoreNoteEvent.ACCIDENTAL_NATURAL).isEmpty()
                    || !AccidentalSlurSpines.prove(normalized, width, previous, x, y, gap,
                            ScoreNoteEvent.ACCIDENTAL_NATURAL).isEmpty()) continue;
            boolean sharp = agreedSign(raw, normalized, width, previous, x, y, gap, ScoreNoteEvent.ACCIDENTAL_SHARP);
            boolean flat = PrintedFlatGlyph.matchesOwnedInk(labels, raw, width, height, x, y, gap)
                    && PrintedFlatGlyph.matchesOwnedInk(labels, normalized, width, height, x, y, gap);
            if (sharp == flat) continue;
            int sign = sharp ? ScoreNoteEvent.ACCIDENTAL_SHARP : ScoreNoteEvent.ACCIDENTAL_FLAT;
            if (edited == null) edited = new ArrayList<>(notes);
            var corrected = note.withWrittenAccidental(sign);
            edited.set(i, corrected);
            printed.accept(new SourceLocalAccidental.ProvedMark(corrected, sign, false));
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    /** Recover printed signs whose two spines belong to a head in a beamed sixth. */
    static ScorePageInterpretation reconcileDyadSigns(
            ScorePageInterpretation score, byte[] labels, byte[] raw, byte[] normalized, int width, int height,
            java.util.function.Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 2; i + 1 < notes.size(); i++) {
            var first = notes.get(i);
            var second = notes.get(i + 1);
            int measure = first.measureIndex();
            if (measure < 0 || measure >= score.measures().size()) continue;
            var bar = score.measures().get(measure);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 24 || !beamedSixth(first, second, bar, width, gap)) continue;
            int start = i++;
            var previous = notes.get(start - 2);
            var previousPartner = notes.get(start - 1);
            if (previous.measureIndex() != measure
                    || !beamedSixth(previous, previousPartner, bar, width, gap)) continue;
            float previousX = Math.max(headX(bar, previous, width), headX(bar, previousPartner, width));
            for (int owner = start; owner <= i; owner++) {
                var note = notes.get(owner);
                float x = headX(bar, note, width);
                float y = note.pageY() * height;
                float spacing = x - previousX;
                if (spacing < 1.8f * gap || spacing > 3.35f * gap) continue;
                int current = note.writtenAccidental();
                if (current != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        && current != ScoreNoteEvent.ACCIDENTAL_NATURAL) continue;
                boolean sharp = agreedSharp(raw, normalized, width, previousX, x, y, gap);
                boolean shiftedUpper = false;
                if (!sharp) {
                    var partner = notes.get(owner == start ? i : start);
                    float partnerX = headX(bar, partner, width), partnerY = partner.pageY() * height;
                    boolean continuousPartner = agreedSign(raw, normalized, width, previousX, partnerX, partnerY,
                            gap, ScoreNoteEvent.ACCIDENTAL_SHARP);
                    sharp = continuousPartner && agreedSign(raw, normalized, width, previousX, x, y,
                            gap, ScoreNoteEvent.ACCIDENTAL_SHARP);
                    if (!sharp && continuousPartner
                            && agreedSharp(raw, normalized, width, previousX, partnerX, partnerY, gap)) {
                        sharp = sharpWithStaffRulesRemoved(raw, width, height, previousX, x, y, gap)
                                && sharpWithStaffRulesRemoved(normalized, width, height, previousX, x, y, gap);
                        shiftedUpper = !sharp && y + 2.2f * gap < partnerY
                                && agreedSign(raw, normalized, width, previousX, x, y - .20f * gap,
                                        gap, ScoreNoteEvent.ACCIDENTAL_SHARP)
                                && agreedSign(raw, normalized, width, previousX, x, y - .25f * gap,
                                        gap, ScoreNoteEvent.ACCIDENTAL_SHARP);
                        sharp |= shiftedUpper;
                    }
                }
                var partner = notes.get(owner == start ? i : start);
                var cancellation = current == ScoreNoteEvent.ACCIDENTAL_NATURAL && y > partner.pageY() * height
                        ? followingDyadCancellation(notes, i, note, bar, raw, normalized, width, height, gap, x)
                        : null;
                boolean naturalPrefix = cancellation != null
                        && stackedCancellationSharp(raw, normalized, width, previousX, x, y, gap);
                boolean natural = !naturalPrefix && agreedSign(raw, normalized, width, previousX, x, y, gap,
                        ScoreNoteEvent.ACCIDENTAL_NATURAL);
                sharp |= naturalPrefix;
                if (sharp == natural || natural && current == ScoreNoteEvent.ACCIDENTAL_NATURAL) continue;
                int accidental = sharp ? ScoreNoteEvent.ACCIDENTAL_SHARP : ScoreNoteEvent.ACCIDENTAL_NATURAL;
                if (edited == null) edited = new ArrayList<>(notes);
                var corrected = note.withWrittenAccidental(accidental);
                edited.set(owner, corrected);
                printed.accept(new SourceLocalAccidental.ProvedMark(corrected, accidental, naturalPrefix));
                if (naturalPrefix)
                    printed.accept(new SourceLocalAccidental.ProvedMark(cancellation,
                            ScoreNoteEvent.ACCIDENTAL_NATURAL, false));
                if (shiftedUpper && current == ScoreNoteEvent.ACCIDENTAL_NATURAL) {
                    retainRepeatedSharp(notes, edited, owner, bar, labels, raw, normalized, width, height, gap);
                }
            }
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    /** Recover label-supported flats in aligned, shared-stem sixths. */
    static ScorePageInterpretation reconcileDyadFlats(
            ScorePageInterpretation score, byte[] labels, byte[] raw, byte[] normalized, int width, int height,
            java.util.function.Consumer<SourceLocalAccidental.ProvedMark> printed) {
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 0; i + 1 < notes.size(); i++) {
            var first = notes.get(i);
            var second = notes.get(i + 1);
            int measure = first.measureIndex();
            if (measure < 0 || measure >= score.measures().size() || second.measureIndex() != measure
                    || first.octaveShift() != second.octaveShift()
                    || first.stemDirection() != second.stemDirection()
                    || !ClosedMonophonicSixteenths.regularQuartetHead(first)
                    || !ClosedMonophonicSixteenths.regularQuartetHead(second)) continue;
            int interval = Math.abs(first.diatonicPitchIdentity() - second.diatonicPitchIdentity());
            if (interval != 5 && interval != 7) continue;
            boolean matchedDottedOctave = interval == 7
                    && first.augmentationDots() == second.augmentationDots()
                    && first.augmentationDots() >= 1 && first.augmentationDots() <= 2
                    && first.beamCount() == 2 && second.beamCount() == 2
                    && first.stemDirection() == -1
                    && first.tupletDivisor() == 1 && second.tupletDivisor() == 1;
            if (!matchedDottedOctave && (!regularDyadHead(first) || !regularDyadHead(second))) continue;
            var bar = score.measures().get(measure);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 24
                    || Math.abs(headX(bar, first, width) - headX(bar, second, width)) > .20f * gap) continue;
            int start = i++;
            for (int owner = start; owner <= i; owner++) {
                var note = notes.get(owner);
                int current = note.writtenAccidental();
                float x = headX(bar, note, width), y = note.pageY() * height;
                if (interval == 7) {
                    var upper = notes.get(owner == start ? i : start);
                    float highY = upper.pageY() * height;
                    if (current != ScoreNoteEvent.ACCIDENTAL_SHARP
                            || upper.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FLAT
                            || Math.abs(y - highY - 3.5f * gap) > .30f * gap) continue;
                    float highX = headX(bar, upper, width);
                    float previous = previousAttack(notes, start, bar, width, gap, matchedDottedOctave);
                    if (!Float.isFinite(previous) || x - previous < 2.35f * gap
                            || agreedSharp(raw, normalized, width, previous, x, y, gap)
                            || !PrintedFlatGlyph.matchesOwnedInk(labels, raw, width, height, highX, highY, gap)
                            || !PrintedFlatGlyph.matchesOwnedInk(labels, normalized, width, height, highX, highY, gap))
                        continue;
                } else if (current != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                        && current != ScoreNoteEvent.ACCIDENTAL_NATURAL) continue;
                if (!PrintedFlatGlyph.matchesOwnedInk(labels, raw, width, height, x, y, gap)
                        || !PrintedFlatGlyph.matchesOwnedInk(labels, normalized, width, height, x, y, gap)) continue;
                if (edited == null) edited = new ArrayList<>(notes);
                var corrected = note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FLAT);
                edited.set(owner, corrected);
                printed.accept(new SourceLocalAccidental.ProvedMark(corrected, ScoreNoteEvent.ACCIDENTAL_FLAT, false));
            }
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static ScoreNoteEvent followingDyadCancellation(List<ScoreNoteEvent> notes, int columnEnd,
            ScoreNoteEvent head, MeasureRegion bar, byte[] raw, byte[] normalized,
            int width, int height, float gap, float x) {
        if (columnEnd + 2 >= notes.size()) return null;
        var a = notes.get(columnEnd + 1);
        var b = notes.get(columnEnd + 2);
        if (a.measureIndex() != head.measureIndex() || !beamedSixth(a, b, bar, width, gap)) return null;
        var next = a.pageY() > b.pageY() ? a : b;
        if (next.staffIndex() != head.staffIndex() || next.octaveShift() != head.octaveShift()
                || next.diatonicPitchIdentity() != head.diatonicPitchIdentity()
                || next.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_NATURAL) return null;
        float nextX = headX(bar, next, width);
        return nextX - x >= gap * 1.8f && nextX - x <= gap * 2.85f
                && agreedSign(raw, normalized, width, x, nextX, next.pageY() * height,
                        gap, ScoreNoteEvent.ACCIDENTAL_NATURAL) ? next : null;
    }

    private static boolean stackedCancellationSharp(byte[] raw, byte[] normalized, int width,
            float previous, float x, float y, float gap) {
        int sharp = ScoreNoteEvent.ACCIDENTAL_SHARP, natural = ScoreNoteEvent.ACCIDENTAL_NATURAL;
        float center = y + .14f * gap;
        var spines = AccidentalSlurSpines.prove(raw, width, previous, x, center, gap, sharp);
        if (spines.size() != 2 || !spines.equals(
                AccidentalSlurSpines.prove(normalized, width, previous, x, center, gap, sharp))) return false;
        for (int step = 0; step < 3; step++) {
            float naturalX = x - (1.17f + .065f * step) * gap;
            float naturalY = y - .37f * gap;
            float size = gap * (1 - .0625f * step);
            var prefix = AccidentalSlurSpines.prove(raw, width, previous, naturalX, naturalY, size, natural);
            if (prefix.size() != 2 || !prefix.equals(AccidentalSlurSpines.prove(
                    normalized, width, previous, naturalX, naturalY, size, natural))) continue;
            if (sameSpine(prefix.get(1), spines.get(0)) && prefix.get(0).right() < spines.get(0).left()) return true;
        }
        return false;
    }

    private static void retainRepeatedSharp(List<ScoreNoteEvent> notes, List<ScoreNoteEvent> edited,
            int owner, MeasureRegion bar, byte[] labels, byte[] raw, byte[] normalized,
            int width, int height, float gap) {
        var start = notes.get(owner);
        for (int i = owner + 1; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.measureIndex() != start.measureIndex()) return;
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.staffIndex() != start.staffIndex() || note.octaveShift() != start.octaveShift()
                    || note.diatonicPitchIdentity() != start.diatonicPitchIdentity()) continue;
            if (!dyadHead(note) || note.writtenAccidental() != start.writtenAccidental()) return;
            float x = headX(bar, note, width), y = note.pageY() * height;
            float previous = x;
            for (int j = i - 1; j >= owner; j--) {
                float earlier = headX(bar, notes.get(j), width);
                if (x - earlier < .7f * gap) continue;
                previous = earlier;
                break;
            }
            if (x - previous < 1.8f * gap || x - previous > 3.35f * gap
                    || !unmarkedRepeat(labels, raw, normalized, width, height, previous, x, y, gap)) return;
            edited.set(i, note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_SHARP));
        }
    }

    static boolean unmarkedRepeat(byte[] labels, byte[] raw, byte[] normalized,
            int width, int height, float previous, float x, float y, float gap) {
        int left = Math.round(Math.max(previous + .62f * gap, x - 2.4f * gap));
        int right = Math.round(x - .65f * gap);
        int top = Math.round(y - 1.55f * gap), bottom = Math.round(y + 1.35f * gap);
        if (labels == null || labels.length != raw.length || left < 0 || right >= width
                || top < 0 || bottom >= height || left >= right) return false;
        int marked = 0;
        for (int column = left; column <= right; column++) {
            int continuous = 0;
            for (int row = top; row <= bottom; row++) {
                int pos = row * width + column;
                if (labels[pos] == OmrMeasurePostProcessor.CLEF_OR_KEY
                        && ((raw[pos] & 255) < 180 || (normalized[pos] & 255) < 180)) {
                    if (++marked > .16f * gap * gap || ++continuous > .4f * gap) return false;
                } else {
                    continuous = 0;
                }
            }
        }
        return !anySharp(raw, normalized, width, previous, x, y, gap)
                && !agreedSign(raw, normalized, width, previous, x, y, gap, ScoreNoteEvent.ACCIDENTAL_NATURAL)
                && !PrintedFlatGlyph.matchesOwnedInk(labels, raw, width, height, x, y, gap)
                && !PrintedFlatGlyph.matchesOwnedInk(labels, normalized, width, height, x, y, gap);
    }

    /** Resolve naturals when staff ink interrupts a spine or joins its crossbars. */
    static ScorePageInterpretation reconcileLedgerNaturals(ScorePageInterpretation score, byte[] labels,
            byte[] raw, byte[] normalized, int width, int height,
            java.util.function.Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (labels == null || raw == null || normalized == null || labels.length != (long) width * height
                || labels.length != raw.length || labels.length != normalized.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 1; i < notes.size(); i++) {
            var note = notes.get(i);
            int current = note.writtenAccidental();
            if (current != ScoreNoteEvent.ACCIDENTAL_FROM_KEY && current != ScoreNoteEvent.ACCIDENTAL_SHARP
                    && current != ScoreNoteEvent.ACCIDENTAL_FLAT) continue;
            int measure = note.measureIndex();
            if (measure < 0 || measure >= score.measures().size() || note.octaveShift() != 0) continue;
            var bar = score.measures().get(measure);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 24 || !beamedNaturalHead(notes, i, bar, width, gap)) continue;
            float x = headX(bar, note, width), y = note.pageY() * height;
            float previousX = previousAttack(notes, i, bar, width, gap);
            float spacing = x - previousX;
            if (spacing < 1.8f * gap || spacing > 3.35f * gap || !Float.isFinite(spacing)
                    || agreedSharp(raw, normalized, width, previousX, x, y, gap)) continue;
            boolean proved = naturalWithLocalMask(labels, raw, normalized, width, height, previousX,
                    x, y, gap, y, gap, gap * 1.125f, false);
            float top = bar.top() * height;
            if (!proved && current == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    && note.staffStep() >= 17 && y < top - 1.2f * gap
                    && followsSamePitchFlat(notes, i, bar, width, gap, previousX)) {
                proved = naturalWithLocalMask(labels, raw, normalized, width, height, previousX,
                        x, y, gap, y - .56f * gap, gap * .9375f, gap * .9375f, false);
            }
            if (!proved && note.stemDirection() <= 0 && y < top - gap * .35f) {
                if (y < top - gap * 1.2f) {
                    float center = current == ScoreNoteEvent.ACCIDENTAL_FLAT ? y - gap * .50f : y;
                    float size = current == ScoreNoteEvent.ACCIDENTAL_FLAT ? gap : gap * .875f;
                    proved = naturalWithLocalMask(labels, raw, normalized, width, height, previousX,
                            x, y, gap, center, size, size, true);
                } else {
                    float center = y + gap * .56f;
                    proved = naturalWithLocalMask(labels, raw, normalized, width, height, previousX,
                            x, y, gap, center, gap * .875f, gap * .875f, false);
                }
            }
            if (!proved) continue;
            if (edited == null) edited = new ArrayList<>(notes);
            var corrected = note.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_NATURAL);
            edited.set(i, corrected);
            printed.accept(new SourceLocalAccidental.ProvedMark(corrected, ScoreNoteEvent.ACCIDENTAL_NATURAL, false));
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static boolean followsSamePitchFlat(List<ScoreNoteEvent> notes, int index, MeasureRegion bar,
            int width, float gap, float previousX) {
        var note = notes.get(index);
        for (int i = index - 1; i >= 0; i--) {
            var prior = notes.get(i);
            if (prior.measureIndex() != note.measureIndex() || headX(bar, prior, width) < previousX - .24f * gap)
                return false;
            if (Math.abs(headX(bar, prior, width) - previousX) <= .24f * gap
                    && prior.staffIndex() == note.staffIndex() && prior.octaveShift() == note.octaveShift()
                    && prior.kind() == ScoreNoteEvent.Kind.PITCHED
                    && prior.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FLAT
                    && prior.diatonicPitchIdentity() == note.diatonicPitchIdentity()) return true;
        }
        return false;
    }

    private static boolean beamedNaturalHead(List<ScoreNoteEvent> notes, int i, MeasureRegion bar,
            int width, float gap) {
        var head = notes.get(i);
        if (regularDyadHead(head)) return true;
        if (head.stemDirection() != 0 || !regularDyadHead(head.withStemDirection(-1))) return false;
        return i > 0 && beamedSixth(head, notes.get(i - 1), bar, width, gap)
                || i + 1 < notes.size() && beamedSixth(head, notes.get(i + 1), bar, width, gap);
    }

    private static float previousAttack(List<ScoreNoteEvent> notes, int index, MeasureRegion bar,
            int width, float gap) {
        return previousAttack(notes, index, bar, width, gap, false);
    }

    private static float previousAttack(List<ScoreNoteEvent> notes, int index, MeasureRegion bar,
            int width, float gap, boolean allowThirtySecond) {
        var note = notes.get(index);
        float x = headX(bar, note, width);
        for (int i = index - 1; i >= 0; i--) {
            var previous = notes.get(i);
            if (previous.measureIndex() != note.measureIndex()) return Float.NaN;
            float previousX = headX(bar, previous, width);
            if (x - previousX < .7f * gap) continue;
            if (regularDyadHead(previous)) return previousX;
            if (allowThirtySecond && previous.beamCount() == 3 && previous.augmentationDots() == 0
                    && previous.kind() == ScoreNoteEvent.Kind.PITCHED
                    && previous.staffIndex() == note.staffIndex()
                    && previous.stemDirection() == note.stemDirection()
                    && previous.tupletDivisor() == 1 && !previous.crossStaffBeam()
                    && previous.unbeamedDurationBeats() == 0) return previousX;
            return Float.NaN;
        }
        return Float.NaN;
    }

    private static boolean naturalWithLocalMask(byte[] labels, byte[] raw, byte[] normalized, int width,
            int height, float previousX, float x, float y, float gap, float center,
            float maskGap, float rawGap, boolean filterRules) {
        int left = (int) (x - 4 * gap), right = (int) (x + gap);
        int top = (int) (y - 3 * gap), bottom = (int) (y + 3 * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var first = naturalMask(raw, labels, width, height, left, top, right, bottom, gap, filterRules);
        var second = naturalMask(normalized, labels, width, height, left, top, right, bottom, gap, filterRules);
        if (first == null || second == null) return false;
        int stride = right - left + 1, natural = ScoreNoteEvent.ACCIDENTAL_NATURAL;
        var spines = AccidentalSlurSpines.prove(first, stride, previousX - left, x - left, center - top, maskGap,
                natural);
        if (spines.size() != 2 || !spines.equals(AccidentalSlurSpines.prove(second, stride, previousX - left,
                x - left, center - top, maskGap, natural))) return false;
        if (!filterRules) {
            var original = AccidentalSlurSpines.prove(raw, width, previousX, x, center, rawGap, natural);
            if (original.size() != 2 || !original.equals(
                    AccidentalSlurSpines.prove(normalized, width, previousX, x, center, rawGap, natural))) return false;
            for (int i = 0; i < spines.size(); i++)
                if (Math.abs((spines.get(i).left() + left) - original.get(i).left()) > .2f * gap) return false;
        }
        for (var spine : spines) {
            int supported = 0;
            for (int row = spine.top(); row <= spine.bottom(); row++)
                for (int column = spine.left(); column <= spine.right(); column++) {
                    int at = (row + top) * width + column + left;
                    if (labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY
                            && (raw[at] & 255) < 190 && (normalized[at] & 255) < 190) supported++;
                }
            if (supported < gap * 2.5f) return false;
        }
        return true;
    }

    private static byte[] naturalMask(byte[] ink, byte[] labels, int width, int height,
            int left, int top, int right, int bottom, float gap, boolean filterRules) {
        if (filterRules) {
            var mask = FaintSharpInk.cropPairedCrossbars(ink, width, height, left, top, right, bottom, gap);
            if (mask != null) for (int i = 0; i < mask.length; i++) mask[i] = mask[i] == 0 ? (byte) 255 : 0;
            return mask;
        }
        byte[] mask = new byte[(right - left + 1) * (bottom - top + 1)];
        for (int row = top, i = 0; row <= bottom; row++)
            for (int column = left; column <= right; column++, i++) {
                int at = row * width + column;
                mask[i] = labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY ? ink[at] : (byte) 255;
            }
        return mask;
    }

    /** Read staggered accidentals where two beamed heads are only a third apart. */
    static ScorePageInterpretation reconcileSpacedThirds(ScorePageInterpretation score, byte[] labels,
            byte[] raw, byte[] normalized, int width, int height,
            java.util.function.Consumer<SourceLocalAccidental.ProvedMark> printed) {
        if (labels == null || raw == null || normalized == null || labels.length != (long) width * height
                || labels.length != raw.length || labels.length != normalized.length) return score;
        var notes = score.notes();
        ArrayList<ScoreNoteEvent> edited = null;
        for (int i = 1; i + 1 < notes.size(); i++) {
            var first = notes.get(i);
            var second = notes.get(i + 1);
            int measure = first.measureIndex();
            if (measure < 0 || measure >= score.measures().size() || second.measureIndex() != measure
                    || first.octaveShift() != 0 || second.octaveShift() != 0
                    || first.stemDirection() != second.stemDirection()
                    || !regularDyadHead(first) || !regularDyadHead(second)
                    || Math.abs(first.diatonicPitchIdentity() - second.diatonicPitchIdentity()) != 2) continue;
            var bar = score.measures().get(measure);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float aX = headX(bar, first, width), bX = headX(bar, second, width);
            if (gap < 10 || gap > 24 || Math.abs(aX - bX) > .20f * gap) continue;
            int leftOwner = i++;
            int upper = first.pageY() < second.pageY() ? leftOwner : i;
            int lower = upper == leftOwner ? i : leftOwner;
            var high = notes.get(upper);
            var low = notes.get(lower);
            float x = headX(bar, high, width), y = high.pageY() * height;
            float lowX = headX(bar, low, width), lowY = low.pageY() * height;
            if (lowY - y < .8f * gap || lowY - y > 1.27f * gap) continue;
            float previous = previousAttack(notes, leftOwner, bar, width, gap);
            float spacing = x - previous;
            if (!(spacing >= 3.12f * gap && spacing <= 5.45f * gap)) continue;
            int highSign = high.writtenAccidental();
            boolean upperNatural = naturalWithLocalMask(labels, raw, normalized, width, height, previous,
                    x, y, gap, y, gap, gap, false)
                    || naturalWithLocalMask(labels, raw, normalized, width, height, previous,
                            x, y, gap, y, gap, gap * 1.125f, false);
            boolean upperSharp = agreeingThirdSharp(raw, normalized, width, previous, x, y, gap);
            if (highSign == ScoreNoteEvent.ACCIDENTAL_FROM_KEY || highSign == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    || highSign == ScoreNoteEvent.ACCIDENTAL_SHARP) {
                int corrected = upperNatural && !upperSharp ? ScoreNoteEvent.ACCIDENTAL_NATURAL
                        : upperSharp && !upperNatural ? ScoreNoteEvent.ACCIDENTAL_SHARP : highSign;
                if (corrected != highSign) {
                    if (edited == null) edited = new ArrayList<>(notes);
                    var changed = high.withWrittenAccidental(corrected);
                    edited.set(upper, changed);
                    printed.accept(new SourceLocalAccidental.ProvedMark(changed, corrected, false));
                }
            }
            boolean upperFlat = highSign == ScoreNoteEvent.ACCIDENTAL_FLAT
                    && PrintedFlatGlyph.matchesOwnedInk(labels, raw, width, height, x, y, gap)
                    && PrintedFlatGlyph.matchesOwnedInk(labels, normalized, width, height, x, y, gap);
            int lowSign = low.writtenAccidental();
            if (lowSign == ScoreNoteEvent.ACCIDENTAL_NATURAL && low.staffStep() >= 12
                    && (low.staffStep() & 1) == 0 && low.stemDirection() == -1
                    && highSign == ScoreNoteEvent.ACCIDENTAL_FROM_KEY && !upperSharp && !upperNatural
                    && naturalWithLocalMask(labels, raw, normalized, width, height, previous,
                            lowX, lowY, gap, lowY, gap, gap, false)) {
                printed.accept(new SourceLocalAccidental.ProvedMark(low, ScoreNoteEvent.ACCIDENTAL_NATURAL, false));
            }
            if (upperNatural == upperFlat || !(lowSign == ScoreNoteEvent.ACCIDENTAL_NATURAL
                    || lowSign == ScoreNoteEvent.ACCIDENTAL_SHARP || lowSign == ScoreNoteEvent.ACCIDENTAL_FROM_KEY))
                continue;
            float staggeredX = lowX - 1.23f * gap;
            boolean flat = upperNatural
                    ? PrintedFlatGlyph.matchesOwnedInk(labels, raw, width, height, staggeredX, lowY, gap)
                        && PrintedFlatGlyph.matchesOwnedInk(labels, normalized, width, height, staggeredX, lowY, gap)
                    : staggeredFlat(raw, width, height, staggeredX, lowY, gap)
                        && staggeredFlat(normalized, width, height, staggeredX, lowY, gap);
            if (!flat) continue;
            if (edited == null) edited = new ArrayList<>(notes);
            var changed = low.withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FLAT);
            edited.set(lower, changed);
            printed.accept(new SourceLocalAccidental.ProvedMark(changed, ScoreNoteEvent.ACCIDENTAL_FLAT, false));
        }
        return edited == null ? score : score.withNotes(edited,
                java.util.stream.IntStream.range(0, notes.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static boolean agreeingThirdSharp(byte[] raw, byte[] normalized, int width,
            float previous, float x, float y, float gap) {
        var source = AccidentalSlurSpines.prove(raw, width, previous, x, y, gap,
                ScoreNoteEvent.ACCIDENTAL_SHARP);
        if (source.size() != 2 || !source.equals(AccidentalSlurSpines.prove(normalized, width,
                previous, x, y, gap, ScoreNoteEvent.ACCIDENTAL_SHARP))) return false;
        for (var ink : List.of(raw, normalized)) {
            var interrupted = AccidentalSlurSpines.proveInterruptedSharp(ink, width, previous, x, y, gap);
            if (interrupted.size() != 2 || !sameSpine(source.get(0), interrupted.get(0))
                    || !sameSpine(source.get(1), interrupted.get(1))) return false;
        }
        return true;
    }

    private static boolean staggeredFlat(byte[] ink, int width, int height, float x, float y, float gap) {
        return PrintedFlatGlyph.matches(ink, width, height, Math.round(x - 1.85f * gap),
                Math.round(y - 1.8f * gap), Math.round(x - .72f * gap), Math.round(y + .85f * gap), gap);
    }

    private static boolean regularDyadHead(ScoreNoteEvent note) {
        return ClosedMonophonicSixteenths.regularQuartetHead(note)
                && note.augmentationDots() == 0 && note.tupletDivisor() == 1;
    }

    private static boolean dyadHead(ScoreNoteEvent note) {
        return note.stemDirection() == -1 && regularDyadHead(note);
    }

    private static boolean beamedSixth(ScoreNoteEvent a, ScoreNoteEvent b, MeasureRegion bar, int width, float gap) {
        if (a.kind() != ScoreNoteEvent.Kind.PITCHED || b.kind() != ScoreNoteEvent.Kind.PITCHED
                || a.measureIndex() != b.measureIndex() || a.octaveShift() != b.octaveShift()
                || Math.abs(a.diatonicPitchIdentity() - b.diatonicPitchIdentity()) != 5
                || Math.abs(headX(bar, a, width) - headX(bar, b, width)) > .20f * gap) return false;
        var upper = a.pageY() < b.pageY() ? a : b;
        var lower = upper == a ? b : a;
        return dyadHead(lower) && (dyadHead(upper) || upper.stemDirection() == 0
                && dyadHead(upper.withStemDirection(-1)));
    }

    private static boolean ledgerHead(ScoreNoteEvent n) {
        return ClosedMonophonicSixteenths.regularQuartetHead(n) && n.stemDirection() == -1
                && n.augmentationDots() == 0 && n.tupletDivisor() == 1 && !n.tiedFromPrevious();
    }

    private static boolean agreedSign(byte[] a, byte[] b, int width, float previous, float x, float y,
            float gap, int accidental) {
        var first = AccidentalSlurSpines.prove(a, width, previous, x, y, gap, accidental);
        return first.size() == 2 && first.equals(AccidentalSlurSpines.prove(b, width, previous, x, y, gap, accidental));
    }

    private static boolean agreedSharp(byte[] a, byte[] b, int width, float previous, float x, float y, float gap) {
        var first = AccidentalSlurSpines.proveInterruptedSharp(a, width, previous, x, y, gap);
        return first.size() == 2 && first.equals(AccidentalSlurSpines.proveInterruptedSharp(b, width, previous, x, y, gap));
    }

    private static boolean sharpWithStaffRulesRemoved(byte[] ink, int width, int height,
            float previousX, float x, float y, float gap) {
        int left = (int) (x - 4 * gap), right = (int) (x + gap);
        int top = (int) (y - 3 * gap), bottom = (int) (y + 3 * gap);
        byte[] crop = FaintSharpInk.cropPairedCrossbars(ink, width, height, left, top, right, bottom, gap);
        if (crop == null) return false;
        for (int i = 0; i < crop.length; i++) crop[i] = crop[i] == 0 ? (byte) 255 : 0;
        int stride = right - left + 1;
        return !AccidentalSlurSpines.prove(crop, stride, previousX - left, x - left, y - top, gap,
                ScoreNoteEvent.ACCIDENTAL_SHARP).isEmpty();
    }

    private static boolean anySharp(byte[] a, byte[] b, int width, float previous, float x, float y, float gap) {
        return !AccidentalSlurSpines.proveInterruptedSharp(a, width, previous, x, y, gap).isEmpty()
                || !AccidentalSlurSpines.proveInterruptedSharp(b, width, previous, x, y, gap).isEmpty();
    }

    private static boolean coveredOpeningQuartet(List<ScoreNoteEvent> notes, int first) {
        if (first + 3 >= notes.size()) return false;
        var start = notes.get(first);
        for (int i = first + 1; i < first + 4; i++) {
            var n = notes.get(i);
            if (n.measureIndex() != start.measureIndex() || n.octaveShift() != 1 || !ledgerHead(n)) return false;
        }
        return true;
    }

    private static boolean sharpAtSoundingEntry(List<ScoreNoteEvent> notes, int owner, MeasureRegion bar,
            byte[] raw, byte[] normalized, int width, int height, float gap) {
        var n = notes.get(owner);
        int natural = n.diatonicPitchIdentity();
        for (int i = owner - 1; i >= Math.max(1, owner - 3); i--) {
            var earlier = notes.get(i);
            if (earlier.measureIndex() != n.measureIndex() || earlier.octaveShift() != 0 || !ledgerHead(earlier))
                return false;
            if (earlier.diatonicPitchIdentity() != natural + 7) continue;
            var previous = notes.get(i - 1);
            return earlier.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                    && previous.measureIndex() == n.measureIndex()
                    && agreedSign(raw, normalized, width, headX(bar, previous, width),
                            headX(bar, earlier, width), earlier.pageY() * height, gap,
                            ScoreNoteEvent.ACCIDENTAL_SHARP);
        }
        return false;
    }

    private static float headX(MeasureRegion bar, ScoreNoteEvent n, int width) {
        return (bar.left() + (bar.right() - bar.left()) * n.positionInMeasure()) * width;
    }

    static List<SourceLocalAccidental.PrintedMark> retain(
            List<SourceLocalAccidental.PrintedMark> original, ScorePageInterpretation score,
            byte[] labels, byte[] source, byte[] normalized, int width, int height) {
        long area = (long) width * height;
        if (width <= 0 || height <= 0 || labels == null || source == null || normalized == null
                || labels.length != area || source.length != area || normalized.length != area
                || score.notes().size() > 2048) return original;
        original = retainTripletSharps(original, score, source, normalized, width, height);
        var notes = score.notes();
        var marked = new HashSet<Integer>();
        for (var mark : original) marked.add(mark.noteIndex());
        boolean pending = false;
        for (int i = 0; i < notes.size(); i++) {
            if (!marked.contains(i) && eligible(notes.get(i))) {
                pending = true;
                break;
            }
        }
        if (!pending) return original;

        var measures = score.measures();
        var staffs = ScoreDynamicsDetector.alignStaffs(
                OmrScoreInterpreter.techniqueStaffs(labels, normalized, width, height, measures), notes, height);
        if (staffs.isEmpty() || staffs.size() > 128) return original;
        var heads = PrintedSlurContext.geometryHeads(notes, measures, width, height);
        var ordinary = PrintedSlurFrames.prepare(normalized, width, height, staffs, notes, heads);
        var supplemental = PrintedSlurFrames.prepareSupplemental(
                normalized, width, height, staffs, notes, heads, ordinary, measures);
        var frames = new ArrayList<>(ordinary.frames());
        frames.addAll(supplemental.frames());
        var proved = new ArrayList<SourceLocalAccidental.ProvedMark>();
        int remaining = 512;
        for (var frame : frames) {
            var ordered = frame.heads().stream()
                    .sorted(Comparator.comparingDouble(PrintedSlurOwners.Location::x)).toList();
            for (int i = 1; i < ordered.size(); i++) {
                var head = ordered.get(i);
                if (marked.contains(head.owner()) || head.grace()) continue;
                var note = notes.get(head.owner());
                if (!eligible(note) || !frame.track().verifiedAt(head.x())) continue;
                var prior = ordered.get(i - 1);
                var previousNote = notes.get(prior.owner());
                float gap = frame.track().at(head.x())[1];
                float separation = head.x() - prior.x();
                if (previousNote.measureIndex() != note.measureIndex()
                        || prior.staffIndex() != head.staffIndex()
                        || gap < 10 || gap > 24
                        || separation < gap * 2.5f || separation > gap * 4.1f
                        || i + 1 < ordered.size() && ordered.get(i + 1).x() - head.x() < gap * .65f)
                    continue;
                if (--remaining < 0) return original;
                int accidental = note.writtenAccidental();
                var first = AccidentalSlurSpines.prove(
                        source, width, prior.x(), head.x(), head.y(), gap, accidental);
                if (first.size() != 2) continue;
                var second = AccidentalSlurSpines.prove(
                        normalized, width, prior.x(), head.x(), head.y(), gap, accidental);
                if (second.size() != 2 || !sameSpine(first.get(0), second.get(0))
                        || !sameSpine(first.get(1), second.get(1))) continue;
                proved.add(new SourceLocalAccidental.ProvedMark(note, accidental, false));
            }
        }
        return SourceLocalAccidental.includeProvedLocalMarks(original, proved, notes);
    }

    private static List<SourceLocalAccidental.PrintedMark> retainTripletSharps(
            List<SourceLocalAccidental.PrintedMark> known, ScorePageInterpretation score,
            byte[] source, byte[] normalized, int width, int height) {
        var notes = score.notes();
        var marks = new java.util.TreeMap<Integer, SourceLocalAccidental.PrintedMark>();
        for (var mark : known) marks.put(mark.noteIndex(), mark);
        for (var group : score.beamSource().resolve().groups()) {
            var printed = group.source();
            if (printed.tupletMark() != ScoreBeamSource.TupletMark.UNNUMBERED
                    || !printed.bridges().equals(List.of(1, 1)) || !printed.hooks().isEmpty()
                    || group.columns().stream().anyMatch(c -> !c.usable() || c.owners().size() != 1
                            || c.source().members().size() != 1)) continue;
            var owners = group.columns().stream().map(c -> c.owners().get(0)).toList();
            int measure = notes.get(owners.get(0)).measureIndex();
            if (owners.stream().map(notes::get).anyMatch(n -> n.measureIndex() != measure
                    || !eligiblePitched(n) || n.beamCount() != 1 || n.tupletDivisor() != 3
                    || n.tupletNormalNotes() != 2 || n.crossStaffBeam() || n.tiedFromPrevious()
                    || n.augmentationDots() != 0 || (n.articulations() & NoteOrnament.GRACE) != 0)) continue;
            var bar = score.measures().get(measure);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 25) continue;
            float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
            for (int i = 1; i < owners.size(); i++) {
                int owner = owners.get(i);
                var note = notes.get(owner);
                if (marks.containsKey(owner) || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP)
                    continue;
                var previous = notes.get(owners.get(i - 1));
                float spacing = (note.positionInMeasure() - previous.positionInMeasure()) * span;
                if (spacing < gap * 2.5f || spacing > gap * 3.9f) continue;
                float x = left + span * note.positionInMeasure(), y = note.pageY() * height;
                if (OmrScoreInterpreter.printedStoppedHarmonicSharp(source, width, height, x, y, gap)
                        && OmrScoreInterpreter.printedStoppedHarmonicSharp(normalized, width, height, x, y, gap))
                    marks.put(owner, new SourceLocalAccidental.PrintedMark(owner, ScoreNoteEvent.ACCIDENTAL_SHARP));
            }
        }
        return marks.size() == known.size() ? known : List.copyOf(marks.values());
    }

    private static boolean eligiblePitched(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.staffCount() == 1 && note.visualSize() == ScoreNoteEvent.VisualSize.FULL;
    }

    private static boolean eligible(ScoreNoteEvent note) {
        return eligiblePitched(note)
                && (note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_NATURAL
                        || note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP);
    }

    private static boolean sameSpine(AccidentalSlurSpines.Spine a, AccidentalSlurSpines.Spine b) {
        return Math.abs(a.left() - b.left()) <= 2 && Math.abs(a.right() - b.right()) <= 2
                && Math.abs(a.top() - b.top()) <= 2 && Math.abs(a.bottom() - b.bottom()) <= 2;
    }
}
