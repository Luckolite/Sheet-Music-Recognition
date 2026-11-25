// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.util.regex.Pattern;

/** Cross-checks above-staff false heads against independently recognized fingering text. */
final class FingeringAnnotationFilter {
    private static final Pattern SINGLE_DIGIT = Pattern.compile("[1-5]");
    private static final Pattern HAND_OR_DIGIT = Pattern.compile("[LH]?[1-5]");
    private static final Pattern HAND_SEQUENCE = Pattern.compile("[LRH]{2,3}");
    private static final Pattern DIGIT_SEQUENCE = Pattern.compile("[1-5]{2,4}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern OPEN_FINGER = Pattern.compile("\\(\\s*[1-5]");
    private static final Pattern CLOSE_FINGER = Pattern.compile("[1-5]\\s*\\)");
    private static final Pattern SPLIT_FINGERING_RUN =
            Pattern.compile("[1-5](?:\\s+[1-5]){3,7}");
    private static final Pattern SINGLE_SEGUE = Pattern.compile("segue[.]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FINGERS_AND_SEGUE =
            Pattern.compile("(?:[1-5]\\s+){4,8}segue[.]?", Pattern.CASE_INSENSITIVE);

    private static final Pattern SHORT_FINGERING_CAPTION =
            Pattern.compile("[1-5](?:\\s+[1-5]){2}\\s+segue[.]?", Pattern.CASE_INSENSITIVE);

    private static final Pattern COMPACT_SEGUE_CAPTION =
            Pattern.compile("[1-5](?:\\s+[1-5])?\\s+segue[.]?", Pattern.CASE_INSENSITIVE);

    private static final Pattern FINGERING_SEGUE =
            Pattern.compile("[0-5]\\s+segue[.]?", Pattern.CASE_INSENSITIVE);

    private FingeringAnnotationFilter() {}

    private record Candidate(
            float left, float right, float top, float bottom,
            boolean fingering, boolean digit, int sequenceLength) {}

    static List<ScoreNoteEvent> apply(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        return apply(words, staffs, measures, notes, gray, null, width, height);
    }

    static List<ScoreNoteEvent> apply(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            byte[] raw,
            int width,
            int height) {
        if (words == null
                || words.isEmpty()
                || notes.isEmpty()
                || staffs.isEmpty()
                || gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height) return notes;
        notes = removeDescenderAliases(words, staffs, measures, notes, gray, raw, width, height);
        notes = detachedSegueBeamAliases(words, staffs, measures, notes, gray, width, height);
        notes = annotatedSingleLines(words, staffs, measures, notes, gray, width, height);
        notes = mergedFingeringSegueInitial(words, staffs, measures, notes, gray, raw, width, height);
        notes = separatedFingeringCaptions(words, staffs, measures, notes, gray, width, height);
        notes = shortFingeringCaptions(words, staffs, measures, notes, gray, width, height);
        notes = pairedSegueLetters(words, staffs, measures, notes, gray, raw, width, height);
        notes = compactSegueCaptions(words, staffs, measures, notes, gray, raw, width, height);
        notes = overlappingFingeringCaptions(words, staffs, measures, notes, gray, width, height);
        notes =
                loweredSegueWithSplitFingerings(
                        words, staffs, measures, notes, gray, width, height);
        notes = removePairedStaffFingeringAliases(words, staffs, measures, notes, gray, raw, width, height);
        notes = isolatedParentheticalSixteenths(words, staffs, measures, notes, gray, width, height);
        notes = belowStaffNumerals(words, staffs, measures, notes, gray, width, height);
        notes = removeHarmonicFingeringAliases(words, measures, notes, gray, raw, width, height);
        notes = removeStaffRuleFingeringLobes(words, staffs, measures, notes, gray, width, height);
        notes = removeChordFingeringAliases(words, measures, notes, gray, raw, width, height);
        List<Candidate> candidates = null;
        var result = new ArrayList<ScoreNoteEvent>(notes.size());
        for (var note : notes) {
            if (note.measureIndex() < 0
                    || note.measureIndex() >= measures.size()
                    || note.staffStep() < 8 && note.staffStep() > -3
                    || (note.articulations() & NoteOrnament.GRACE) != 0) {
                result.add(note);
                continue;
            }
            if (candidates == null) {
                candidates = candidates(words, width, height);
                if (candidates.isEmpty()) return notes;
            }
            var measure = measures.get(note.measureIndex());
            float
                    x =
                            (measure.left()
                                            + note.positionInMeasure()
                                                    * (measure.right() - measure.left()))
                                    * width,
                    y = note.pageY() * height;
            boolean annotation = false;
            boolean below = note.staffStep() <= -3;
            for (var word : candidates) {
                boolean sequence = below && word.sequenceLength() != 0;
                if (!word.fingering() && !sequence) continue;
                float l = word.left(), r = word.right(), t = word.top(), b = word.bottom();
                if (x < l || x > r || y < t || y > b) continue;
                PlayingTechniqueDetector.Staff owner = null;
                float distance = Float.MAX_VALUE;
                for (var staff : staffs) {
                    if (staff.index() != note.staffIndex()
                            || staff.count() != note.staffCount()
                            || staff.gap() < 5) continue;
                    float dy =
                            below
                                    ? (t - staff.bottom()) / staff.gap()
                                    : (staff.top() - b) / staff.gap();
                    if (dy < (below ? -2.5f : -.4f)
                            || dy > (below ? 6 : 5)
                            || Math.abs(dy) >= distance) continue;
                    owner = staff;
                    distance = Math.abs(dy);
                }
                if (owner == null) continue;
                float gap = owner.gap();
                if (b - t < gap * 1.15f
                        || b - t > gap * (sequence ? 6 : 5.5f)
                        || r - l < gap * .45f
                        || r - l > gap * (sequence ? word.sequenceLength() * 3.5f : 7)) continue;
                if (sequence && (r - l) / word.sequenceLength() > gap * 3.5f) continue;
                if ((below || word.digit())
                        && printedStemBeyondWord(gray, width, height, x, y, t, b, gap)) continue;
                if ((note.staffStep() >= 10 || below)
                        && printedLedgerAcrossWord(
                                gray,
                                width,
                                height,
                                sequence ? x - gap * .75f : l,
                                sequence ? x + gap * .75f : r,
                                y,
                                gap)) continue;
                annotation = true;
                break;
            }
            if (!annotation) result.add(note);
        }
        return result.size() == notes.size() ? notes : List.copyOf(result);
    }

    /** A compact two-counter numeral in a below-staff fingering line can be read as a note. */
    private static List<ScoreNoteEvent> belowStaffNumerals(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        var numerals =
                words.stream().filter(w -> "8".equals(w.text()) || "3".equals(w.text())).toList();
        if (numerals.isEmpty()) return notes;
        var rowTokens = candidates(words, width, height);
        var remove = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var note : notes) {
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.staffCount() != 1
                    || note.staffIndex() != 0
                    || note.staffStep() > -2
                    || note.stemDirection() != 0
                    || note.beamCount() != 0
                    || note.unbeamedDurationBeats() != 1
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.articulations() != 0
                    || note.augmentationDots() != 0
                    || note.tupletDivisor() != 1
                    || note.crossStaffBeam()
                    || note.tiedFromPrevious()
                    || note.boundaryTies() != 0
                    || note.measureIndex() < 0
                    || note.measureIndex() >= measures.size()) continue;
            var measure = measures.get(note.measureIndex());
            float x =
                    (measure.left() + note.positionInMeasure() * (measure.right() - measure.left()))
                            * width;
            float y = note.pageY() * height;
            for (var word : numerals) {
                float l = word.left() * width, r = word.right() * width;
                float t = word.top() * height, b = word.bottom() * height;
                if (x < l || x > r || y < t || y > b) continue;
                for (var staff : staffs) {
                    float gap = staff.gap();
                    if (staff.index() != 0
                            || staff.count() != 1
                            || gap < 8
                            || gap > 32
                            || staff.top() < measure.top() * height
                            || staff.bottom() > measure.bottom() * height
                            || t < staff.bottom() - gap * .1f
                            || b > staff.bottom() + gap * 2.6f
                            || b - t < gap
                            || b - t > gap * 2.1f
                            || r - l > gap * 2.2f
                            || !bracketedByFingerings(
                                    rowTokens, measure, x, (t + b) / 2, gap, width)
                            || printedStemBeyondWord(gray, width, height, x, y, t, b, gap)
                            || printedLedgerAcrossWord(gray, width, height, l, r, y, gap)
                            || !completeTwoCounterNumeral(
                                    gray, width, height, x, y, l, t, r, b, gap)) continue;
                    remove.add(note);
                    break;
                }
                if (remove.contains(note)) break;
            }
        }
        return remove.isEmpty() ? notes : notes.stream().filter(n -> !remove.contains(n)).toList();
    }

    /** A closed 3 or 8 above a separate open-string 0 can be a stemless eighth alias. */
    private static List<ScoreNoteEvent> removeHarmonicFingeringAliases(
            List<PlayingTechniqueDetector.Word> words, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] gray, byte[] raw, int width, int height) {
        if (raw == null || raw.length != gray.length) return notes;
        var rings = words.stream().filter(word -> "0".equals(word.text().strip())).toList();
        if (rings.isEmpty()) return notes;
        var digits = words.stream().filter(word -> {
            String value = word.text().strip();
            return "3".equals(value) || "8".equals(value);
        }).toList();
        if (digits.isEmpty()) return notes;
        var kept = new ArrayList<ScoreNoteEvent>();
        for (var note : notes) {
            if (!stemlessFingeringEighth(note, measures.size())
                    || !ownedHarmonicFingering(note, notes, measures.get(note.measureIndex()),
                            digits, rings, gray, raw, width, height))
                kept.add(note);
        }
        return kept.size() == notes.size() ? notes : List.copyOf(kept);
    }

    private static boolean stemlessFingeringEighth(ScoreNoteEvent n, int bars) {
        return n.measureIndex() >= 0 && n.measureIndex() < bars
                && n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.staffStep() <= -2 && n.staffStep() >= -4 && n.stemDirection() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.beamCount() == 1
                && n.unbeamedDurationBeats() == 0 && n.augmentationDots() == 0
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1 && n.articulations() == 0
                && !n.crossStaffBeam() && !n.tiedFromPrevious() && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0 && n.octaveShift() == 0
                && n.harmonicSemitones() == 0 && n.openStringTouch() == null;
    }

    private static boolean ownedHarmonicFingering(ScoreNoteEvent alias, List<ScoreNoteEvent> notes,
            MeasureRegion bar, List<PlayingTechniqueDetector.Word> digits,
            List<PlayingTechniqueDetector.Word> rings, byte[] gray, byte[] raw, int width, int height) {
        float x = fingeringHeadX(bar, alias, width), y = alias.pageY() * height;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 12 || gap > 23) return false;
        var owners = notes.stream().filter(n -> n.measureIndex() == alias.measureIndex()
                && n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1 && n.staffIndex() == 0
                && n.stemDirection() == -1 && n.beamCount() == 0 && n.unbeamedDurationBeats() == 1
                && Math.abs(fingeringHeadX(bar, n, width) - x) < gap * .26f
                && y - n.pageY() * height >= gap * 4.5f && y - n.pageY() * height <= gap * 7.2f).toList();
        if (owners.size() != 2 || Math.abs(owners.get(0).pageY() - owners.get(1).pageY()) * height < gap * .65f
                || Math.abs(owners.get(0).pageY() - owners.get(1).pageY()) * height > gap * 1.20f) return false;
        for (var digit : digits) {
            float l = digit.left() * width, r = digit.right() * width;
            float t = digit.top() * height, b = digit.bottom() * height;
            if (x <= l || x >= r || y <= t || y >= b || r - l > gap * 2.1f
                    || b - t < gap * 1.2f || b - t > gap * 2.2f) continue;
            for (var ring : rings) {
                float rl = ring.left() * width, rr = ring.right() * width;
                float rt = ring.top() * height, rb = ring.bottom() * height;
                if (Math.abs((rl + rr) * .5f - x) > gap * .24f || rt - y < gap * .19f
                        || rt - y > gap * .68f || b - rt > gap * .70f
                        || rb - rt < gap || rb - rt > gap * 2f || rr - rl > gap * 1.9f) continue;
                if (detachedHarmonicDigits(gray, width, height, bar, owners, x, y, gap, l, r, t, rl, rr, rt, rb)
                        && detachedHarmonicDigits(raw, width, height, bar, owners, x, y, gap, l, r, t, rl, rr, rt, rb))
                    return true;
            }
        }
        return false;
    }

    private static boolean detachedHarmonicDigits(byte[] plane, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> owners, float x, float y, float nominal,
            float left, float right, float top, float ringLeft, float ringRight, float split, float bottom) {
        var staff = PrintedDirectionStaff.printedRules(
                plane, width, height, x, bar.bottom() * height - nominal * 2.1f, nominal);
        if (staff == null) return false;
        float gap = staff[1];
        float floor = staff[0];
        if (y - floor < gap * 1.05f || y - floor > gap * 1.65f
                || top < floor - gap * .18f || top > floor + gap * .45f) return false;
        float detachedTop = Math.max(top, floor + gap * .20f);
        if (!completeTwoCounterNumeral(plane, width, height, x, y, left, detachedTop, right, split, gap)
                || printedLedgerAcrossWord(plane, width, height, left, right, y, gap)) return false;
        var ring = completeNumeralInk(plane, width, height, ringLeft, split, ringRight, bottom, gap);
        if (ring == null) return false;
        int w = ring.right - ring.left + 1, h = ring.bottom - ring.top + 1;
        if (h < gap * .95f || h > gap * 1.48f || w < h * .60f || w > h * .90f
                || Math.abs((ring.left + ring.right) * .5f - x) > gap * .23f
                || OctaveClefDigit.holes(plane, width, ring.left, ring.top, w, h) != 1) return false;
        var budget = new PrintedBeamShafts.Budget(125_000);
        var first = PrintedBeamShafts.at(plane, width, height, fingeringHeadX(bar, owners.get(0), width),
                owners.get(0).pageY() * height, gap, budget);
        var second = PrintedBeamShafts.at(plane, width, height, fingeringHeadX(bar, owners.get(1), width),
                owners.get(1).pageY() * height, gap, budget);
        if (first.size() != 1 || second.size() != 1) return false;
        var a = first.get(0);
        var b = second.get(0);
        return a.direction() == 1 && b.direction() == 1
                && Math.abs(a.left() - b.left()) <= 2 && Math.abs(a.endY() - b.endY()) <= 2
                && a.endY() < detachedTop - gap * .52f
                && PrintedBeamShafts.at(plane, width, height, x, y, gap, budget).isEmpty()
                && !budget.refused();
    }

    /** A curled fingering 3 crosses the bottom rule and its lower bowl can be labeled a whole head. */
    static List<ScoreNoteEvent> removeWholeFingeringAliases(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] normalized,
            byte[] raw,
            int width,
            int height) {
        if (raw == null
                || normalized == null
                || raw.length != (long) width * height
                || normalized.length != raw.length
                || staffs.isEmpty()) return notes;
        notes = removeRuleCrossingFingeringTwos(words, measures, notes, normalized, raw, width, height);
        notes = removeStemlessFingeringThrees(words, measures, notes, normalized, raw, width, height);
        notes = removeTerminalFingeringThree(words, measures, notes, normalized, raw, width, height);
        notes = removeStackedBeamFingerings(measures, notes, normalized, raw, width, height);
        notes = removeSmallParentheticalFingeringThrees(measures, notes, normalized, raw, width, height);
        var digits = words.stream().filter(word -> "3".equals(word.text()) || fingerThreeRun(word.text())).toList();
        if (digits.isEmpty()) return notes;
        var remove = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var note : notes) {
            int index = note.measureIndex();
            if (index < 0
                    || index >= measures.size()
                    || !captionGlyphHead(note, false, -1, -1)
                    || note.beamCount() != 0
                    || note.unbeamedDurationBeats() != 4) continue;
            var measure = measures.get(index);
            float x = fingeringHeadX(measure, note, width), y = note.pageY() * height;
            for (var staff : staffs) {
                float gap = staff.gap(), bottom = staff.bottom();
                if (staff.index() != 0
                        || staff.count() != 1
                        || gap < 9
                        || gap > 26
                        || staff.top() < measure.top() * height
                        || bottom > measure.bottom() * height
                        || y - bottom < gap * .22f
                        || y - bottom > gap * .92f) continue;
                for (var digit : digits) {
                    float l = digit.left() * width, r = digit.right() * width;
                    float t = digit.top() * height, b = digit.bottom() * height;
                    if ("3".equals(digit.text()) && y - bottom < gap * .40f) continue;
                    if (!"3".equals(digit.text())) {
                        if (wholeAliasInFingeringRun(digit, staff, measure, note, notes, normalized, raw,
                                width, height, x, y)) {
                            remove.add(note);
                            break;
                        }
                        continue;
                    }
                    if (x <= l
                            || x >= r
                            || y <= t
                            || y >= b
                            || Math.abs((l + r) * .5f - x) > gap * .25f
                            || r - l < gap * 1.15f
                            || r - l > gap * 2.1f
                            || bottom - t < gap * .36f
                            || bottom - t > gap * .85f
                            || b - bottom < gap * .95f
                            || b - bottom > gap * 1.65f) continue;
                    if (printedStemBeyondWord(raw, width, height, x, y, t, b, gap)
                            || printedStemBeyondWord(normalized, width, height, x, y, t, b, gap)
                            || printedLedgerAcrossWord(raw, width, height, l, r, y, gap)
                            || printedLedgerAcrossWord(normalized, width, height, l, r, y, gap)
                            || !hasFingerThreeBowl(raw, width, height, x, bottom, gap)
                            || !hasFingerThreeBowl(normalized, width, height, x, bottom, gap)
                            || !hasFingeredBeamOwner(
                                    normalized,
                                    raw,
                                    width,
                                    height,
                                    measure,
                                    note,
                                    notes,
                                    x,
                                    y,
                                    gap)) continue;
                    remove.add(note);
                    break;
                }
                if (remove.contains(note)) break;
            }
        }
        return remove.isEmpty() ? notes : notes.stream().filter(n -> !remove.contains(n)).toList();
    }

    private static List<ScoreNoteEvent> removeStackedBeamFingerings(
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes,
            byte[] normalized, byte[] raw, int width, int height) {
        var rejected = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var alias : notes) {
            int measure = alias.measureIndex();
            if (measure < 0 || measure >= measures.size()
                    || !captionGlyphHead(alias, false, -1, -1)
                    || alias.beamCount() != 0 || alias.unbeamedDurationBeats() != 4) continue;
            var bar = measures.get(measure);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = fingeringHeadX(bar, alias, width), y = alias.pageY() * height;
            float hint = bar.bottom() * height - 2.1f * nominal;
            if (nominal < 10 || nominal > 24 || Math.abs(y - hint - nominal * .5f) > nominal * .2f)
                continue;
            var a = stackedFingeringThree(raw, width, height, x, y, hint, nominal);
            if (a == null) continue;
            var b = stackedFingeringThree(normalized, width, height, x, y, hint, nominal);
            if (b == null || Math.abs(a[0] - b[0]) > .12f * nominal
                    || !hasFingeredBeamOwner(normalized, raw, width, height, bar, alias, notes, x, y,
                            a[1], .55f)) continue;
            rejected.add(alias);
        }
        return rejected.isEmpty() ? notes : notes.stream().filter(n -> !rejected.contains(n)).toList();
    }

    private static float[] stackedFingeringThree(
            byte[] plane, int width, int height, float x, float y, float hint, float nominal) {
        var left = PrintedDirectionStaff.printedRules(plane, width, height, x - 4 * nominal, hint, nominal);
        var right = PrintedDirectionStaff.printedRules(plane, width, height, x + 4 * nominal, hint, nominal);
        if (left == null || right == null || Math.abs(left[0] - right[0]) > .13f * nominal
                || Math.abs(left[1] - right[1]) > .06f * nominal) return null;
        float floor = (left[0] + right[0]) * .5f;
        float gap = (left[1] + right[1]) * .5f;
        if (y - floor < .36f * gap || y - floor > .66f * gap) return null;
        int reach = Math.round(.8f * gap);
        int xl = Math.round(x) - reach, xr = Math.round(x) + reach;
        int top = Math.round(floor + .13f * gap), bottom = Math.round(floor + 2.22f * gap);
        var parts = RawNumeralComponents.capture(plane, width, height, xl, top, xr, bottom,
                165, () -> {}, () -> {}).components().stream()
                .filter(c -> c.size() >= .14f * gap * gap).toList();
        if (parts.size() != 2) return null;
        var a = parts.get(0);
        var b = parts.get(1);
        var upper = a.top < b.top ? a : b;
        var lower = upper == a ? b : a;
        if (upper.left <= xl || upper.right >= xr || lower.left <= xl || lower.right >= xr
                || x < upper.left || x > upper.right || y < upper.top || y > upper.bottom
                || upper.right - upper.left < .63f * gap || upper.right - upper.left > .94f * gap
                || lower.right - lower.left < .39f * gap || lower.right - lower.left > .68f * gap
                || lower.bottom - lower.top < .83f * gap || lower.bottom - lower.top > 1.10f * gap
                || lower.top - upper.bottom < .30f * gap || lower.top - upper.bottom > .55f * gap
                || Math.abs(upper.left + upper.right - lower.left - lower.right) > .3f * gap
                || printedLedgerAcrossWord(plane, width, height, xl, xr, y, gap,
                        Math.round(floor + .13f * gap))) return null;
        for (int dx = -1; dx <= 1; dx++) {
            if (hasFingerThreeBowl(plane, width, height, x + dx, floor, gap, 165))
                return new float[] {floor, gap};
        }
        return null;
    }

    private static List<ScoreNoteEvent> removeSmallParentheticalFingeringThrees(
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, byte[] raw,
            int width, int height) {
        var rejected = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var alias : notes) {
            int i = alias.measureIndex();
            if (i < 1 || i >= measures.size() || !captionGlyphHead(alias, false, -1, -1)
                    || !(alias.beamCount() == 0 && alias.unbeamedDurationBeats() == 4
                            || alias.beamCount() == 1 && alias.unbeamedDurationBeats() == 0)) continue;
            var bar = measures.get(i);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            float x = fingeringHeadX(bar, alias, width), y = alias.pageY() * height;
            if (gap < 10 || gap > 24 || (bar.top() - measures.get(i - 1).bottom()) * height < 3 * gap
                    || alias.positionInMeasure() < .3f || alias.positionInMeasure() > .5f
                    || Math.abs(y - bar.bottom() * height + 1.6f * gap) > .3f * gap) continue;
            var music = notes.stream().filter(n -> n.measureIndex() == i && n != alias)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (music.size() != 16 || Math.abs(fingeringHeadX(bar, music.get(5), width) - x) > .6f * gap)
                continue;
            if (alias.beamCount() == 1) {
                if (ruledSmallFingerPair(raw, width, height, bar, music, x, y, gap)
                        && ruledSmallFingerPair(gray, width, height, bar, music, x, y, gap))
                    rejected.add(alias);
            } else if (smallFingerPair(raw, width, height, bar, music, x, y, gap)
                    && smallFingerPair(gray, width, height, bar, music, x, y, gap)) {
                rejected.add(alias);
            }
        }
        return rejected.isEmpty() ? notes : notes.stream().filter(n -> !rejected.contains(n)).toList();
    }

    private static boolean ruledSmallFingerPair(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, float x, float y, float gap) {
        float close = fingeringHeadX(bar, notes.get(11), width);
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar, true);
        if (track == null || !track.verifiedAt(x) || !track.verifiedAt(close)) return false;
        float floor = track.at(x)[0], closeFloor = track.at(close)[0];
        if (y - floor < gap * .25f || y - floor > gap * .75f || close - x < gap * 7f
                || close - x > gap * 15f) return false;
        var budget = new PrintedBeamShafts.Budget(60_000);
        if (!PrintedBeamShafts.at(ink, width, height, x, y, gap, budget).isEmpty() || budget.refused()) return false;
        int[] a = ruledFingerDigit(ink, width, height, x, floor, gap, true);
        int[] b = ruledFingerDigit(ink, width, height, close, closeFloor, gap, false);
        if (a == null || b == null || y < a[0] || y > a[1]
                || Math.abs((a[1] - a[0]) - (b[1] - b[0])) > .25f * gap
                || !opposingFingerCurve(ink, width, height, x, floor, gap, true)
                || !opposingFingerCurve(ink, width, height, close, closeFloor, gap, false)) return false;
        return WrappedMeasureBoundaries.confirmsSampledClosingEdge(
                        ink, width, height, bar, fingeringHeadX(bar, notes.get(15), width))
                && (MixedUpperLedgerBeamSource.provesUpperSixteenthLine(ink, width, height, track, bar, notes)
                    || ruledCaptionQuartets(ink, width, height, track, bar, notes, gap));
    }

    private static boolean ruledCaptionQuartets(byte[] ink, int width, int height,
            StaffPitchTrack track, MeasureRegion bar, List<ScoreNoteEvent> notes, float gap) {
        var budget = new PrintedBeamShafts.Budget(155_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (!ClosedMonophonicSixteenths.regularQuartetHead(note)
                    || note.stemDirection() != -1 || note.tupletDivisor() != 1
                    || note.augmentationDots() != 0) return false;
            float x = fingeringHeadX(bar, note, width), y = note.pageY() * height;
            if (previous != null && (x - previousX < 1.55f * gap || x - previousX > 3.95f * gap))
                return false;
            var shafts = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (budget.refused() || shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
            var shaft = shafts.get(0);
            if (previous != null) {
                boolean edge = i % 4 == 0
                        ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, width, height, bar, previous, shaft, gap)
                            || WrappedEighthTriplets.separatedOctaveQuartets(
                                     ink, width, height, previous, shaft, gap, track.at(x)[0])
                        : WrappedEighthTriplets.matchesExactlyTwoBeamRails(ink, width, height, previous, shaft, gap, track);
                if (!edge) return false;
            }
            previous = shaft;
            previousX = x;
        }
        return true;
    }

    private static int[] ruledFingerDigit(byte[] ink, int width, int height,
            float x, float floor, float gap, boolean opening) {
        int left = Math.round(x - (opening ? .49f : .68f) * gap);
        int right = Math.round(x + (opening ? .56f : .20f) * gap);
        int top = Math.round(floor - .55f * gap), bottom = Math.round(floor + gap);
        return staffRuleThree(ink, width, height, left, top, right, bottom, x, floor + .45f * gap, gap);
    }

    private static boolean opposingFingerCurve(byte[] ink, int width, int height,
            float x, float floor, float gap, boolean opening) {
        int left = Math.round(x + (opening ? -1.35f : .30f) * gap);
        int right = Math.round(x + (opening ? -.37f : 1.2f) * gap);
        int first = Math.round(floor - gap), last = Math.round(floor + 1.2f * gap);
        int center = Math.round(floor);
        int clear = Math.round(gap * .22f);
        if (left < 0 || right >= width || first < 0 || last >= height) return false;
        for (int side : new int[] {-1, 1}) {
            var columns = new ArrayList<Float>();
            for (int row = center + side * clear; row >= first && row <= last; row += side) {
                int body = 0, sum = 0;
                for (int col = left; col <= right; col++) {
                    if ((ink[row * width + col] & 255) >= 165) continue;
                    body++;
                    sum += col;
                }
                if (body > 0 && body <= gap * .48f) columns.add(sum / (float)body);
                else if (!columns.isEmpty()) break;
            }
            if (columns.size() < 5) return false;
            int n = columns.size();
            float near = (columns.get(0) + columns.get(1)) * .5f;
            float far = (columns.get(n - 1) + columns.get(n - 2)) * .5f;
            if ((opening ? 1 : -1) * (far - near) < gap * .08f) return false;
        }
        return true;
    }

    private static boolean smallFingerPair(
            byte[] plane, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> notes,
            float x, float y, float gap) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(plane, width, height, bar);
        float close = fingeringHeadX(bar, notes.get(11), width);
        if (track == null || !track.verifiedAt(x) || !track.verifiedAt(close)) return false;
        float floor = track.at(x)[0], lastFloor = track.at(close)[0];
        if (y - floor < .25f * gap || y - floor > .72f * gap) return false;
        var a = smallFingerGlyph(plane, width, height, x, floor, gap, true);
        var b = smallFingerGlyph(plane, width, height, close, lastFloor, gap, false);
        if (a == null || b == null || !ownsTextCenter(a, x, y, width)
                || !sameDetachedDigit(plane, width, a, b)
                || printedLedgerAcrossWord(plane, width, height, x - 1.3f * gap, x + 1.3f * gap, y, gap,
                        Math.round(floor + .15f * gap))) return false;
        var budget = new PrintedBeamShafts.Budget(60_000);
        if (!PrintedBeamShafts.at(plane, width, height, x, y, gap, budget).isEmpty() || budget.refused())
            return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        if (!WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                plane, width, height, bar, fingeringHeadX(bar, notes.get(15), width))) return false;
        var staff = new PlayingTechniqueDetector.Staff(floor - 4 * gap, floor, gap, 0, 1);
        // The measured closing digit supplies only the beam's horizontal ownership check.
        var closing = new PlayingTechniqueDetector.Word("3)", (float)b.left / width, (float)b.top / height,
                (float)b.right / width, (float)b.bottom / height);
        return !printedSixteenthLine(notes, closing, left, span, staff, plane, width, height, true, 11, true).isEmpty();
    }

    private static RawNumeralComponents.Component smallFingerGlyph(
            byte[] plane, int width, int height, float x, float floor, float gap, boolean opens) {
        int xl = Math.round(x - 1.22f * gap), xr = Math.round(x + 1.22f * gap);
        int top = Math.round(floor + .11f * gap), bottom = Math.round(floor + 1.85f * gap);
        var parts = RawNumeralComponents.capture(plane, width, height, xl, top, xr, bottom,
                165, () -> {}, () -> {}).components().stream()
                .filter(c -> c.size() > .15f * gap * gap).toList();
        if (parts.size() != 2) return null;
        var a = parts.get(0);
        var b = parts.get(1);
        var curve = (a.left < b.left) == opens ? a : b;
        var digit = curve == a ? b : a;
        float separation = opens ? digit.left - curve.right : curve.left - digit.right;
        if (digit.left <= xl || digit.right >= xr || curve.left <= xl || curve.right >= xr
                || digit.bottom >= bottom || curve.bottom >= bottom
                || separation < .09f * gap || separation > .36f * gap
                || digit.right - digit.left < .55f * gap || digit.right - digit.left > .90f * gap
                || digit.bottom - digit.top < .88f * gap || digit.bottom - digit.top > 1.25f * gap
                || curve.right - curve.left < .19f * gap || curve.right - curve.left > .43f * gap
                || curve.bottom - curve.top < .91f * gap || curve.bottom - curve.top > 1.30f * gap)
            return null;
        int ends = 0, middle = 0, endCount = 0, middleCount = 0;
        float tall = curve.bottom - curve.top + 1;
        for (int i = 0; i < curve.size(); i++) {
            int px = curve.pixel(i);
            float y = (px / width - curve.top) / tall;
            if (y < .2f || y > .8f) {
                ends += px % width;
                endCount++;
            } else if (y > .33f && y < .66f) {
                middle += px % width;
                middleCount++;
            }
        }
        return endCount > 0 && middleCount > 0 && (opens ? 1 : -1)
                * (ends / (float)endCount - middle / (float)middleCount) >= .08f * gap ? digit : null;
    }

    /** A staff-crossing alternate finger can be decoded as a stemless short note. */
    static List<ScoreNoteEvent> removeTerminalFingeringThree(
            List<PlayingTechniqueDetector.Word> words, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] gray, byte[] raw, int width, int height) {
        var threes = words.stream().filter(word -> "3".equals(word.text().strip())).toList();
        if (threes.size() < 2) return notes;
        var removed = new HashSet<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            var alias = notes.get(i);
            int bar = alias.measureIndex();
            if (bar < 0 || bar >= measures.size() || alias.staffStep() != -1
                    || !captionGlyphHead(alias, false, -1, -1) || alias.beamCount() != 3
                    || alias.unbeamedDurationBeats() != 0 || alias.articulations() != 0) continue;
            var region = measures.get(bar);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float x = fingeringHeadX(region, alias, width), y = alias.pageY() * height;
            if (gap < 10 || gap > 24 || (region.right() * width - x) > 2.4f * gap) continue;
            var candidates = threes.stream().filter(word -> x >= word.left() * width && x <= word.right() * width
                    && y >= word.top() * height && y <= word.bottom() * height).toList();
            if (candidates.size() != 1) continue;
            var owners = notes.stream().filter(note -> note.measureIndex() == bar && note != alias)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (owners.size() != 12 || owners.stream().anyMatch(n -> n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.staffIndex() != 0 || n.staffCount() != 1 || n.stemDirection() != -1
                    || n.beamCount() != 2 || n.tupletDivisor() != 1 || n.augmentationDots() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.crossStaffBeam())) continue;
            var winner = owners.get(owners.size() - 1);
            float headX = fingeringHeadX(region, winner, width), headY = winner.pageY() * height;
            if (headX - x < .4f * gap || headX - x > .94f * gap
                    || y - headY < 6f * gap || y - headY > 10.2f * gap) continue;
            var upper = threes.stream().filter(word ->
                    Math.abs((word.left() + word.right()) * width * .5f - headX) < .4f * gap
                    && headY - word.bottom() * height > .55f * gap
                    && headY - word.bottom() * height < 1.25f * gap).toList();
            if (upper.size() == 1 && terminalFingerThreeInk(gray, width, height, region, owners,
                    x, y, gap, candidates.get(0), upper.get(0))
                    && terminalFingerThreeInk(raw, width, height, region, owners,
                            x, y, gap, candidates.get(0), upper.get(0))) removed.add(i);
        }
        if (removed.isEmpty()) return notes;
        var result = new ArrayList<ScoreNoteEvent>(notes.size() - removed.size());
        for (int i = 0; i < notes.size(); i++) if (!removed.contains(i)) result.add(notes.get(i));
        return List.copyOf(result);
    }

    private static boolean terminalFingerThreeInk(
            byte[] ink, int width, int height, MeasureRegion bar, List<ScoreNoteEvent> members,
            float x, float y, float gap, PlayingTechniqueDetector.Word lower,
            PlayingTechniqueDetector.Word upper) {
        if (!WrappedMeasureBoundaries.confirmsClosedTripletBar(ink, width, height, bar,
                fingeringHeadX(bar, members.get(0), width), fingeringHeadX(bar, members.get(members.size() - 1), width))) return false;
        var budget = new PrintedBeamShafts.Budget(180_000);
        PrintedBeamShafts.Shaft previous = null;
        for (var member : members.subList(8, 12)) {
            var read = PrintedBeamShafts.at(ink, width, height,
                    fingeringHeadX(bar, member, width), member.pageY() * height, gap, budget);
            if (read.size() != 1 || read.get(0).direction() != 1 || budget.refused()
                    || previous != null && !WrappedEighthTriplets.matchesBeamRails(
                            ink, width, height, previous, read.get(0), gap, 2)) return false;
            previous = read.get(0);
        }
        float shaft = (previous.left() + previous.right()) * .5f;
        if (Math.abs(shaft - x) > .23f * gap || y - previous.endY() < 1.65f * gap
                || !PrintedBeamShafts.at(ink, width, height, x, y, gap, budget).isEmpty()
                || budget.refused()) return false;
        var above = completeNumeralInk(ink, width, height, upper.left() * width, upper.top() * height,
                upper.right() * width, upper.bottom() * height, gap);
        var staff = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar);
        if (above == null || staff == null) return false;
        int l = Math.round(lower.left() * width), r = Math.round(lower.right() * width);
        int t = Math.round(lower.top() * height), b = Math.round(lower.bottom() * height);
        int floor = Math.round(staff.at(x)[0]), centre = Math.round(x);
        int outer = Math.round(gap * 1.55f), inner = Math.round(gap * .91f);
        if (centre - outer < 0 || centre + outer >= width || l < 0 || r >= width || b >= height
                || r - l < 1.15f * gap || r - l > 1.65f * gap
                || floor - t < .43f * gap || b - floor < .68f * gap) return false;
        int edge = floor + Math.round(.22f * gap), ruleEnd = -1;
        for (int row = floor - Math.round(.34f * gap); row <= edge; row++) {
            if (row < 0 || row >= height) return false;
            int dark = 0;
            for (int offset = inner; offset <= outer; offset++) {
                if ((ink[row * width + centre - offset] & 255) < 155) dark++;
                if ((ink[row * width + centre + offset] & 255) < 155) dark++;
            }
            if (dark >= (outer - inner + 1) * 1.84f) ruleEnd = row;
        }
        if (ruleEnd < 0 || y <= ruleEnd || ruleEnd + 1 >= b
                || b - ruleEnd > 1.4f * gap) return false;
        var glyphs = RawNumeralComponents.capture(ink, width, height, l, ruleEnd + 1, r, b,
                165, () -> {}, () -> {}).components().stream().filter(g -> g.size() > .10f * gap * gap).toList();
        if (glyphs.size() != 1) return false;
        var lowerBowl = glyphs.get(0);
        return lowerBowl.left > l && lowerBowl.right < r && lowerBowl.bottom < b
                && lowerBowl.top == ruleEnd + 1 && lowerBowl.left <= x && x <= lowerBowl.right
                && y <= lowerBowl.bottom && lowerBowl.bottom - lowerBowl.top >= .51f * gap
                && Math.abs(above.right - above.left - (lowerBowl.right - lowerBowl.left)) <= .20f * gap;
    }


    /** A split numeral near another fingering cannot also supply a stemless sixteenth. */
    private static List<ScoreNoteEvent> removeStemlessFingeringThrees(
            List<PlayingTechniqueDetector.Word> words, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] gray, byte[] raw, int width, int height) {
        var threes = words.stream().filter(word -> "3".equals(word.text().strip())).toList();
        if (threes.isEmpty()) return notes;
        var hints = words.stream().filter(word -> "4".equals(word.text().strip())).toList();
        if (hints.isEmpty()) return notes;
        List<ScoreNoteEvent> output = null;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            int m = note.measureIndex();
            if (m < 0 || m >= measures.size() || !captionGlyphHead(note, false, -1, -1)
                    || note.beamCount() != 2 || note.unbeamedDurationBeats() != 0
                    || note.articulations() != NoteArticulation.STACCATO) continue;
            var bar = measures.get(m);
            if (!stemlessFingeringThree(threes, hints, bar, note, notes, gray, raw, width, height)) continue;
            if (output == null) output = new ArrayList<>(notes);
            output.set(i, null);
        }
        return output == null ? notes : output.stream().filter(Objects::nonNull).toList();
    }

    private static boolean stemlessFingeringThree(
            List<PlayingTechniqueDetector.Word> threes, List<PlayingTechniqueDetector.Word> hints,
            MeasureRegion bar, ScoreNoteEvent note, List<ScoreNoteEvent> notes,
            byte[] gray, byte[] raw, int width, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        float x = fingeringHeadX(bar, note, width), y = note.pageY() * height;
        float bottom = bar.bottom() * height - gap * 2.1f;
        boolean companion = hints.stream().anyMatch(word -> {
            float center = (word.left() + word.right()) * width * .5f;
            float d = x - center;
            return center > bar.left() * width && d >= gap * 7 && d <= gap * 9.5f
                    && Math.abs((word.top() + word.bottom()) * height * .5f - y) <= gap * .57f;
        });
        if (!companion || !hasFingeredBeamOwner(gray, raw, width, height, bar, note, notes, x, y, gap, .55f))
            return false;
        var a = isolatedFingeringThree(gray, width, height, threes, x, y, bottom, gap, false);
        var b = isolatedFingeringThree(raw, width, height, threes, x, y, bottom, gap, false);
        return a != null && b != null && a.left == b.left && a.right == b.right
                && a.top == b.top && a.bottom == b.bottom;
    }

    private static List<ScoreNoteEvent> removeRuleCrossingFingeringTwos(
            List<PlayingTechniqueDetector.Word> words, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] normalized, byte[] raw, int width, int height) {
        var twos = words.stream().filter(word -> "2".equals(word.text())).toList();
        if (twos.isEmpty()) return notes;
        var remove = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var alias : notes) {
            int index = alias.measureIndex();
            if (index < 0 || index >= measures.size()
                    || !captionGlyphHead(alias, false, 3, 3) || alias.beamCount() != 0
                    || alias.unbeamedDurationBeats() != 4 || alias.articulations() != 0) continue;
            var bar = measures.get(index);
            float x = fingeringHeadX(bar, alias, width), y = alias.pageY() * height;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            for (var word : twos) {
                float l = word.left() * width, t = word.top() * height;
                float r = word.right() * width, b = word.bottom() * height;
                if (x <= l || x >= r || y <= t || y >= b
                        || Math.abs(x - (l + r) * .5f) > .30f * gap
                        || y - (t + b) * .5f < .12f * gap
                        || y - (t + b) * .5f > .36f * gap) continue;
                if (PairedTremoloInk.stem(raw, width, height, x, y, gap, -1) != null
                        || PairedTremoloInk.stem(raw, width, height, x, y, gap, 1) != null
                        || PairedTremoloInk.stem(normalized, width, height, x, y, gap, -1) != null
                        || PairedTremoloInk.stem(normalized, width, height, x, y, gap, 1) != null) continue;
                boolean owner = false;
                for (var note : notes) {
                    if (note.measureIndex() != index || !BeamedStaffFingeringTwo.owns(
                            note, bar, normalized, raw, width, height, l, t, r, b)) continue;
                    if (owner) {
                        owner = false;
                        break;
                    }
                    owner = true;
                }
                if (owner) {
                    remove.add(alias);
                    break;
                }
            }
        }
        return remove.isEmpty() ? notes : notes.stream().filter(n -> !remove.contains(n)).toList();
    }

    private static boolean fingerThreeRun(String text) {
        String compact = WHITESPACE.matcher(text).replaceAll("");
        return compact.length() >= 2 && compact.length() <= 12
                && compact.indexOf('3') >= 0 && compact.chars().allMatch(c -> c >= '1' && c <= '4');
    }

    private static boolean wholeAliasInFingeringRun(PlayingTechniqueDetector.Word run,
            PlayingTechniqueDetector.Staff staff, MeasureRegion bar, ScoreNoteEvent alias,
            List<ScoreNoteEvent> notes, byte[] normalized, byte[] raw, int width, int height, float x, float y) {
        float gap = staff.gap(), bottom = staff.bottom();
        float top = run.top() * height, end = run.bottom() * height;
        float left = run.left() * width, right = run.right() * width;
        int belowRule = (int) Math.ceil(bottom + gap * .13f);
        if (x < left + gap * .23f || x > right - gap * .23f
                || y <= top || y >= end || bottom - top < gap * .40f || bottom - top > gap * 1.55f
                || end - bottom < gap * .65f || end - bottom > gap * 1.6f
                || printedStemBeyondWord(raw, width, height, x, y, top, end, gap)
                || printedStemBeyondWord(normalized, width, height, x, y, top, end, gap)
                || printedLedgerAcrossWord(raw, width, height, x - gap * .70f, x + gap * .70f, y, gap, belowRule)
                || printedLedgerAcrossWord(normalized, width, height, x - gap * .70f, x + gap * .70f, y, gap, belowRule)
                || !hasFingeredBeamOwner(normalized, raw, width, height, bar, alias, notes, x, y, gap, .67f))
            return false;
        for (int dx = -2; dx <= 2; dx++) {
            if (hasFingerThreeBowl(raw, width, height, x + dx, bottom - 1, gap, 165)
                    && hasFingerThreeBowl(normalized, width, height, x + dx, bottom - 1, gap, 165))
                return true;
        }
        return false;
    }

    private static boolean hasFingerThreeBowl(
            byte[] plane, int width, int height, float x, float bottom, float gap) {
        return hasFingerThreeBowl(plane,width,height,x,bottom,gap,180);
    }

    private static boolean hasFingerThreeBowl(
            byte[] plane, int width, int height, float x, float bottom, float gap, int recessThreshold) {
        int cx = Math.round(x), cy = Math.round(bottom);
        int outside = Math.round(gap * .63f),
                left = Math.round(gap * .20f),
                spine = Math.round(gap * .30f),
                inset = Math.max(1, Math.round(gap * .075f));
        if (cx < outside + 1
                || cx + outside + 1 >= width
                || cy - gap < 0
                || cy + gap * 1.15f >= height) return false;
        int open = 0, pocket = 0, joined = 0;
        for (int row = cy + Math.round(gap * .17f); row <= cy + Math.round(gap * .93f); row++) {
            int stride = row * width;
            if ((plane[stride + cx - outside] & 255) < 155
                    || (plane[stride + cx + outside] & 255) < 155) return false;
            boolean rightInk = (plane[stride + cx + spine] & 255) < 155;
            boolean leftInk = (plane[stride + cx - left] & 255) < 155;
            boolean recess = (plane[stride + cx + inset] & 255) >= recessThreshold;
            if (!rightInk) continue;
            if (recess && !leftInk && pocket == 0) open++;
            else if (recess && leftInk && open > 0 && joined == 0) pocket++;
            else if (!recess && pocket >= 2) joined++;
        }
        return open >= 1 && pocket >= 2 && joined >= 1;
    }

    private static boolean hasFingeredBeamOwner(
            byte[] normalized,
            byte[] raw,
            int width,
            int height,
            MeasureRegion measure,
            ScoreNoteEvent alias,
            List<ScoreNoteEvent> notes,
            float x,
            float y,
            float gap) {
        return hasFingeredBeamOwner(normalized, raw, width, height, measure, alias, notes, x, y, gap, .27f);
    }

    private static boolean hasFingeredBeamOwner(
            byte[] normalized,
            byte[] raw,
            int width,
            int height,
            MeasureRegion measure,
            ScoreNoteEvent alias,
            List<ScoreNoteEvent> notes,
            float x,
            float y,
            float gap, float offsetLimit) {
        ScoreNoteEvent owner = null;
        for (var note : notes) {
            if (note.measureIndex() != alias.measureIndex()
                    || note.staffIndex() != 0
                    || note.staffCount() != 1
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.stemDirection() != -1
                    || note.beamCount() < 1
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || (note.articulations() & NoteOrnament.GRACE) != 0
                    || Math.abs(fingeringHeadX(measure, note, width) - x) > gap * offsetLimit) continue;
            float rise = y - note.pageY() * height;
            if (rise < gap * 4.2f || rise > gap * 8f) continue;
            if (owner != null) return false;
            owner = note;
        }
        if (owner == null) return false;
        float headX = fingeringHeadX(measure, owner, width), headY = owner.pageY() * height;
        var a = PairedTremoloInk.stem(normalized, width, height, headX, headY, gap, -1);
        var b = PairedTremoloInk.stem(raw, width, height, headX, headY, gap, -1);
        return a != null
                && b != null
                && Math.abs(a.x() - b.x()) <= gap * .12f
                && Math.abs(a.endY() - b.endY()) <= gap * .17f
                && a.endY() <= y - gap * 1.15f
                && b.endY() <= y - gap * 1.15f;
    }


    /** A decoded head can coincide with a small printed fingering on or below the bottom rule. */
    static boolean beamedNumeralAlias(List<PlayingTechniqueDetector.Word> words, MeasureRegion bar,
            ScoreNoteEvent note, byte[] raw, byte[] normalized, int width, int height) {
        if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffIndex() != 0 || note.staffCount() != 1
                || note.visualSize() != ScoreNoteEvent.VisualSize.FULL || note.stemDirection() != 0
                || note.unbeamedDurationBeats() != 0 || note.tupletDivisor() != 1
                || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || note.tiedFromPrevious() || note.boundaryTies() != 0 || note.crossStaffBeam()
                || (note.articulations() & ~NoteArticulation.STACCATO) != 0
                || note.augmentationDots() != 0 || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0)
            return false;
        boolean crossingTwo = note.staffStep() == 1 && note.beamCount() == 2;
        if (!crossingTwo && !(note.staffStep() == -2 && note.beamCount() == 1)) return false;
        String digit = crossingTwo ? "2" : "3";
        float x = fingeringHeadX(bar, note, width), y = note.pageY() * height;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        var rowDigits = candidates(words, width, height);
        for (var word : words) {
            if (!digit.equals(word.text().strip())) continue;
            float l = word.left()*width, r = word.right()*width;
            float t = word.top()*height, b = word.bottom()*height;
            if (r-l < gap || r-l > gap*1.8f || b-t < gap*1.25f || b-t > gap*2.1f
                    || x <= l || x >= r || y <= t || y >= b
                    || Math.abs((l+r)*.5f-x) > gap*.24f
                    || !bracketedByFingerings(rowDigits, bar, x, (t+b)*.5f, gap, width, 9.5f))
                continue;
            if (provesBeamedNumeral(raw, width, height, bar, x, y, l, t, r, b, gap, crossingTwo)
                    && provesBeamedNumeral(normalized, width, height, bar, x, y, l, t, r, b, gap, crossingTwo))
                return true;
        }
        return false;
    }

    /** A staff rule through a fingering can close the bowls and make OCR report 8 or 9. */
    static boolean crossedFingeringThreeAlias(List<PlayingTechniqueDetector.Word> words,
            MeasureRegion bar, ScoreNoteEvent note, List<ScoreNoteEvent> notes,
            byte[] raw, byte[] normalized, int width, int height) {
        if (!captionGlyphHead(note, false, -1, -1) || note.articulations() != 0
                || !(note.beamCount() == 2 && note.unbeamedDurationBeats() == 0
                    || note.beamCount() == 0 && note.unbeamedDurationBeats() == 1)) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float x = fingeringHeadX(bar, note, width), y = note.pageY() * height;
        if (gap < 12 || gap > 24 || !hasFingeredBeamOwner(
                normalized, raw, width, height, bar, note, notes, x, y, gap, .35f)) return false;
        var candidates = words.stream().filter(word -> {
            if (!Set.of("3", "8", "9").contains(word.text().strip())) return false;
            return Math.abs((word.left() + word.right()) * width * .5f - x) <= gap * .20f
                    && word.top() * height <= y && y <= word.bottom() * height;
        }).toList();
        return candidates.size() == 1 && crossedFingeringThreeInk(
                words, bar, raw, normalized, width, height, x, y);
    }

    static boolean crossedFingeringThreeInk(List<PlayingTechniqueDetector.Word> words,
            MeasureRegion bar, byte[] raw, byte[] normalized, int width, int height, float x, float y) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 12 || gap > 24) return false;
        float bottom = bar.bottom() * height - gap * 2.1f;
        for (byte[] plane : new byte[][] {raw, normalized}) {
            var staff = PrintedDirectionStaff.printedRules(plane, width, height, x, bottom, gap);
            var budget = new PrintedBeamShafts.Budget(100_000);
            if (staff == null || y - staff[0] < staff[1] * .16f || y - staff[0] > staff[1] * .65f
                    || !PrintedBeamShafts.at(plane, width, height, x, y, staff[1], budget).isEmpty()
                    || budget.refused()) return false;
            var ink = fingerInkWithoutRule(plane, width, height, x, staff);
            if (ink.isEmpty()) return false;
            int agreeing = 0;
            float best = 0, bestOther = 0;
            for (var word : words) {
                String digit = word.text().strip();
                if (!Set.of("1", "2", "3", "4").contains(digit)) continue;
                float centerX = (word.left() + word.right()) * width * .5f;
                float centerY = (word.top() + word.bottom()) * height * .5f;
                if (Math.abs(centerX - x) < gap * 3 || Math.abs(centerX - x) > gap * 58
                        || Math.abs(centerY - y) > gap * .55f) continue;
                // The reference numeral itself can hide staff rows locally. Its baseline
                // must be near the measured staff and the mask still proves the crossing rule.
                float cueFloor = centerY - staff[1] * .29f;
                if (Math.abs(cueFloor - staff[0]) > gap * .35f) continue;
                float similarity = sameFingerGlyph(ink,
                        fingerInkWithoutRule(plane, width, height, centerX, new float[]{cueFloor,staff[1]}));
                if ("3".equals(digit)) {
                    if (similarity >= .635f) agreeing++;
                    best = Math.max(best, similarity);
                } else {
                    bestOther = Math.max(bestOther, similarity);
                }
            }
            if (agreeing < 2 || best < .675f || best - bestOther < .065f) return false;
        }
        return true;
    }

    private static Set<Integer> fingerInkWithoutRule(
            byte[] ink, int width, int height, float x, float[] staff) {
        int cx = Math.round(x), cy = Math.round(staff[0]);
        int radius = Math.round(staff[1] * .87f), outside = Math.round(staff[1] * 1.06f);
        int top = Math.round(staff[1] * .69f), bottom = Math.round(staff[1] * 1.15f);
        if (cx - outside - 3 < 0 || cx + outside + 3 >= width || cy - top < 0 || cy + bottom >= height)
            return Set.of();
        var points = new HashSet<Integer>();
        int ruleRows = 0, completeRules = 0;
        for (int dy = -top; dy <= bottom; dy++) {
            int at = (cy + dy) * width + cx;
            boolean leftRule = (ink[at - outside - 2] & 255) < 155 || (ink[at - radius] & 255) < 155;
            boolean rightRule = (ink[at + outside + 2] & 255) < 155 || (ink[at + radius] & 255) < 155;
            if (Math.abs(dy) <= staff[1] * .3f && (leftRule || rightRule)) {
                ruleRows++;
                if (leftRule && rightRule) completeRules++;
                continue;
            }
            if ((ink[at - radius] & 255) < 155 || (ink[at + radius] & 255) < 155) return Set.of();
            for (int dx = -radius + 1; dx < radius; dx++) {
                if ((ink[at + dx] & 255) < 155) points.add(dy * 64 + dx);
            }
        }
        if (completeRules == 0 || ruleRows > staff[1] * .29f
                || points.size() < staff[1] * staff[1] * .39f) return Set.of();
        return points;
    }

    private static float sameFingerGlyph(Set<Integer> first, Set<Integer> second) {
        float best = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int shared = 0, shift = dy * 64 + dx;
                for (int pixel : second) if (first.contains(pixel + shift)) shared++;
                best = Math.max(best, shared / (float) (first.size() + second.size() - shared));
            }
        }
        return best;
    }

    /** A repeated below-staff 3 can seed both a whole head and a stemless beamed head. */
    static boolean pairedTripletFingeringAliases(List<PlayingTechniqueDetector.Word> words,
            MeasureRegion bar, ScoreNoteEvent whole, ScoreNoteEvent beamed, List<ScoreNoteEvent> notes,
            byte[] gray, byte[] raw, int width, int height) {
        if (!captionGlyphHead(whole, false, -1, -1)
                || !captionGlyphHead(beamed, false, -1, -1)
                || whole.beamCount() != 0 || whole.unbeamedDurationBeats() != 4
                || beamed.beamCount() != 1 || beamed.unbeamedDurationBeats() != 0) return false;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float first = fingeringHeadX(bar, whole, width), last = fingeringHeadX(bar, beamed, width);
        if (gap < 10 || gap > 25 || last - first < gap * 8 || last - first > gap * 19
                || Math.abs(whole.pageY() - beamed.pageY()) * height > gap * .20f
                || !hasFingeredBeamOwner(gray, raw, width, height, bar, whole, notes,
                        first, whole.pageY() * height, gap, .55f)
                || !hasFingeredBeamOwner(gray, raw, width, height, bar, beamed, notes,
                        last, beamed.pageY() * height, gap, .55f)) return false;
        return matchingFingeringThrees(gray, width, height, bar, words, first, whole.pageY() * height,
                        last, beamed.pageY() * height, gap)
                && matchingFingeringThrees(raw, width, height, bar, words, first, whole.pageY() * height,
                        last, beamed.pageY() * height, gap);
    }

    private static boolean matchingFingeringThrees(byte[] plane, int width, int height,
            MeasureRegion bar, List<PlayingTechniqueDetector.Word> words,
            float first, float firstY, float last, float lastY, float gap) {
        float bottom = bar.bottom() * height - gap * 2.1f;
        var a = isolatedFingeringThree(plane, width, height, words, first, firstY, bottom, gap, true);
        var b = isolatedFingeringThree(plane, width, height, words, last, lastY, bottom, gap, false);
        return a != null && b != null && Math.abs(a.top - b.top) <= Math.round(gap * .13f)
                && Math.abs(a.bottom - b.bottom) <= Math.round(gap * .15f)
                && sameDetachedDigit(plane, width, a, b);
    }

    private static RawNumeralComponents.Component isolatedFingeringThree(byte[] plane, int width, int height,
            List<PlayingTechniqueDetector.Word> words, float x, float y, float bottom, float nominal,
            boolean closeBowl) {
        var staff = PrintedDirectionStaff.printedRules(plane, width, height, x, bottom, nominal);
        if (staff == null || Math.abs(staff[1] - nominal) > nominal * .13f) return null;
        float floor = staff[0], gap = staff[1];
        if (y - floor < gap * .35f || y - floor > gap * .81f) return null;
        var matching = words.stream().filter(w -> "3".equals(w.text().strip())
                && x > w.left() * width && x < w.right() * width
                && y > w.top() * height && y < w.bottom() * height).toList();
        if (matching.size() != 1) return null;
        var word = matching.get(0);
        float left = word.left() * width, right = word.right() * width;
        float top = word.top() * height, end = word.bottom() * height;
        if (left < 0 || right >= width || top < 0 || end >= height
                || right - left < gap * 1.15f || right - left > gap * 1.65f
                || Math.abs(x - (left + right) * .5f) > gap * .22f
                || floor - top < gap * .35f || floor - top > gap * .68f
                || end - floor < gap || end - floor > gap * 1.40f
                || printedStemBeyondWord(plane, width, height, x, y, top, end, gap)
                || printedLedgerAcrossWord(plane, width, height, left, right, y, gap)
                || closeBowl && !hasFingerThreeBowl(plane, width, height, x, floor, gap, 165)) return null;
        var glyphs = RawNumeralComponents.capture(plane, width, height, Math.round(left),
                Math.round(floor + gap * .18f), Math.round(right), Math.round(end),
                165, () -> {}, () -> {}).components().stream().filter(c -> c.size() >= gap * gap * .14f).toList();
        if (glyphs.size() != 1) return null;
        var digit = glyphs.get(0);
        return digit.left > Math.round(left) && digit.right < Math.round(right) && digit.bottom < end
                && x >= digit.left && x <= digit.right && y >= digit.top && y <= digit.bottom
                && digit.right - digit.left + 1 >= gap * .65f && digit.right - digit.left + 1 <= gap * 1.05f
                ? digit : null;
    }

    private static boolean provesBeamedNumeral(byte[] gray, int width, int height, MeasureRegion bar,
            float x, float y, float l, float t, float r, float b, float nominal, boolean crossingTwo) {
        var staff = PrintedDirectionStaff.printedRules(
                gray, width, height, x, bar.bottom()*height - nominal*2.1f, nominal);
        if (staff == null || l < nominal || r > width - nominal || t < nominal || b > height - nominal)
            return false;
        float gap = staff[1], floor = staff[0];
        var budget = new PrintedBeamShafts.Budget(100_000);
        if (!PrintedBeamShafts.at(gray, width, height, x, y, gap, budget).isEmpty() || budget.refused())
            return false;
        if (!crossingTwo) {
            if (Math.abs(y-floor-gap) > gap*.34f || t < floor-gap*.15f || b > floor+gap*2.1f)
                return false;
            return completeTwoCounterNumeral(gray, width, height, x, y, l,
                    Math.max(t, floor+gap*.2f), r, b, gap);
        }
        if (y > floor || y < floor-gap*.52f || t >= floor || b <= floor+gap*.45f)
            return false;
        int left = Math.round(l), right = Math.round(r), top = Math.round(t), bottom = Math.round(b);
        int lineTop = -1, lineBottom = -1, outside = Math.round(gap*.46f);
        for (int row = Math.round(floor-gap*.18f); row <= Math.round(floor+gap*.18f); row++) {
            boolean connected = true;
            for (int col = 1; col <= outside; col++)
                if ((gray[row*width+left-col]&255)>165 || (gray[row*width+right+col]&255)>165)
                    connected = false;
            if (!connected) continue;
            if (lineBottom >= 0 && row != lineBottom+1) return false;
            if (lineTop < 0) lineTop = row;
            lineBottom = row;
        }
        if (lineTop <= top+2 || lineBottom >= bottom-2 || lineBottom-lineTop + 1 > gap*.26f)
            return false;
        var upper = joinedLobe(RawNumeralComponents.capture(gray, width, height, left, top,
                        right, lineTop-1, () -> {}, () -> {}), left, right, x, gap, .36f, .72f);
        var lower = joinedLobe(RawNumeralComponents.capture(gray, width, height, left, lineBottom+1,
                        right, bottom, () -> {}, () -> {}), left, right, x, gap, .28f, .66f);
        if (upper == null || lower == null || upper.bottom != lineTop-1 || lower.top != lineBottom+1
                || upper.top <= top || lower.bottom >= bottom || y < upper.top || y > upper.bottom
                || x < upper.left || x > upper.right) return false;
        int open = 0, foot = 0;
        for (int row = upper.top; row <= upper.bottom; row++) {
            int background = 0;
            for (int col = upper.left+1; col < upper.right; col++)
                if ((gray[row*width+col]&255)>165) background++;
            if (background >= 2 && (gray[row*width+Math.round(x-gap*.29f)]&255)<=165
                    && (gray[row*width+Math.round(x+gap*.34f)]&255)<=165) open++;
        }
        for (int row = lower.top; row <= lower.bottom; row++) {
            int ink = 0;
            for (int col = lower.left; col <= lower.right; col++)
                if ((gray[row*width+col]&255)<=165) ink++;
            if (ink >= gap*.72f) foot++;
        }
        return open >= 2 && foot >= 2;
    }

    private static boolean bracketedByFingerings(
            List<Candidate> words, MeasureRegion measure, float x, float y, float gap, int width) {
        return bracketedByFingerings(words, measure, x, y, gap, width, 5);
    }

    private static boolean bracketedByFingerings(List<Candidate> words, MeasureRegion measure,
            float x, float y, float gap, int width, float maxGaps) {
        boolean left = false, right = false;
        for (var word : words) {
            if (!word.digit() && word.sequenceLength() == 0
                    || word.left() < measure.left() * width
                    || word.right() > measure.right() * width) continue;
            float dx = (word.left() + word.right()) / 2 - x;
            if (Math.abs(dx) < gap
                    || Math.abs(dx) > maxGaps * gap
                    || Math.abs((word.top() + word.bottom()) / 2 - y) > .5f * gap) continue;
            if (dx < 0) left = true;
            else right = true;
        }
        return left && right;
    }

    /** A below-staff numeric stack has a complete fingering and a separate open zero. */
    static boolean stackedOpenFingering(byte[] ink, int width, int height,
            float x, float aliasY, float tip, float gap) {
        float left = x - .91f * gap, right = x + .91f * gap;
        float split = tip + 1.4f * gap;
        if (aliasY - tip < .8f * gap || aliasY - tip > 1.25f * gap
                || !completeTwoCounterNumeral(ink, width, height, x, aliasY,
                        left, tip + .1f * gap, right, split, gap)) return false;
        var zero = completeNumeralInk(ink, width, height, left, split, right, tip + 3.4f * gap, gap);
        if (zero == null) return false;
        int w = zero.right - zero.left + 1, h = zero.bottom - zero.top + 1;
        return h >= .99f * gap && h <= 1.4f * gap && w >= .61f * h && w <= .84f * h
                && Math.abs((zero.left + zero.right) * .5f - x) <= .24f * gap
                && OctaveClefDigit.holes(ink, width, zero.left, zero.top, w, h) == 1;
    }

    static boolean detachedOpenZeroAbove(byte[] ink, int width, int height,
            float x, float headY, float gap) {
        var glyph = completeNumeralInk(ink, width, height,
                x - .93f * gap, headY - 3.02f * gap, x + .93f * gap, headY - 1.39f * gap, gap);
        if (glyph == null) return false;
        int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
        return h >= .98f * gap && h <= 1.33f * gap && w >= h * .60f && w <= h * .89f
                && Math.abs((glyph.left + glyph.right) * .5f - x) <= .24f * gap
                && OctaveClefDigit.holes(ink, width, glyph.left, glyph.top, w, h) == 1;
    }

    private static boolean completeTwoCounterNumeral(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float l,
            float t,
            float r,
            float b,
            float gap) {
        var glyph = completeNumeralInk(gray, width, height, l, t, r, b, gap);
        if (glyph == null || x < glyph.left || x > glyph.right || y < glyph.top || y > glyph.bottom)
            return false;
        int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
        return h >= gap
                && h <= gap * 1.6f
                && w >= h * .52f
                && w <= h * .85f
                && OctaveClefDigit.holes(gray, width, glyph.left, glyph.top, w, h) == 2;
    }

    private static RawNumeralComponents.Component completeNumeralInk(byte[] gray, int width, int height,
            float l, float t, float r, float b, float gap) {
        int left = Math.max(0, Math.round(l)), right = Math.min(width - 1, Math.round(r));
        int top = Math.max(0, Math.round(t)), bottom = Math.min(height - 1, Math.round(b));
        if (left >= right || top >= bottom) return null;
        var source = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, 165, () -> {}, () -> {});
        RawNumeralComponents.Component glyph = null;
        for (var piece : source.components()) {
            if (piece.size() < gap * gap * .05f) continue;
            if (glyph != null) return null;
            glyph = piece;
        }
        return glyph != null && glyph.left > left && glyph.right < right
                && glyph.top > top && glyph.bottom < bottom ? glyph : null;
    }

    /** Staff-line fragments can be notehead-shaped while belonging to an alternate fingering. */
    private static List<ScoreNoteEvent> annotatedSingleLines(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, int width, int height) {
        var openings = words.stream().filter(w -> OPEN_FINGER.matcher(w.text().strip()).matches()
                || w.text().strip().equalsIgnoreCase("(a")).toList();
        var closings = words.stream().filter(w -> CLOSE_FINGER.matcher(w.text().strip()).matches()).toList();
        var captions = words.stream().filter(w -> FINGERS_AND_SEGUE.matcher(w.text().strip()).matches()).toList();
        if (captions.isEmpty() && (openings.isEmpty() || closings.isEmpty())) return notes;
        var result = new ArrayList<>(notes);
        for (int m = 0; m < measures.size(); m++) {
            int index=m;
            var local=notes.stream().filter(n -> n.measureIndex()==index).toList();
            long whole=local.stream().filter(FingeringAnnotationFilter::possiblyPrintedText).count();
            boolean isolatedOpening = local.size() == 17 && whole == 1;
            boolean parenthesized = isolatedOpening || local.size() == 19 && whole == 3;
            if (!parenthesized && (local.size()!=15 || whole!=3)) continue;
            var region=measures.get(m);
            var music=local.stream().filter(n->!possiblyPrintedText(n)).toList();
            if (music.stream().anyMatch(n -> n.kind()!=ScoreNoteEvent.Kind.PITCHED
                    || n.staffCount()!=1 || n.staffIndex()!=0 || n.stemDirection()!=-1
                    || n.beamCount()!=2 || n.unbeamedDurationBeats()!=0 || n.tupletDivisor()!=1
                    || n.augmentationDots()!=0 || n.leadingRestBeats()!=0 || n.followingRestBeats()!=0))
                continue;
            var staff=staffs.stream().filter(st -> st.count()==1 && st.index()==0
                    && st.top()>=region.top()*height && st.bottom()<=region.bottom()*height)
                    .min(Comparator.comparingDouble(st -> Math.abs((st.top()+st.bottom())*.5
                            -(region.top()+region.bottom())*height*.5))).orElse(null);
            if (staff==null) continue;
            float gap=staff.gap();
            var approved=parenthesized
                    ? isolatedOpening
                            ? isolatedOpeningBox(openings, closings, region, staff, music, width, height)
                            : parenthesizedBoxes(openings,closings,region,staff,width,height)
                    : captions.stream().filter(w -> insideStaffText(w,region,staff,width,height)
                            && (w.right()-w.left())*width>=gap*13
                            && (w.right()-w.left())*width<=gap*25).toList();
            if (approved.isEmpty()) continue;
            var falseHeads=local.stream().filter(FingeringAnnotationFilter::possiblyPrintedText).toList();
            if (falseHeads.stream().allMatch(n -> insideInk(n,region,staff,approved,gray,width,height))
                    && closedWrittenSixteenths(music)) result.removeAll(falseHeads);
        }
        return result.size()==notes.size()?notes:List.copyOf(result);
    }

    /** A detached initial can inherit a beam beneath an otherwise complete sixteenth-note bar. */
    private static List<ScoreNoteEvent> mergedFingeringSegueInitial(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, byte[] raw,
            int width, int height) {
        if (raw == null || raw.length != gray.length) return notes;
        Set<ScoreNoteEvent> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var caption : words) {
            if (caption.text() == null || !FINGERS_AND_SEGUE.matcher(caption.text().strip()).matches())
                continue;
            int digits = WHITESPACE.split(caption.text().strip()).length - 1;
            int measure = uniqueCaptionMeasure(
                    measures, caption, caption, (caption.top() + caption.bottom()) * .5f);
            if (measure < 0) continue;
            var bar = measures.get(measure);
            var local = notes.stream().filter(n -> n.measureIndex() == measure).toList();
            if (local.size() != 13) continue;
            var aliases = local.stream().filter(n -> captionGlyphHead(n, true, -2, -2)
                    && n.beamCount() == 2 && n.unbeamedDurationBeats() == 0
                    && n.octaveShift() == 0 && n.articulations() == 0).toList();
            if (aliases.size() != 1) continue;
            var alias = aliases.get(0);
            var music = local.stream().filter(n -> n != alias)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (music.stream().anyMatch(n -> n.stemDirection() != -1)) continue;
            float x = fingeringHeadX(bar, alias, width), y = alias.pageY() * height;
            for (var staff : staffs) {
                float gap = staff.gap(), floor = staff.bottom();
                if (staff.index() != 0 || staff.count() != 1 || gap < 10 || gap > 25
                        || staff.top() < bar.top() * height || floor > bar.bottom() * height
                        || caption.top() * height < floor - gap * .15f
                        || caption.top() * height > floor + gap * .55f
                        || caption.bottom() * height > floor + gap * 3.2f
                        || y < floor + gap * .6f || y > floor + gap * 1.5f) continue;
                if (!closedMergedSegueInitial(gray, width, height, bar, staff, caption, digits, alias, x, y, music)
                        || !closedMergedSegueInitial(raw, width, height, bar, staff, caption, digits, alias, x, y, music))
                    continue;
                discarded.add(alias);
                break;
            }
        }
        return discarded.isEmpty() ? notes : notes.stream().filter(n -> !discarded.contains(n)).toList();
    }

    private static boolean closedMergedSegueInitial(byte[] gray, int width, int height, MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word caption, int digits,
            ScoreNoteEvent alias, float x, float y, List<ScoreNoteEvent> notes) {
        return insideInk(alias, bar, staff, List.of(caption), gray, width, height)
                && ownsCompactSegueInitial(gray, width, height, staff, caption, digits, x, y, true)
                && closedCaptionSixteenths(notes, bar, staff, gray, width, height,
                        3.9f, true, .95f, 0, 3.1f, true, true, true);
    }

    /** OCR can split the printed fingering line and its following word into separate boxes. */
    private static List<ScoreNoteEvent> separatedFingeringCaptions(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, int width, int height) {
        var fingerings = words.stream().filter(w -> w.text() != null
                && SPLIT_FINGERING_RUN.matcher(w.text().strip()).matches()).toList();
        var captions = words.stream().filter(w -> w.text() != null
                && SINGLE_SEGUE.matcher(w.text().strip()).matches()).toList();
        if (fingerings.isEmpty() || captions.isEmpty()) return notes;
        var byMeasure = new HashMap<Integer, List<ScoreNoteEvent>>();
        for (var note : notes)
            byMeasure.computeIfAbsent(note.measureIndex(), ignored -> new ArrayList<>()).add(note);
        var discard = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var caption : captions) {
            for (var digits : fingerings) {
                if (!(digits.right() < caption.left())) continue;
                int measure = uniqueCaptionMeasure(measures, digits, caption);
                if (measure < 0) continue;
                var region = measures.get(measure);
                var staff = captionStaff(staffs, digits, caption, region, width, height);
                if (staff == null) continue;
                var local = byMeasure.getOrDefault(measure, List.of());
                var music = local.stream().filter(n -> n.stemDirection() == -1)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                if (music.size() != 12 || local.size() < 15 || local.size() > 18) continue;
                var nonMusic = local.stream().filter(n -> n.stemDirection() != -1).toList();
                if (nonMusic.stream().anyMatch(n -> !captionHead(n)
                        || !insideInk(n, region, staff, List.of(digits, caption), gray, width, height)))
                    continue;
                long wordHeads = nonMusic.stream().filter(n -> (region.left() + n.positionInMeasure()
                        * (region.right() - region.left())) > caption.left()).count();
                if (wordHeads < 2 || wordHeads == nonMusic.size()) continue;
                if (closedCaptionSixteenths(music, region, staff, gray, width, height))
                    discard.addAll(nonMusic);
            }
        }
        if (discard.isEmpty()) return notes;
        return notes.stream().filter(n -> !discard.contains(n)).toList();
    }

    static boolean hasSegueWord(List<PlayingTechniqueDetector.Word> words) {
        return words.stream().anyMatch(word -> word.text() != null
                && SINGLE_SEGUE.matcher(word.text().strip()).matches());
    }

    /** Detached printed lettering can inherit a nearby beam without having a written shaft. */
    static List<ScoreNoteEvent> detachedSegueBeamAliases(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes,
            byte[] gray, int width, int height) {
        Set<ScoreNoteEvent> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var word : words) {
            if (word.text() == null || !SINGLE_SEGUE.matcher(word.text().strip()).matches())
                continue;
            float left = word.left() * width, right = word.right() * width;
            float top = word.top() * height, bottom = word.bottom() * height;
            for (var staff : staffs) {
                float gap = staff.gap(), floor = staff.bottom();
                if (staff.index() != 0 || staff.count() != 1 || gap < 10 || gap > 25
                        || top < floor || top > floor + gap * .50f
                        || bottom < floor + gap * 2 || bottom > floor + gap * 3.2f
                        || right - left < gap * 5 || right - left > gap * 7.5f)
                    continue;
                int measure = detachedCaptionMeasure(measures, word, staff, height);
                if (measure < 0) continue;
                var region = measures.get(measure);
                var proposals = notes.stream()
                        .filter(n -> n.measureIndex() == measure && captionGlyphHead(n, false, -5, -1)
                                && (n.beamCount() == 2 || n.beamCount() == 3)
                                && n.unbeamedDurationBeats() == 0
                                && insideInk(n, region, staff, List.of(word), gray, width, height))
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                if (proposals.size() == 2
                        && ownsDetachedSegueGlyphs(proposals, region, staff, word, gray, width, height))
                    rejected.addAll(proposals);
            }
        }
        return rejected.isEmpty() ? notes : notes.stream().filter(n -> !rejected.contains(n)).toList();
    }

    private static int detachedCaptionMeasure(
            List<MeasureRegion> measures, PlayingTechniqueDetector.Word word,
            PlayingTechniqueDetector.Staff staff, int height) {
        int selected = -1;
        for (int i = 0; i < measures.size(); i++) {
            var bar = measures.get(i);
            if (bar.cadenceQuarterBeats() != 0 || word.left() < bar.left() || word.right() > bar.right()
                    || staff.top() < bar.top() * height || staff.bottom() > bar.bottom() * height) continue;
            if (selected >= 0) return -1;
            selected = i;
        }
        return selected;
    }

    private static boolean ownsDetachedSegueGlyphs(
            List<ScoreNoteEvent> aliases, MeasureRegion bar, PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word word, byte[] gray, int width, int height) {
        var graph = captionComponents(gray, width, height, staff, word);
        if (graph == null) return false;
        float gap = staff.gap();
        var glyphs = graph.components().stream()
                .filter(c -> c.left > graph.left && c.right < graph.right
                        && c.top > graph.top && c.bottom < graph.bottom
                        && c.top > staff.bottom() + gap * .55f
                        && c.right - c.left >= gap * .55f
                        && c.bottom - c.top >= gap && c.bottom - c.top <= gap * 2
                        && c.size() >= gap * 4)
                .sorted(Comparator.comparingInt(c -> c.left)).toList();
        if (glyphs.size() < 3 || glyphs.size() > 5) return false;
        var first = glyphs.get(0);
        var last = glyphs.get(glyphs.size() - 1);
        if (first.right - first.left > gap * 1.1f || last.right - first.left < gap * 4)
            return false;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var upper = aliases.get(0);
        var descender = aliases.get(1);
        return Math.abs(first.top - last.top) <= gap * .20f
                && last.bottom - first.bottom >= gap * .33f
                && ownsTextCenter(first, left + span * upper.positionInMeasure(), upper.pageY() * height, width)
                && ownsTextCenter(last, left + span * descender.positionInMeasure(),
                        descender.pageY() * height, width)
                && descender.pageY() * height > first.bottom;
    }

    /** A merged OCR record retains the caption, including an ink lobe read as a whole note. */
    private static List<ScoreNoteEvent> shortFingeringCaptions(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, int width, int height) {
        List<ScoreNoteEvent> output = null;
        for (var caption : words) {
            if (caption.text() == null || !SHORT_FINGERING_CAPTION.matcher(caption.text().strip()).matches())
                continue;
            int measure = uniqueCaptionMeasure(measures, caption, caption, (caption.top() + caption.bottom()) * .5f);
            if (measure < 0) continue;
            var region = measures.get(measure);
            var local = notes.stream().filter(n -> n.measureIndex() == measure).toList();
            if (local.size() != 13) continue;
            var alias = local.stream().filter(n -> captionHead(n) && n.unbeamedDurationBeats() == 4
                    && n.staffStep() == -1).findFirst().orElse(null);
            if (alias == null) continue;
            var music = local.stream().filter(n -> n != alias)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (music.stream().anyMatch(n -> n.stemDirection() != -1)) continue;
            int firstTriplet = tripletRunStart(music);
            if (firstTriplet == -2) continue;
            var corrected = music.stream()
                    .map(n -> n.tupletDivisor() == 3 ? n.withTupletRatio(1, 1) : n).toList();
            for (var staff : staffs) {
                float gap = staff.gap();
                if (staff.index() != 0 || staff.count() != 1 || staff.top() < region.top() * height
                        || staff.bottom() > region.bottom() * height || gap < 10 || gap > 25
                        || caption.top() * height < staff.bottom() - gap
                        || caption.top() * height > staff.bottom()
                        || caption.bottom() * height < staff.bottom() + gap
                        || caption.bottom() * height > staff.bottom() + gap * 2.6f
                        || !shortCaptionInitialInk(alias, region, staff, caption, gray, width, height))
                    continue;
                if (!closedCaptionSixteenths(
                        corrected, region, staff, gray, width, height, 3.55f, true)) continue;
                if (output == null) output = new ArrayList<>(notes);
                if (firstTriplet >= 0)
                    for (int i = firstTriplet; i < firstTriplet + 3; i++)
                        output.set(notes.indexOf(music.get(i)), corrected.get(i));
                output.set(notes.indexOf(alias), null);
                break;
            }
        }
        return output == null ? notes : output.stream().filter(Objects::nonNull).toList();
    }

    /** A detached caption descender can be segmented as a stemless whole note. */
    private static List<ScoreNoteEvent> removeDescenderAliases(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray, byte[] raw, int width, int height) {
        var captions = words.stream().filter(word -> word.text() != null
                && (FINGERING_SEGUE.matcher(word.text().strip()).matches()
                        || SINGLE_SEGUE.matcher(word.text().strip()).matches())).toList();
        if (captions.isEmpty()) return notes;
        var removed = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var note : notes) {
            boolean wholeAlias = note.beamCount() == 0 && note.unbeamedDurationBeats() == 4;
            boolean beamAlias = note.beamCount() == 3 && note.unbeamedDurationBeats() == 0
                    && raw != null && raw.length == gray.length;
            if (note.measureIndex() < 0 || note.measureIndex() >= measures.size()
                    || !captionGlyphHead(note, false) || note.staffStep() != -2
                    || (!wholeAlias && !beamAlias) || note.articulations() != 0) continue;
            var region = measures.get(note.measureIndex());
            float span = (region.right() - region.left()) * width;
            float x = region.left() * width + note.positionInMeasure() * span, y = note.pageY() * height;
            for (var staff : staffs) {
                float gap = staff.gap(), distance = y - staff.bottom();
                if (staff.index() != 0 || staff.count() != 1 || gap < 10 || gap > 26
                        || staff.bottom() > region.bottom() * height || staff.top() < region.top() * height
                        || distance < gap * .55f || distance > gap * 1.40f) continue;
                for (var caption : captions) {
                    float left = caption.left() * width, right = caption.right() * width;
                    float top = caption.top() * height, bottom = caption.bottom() * height;
                    float captionWidth = right - left;
                    // The OCR may split the word from the fingering digit.
                    boolean separate = SINGLE_SEGUE.matcher(caption.text().strip()).matches();
                    if (captionWidth < gap * (separate ? 5.5f : 7)
                            || captionWidth > gap * (separate ? 7.25f : 11)
                            || bottom - top < gap * 1.7f || bottom - top > gap * 3
                            || left < region.left() * width || right > region.right() * width
                            || x < left + captionWidth * (separate ? .33f : .50f)
                            || x > left + captionWidth * (separate ? .58f : .72f)
                            || y < top || y > bottom
                            || printedStemBeyondWord(gray, width, height, x, y, top, bottom, gap)
                            || !captionDescender(gray, width, height, staff, caption, x, y, separate)) continue;
                    var run = notes.stream().filter(n -> n.measureIndex() == note.measureIndex() && n != note)
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                    if (!closesDescenderBar(run, span, gap)) continue;
                    if (beamAlias && (!separate
                            || !captionDescender(raw, width, height, staff, caption, x, y, true)
                            || !insideInk(note, region, staff, List.of(caption), raw, width, height)
                            || !closedCaptionSixteenths(run, region, staff, gray, width, height,
                                    3.9f, true, .95f, 0, 3.1f, true, true, true)
                            || !closedCaptionSixteenths(run, region, staff, raw, width, height,
                                    3.9f, true, .95f, 0, 3.1f, true, true, true))) continue;
                    removed.add(note);
                    break;
                }
                if (removed.contains(note)) break;
            }
        }
        return removed.isEmpty() ? notes : notes.stream().filter(n -> !removed.contains(n)).toList();
    }

    private static boolean captionDescender(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word caption, float x, float y, boolean separate) {
        float gap = staff.gap();
        int left = Math.round(caption.left() * width), right = Math.round(caption.right() * width);
        int top = Math.max(Math.round(caption.top() * height), Math.round(staff.bottom() + gap * .14f));
        int bottom = Math.round(caption.bottom() * height);
        if (left < 0 || right >= width || top < 0 || bottom >= height || left >= right || top >= bottom)
            return false;
        var graph = RawNumeralComponents.captureLongWindow(
                gray, width, height, left, top, right, bottom, 165);
        for (var glyph : graph.components()) {
            if (glyph.left >= x || glyph.right <= x || glyph.top >= y || glyph.bottom <= y + gap * .65f
                    || glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || glyph.right - glyph.left < gap * .9f || glyph.right - glyph.left > gap * 2.1f
                    || glyph.bottom - glyph.top < gap || glyph.bottom - glyph.top > gap * 1.8f) continue;
            int neighbors = 0, before = 0, after = 0;
            for (var peer : graph.components()) {
                if (peer == glyph || peer.left <= left || peer.right >= right || peer.top <= top || peer.bottom >= bottom
                        || Math.abs(peer.top - glyph.top) > gap * .35f
                        || peer.bottom - peer.top < gap * .52f || peer.bottom - peer.top > gap * (separate ? 1.22f : 1.1f)
                        || peer.right - peer.left < gap * .4f || peer.right - peer.left > gap * (separate ? 1.65f : 1.3f)) continue;
                if (peer.right < glyph.left && glyph.left - peer.right < gap * 2.7f) before++;
                if (peer.left > glyph.right && peer.left - glyph.right < gap * 2.7f) after++;
                neighbors++;
            }
            if (neighbors >= 3 && before >= 1 && after >= 1) return true;
        }
        return false;
    }

    private static boolean closesDescenderBar(List<ScoreNoteEvent> run, float span, float gap) {
        if (run.size() != 12) return false;
        for (int i = 0; i < run.size(); i++) {
            var note = run.get(i);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1 || note.staffIndex() != 0
                    || note.stemDirection() != -1 || note.beamCount() != 2 || note.augmentationDots() != 0
                    || note.unbeamedDurationBeats() != 0 || note.tupletDivisor() != 1
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0) return false;
            if (i > 0) {
                float spacing = (note.positionInMeasure() - run.get(i - 1).positionInMeasure()) * span;
                if (spacing < gap * 1.6f || spacing > gap * 4) return false;
            }
        }
        return closedWrittenSixteenths(run);
    }


    /** Corroborate two stemless whole aliases in merged caption text and its separate fingering lobe. */
    static List<ScoreNoteEvent> removeMergedSegueAliases(List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height || raw.length != gray.length)
            return notes;
        var discarded = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var caption : words) {
            if (caption.text() == null || !COMPACT_SEGUE_CAPTION.matcher(caption.text().strip()).matches()
                    || WHITESPACE.split(caption.text().strip()).length != 3) continue;
            int index = uniqueCaptionMeasure(
                    measures, caption, caption, (caption.top() + caption.bottom()) * .5f);
            if (index < 0) continue;
            var bar = measures.get(index);
            var row = notes.stream().filter(n -> n.measureIndex() == index).toList();
            if (row.size() != 15) continue;
            var aliases = row.stream().filter(n -> captionGlyphHead(n, true)
                    && n.staffStep() == -2).sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                    .toList();
            if (aliases.size() != 3) continue;
            var digit = aliases.get(0);
            var initial = aliases.get(1);
            var descender = aliases.get(2);
            if (digit.tiedFromPrevious() || digit.beamCount() != 3 || digit.unbeamedDurationBeats() != 0
                    || initial.tiedFromPrevious() || initial.beamCount() != 0 || initial.unbeamedDurationBeats() != 4
                    || descender.beamCount() != 0 || descender.unbeamedDurationBeats() != 4
                    || initial.clefBottomDiatonic() != descender.clefBottomDiatonic()
                    || initial.octaveShift() != descender.octaveShift()) continue;
            var music = row.stream().filter(n -> !aliases.contains(n))
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (music.stream().anyMatch(n -> n.stemDirection() != -1)) continue;
            for (var staff : staffs) {
                float g = staff.gap(), floor = staff.bottom(),
                        span = (caption.right() - caption.left()) * width;
                if (staff.index() != 0 || staff.count() != 1 || g < 10 || g > 25
                        || staff.top() < bar.top() * height || floor > bar.bottom() * height
                        || span < g * 8 || span > g * 12.2f
                        || caption.top() * height < floor - g * .65f
                        || caption.top() * height > floor + g * .82f
                        || caption.bottom() * height > floor + g * 3.4f) continue;
                var digitWords = words.stream().filter(word -> word.text() != null
                        && word.text().strip().matches("3\s+2")
                        && word.left() >= bar.left() && word.right() < caption.left()
                        && Math.abs(word.top() - caption.top()) * height < g * .6f
                        && ownsWordPosition(word, bar, digit, width, height)).toList();
                if (digitWords.size() != 1) continue;
                var fingers = digitWords.get(0);
                if (ownsMergedSegueGlyphs(gray, width, height, bar, staff, caption, fingers, aliases)
                        && ownsMergedSegueGlyphs(raw, width, height, bar, staff, caption, fingers, aliases)
                        && closedCaptionSixteenths(music, bar, staff, gray, width, height,
                                3.55f, false, 1.65f, 0, 2.85f, true)
                        && closedCaptionSixteenths(music, bar, staff, raw, width, height,
                                3.55f, false, 1.65f, 0, 2.85f, true)) {
                    discarded.addAll(aliases);
                    break;
                }
            }
        }
        return discarded.isEmpty() ? notes : notes.stream().filter(n -> !discarded.contains(n)).toList();
    }

    static List<ScoreNoteEvent> removeStandaloneSegueBeamAliases(List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height || raw.length != gray.length)
            return notes;
        var discarded = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var caption : words) {
            if (caption.text() == null || !"segue".equalsIgnoreCase(caption.text().strip())) continue;
            int index = uniqueCaptionMeasure(measures, caption, caption, (caption.top() + caption.bottom()) * .5f);
            if (index < 0) continue;
            var bar = measures.get(index);
            var row = notes.stream().filter(n -> n.measureIndex() == index)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (row.size() != 14) continue;
            var aliases = row.stream().filter(n -> captionGlyphHead(n, true, -2, -2)
                    && n.beamCount() == 3 && n.unbeamedDurationBeats() == 0
                    && ownsWordPosition(caption, bar, n, width, height)).toList();
            if (aliases.size() != 2) continue;
            var music = row.stream().filter(n -> !aliases.contains(n)).toList();
            if (music.stream().anyMatch(n -> n.stemDirection() != -1 || n.octaveShift() != 1)) continue;
            for (var staff : staffs) {
                float gap = staff.gap();
                float span = (caption.right() - caption.left()) * width;
                float top = caption.top() * height - staff.bottom();
                if (staff.count() != 1 || staff.top() < bar.top() * height || staff.bottom() > bar.bottom() * height
                        || gap < 12 || gap > 23 || span < 5.1f * gap || span > 7.6f * gap
                        || top < -.25f * gap || top > .69f * gap) continue;
                if (ownsDetachedSegueLetters(gray, width, height, staff, caption, bar, aliases)
                        && ownsDetachedSegueLetters(raw, width, height, staff, caption, bar, aliases)
                        && closedCaptionSixteenths(music, bar, staff, gray, width, height,
                                5.5f, true, 3.5f, 0, 2.2f, true, true)
                        && closedCaptionSixteenths(music, bar, staff, raw, width, height,
                                5.5f, true, 3.5f, 0, 2.2f, true, true)) {
                    discarded.addAll(aliases);
                    break;
                }
            }
        }
        return discarded.isEmpty() ? notes : notes.stream().filter(n -> !discarded.contains(n)).toList();
    }

    private static boolean ownsDetachedSegueLetters(byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word word, MeasureRegion bar,
            List<ScoreNoteEvent> aliases) {
        var glyphs = captionGlyphs(gray, width, height, staff, word);
        if (glyphs.size() != 5) return false;
        var distinct = new java.util.HashSet<RawNumeralComponents.Component>();
        for (var alias : aliases) {
            float x = fingeringHeadX(bar, alias, width), y = alias.pageY() * height;
            var matches = glyphs.stream().filter(glyph -> ownsTextCenter(glyph, x, y, width)).toList();
            if (matches.size() != 1 || !distinct.add(matches.get(0))) return false;
        }
        return true;
    }

    private static boolean ownsWordPosition(PlayingTechniqueDetector.Word word, MeasureRegion bar,
            ScoreNoteEvent owner, int width, int height) {
        float x = fingeringHeadX(bar, owner, width), y = owner.pageY() * height;
        return x >= word.left() * width && x <= word.right() * width
                && y >= word.top() * height && y <= word.bottom() * height;
    }

    private static boolean ownsMergedSegueGlyphs(byte[] gray, int width, int height, MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word caption,
            PlayingTechniqueDetector.Word fingers, List<ScoreNoteEvent> aliases) {
        var numbers = captionGlyphs(gray, width, height, staff, fingers);
        var glyphs = captionGlyphs(gray, width, height, staff, caption);
        if (numbers.size() != 2 || glyphs.size() != 7
                || !aliases.stream().allMatch(n -> insideInk(
                        n, bar, staff, List.of(caption, fingers), gray, width, height))) return false;
        float gap = staff.gap();
        if (glyphs.get(2).left - glyphs.get(1).right < gap * .68f
                || glyphs.get(2).left - glyphs.get(1).right > gap * 2.3f) return false;
        var owners = List.of(numbers.get(0), glyphs.get(2), glyphs.get(4));
        for (int i = 0; i < aliases.size(); i++) {
            var note = aliases.get(i);
            if (!ownsTextCenter(owners.get(i), fingeringHeadX(bar, note, width),
                    note.pageY() * height, width)) return false;
        }
        return true;
    }

    /** Two separate letters can seed stemless, falsely tied heads under a complete printed run. */
    private static List<ScoreNoteEvent> pairedSegueLetters(List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] gray, byte[] raw, int width, int height) {
        if (raw == null || raw.length != gray.length) return notes;
        var discarded = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var caption : words) {
            if (caption.text() == null || !COMPACT_SEGUE_CAPTION.matcher(caption.text().strip()).matches()
                    || WHITESPACE.split(caption.text().strip()).length != 2) continue;
            int index = uniqueCaptionMeasure(
                    measures, caption, caption, (caption.top() + caption.bottom()) * .5f);
            if (index < 0) continue;
            var bar = measures.get(index);
            var row = notes.stream().filter(n -> n.measureIndex() == index)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (row.size() != 14) continue;
            var aliases = row.stream().filter(n -> captionGlyphHead(n, true, -3, -3)).toList();
            if (aliases.size() != 2) continue;
            var initial = aliases.get(0);
            var descender = aliases.get(aliases.size() - 1);
            if (initial.tiedFromPrevious() || initial.beamCount() != 2
                    || !descender.tiedFromPrevious() || descender.beamCount() != 3
                    || initial.unbeamedDurationBeats() != 0 || descender.unbeamedDurationBeats() != 0
                    || initial.clefBottomDiatonic() != descender.clefBottomDiatonic()
                    || initial.octaveShift() != descender.octaveShift()) continue;
            var music = row.stream().filter(n -> !aliases.contains(n)).toList();
            if (music.stream().anyMatch(n -> n.stemDirection() != -1)) continue;
            for (var staff : staffs) {
                float g = staff.gap(), floor = staff.bottom();
                float span = (caption.right() - caption.left()) * width;
                if (staff.index() != 0 || staff.count() != 1 || g < 10 || g > 25
                        || staff.top() < bar.top() * height || floor > bar.bottom() * height
                        || span < g * 8 || span > g * 12.2f
                        || caption.top() * height < floor - g * .65f
                        || caption.top() * height > floor + g * .82f
                        || caption.bottom() * height > floor + g * 3.4f) continue;
                if (!printedSegueLetterPair(gray, width, height, bar, staff, caption, initial, descender)
                        || !printedSegueLetterPair(raw, width, height, bar, staff, caption, initial, descender))
                    continue;
                if (closedCaptionSixteenths(music, bar, staff, gray, width, height,
                            3.9f, true, .95f, 0, 3.1f, true, true, true)
                        && closedCaptionSixteenths(music, bar, staff, raw, width, height,
                            3.9f, true, .95f, 0, 3.1f, true, true, true)) {
                    discarded.addAll(aliases);
                    break;
                }
            }
        }
        return discarded.isEmpty() ? notes : notes.stream().filter(n -> !discarded.contains(n)).toList();
    }

    private static boolean printedSegueLetterPair(byte[] gray, int width, int height, MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word caption,
            ScoreNoteEvent initial, ScoreNoteEvent descender) {
        var glyphs = captionGlyphs(gray, width, height, staff, caption);
        if (glyphs.size() != 6) return false;
        float x = fingeringHeadX(bar, initial, width);
        float y = initial.pageY() * height;
        float space = glyphs.get(1).left - glyphs.get(0).right;
        return space >= staff.gap() * .68f && space <= staff.gap() * 2.3f
                && ownsTextCenter(glyphs.get(1), x, y, width)
                && ownsTextCenter(glyphs.get(3), fingeringHeadX(bar, descender, width),
                        descender.pageY() * height, width)
                && insideInk(initial, bar, staff, List.of(caption), gray, width, height)
                && insideInk(descender, bar, staff, List.of(caption), gray, width, height);
    }

    /** An italic initial alongside fingering digits can own a false head below the staff. */
    private static List<ScoreNoteEvent> compactSegueCaptions(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray, byte[] raw, int width, int height) {
        List<ScoreNoteEvent> output = null;
        for (var caption : words) {
            if (caption.text() == null || !COMPACT_SEGUE_CAPTION.matcher(caption.text().strip()).matches())
                continue;
            int digitCount = WHITESPACE.split(caption.text().strip()).length - 1;
            for (var alias : notes) {
                if (!captionGlyphHead(alias, false) || alias.staffStep() >= 0
                        || alias.measureIndex() < 0 || alias.measureIndex() >= measures.size()) continue;
                boolean beamAlias = alias.beamCount() == 3 && alias.unbeamedDurationBeats() == 0
                        && alias.staffStep() == -2 && digitCount == 2;
                boolean wholeAlias = alias.beamCount() == 0 && alias.unbeamedDurationBeats() == 4
                        && alias.staffStep() == -1 && digitCount == 1;
                boolean nearStaffCaption = alias.staffStep() == -2
                        && raw != null && raw.length == gray.length;
                boolean singleDigitBeamAlias = digitCount == 1 && (alias.staffStep() == -1 || nearStaffCaption)
                        && alias.beamCount() == 3 && alias.unbeamedDurationBeats() == 0;
                nearStaffCaption &= singleDigitBeamAlias;
                if (!beamAlias && !wholeAlias && !singleDigitBeamAlias) continue;
                var bar = measures.get(alias.measureIndex());
                float x = (bar.left() + (bar.right() - bar.left()) * alias.positionInMeasure()) * width;
                float y = alias.pageY() * height;
                if (x < caption.left() * width || x > caption.right() * width
                        || y < caption.top() * height || y > caption.bottom() * height) continue;
                var music = notes.stream().filter(n -> n.measureIndex() == alias.measureIndex() && n != alias)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                if (music.size() != 12 || music.stream().anyMatch(n -> n.stemDirection() != -1)) continue;
                List<ScoreNoteEvent> corrected;
                if (singleDigitBeamAlias || wholeAlias) {
                    corrected = straightCompactBeamGroups(music);
                    if (corrected.isEmpty()) continue;
                } else {
                    if (tripletRunStart(music) != -1) continue;
                    corrected = music;
                }
                for (var staff : staffs) {
                    float gap = staff.gap(), bottom = staff.bottom();
                    float left = caption.left() * width, right = caption.right() * width;
                    if (staff.index() != 0 || staff.count() != 1 || gap < 10 || gap > 25
                            || staff.top() < bar.top() * height || bottom > bar.bottom() * height
                            || left < bar.left() * width || right > bar.right() * width + gap * 3.1f
                            || right - left < gap * 8 || right - left > gap * 12.2f
                            || caption.top() * height < bottom - gap * .65f
                            || caption.top() * height > bottom + gap * .82f
                            || caption.bottom() * height > bottom + gap * 3.4f
                            || !closedCompactSegue(gray, width, height, bar, staff, caption, digitCount,
                                    alias, x, y, corrected, beamAlias, nearStaffCaption)
                            || (nearStaffCaption && !closedCompactSegue(raw, width, height, bar, staff,
                                    caption, digitCount, alias, x, y, corrected, false, true))) continue;
                    if (output == null) output = new ArrayList<>(notes);
                    for (int i = 0; i < music.size(); i++)
                        if (music.get(i) != corrected.get(i))
                            output.set(notes.indexOf(music.get(i)), corrected.get(i));
                    output.set(notes.indexOf(alias), null);
                    break;
                }
            }
        }
        return output == null ? notes : output.stream().filter(Objects::nonNull).toList();
    }

    /** Verify soft triplet interpretations fall within independently closed four-note beams. */
    private static List<ScoreNoteEvent> straightCompactBeamGroups(List<ScoreNoteEvent> notes) {
        var corrected = new ArrayList<>(notes);
        int runs = 0;
        for (int group = 0; group < notes.size(); group += 4) {
            int first = -1, count = 0;
            for (int i = group; i < group + 4; i++) {
                var note = notes.get(i);
                if (note.tupletDivisor() == 1 && note.tupletNormalNotes() == 1) continue;
                if (note.tupletDivisor() != 3 || note.tupletNormalNotes() != 2) return List.of();
                if (first < 0) first = i;
                else if (i != first + count) return List.of();
                count++;
            }
            if (count == 0) continue;
            if (count != 3 || ++runs > 2) return List.of();
            for (int i = first; i < first + 3; i++)
                corrected.set(i, notes.get(i).withTupletRatio(1, 1));
        }
        return corrected;
    }


    private static boolean closedCompactSegue(byte[] gray, int width, int height, MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word caption, int digitCount,
            ScoreNoteEvent alias, float x, float y, List<ScoreNoteEvent> notes, boolean beamAlias,
            boolean nearStaffCaption) {
        return insideInk(alias, bar, staff, List.of(caption), gray, width, height)
                && (ownsCompactSegueInitial(gray, width, height, staff, caption, digitCount, x, y, nearStaffCaption)
                        || nearStaffCaption && digitCount == 1
                                && ownsCompactSegueDescender(gray, width, height, staff, caption, x, y))
                && closedCaptionSixteenths(notes, bar, staff, gray, width, height, 3.9f, true, .95f,
                        beamAlias ? 1 : 0, 3.1f, true, nearStaffCaption);
    }

    private static boolean ownsCompactSegueInitial(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word caption, int digitCount, float x, float y, boolean nearStaffCaption) {
        float gap = staff.gap();
        var glyphs = captionGlyphs(gray, width, height, staff, caption, nearStaffCaption);
        if (glyphs.size() != digitCount + 5) return false;
        var initial = glyphs.get(digitCount);
        var lastDigit = glyphs.get(digitCount - 1);
        return initial.left - lastDigit.right >= gap * .68f
                && initial.left - lastDigit.right <= gap * 2.3f
                && ownsTextCenter(initial, x, y, width);
    }

    /** The upper bowl of an italic lower-case g can seed another head in the same caption. */
    private static boolean ownsCompactSegueDescender(byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word caption, float x, float y) {
        var glyphs = captionGlyphs(gray, width, height, staff, caption, true);
        if (glyphs.size() != 6) return false;
        float gap = staff.gap();
        var digit = glyphs.get(0);
        var initial = glyphs.get(1);
        var descender = glyphs.get(3);
        return initial.left - digit.right >= gap * .68f && initial.left - digit.right <= gap * 2.3f
                && descender.bottom - Math.max(glyphs.get(2).bottom, glyphs.get(4).bottom) >= gap * .24f
                && y <= descender.top + gap * .65f
                && ownsTextCenter(descender, x, y, width);
    }

    private static List<RawNumeralComponents.Component> captionGlyphs(byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word caption) {
        return captionGlyphs(gray, width, height, staff, caption, false);
    }

    private static List<RawNumeralComponents.Component> captionGlyphs(byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word caption, boolean nearStaffCaption) {
        var graph = captionComponents(gray, width, height, staff, caption, nearStaffCaption);
        if (graph == null) return List.of();
        float gap = staff.gap();
        return graph.components().stream().filter(c ->
                        c.size() >= gap * 3 && c.left > graph.left && c.right < graph.right
                                && c.top > graph.top && c.bottom < graph.bottom
                                && c.right - c.left >= gap * .45f && c.right - c.left <= gap * 2.15f
                                && c.bottom - c.top >= gap * .65f && c.bottom - c.top <= gap * 1.95f)
                .sorted(Comparator.comparingInt(c -> c.left)).toList();
    }

    /** Returns the one contiguous soft 3:2 run, no run, or an incompatible timing pattern. */
    private static int tripletRunStart(List<ScoreNoteEvent> notes) {
        int first = -1, count = 0;
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.tupletDivisor() == 1 && note.tupletNormalNotes() == 1) continue;
            if (note.tupletDivisor() != 3 || note.tupletNormalNotes() != 2) return -2;
            if (first < 0) first = i;
            if (i != first + count) return -2;
            count++;
        }
        return count == 0 || count == 3 ? first : -2;
    }

    private static boolean shortCaptionInitialInk(ScoreNoteEvent alias, MeasureRegion region,
            PlayingTechniqueDetector.Staff staff, PlayingTechniqueDetector.Word caption,
            byte[] gray, int width, int height) {
        if (!insideInk(alias, region, staff, List.of(caption), gray, width, height)) return false;
        float gap = staff.gap();
        int left = Math.round(caption.left() * width), right = Math.round(caption.right() * width);
        if (left < 0 || right > width || right - left < gap * 10 || right - left > gap * 17) return false;
        int top = Math.round(staff.bottom() + .22f * gap);
        int bottom = Math.round(Math.min(staff.bottom() + 1.8f * gap, caption.bottom() * height));
        if (top < 0 || bottom >= height || bottom <= top) return false;
        float x = (region.left() + alias.positionInMeasure() * (region.right() - region.left())) * width;
        int[] starts = new int[5], ends = new int[5];
        int count = 0;
        boolean run = false;
        for (int xx = left; xx < right; xx++) {
            int ink = 0;
            for (int yy = top; yy <= bottom; yy++) if ((gray[yy * width + xx] & 255) < 165) ink++;
            if (ink < 2) {
                run = false;
            } else {
                if (!run) {
                    starts[count++] = xx;
                    run = true;
                }
                ends[count - 1] = xx;
                if (count == starts.length) break;
            }
        }
        if (count != starts.length || x < starts[3] || x > ends[3]) return false;
        for (int i = 0; i < 4; i++) {
            float runWidth = ends[i] - starts[i] + 1;
            float clearance = starts[i + 1] - ends[i] - 1;
            if (runWidth < gap * .5f || runWidth > gap * 1.15f
                    || clearance < gap * (i < 3 ? .6f : .10f)
                    || clearance > gap * (i < 3 ? 2.3f : .8f)) return false;
        }
        return starts[3] - left >= gap * 6 && starts[3] - left <= gap * 10;
    }

    private static int uniqueCaptionMeasure(List<MeasureRegion> measures,
            PlayingTechniqueDetector.Word digits, PlayingTechniqueDetector.Word caption) {
        return uniqueCaptionMeasure(measures, digits, caption, caption.bottom());
    }

    private static int uniqueCaptionMeasure(List<MeasureRegion> measures,
            PlayingTechniqueDetector.Word digits, PlayingTechniqueDetector.Word caption, float baselineLimit) {
        int selected = -1;
        for (int i = 0; i < measures.size(); i++) {
            var m = measures.get(i);
            if (m.cadenceQuarterBeats() != 0 || digits.left() < m.left() || caption.right() > m.right()
                    || digits.top() < m.top() || baselineLimit > m.bottom()) continue;
            if (selected >= 0) return -1;
            selected = i;
        }
        return selected;
    }

    private static PlayingTechniqueDetector.Staff captionStaff(
            List<PlayingTechniqueDetector.Staff> staffs, PlayingTechniqueDetector.Word digits,
            PlayingTechniqueDetector.Word word, MeasureRegion region, int width, int height) {
        PlayingTechniqueDetector.Staff chosen = null;
        for (var staff : staffs) {
            float gap = staff.gap();
            if (staff.index() != 0 || staff.count() != 1 || staff.top() < region.top() * height
                    || staff.bottom() > region.bottom() * height || gap < 10 || gap > 25
                    || !insideStaffText(digits, region, staff, width, height)
                    || !insideStaffText(word, region, staff, width, height)
                    || (word.left() - digits.right()) * width < gap * .7f
                    || (word.left() - digits.right()) * width > gap * 2.7f
                    || (word.right() - word.left()) * width < gap * 5
                    || (word.right() - word.left()) * width > gap * 10
                    || Math.abs(word.top() - digits.top()) * height > gap * .75f
                    || Math.abs(word.bottom() - digits.bottom()) * height > gap * .85f) continue;
            if (chosen != null) return null;
            chosen = staff;
        }
        return chosen;
    }

    private static boolean captionHead(ScoreNoteEvent n) {
        return captionHead(n, false);
    }

    private static boolean captionHead(ScoreNoteEvent n, boolean allowTextTie) {
        return captionGlyphHead(n, allowTextTie)
                && (n.beamCount() >= 1 && n.beamCount() <= 2 && n.unbeamedDurationBeats() == 0
                        || n.beamCount() == 0 && n.unbeamedDurationBeats() == 4);
    }

    private static boolean captionGlyphHead(ScoreNoteEvent n, boolean allowTextTie) {
        return captionGlyphHead(n, allowTextTie, -2, 1);
    }

    private static boolean captionGlyphHead(
            ScoreNoteEvent n, boolean allowTextTie, int minStep, int maxStep) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffCount() == 1
                && n.staffIndex() == 0
                && n.stemDirection() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffStep() >= minStep
                && n.staffStep() <= maxStep
                && !n.crossStaffBeam()
                && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.augmentationDots() == 0
                && n.tupletDivisor() == 1
                && (n.articulations() & (NoteOrnament.GRACE | NoteOrnament.TYPE_MASK)) == 0
                && (allowTextTie || !n.tiedFromPrevious())
                && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0;
    }

    private static boolean closedCaptionSixteenths(List<ScoreNoteEvent> music, MeasureRegion region,
            PlayingTechniqueDetector.Staff staff, byte[] gray, int width, int height) {
        return closedCaptionSixteenths(music, region, staff, gray, width, height, 3.55f, false);
    }

    private static boolean closedCaptionSixteenths(
            List<ScoreNoteEvent> music,
            MeasureRegion region,
            PlayingTechniqueDetector.Staff staff,
            byte[] gray,
            int width,
            int height,
            float widestGap,
            boolean fineBeamSeam) {
        return closedCaptionSixteenths(music, region, staff, gray, width, height, widestGap,
                fineBeamSeam, 1.65f, 0, 2.6f, false);
    }

    private static boolean closedCaptionSixteenths(
            List<ScoreNoteEvent> music, MeasureRegion region, PlayingTechniqueDetector.Staff staff,
            byte[] gray, int width, int height, float widestGap, boolean fineBeamSeam,
            float openingGap, int missingInteriorRails, float closingGap, boolean useSourceClose) {
        return closedCaptionSixteenths(music, region, staff, gray, width, height, widestGap, fineBeamSeam,
                openingGap, missingInteriorRails, closingGap, useSourceClose, false);
    }

    private static boolean closedCaptionSixteenths(List<ScoreNoteEvent> music, MeasureRegion region,
            PlayingTechniqueDetector.Staff staff, byte[] gray, int width, int height,
            float widestGap, boolean fineBeamSeam, float openingGap, int missingInteriorRails,
            float closingGap, boolean useSourceClose, boolean useStandardBeamProof) {
        return closedCaptionSixteenths(music, region, staff, gray, width, height, widestGap, fineBeamSeam,
                openingGap, missingInteriorRails, closingGap, useSourceClose, useStandardBeamProof, false);
    }

    private static boolean closedCaptionSixteenths(List<ScoreNoteEvent> music, MeasureRegion region,
            PlayingTechniqueDetector.Staff staff, byte[] gray, int width, int height,
            float widestGap, boolean fineBeamSeam, float openingGap, int missingInteriorRails,
            float closingGap, boolean useSourceClose, boolean useStandardBeamProof, boolean allowFaintShafts) {
        float gap = staff.gap();
        float left = region.left() * width, right = region.right() * width;
        if (right - left < gap * 26 || right - left > gap * 39) return false;
        var shafts = new PrintedBeamShafts.Shaft[12];
        var budget = new PrintedBeamShafts.Budget(180000);
        float previous = left;
        for (int i = 0; i < shafts.length; i++) {
            var note = music.get(i);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1 || note.staffIndex() != 0
                    || note.beamCount() != 2 || note.unbeamedDurationBeats() != 0
                    || note.tupletDivisor() != 1 || note.augmentationDots() != 0 || note.crossStaffBeam()
                    || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) return false;
            float x = left + note.positionInMeasure() * (right - left);
            float y = note.pageY() * height;
            if (x - previous < gap * (i == 0 ? openingGap : 1.65f)
                    || x - previous > gap * widestGap) return false;
            previous = x;
            var source = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
            if (allowFaintShafts && source.isEmpty()) {
                var frame = new PrintedBeamShaftPixels.Frame(
                        gray, gray, width, height, 0, unused -> {}, true);
                source = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget, frame);
            }
            if (allowFaintShafts && i == 1 && source.isEmpty()) continue;
            if (source.size() != 1 || source.get(0).direction() != 1) return false;
            shafts[i] = source.get(0);
            if (allowFaintShafts && i == 2 && shafts[1] == null) {
                if (!fadedCaptionOpening(gray, width, height, region, music, shafts[0], shafts[2], gap))
                    return false;
            } else if (i > 0) {
                boolean rails = pairedRails(gray, width, height, shafts[i - 1], shafts[i], gap, true, fineBeamSeam)
                        || useStandardBeamProof && WrappedEighthTriplets.matchesBeamRails(
                                gray, width, height, shafts[i - 1], shafts[i], gap, 2);
                if (i % 4 == 0) {
                    if (rails) return false;
                } else if (!rails) {
                    if (missingInteriorRails == 0) return false;
                    missingInteriorRails--;
                }
            }
        }
        int boundary = PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, true, false);
        if (boundary < 0) return false;
        float space = (useSourceClose ? boundary : right) - previous;
        return space >= gap * .9f && space <= gap * closingGap
                && closedWrittenSixteenths(music);
    }

    private static boolean fadedCaptionOpening(byte[] gray, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, PrintedBeamShafts.Shaft first, PrintedBeamShafts.Shaft third, float gap) {
        if (!WrappedEighthTriplets.matchesBeamRails(gray, width, height, first, third, gap, 2)) return false;
        for (int i = 0; i < 2; i++) {
            var a = notes.get(i);
            var b = notes.get(i + 1);
            if (!PrintedTupletBeamOwner.connectedHeads(gray, width, height,
                    fingeringHeadX(bar, a, width), a.pageY() * height,
                    fingeringHeadX(bar, b, width), b.pageY() * height, gap, -1)) return false;
        }
        return true;
    }

    /** A dense fingering line can split into several OCR boxes above a separate segue. */
    private static List<ScoreNoteEvent> loweredSegueWithSplitFingerings(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        var discard = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        for (var caption : words) {
            if (caption.text() == null || !SINGLE_SEGUE.matcher(caption.text().strip()).matches())
                continue;
            int measure =
                    uniqueCaptionMeasure(
                            measures, caption, caption, (caption.top() + caption.bottom()) * .5f);
            if (measure < 0) continue;
            var bar = measures.get(measure);
            var row = notes.stream().filter(n -> n.measureIndex() == measure).toList();
            if (row.size() != 15) continue;
            var aliases = row.stream().filter(n -> captionHead(n, true)).toList();
            if (aliases.size() != 3) continue;
            var lower =
                    aliases.stream()
                            .filter(n -> n.beamCount() == 0 && n.staffStep() <= -1)
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                            .toList();
            var upper =
                    aliases.stream().filter(n -> n.beamCount() == 2 && n.staffStep() >= 0).toList();
            if (lower.size() != 2
                    || upper.size() != 1
                    || lower.get(0).tiedFromPrevious()
                    || upper.get(0).tiedFromPrevious()) continue;
            var first = lower.get(0);
            var last = lower.get(1);
            if (last.tiedFromPrevious()
                    && (first.staffStep() != last.staffStep()
                            || first.octaveShift() != last.octaveShift()
                            || first.clefBottomDiatonic() != last.clefBottomDiatonic())) continue;
            var music =
                    row.stream()
                            .filter(n -> !aliases.contains(n))
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                            .toList();
            for (var staff : staffs) {
                float gap = staff.gap(),
                        left = caption.left() * width,
                        right = caption.right() * width;
                if (staff.index() != 0
                        || staff.count() != 1
                        || gap < 10
                        || gap > 25
                        || staff.top() < bar.top() * height
                        || staff.bottom() > bar.bottom() * height
                        || caption.top() * height < staff.bottom() - gap * .12f
                        || caption.top() * height > staff.bottom() + gap * .5f
                        || caption.bottom() * height < staff.bottom() + gap * 1.7f
                        || caption.bottom() * height > staff.bottom() + gap * 2.8f
                        || right - left < gap * 5
                        || right - left > gap * 8
                        || !twoCaptionOwners(lower, bar, staff, caption, gray, width, height)
                        || !splitCaptionNumeral(
                                upper.get(0), bar, staff, caption, words, gray, width, height)
                        || !closedCaptionSixteenths(music, bar, staff, gray, width, height))
                    continue;
                discard.addAll(aliases);
                break;
            }
        }
        return discard.isEmpty()
                ? notes
                : notes.stream().filter(n -> !discard.contains(n)).toList();
    }

    private static boolean twoCaptionOwners(
            List<ScoreNoteEvent> aliases,
            MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word word,
            byte[] gray,
            int width,
            int height) {
        var graph = captionComponents(gray, width, height, staff, word);
        if (graph == null) return false;
        float gap = staff.gap();
        var glyphs =
                graph.components().stream()
                        .filter(
                                c ->
                                        c.size() >= gap * 2.1f
                                                && c.left > graph.left
                                                && c.right < graph.right
                                                && c.top > graph.top
                                                && c.bottom < graph.bottom
                                                && c.right - c.left >= gap * .45f
                                                && c.right - c.left <= gap * 3.4f
                                                && c.bottom - c.top >= gap * .65f
                                                && c.bottom - c.top <= gap * 1.9f)
                        .sorted(Comparator.comparingInt(c -> c.left))
                        .toList();
        if (glyphs.size() < 4
                || glyphs.size() > 5
                || !aliases.stream()
                        .allMatch(
                                n -> insideInk(n, bar, staff, List.of(word), gray, width, height)))
            return false;
        float start = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float first = start + span * aliases.get(0).positionInMeasure();
        float later = start + span * aliases.get(1).positionInMeasure();
        return ownsTextCenter(glyphs.get(0), first, aliases.get(0).pageY() * height, width)
                && glyphs.subList(1, glyphs.size() - 1).stream()
                                .filter(
                                        g ->
                                                ownsTextCenter(
                                                        g,
                                                        later,
                                                        aliases.get(1).pageY() * height,
                                                        width))
                                .count()
                        == 1;
    }

    private static boolean splitCaptionNumeral(
            ScoreNoteEvent alias,
            MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word caption,
            List<PlayingTechniqueDetector.Word> words,
            byte[] gray,
            int width,
            int height) {
        float gap = staff.gap(),
                x = (bar.left() + alias.positionInMeasure() * (bar.right() - bar.left())) * width,
                y = alias.pageY() * height;
        if (y < staff.bottom() - gap * .83f || y > staff.bottom() - gap * .12f) return false;
        for (var digit : words) {
            if (digit.text() == null
                    || !digit.text().strip().equals("3")
                    || (digit.right() - digit.left()) * width < gap * .7f
                    || (digit.right() - digit.left()) * width > gap * 2.2f
                    || !insideInk(alias, bar, staff, List.of(digit), gray, width, height)
                    || !ownsNumeralBowl(gray, width, height, digit, staff, x, y)) continue;
            float center = (digit.top() + digit.bottom()) * height * .5f;
            boolean left = false, toCaption = false;
            for (var word : words) {
                if (word.text() == null
                        || !word.text().strip().matches("[1-5](?:\\s*[1-5]){1,4}")
                        || word.left() < bar.left()
                        || word.right() > caption.left()
                        || Math.abs((word.top() + word.bottom()) * height * .5f - center)
                                > gap * .4f
                        || (word.bottom() - word.top()) * height < gap * 1.2f
                        || (word.bottom() - word.top()) * height > gap * 2.4f) continue;
                float before = (digit.left() - word.right()) * width;
                float after = (word.left() - digit.right()) * width;
                if (before > 0 && before < gap * 3) left = true;
                if (after > 0
                        && after < gap * 2
                        && (caption.left() - word.right()) * width <= gap * 1.1f) toCaption = true;
            }
            if (left && toCaption) return true;
        }
        return false;
    }

    private static boolean ownsNumeralBowl(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Word word,
            PlayingTechniqueDetector.Staff staff,
            float x,
            float y) {
        // The staff rule crosses the glyph above this bowl. Keep it out of the component graph.
        float gap = staff.gap();
        int left = Math.round(word.left() * width), right = Math.round(word.right() * width);
        int top = Math.round(staff.bottom() - gap * .78f),
                bottom = Math.round(word.bottom() * height);
        if (left < 0 || right >= width || top < 0 || bottom >= height || bottom <= top)
            return false;
        var graph =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 165, () -> {}, () -> {});
        return graph.components().stream()
                        .filter(
                                c ->
                                        c.left > left
                                                && c.right < right
                                                && c.bottom < bottom
                                                && c.size() >= gap * 2.1f
                                                && c.right - c.left >= gap * .35f
                                                && c.right - c.left <= gap * 1.1f
                                                && ownsTextCenter(c, x, y, width))
                        .count()
                == 1;
    }

    /** A split, slightly overlapping OCR caption can seed two digit bowls and one whole-note alias. */
    private static List<ScoreNoteEvent> overlappingFingeringCaptions(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        var digitRuns =
                words.stream()
                        .filter(
                                w ->
                                        w.text() != null
                                                && w.text()
                                                        .strip()
                                                        .matches("[1-5](?:\\s+[1-5]){5}"))
                        .toList();
        var captions =
                words.stream()
                        .filter(
                                w ->
                                        w.text() != null
                                                && SINGLE_SEGUE.matcher(w.text().strip()).matches())
                        .toList();
        if (digitRuns.isEmpty() || captions.isEmpty()) return notes;
        var discard = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        var replace = new IdentityHashMap<ScoreNoteEvent, ScoreNoteEvent>();
        for (var caption : captions)
            for (var digits : digitRuns) {
                int index = uniqueOverlapMeasure(measures, digits, caption, height);
                if (index < 0) continue;
                var bar = measures.get(index);
                var local = notes.stream().filter(n -> n.measureIndex() == index).toList();
                if (local.size() != 15) continue;
                var music =
                        local.stream()
                                .filter(n -> n.stemDirection() == -1)
                                .sorted(
                                        Comparator.comparingDouble(
                                                ScoreNoteEvent::positionInMeasure))
                                .toList();
                if (music.size() != 12) continue;
                var whole =
                        local.stream()
                                .filter(
                                        n ->
                                                possiblyPrintedText(n)
                                                        && n.staffStep() == -2
                                                        && n.beamCount() == 0
                                                        && n.unbeamedDurationBeats() == 4
                                                        && n.leadingRestBeats() == 0
                                                        && n.followingRestBeats() == 0)
                                .toList();
                var digitAliases =
                        local.stream()
                                .filter(
                                        n ->
                                                possiblyPrintedText(withoutCaptionDot(n))
                                                        && n.augmentationDots() <= 1
                                                        && n.staffStep() == -1
                                                        && n.beamCount() == 3
                                                        && n.unbeamedDurationBeats() == 0
                                                        && n.leadingRestBeats() == 0
                                                        && n.followingRestBeats() == 0)
                                .toList();
                if (whole.size() != 1 || digitAliases.size() != 2) continue;
                for (var staff : staffs) {
                    float gap = staff.gap(), left = bar.left() * width, right = bar.right() * width;
                    if (staff.index() != 0
                            || staff.count() != 1
                            || gap < 12
                            || gap > 24
                            || staff.top() < bar.top() * height
                            || staff.bottom() > bar.bottom() * height
                            || !insideStaffText(digits, bar, staff, width, height)
                            || (digits.right() - digits.left()) * width < gap * 12
                            || (digits.right() - digits.left()) * width > gap * 18
                            || (caption.right() - caption.left()) * width < gap * 5.5f
                            || (caption.right() - caption.left()) * width > gap * 8.5f
                            || (digits.right() - caption.left()) * width > gap * .25f
                            || Math.abs(caption.top() * height - staff.bottom()) > gap * .65f
                            || caption.bottom() * height - staff.bottom() > gap * 2.6f) continue;
                    var alias = whole.get(0);
                    float x = left + (right - left) * alias.positionInMeasure(),
                            y = height * alias.pageY();
                    if (y - staff.bottom() < gap * .5f
                            || y - staff.bottom() > gap * 1.2f
                            || !insideInk(alias, bar, staff, List.of(caption), gray, width, height)
                            || !proofCaptionInitial(gray, width, height, staff, caption, x, y))
                        continue;
                    if (!proofCaptionDigits(gray, width, height, staff, digits, bar, digitAliases))
                        continue;
                    var written = new ArrayList<ScoreNoteEvent>(music);
                    var first = music.get(0);
                    if (first.augmentationDots() != 0) {
                        if (first.augmentationDots() != 1
                                || !captionDoubleSharpInsteadOfDot(
                                        gray, width, height, bar, staff, first, music.get(1)))
                            continue;
                        written.set(0, withoutCaptionDot(first));
                    }
                    long ratios =
                            music.subList(8, 11).stream()
                                    .filter(
                                            n ->
                                                    n.tupletDivisor() == 3
                                                            && n.tupletNormalNotes() == 2)
                                    .count();
                    if (ratios != 0 && ratios != 3) continue;
                    if (ratios == 3)
                        for (int j = 8; j < 11; j++)
                            written.set(j, music.get(j).withTupletRatio(1, 1));
                    if (!closedCaptionSixteenths(
                            written, bar, staff, gray, width, height, 4.10f, false)) continue;
                    for (int j = 0; j < written.size(); j++)
                        if (written.get(j) != music.get(j))
                            replace.put(music.get(j), written.get(j));
                    discard.add(alias);
                    discard.addAll(digitAliases);
                    break;
                }
            }
        if (discard.isEmpty()) return notes;
        return notes.stream()
                .filter(n -> !discard.contains(n))
                .map(n -> replace.getOrDefault(n, n))
                .toList();
    }

    private static boolean captionDoubleSharpInsteadOfDot(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff,
            ScoreNoteEvent first,
            ScoreNoteEvent next) {
        float gap = staff.gap(),
                left = bar.left() * width,
                span = (bar.right() - bar.left()) * width;
        float x = left + span * first.positionInMeasure(),
                nextX = left + span * next.positionInMeasure();
        float y = first.pageY() * height, nextY = next.pageY() * height;
        if (LegacyTripletDotInk.singleDotCore(gray, width, height, x, y, nextX, gap)) return false;
        int radius = Math.round(gap * .48f);
        int from = Math.round(x + gap), to = Math.round(nextX - gap);
        int top = Math.round(nextY - gap * .22f), bottom = Math.round(nextY + gap * .22f);
        for (int xx = from; xx <= to; xx++)
            for (int yy = top; yy <= bottom; yy++)
                if (DoubleSharpGlyph.matchesRaw(
                        gray, width, height, xx - radius, yy - radius, xx + radius, yy + radius,
                        gap)) return true;
        return false;
    }

    private static ScoreNoteEvent withoutCaptionDot(ScoreNoteEvent n) {
        if (n.augmentationDots() == 0) return n;
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                0,
                n.beamCount(),
                n.writtenAccidental(),
                n.unbeamedDurationBeats(),
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
                n.stemDirection(),
                n.kind(),
                n.visualSize(),
                n.harmonicSemitones(),
                n.openStringTouch());
    }

    private static int uniqueOverlapMeasure(
            List<MeasureRegion> measures,
            PlayingTechniqueDetector.Word digits,
            PlayingTechniqueDetector.Word caption,
            int height) {
        if (!(caption.left() < digits.right() && caption.left() > digits.left())) return -1;
        int owner = -1;
        for (int i = 0; i < measures.size(); i++) {
            var m = measures.get(i);
            if (m.cadenceQuarterBeats() != 0
                    || digits.left() < m.left()
                    || caption.right() > m.right()
                    || Math.min(digits.top(), caption.top()) < m.top()
                    || Math.max(digits.bottom(), caption.bottom()) > m.bottom() + 3f / height)
                continue;
            if (owner >= 0) return -1;
            owner = i;
        }
        return owner;
    }

    private static boolean proofCaptionInitial(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word caption,
            float x,
            float y) {
        float gap = staff.gap();
        var graph = captionComponents(gray, width, height, staff, caption);
        if (graph == null) return false;
        var glyphs =
                graph.components().stream()
                        .filter(
                                c ->
                                        c.top > graph.top
                                                && c.bottom < graph.bottom
                                                && c.left > graph.left
                                                && c.right < graph.right
                                                && c.size() >= gap * 2.1f
                                                && c.bottom - c.top >= gap * .65f
                                                && c.bottom - c.top <= gap * 1.75f
                                                && c.right - c.left >= gap * .5f
                                                && c.right - c.left <= gap * 1.5f)
                        .toList();
        return glyphs.size() >= 4
                && ownsTextCenter(glyphs.get(0), x, y, width)
                && glyphs.stream()
                                .skip(1)
                                .filter(
                                        c ->
                                                c.left > x
                                                        && Math.abs(c.top - glyphs.get(0).top)
                                                                <= gap * .3f)
                                .count()
                        >= 3;
    }

    private static boolean proofCaptionDigits(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word digits,
            MeasureRegion bar,
            List<ScoreNoteEvent> aliases) {
        var graph = captionComponents(gray, width, height, staff, digits);
        if (graph == null) return false;
        float gap = staff.gap(),
                left = bar.left() * width,
                span = (bar.right() - bar.left()) * width;
        var glyphs =
                graph.components().stream()
                        .filter(
                                c ->
                                        c.size() >= gap * 2.1f
                                                && c.left > graph.left
                                                && c.right < graph.right
                                                && c.right - c.left >= gap * .35f
                                                && c.right - c.left <= gap * 1.0f
                                                && c.bottom - c.top >= gap * .28f
                                                && c.bottom - c.top <= gap * .8f)
                        .toList();
        if (glyphs.size() != 6) return false;
        var sorted =
                aliases.stream()
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        return ownsTextCenter(
                        glyphs.get(0),
                        left + span * sorted.get(0).positionInMeasure(),
                        sorted.get(0).pageY() * height,
                        width)
                && ownsTextCenter(
                        glyphs.get(3),
                        left + span * sorted.get(1).positionInMeasure(),
                        sorted.get(1).pageY() * height,
                        width)
                && sorted.stream()
                        .allMatch(
                                n ->
                                        insideInk(
                                                n,
                                                bar,
                                                staff,
                                                List.of(digits),
                                                gray,
                                                width,
                                                height));
    }

    private static RawNumeralComponents captionComponents(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word word) {
        return captionComponents(gray, width, height, staff, word, false);
    }

    private static RawNumeralComponents captionComponents(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word word, boolean nearStaffCaption) {
        int left = Math.round(word.left() * width) - 2,
                right = Math.round(word.right() * width) + 2;
        int firstPaperRow = nearStaffCaption ? (int) Math.floor(staff.bottom()) + 1
                : Math.round(staff.bottom() + staff.gap() * .14f);
        int top = Math.max(Math.round(word.top() * height) - 2, firstPaperRow);
        int bottom = Math.round(word.bottom() * height) + 2;
        if (left < 0
                || right >= width
                || top < 0
                || bottom >= height
                || left >= right
                || top >= bottom
                || (long) (right - left + 1) * (bottom - top + 1) > 16384) return null;
        return RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, () -> {}, () -> {});
    }

    private static boolean ownsTextCenter(
            RawNumeralComponents.Component glyph, float x, float y, int width) {
        if (!(x >= glyph.left && x <= glyph.right && y >= glyph.top && y <= glyph.bottom))
            return false;
        for (int j = 0; j < glyph.size(); j++) {
            int pixel = glyph.pixel(j);
            if (Math.abs(pixel % width - x) <= 2 && Math.abs(pixel / width - y) <= 2) return true;
        }
        return false;
    }

    /** Two aligned fingering threes can create stemless heads below a complete sixteenth-note bar. */
    static List<ScoreNoteEvent> removePairedStaffFingeringAliases(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes,
            byte[] gray, byte[] raw, int width, int height) {
        if (raw == null || raw.length != gray.length) return notes;
        var openings = words.stream().filter(w -> "(3".equals(w.text())).toList();
        var closings = words.stream().filter(w -> "3)".equals(w.text())).toList();
        if (openings.isEmpty() || closings.isEmpty()) return notes;
        var rejected = new HashSet<ScoreNoteEvent>();
        for (int measure = 0; measure < measures.size(); measure++) {
            int index = measure;
            var region = measures.get(measure);
            var local = notes.stream().filter(n -> n.measureIndex() == index)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if (local.size() != 18) continue;
            var real = local.stream().filter(n -> !possiblyPrintedText(n)).toList();
            if (real.size() != 16) continue;
            var aliases = local.stream().filter(FingeringAnnotationFilter::possiblyPrintedText).toList();
            if (aliases.stream().anyMatch(n -> n.staffStep() != -1 || n.leadingRestBeats() != 0
                    || n.followingRestBeats() != 0 || n.octaveShift() != 0 || n.harmonicSemitones() != 0
                    || n.openStringTouch() != null || n.crossStaffBeam()
                    || !(n.beamCount() == 1 && n.unbeamedDurationBeats() == 0
                            || n.beamCount() == 0 && n.unbeamedDurationBeats() == 4))) continue;
            float left = region.left() * width, span = (region.right() - region.left()) * width;
            for (var staff : staffs) {
                float gap = staff.gap();
                if (staff.count() != 1 || staff.index() != 0 || gap < 10 || gap > 24
                        || staff.top() < region.top() * height
                        || staff.bottom() > region.bottom() * height) continue;
                var pair = parenthesizedBoxes(openings, closings, region, staff, width, height);
                if (pair.isEmpty()) continue;
                if (pairedStaffFingeringProved(gray, width, height, region, staff, pair, aliases, real, left, span)
                        && pairedStaffFingeringProved(raw, width, height, region, staff, pair, aliases, real, left, span)) {
                    rejected.addAll(aliases);
                    break;
                }
            }
        }
        return rejected.isEmpty() ? notes : notes.stream().filter(n -> !rejected.contains(n)).toList();
    }

    private static boolean pairedStaffFingeringProved(
            byte[] plane, int width, int height, MeasureRegion region,
            PlayingTechniqueDetector.Staff staff, List<PlayingTechniqueDetector.Word> pair,
            List<ScoreNoteEvent> aliases, List<ScoreNoteEvent> real, float left, float span) {
        float gap = staff.gap(), floor = staff.bottom();
        for (int i = 0; i < aliases.size(); i++) {
            var alias = aliases.get(i);
            var owner = real.get(i == 0 ? 5 : 11);
            float x = left + span * alias.positionInMeasure(), y = alias.pageY() * height;
            if (y < floor + gap * .28f || y > floor + gap * .78f
                    || y - owner.pageY() * height < gap * 6
                    || Math.abs(span * (alias.positionInMeasure() - owner.positionInMeasure())) > gap * .38f
                    || !insideInk(alias, region, staff, pair.subList(i, i + 1), plane, width, height)
                    || !pairedStaffFingeringBowl(plane, width, height, x, floor, gap)) return false;
            var budget = new PrintedBeamShafts.Budget(20_000);
            if (!PrintedBeamShafts.at(plane, width, height, x, y, gap, budget).isEmpty() || budget.refused())
                return false;
        }
        return !printedSixteenthLine(real, pair.get(1), left, span, staff, plane, width, height, true, 11).isEmpty();
    }

    private static boolean pairedStaffFingeringBowl(
            byte[] plane, int width, int height, float x, float floor, float gap) {
        int reach = Math.max(1, Math.round(gap * .13f));
        for (int dy = -reach; dy <= reach; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                if (hasFingerThreeBowl(plane, width, height, x + dx, floor + dy, gap)) return true;
            }
        }
        return false;
    }


    /** Reject a simulated head only when matched fingering glyphs and the real beam owners agree. */
    static List<ScoreNoteEvent> removePairedFingeringAliases(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, byte[] raw,
            int width, int height) {
        if (gray == null || raw == null || gray.length != raw.length
                || gray.length != (long) width * height) return notes;
        var opening = words.stream().filter(w -> w.text() != null && w.text().matches("\\([1-5]")).toList();
        if (opening.isEmpty()) return notes;
        var closing = words.stream().filter(w -> w.text() != null && w.text().matches("[1-5]\\)")).toList();
        if (closing.isEmpty()) return notes;
        var rejected = new HashSet<ScoreNoteEvent>();
        for (var alias : notes) {
            int measure = alias.measureIndex();
            if (measure < 0 || measure >= measures.size() || !captionGlyphHead(alias, false, -3, -3)
                    || alias.beamCount() != 2 || alias.unbeamedDurationBeats() != 0) continue;
            var bar = measures.get(measure);
            float x = fingeringHeadX(bar, alias, width), y = alias.pageY() * height;
            for (var open : opening) {
                if (x < open.left() * width || x > open.right() * width
                        || y < open.top() * height || y > open.bottom() * height) continue;
                for (var close : closing) {
                    if (open.text().charAt(1) != close.text().charAt(0)
                            || close.left() <= open.right() || close.right() > bar.right()) continue;
                    for (var staff : staffs) {
                        float gap = staff.gap(), floor = staff.bottom();
                        if (staff.count() != 1 || staff.index() != 0 || gap < 10 || gap > 25
                                || y < floor + gap || y > floor + gap * 1.85f
                                || Math.abs(open.top() - close.top()) * height > gap * .30f
                                || (close.left() - open.right()) * width < gap * 7
                                || (close.left() - open.right()) * width > gap * 13) continue;
                        var line = notes.stream().filter(n -> n.measureIndex() == measure && n != alias)
                                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                        if (line.size() != 16 || line.stream().anyMatch(n -> n.kind() != ScoreNoteEvent.Kind.PITCHED
                                || n.beamCount() != 2 || n.stemDirection() != -1 || n.crossStaffBeam())
                                || !closedWrittenSixteenths(line)) continue;
                        float closeX = (close.left() + close.right()) * width * .5f;
                        for (int slot = 0; slot + 6 < line.size(); slot++) {
                            var first = line.get(slot);
                            var last = line.get(slot + 6);
                            if (Math.abs(fingeringHeadX(bar, first, width) - x) > gap * .50f
                                    || Math.abs(fingeringHeadX(bar, last, width) - closeX) > gap * .50f
                                    || first.pageY() * height >= floor - gap
                                    || last.pageY() * height >= floor - gap) continue;
                            if (pairedFingeringProof(gray, width, height, floor, gap, open, close, x, y)
                                    && pairedFingeringProof(raw, width, height, floor, gap, open, close, x, y))
                                rejected.add(alias);
                        }
                    }
                }
            }
        }
        return rejected.isEmpty() ? notes : notes.stream().filter(n -> !rejected.contains(n)).toList();
    }

    private static boolean pairedFingeringProof(byte[] gray, int width, int height, float floor,
            float gap, PlayingTechniqueDetector.Word open, PlayingTechniqueDetector.Word close, float x, float y) {
        var a = parenthesizedDigit(gray, width, height, open, floor, gap, true, true, 0);
        var b = parenthesizedDigit(gray, width, height, close, floor, gap, false, true, 0);
        if (a == null || b == null || !ownsTextCenter(a, x, y, width)
                || !sameDetachedDigit(gray, width, a, b)
                || printedLedgerAcrossWord(gray, width, height, open.left() * width, open.right() * width, y, gap)
                || printedStemBeyondWord(gray, width, height, x, y, open.top() * height, open.bottom() * height, gap)) return false;
        var budget = new PrintedBeamShafts.Budget(12_000);
        return PrintedBeamShafts.at(gray, width, height, x, y, gap, budget).isEmpty() && !budget.refused();
    }

    /** A printing of matching parenthesized fingerings can seed a stemless beam alias. */
    static List<ScoreNoteEvent> removeDetachedParentheticalAliases(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != raw.length
                || gray.length != (long) width * height) return notes;
        var openings = words.stream().filter(w -> "(3".equals(w.text())).toList();
        var staffTouchingOpenings = words.stream().filter(w -> "(3".equals(w.text()) || "(8".equals(w.text())).toList();
        if (staffTouchingOpenings.isEmpty()) return notes;
        var closings = words.stream().filter(w -> opticalParenthesisCandidate(w.text())).toList();
        if (closings.isEmpty()) return notes;
        var rejected = new HashSet<ScoreNoteEvent>();
        for (var alias : notes) {
            int measure = alias.measureIndex();
            if (measure < 0 || measure >= measures.size() || !captionGlyphHead(alias, false, -3, -2)
                    || (alias.beamCount() != 2 && alias.beamCount() != 3)
                    || alias.unbeamedDurationBeats() != 0) continue;
            boolean latterHalf = alias.beamCount() == 2;
            int firstOwner = latterHalf ? 9 : 5, lastOwner = latterHalf ? 15 : 11;
            var bar = measures.get(measure);
            float x = fingeringHeadX(bar, alias, width), y = alias.pageY() * height;
            for (var staff : staffs) {
                float gap = staff.gap(), floor = staff.bottom();
                if (staff.count() != 1 || staff.index() != 0 || gap < 10 || gap > 25
                        || staff.top() < bar.top() * height || floor > bar.bottom() * height
                        || y < floor + gap || y > floor + gap * 1.8f) continue;
                for (var opening : staffTouchingOpenings) {
                    if (!staffTouchingPairBox(opening, bar, floor, gap, width, height)
                            || x < opening.left() * width || x > opening.right() * width
                            || y < opening.top() * height || y > opening.bottom() * height) continue;
                    var matches = closings.stream().filter(c -> "3)".equals(c.text())
                                    && staffTouchingPairBox(c, bar, floor, gap, width, height)
                                    && (c.left() - opening.right()) * width >= gap * 7
                                    && (c.left() - opening.right()) * width <= gap * 13).toList();
                    if (matches.size() != 1) continue;
                    var actual = notes.stream().filter(n -> n.measureIndex() == measure && n != alias)
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                    if (actual.size() == 16
                            && provedStaffTouchingFingerPair(gray, width, height, staff, measures,
                                    measure, actual, opening, matches.get(0), x, y)
                            && provedStaffTouchingFingerPair(raw, width, height, staff, measures,
                                    measure, actual, opening, matches.get(0), x, y))
                        rejected.add(alias);
                }
                if (alias.staffStep() != -3) continue;
                for (var opening : openings) {
                    if (!detachedParenthesisBox(opening, bar, floor, gap, width, height)
                            || x < opening.left() * width || x > opening.right() * width
                            || y < opening.top() * height || y > opening.bottom() * height) continue;
                    var mirrored = closings.stream()
                            .filter(closing -> (latterHalf || "3)".equals(closing.text()))
                                    && detachedParenthesisBox(closing, bar, latterHalf ? floor - 1f : floor,
                                            gap, width, height)
                                    && (closing.left() - opening.right()) * width >= gap * 7
                                    && (closing.left() - opening.right()) * width <= gap * 13
                                    && Math.abs(closing.top() - opening.top()) * height <= gap * .24f).toList();
                    if (mirrored.size() != 1) continue;
                    var closing = mirrored.get(0);
                    var actual = notes.stream().filter(n -> n.measureIndex() == measure && n != alias)
                            .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                    if (actual.size() != 16) continue;
                    if (detachedParenthesesProve(gray, width, height, staff, measures, measure, actual,
                            opening, closing, x, y, firstOwner, lastOwner)
                            && detachedParenthesesProve(raw, width, height, staff, measures, measure, actual,
                            opening, closing, x, y, firstOwner, lastOwner))
                        rejected.add(alias);
                }
            }
        }
        return rejected.isEmpty() ? notes : notes.stream().filter(n -> !rejected.contains(n)).toList();
    }

    /** A line-clipped parenthesis may make OCR read a detached 3 as 8. */
    private static boolean provedStaffTouchingFingerPair(
            byte[] ink, int width, int height, PlayingTechniqueDetector.Staff staff,
            List<MeasureRegion> measures, int measure, List<ScoreNoteEvent> actual,
            PlayingTechniqueDetector.Word opening, PlayingTechniqueDetector.Word closing,
            float x, float y) {
        var bar = measures.get(measure);
        float gap = staff.gap();
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink, width, height, bar);
        if (track == null || !track.verifiedAt(x)) return false;
        float floor = track.at(x)[0];
        if (Math.abs(floor - staff.bottom()) > .30f * gap) return false;
        var left = parenthesizedDigit(ink, width, height, opening, floor, gap, true, false, .17f, true);
        float closeX = (closing.left() + closing.right()) * width * .5f;
        var right = parenthesizedDigit(ink, width, height, closing, track.at(closeX)[0],
                gap, false, false, .17f, true);
        if (left == null || right == null || !ownsTextCenter(left, x, y, width)
                || !sameDetachedDigit(ink, width, left, right)
                || printedLedgerAcrossWord(ink, width, height,
                        opening.left() * width, opening.right() * width, y, gap)) return false;
        var budget = new PrintedBeamShafts.Budget(60_000);
        if (!PrintedBeamShafts.at(ink, width, height, x, y, gap, budget).isEmpty()
                || budget.refused()) return false;
        if (Math.abs(fingeringHeadX(bar, actual.get(5), width) - x) > gap * .38f
                || Math.abs(fingeringHeadX(bar, actual.get(11), width) - closeX) > gap * .55f)
            return false;
        float last = fingeringHeadX(bar, actual.get(15), width);
        return WrappedMeasureBoundaries.confirmsSampledClosingEdge(ink, width, height, bar, last)
                && (MixedUpperLedgerBeamSource.provesUpperSixteenthLine(ink, width, height, track, bar, actual)
                        || "(3".equals(opening.text()) && !printedSixteenthLine(actual, closing,
                                bar.left() * width, (bar.right() - bar.left()) * width, staff,
                                ink, width, height, true, 11).isEmpty());
    }

    private static boolean staffTouchingPairBox(PlayingTechniqueDetector.Word word,
            MeasureRegion bar, float floor, float gap, int width, int height) {
        float w = (word.right() - word.left()) * width;
        float top = word.top() * height - floor;
        float bottom = word.bottom() * height - floor;
        return word.left() >= bar.left() && word.right() <= bar.right()
                && w >= 1.9f * gap && w <= 3.15f * gap
                && top >= -.45f * gap && top <= .23f * gap
                && bottom >= 1.65f * gap && bottom <= 2.7f * gap;
    }

    private static boolean opticalParenthesisCandidate(String text) {
        return "3)".equals(text) || text != null && text.length() == 2
                && text.charAt(0) == '(' && Character.isDigit(text.charAt(1));
    }

    private static boolean detachedParenthesisBox(PlayingTechniqueDetector.Word word,
            MeasureRegion measure, float floor, float gap, int width, int height) {
        float left = word.left() * width, right = word.right() * width;
        float top = word.top() * height, bottom = word.bottom() * height;
        return left >= measure.left() * width && right <= measure.right() * width
                && right - left >= gap * 2.15f && right - left <= gap * 3.15f
                && top >= floor - gap * .20f && top <= floor + gap * .30f
                && bottom >= floor + gap * 1.85f && Math.round(bottom) <= Math.round(floor + gap * 2.70f) + 1;
    }

    private static boolean detachedParenthesesProve(byte[] plane, int width, int height,
            PlayingTechniqueDetector.Staff staff, List<MeasureRegion> measures, int index,
            List<ScoreNoteEvent> actual, PlayingTechniqueDetector.Word opening,
            PlayingTechniqueDetector.Word closing, float x, float y, int firstOwner, int lastOwner) {
        var bar = measures.get(index);
        float gap = staff.gap(), left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(plane, width, height, bar);
        if (track == null || !track.verifiedAt(x)) return false;
        var frame = track.at(x);
        if (Math.abs(frame[0] - staff.bottom()) > gap * .22f) return false;
        var a = parenthesizedDigit(plane, width, height, opening, frame[0], gap, true);
        var b = lastOwner == 15
                ? parenthesizedDigit(plane, width, height, closing, frame[0], gap, false, true)
                : parenthesizedDigit(plane, width, height, closing, frame[0], gap, false);
        if (a == null || b == null || !ownsTextCenter(a, x, y, width)
                || !sameDetachedDigit(plane, width, a, b)
                || printedLedgerAcrossWord(plane, width, height,
                        opening.left() * width, opening.right() * width, y, gap)) return false;
        var budget = new PrintedBeamShafts.Budget(60_000);
        if (!PrintedBeamShafts.at(plane, width, height, x, y, gap, budget).isEmpty()
                || budget.refused()) return false;
        float first = fingeringHeadX(bar, actual.get(0), width);
        float last = fingeringHeadX(bar, actual.get(15), width);
        if (Math.abs(fingeringHeadX(bar, actual.get(firstOwner), width) - x) > gap * .38f)
            return false;
        boolean separateSystem = lastOwner == 15 && index > 0
                && (bar.top() - measures.get(index - 1).bottom()) * height > gap * 3;
        if (!WrappedMeasureBoundaries.confirmsInitialStaffBar(plane, width, height, measures, index, first, last)
                && !(separateSystem && WrappedMeasureBoundaries.confirmsInitialStaffBarClosingEdge(
                        plane, width, height, bar, last))) return false;
        return !printedSixteenthLine(actual, closing, left, span, staff, plane, width, height, true, lastOwner).isEmpty();
    }

    private static RawNumeralComponents.Component parenthesizedDigit(
            byte[] plane, int width, int height, PlayingTechniqueDetector.Word word,
            float floor, float gap, boolean opening) {
        return parenthesizedDigit(plane, width, height, word, floor, gap, opening, false);
    }

    private static RawNumeralComponents.Component parenthesizedDigit(
            byte[] plane, int width, int height, PlayingTechniqueDetector.Word word,
            float floor, float gap, boolean opening, boolean closeBesideStaff) {
        return parenthesizedDigit(plane, width, height, word, floor, gap, opening, closeBesideStaff,
                closeBesideStaff ? .14f : .20f);
    }

    private static RawNumeralComponents.Component parenthesizedDigit(
            byte[] plane, int width, int height, PlayingTechniqueDetector.Word word,
            float floor, float gap, boolean opening, boolean closeBesideStaff, float clearance) {
        return parenthesizedDigit(plane, width, height, word, floor, gap, opening,
                closeBesideStaff, clearance, false);
    }

    private static RawNumeralComponents.Component parenthesizedDigit(
            byte[] plane, int width, int height, PlayingTechniqueDetector.Word word,
            float floor, float gap, boolean opening, boolean closeBesideStaff, float clearance,
            boolean staffTouching) {
        int left = Math.round(word.left() * width), right = Math.round(word.right() * width);
        int top = Math.max(Math.round(word.top() * height), Math.round(floor + gap * clearance));
        int bottom = Math.round(word.bottom() * height);
        if (left < 0 || right >= width || top < 0 || bottom >= height || left >= right || top >= bottom)
            return null;
        if (clearance == 0) {
            while (top < bottom && top <= floor + gap * .3f) {
                int ink = 0;
                for (int x = left; x <= right; x++) if ((plane[top * width + x] & 255) < 165) ink++;
                if (ink < (right - left + 1) * .60f) break;
                top++;
            }
            if (top >= bottom) return null;
        }
        var glyphs = RawNumeralComponents.capture(plane, width, height, left, top, right, bottom,
                        165, () -> {}, () -> {}).components().stream()
                .filter(c -> c.size() >= gap * gap * .11f)
                .sorted(Comparator.comparingInt(c -> c.left)).toList();
        if (glyphs.size() != 2) return null;
        var digit = glyphs.get(opening ? 1 : 0);
        var parenthesis = glyphs.get(opening ? 0 : 1);
        int separation = opening ? digit.left - parenthesis.right : parenthesis.left - digit.right;
        if (digit.left <= left || digit.right >= right || digit.top <= top || digit.bottom >= bottom
                || parenthesis.left <= left || parenthesis.right >= right
                || !staffTouching && parenthesis.top <= top
                || parenthesis.bottom >= bottom || separation < gap * (staffTouching ? -.13f : closeBesideStaff ? 0 : .11f) || separation > gap * .42f
                || digit.right - digit.left + 1 < gap * .65f || digit.right - digit.left + 1 > gap
                || digit.bottom - digit.top + 1 < gap || digit.bottom - digit.top + 1 > gap * 1.33f
                || parenthesis.right - parenthesis.left + 1 < gap * .40f
                || parenthesis.right - parenthesis.left + 1 > gap * .76f
                || parenthesis.bottom - parenthesis.top + 1 < gap * (staffTouching ? 1.14f : 1.40f)
                || parenthesis.bottom - parenthesis.top + 1 > gap * 1.91f) return null;
        int center = 0, end = 0, centerCount = 0, endCount = 0;
        int tall = parenthesis.bottom - parenthesis.top + 1;
        for (int i = 0; i < parenthesis.size(); i++) {
            int pixel = parenthesis.pixel(i), yy = pixel / width - parenthesis.top;
            if (yy < tall * .18f || yy > tall * .83f) {
                end += pixel % width;
                endCount++;
            } else if (yy >= tall * .36f && yy <= tall * .65f) {
                center += pixel % width;
                centerCount++;
            }
        }
        return centerCount > 0 && endCount > 0 && (opening ? 1 : -1)
                * (end / (float) endCount - center / (float) centerCount) >= gap * .15f ? digit : null;
    }

    private static boolean sameDetachedDigit(byte[] plane, int width,
            RawNumeralComponents.Component a, RawNumeralComponents.Component b) {
        int same = 0, covered = 0;
        for (int y = 0; y < 12; y++)
            for (int x = 0; x < 12; x++) {
                boolean first = sampledDigitInk(plane, width, a, x, y);
                boolean second = sampledDigitInk(plane, width, b, x, y);
                if (first || second) covered++;
                if (first && second) same++;
            }
        return covered > 0 && same >= covered * .64f;
    }

    private static boolean sampledDigitInk(byte[] plane, int width,
            RawNumeralComponents.Component digit, int col, int row) {
        int x = digit.left + (int) ((col + .5f) * (digit.right - digit.left + 1) / 12);
        int y = digit.top + (int) ((row + .5f) * (digit.bottom - digit.top + 1) / 12);
        return (plane[y * width + x] & 255) < 165;
    }

    /** Paired below-staff fingerings can seed both a false pitch and a false triplet. */
    private static List<ScoreNoteEvent> isolatedParentheticalSixteenths(
            List<PlayingTechniqueDetector.Word> words,List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> regions,List<ScoreNoteEvent> notes,byte[] gray,int width,int height) {
        var opening=words.stream().filter(w->w.text()!=null && w.text().strip().equals("(3")).toList();
        var closing=words.stream().filter(w->w.text()!=null && w.text().strip().equals("3)")).toList();
        if(opening.isEmpty() || closing.isEmpty()) return notes;
        List<ScoreNoteEvent> output=null;
        for (int index=0;index<notes.size();index++) {
            var falseHead=notes.get(index);
            if (!possiblyPrintedText(falseHead) || falseHead.beamCount()!=0
                    || falseHead.unbeamedDurationBeats()!=4 || falseHead.staffStep()!=-1
                    || falseHead.leadingRestBeats()!=0 || falseHead.followingRestBeats()!=0)
                continue;
            int measure=falseHead.measureIndex();
            if (measure<0 || measure>=regions.size()) continue;
            var bar=regions.get(measure);
            float left=bar.left()*width, right=bar.right()*width, span=right-left;
            float falseX=left+span*falseHead.positionInMeasure(),falseY=falseHead.pageY()*height;
            var real=notes.stream().filter(n->n.measureIndex()==measure && n!=falseHead)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            if(real.size()!=16) continue;
            for (var staff:staffs) {
                if (staff.count()!=1 || staff.index()!=0 || staff.top()<bar.top()*height
                        || staff.bottom()>bar.bottom()*height || staff.gap()<10 || staff.gap()>24)
                    continue;
                float gap=staff.gap();
                if (falseY<staff.bottom()+gap*.32f || falseY>staff.bottom()+gap*.9f) continue;
                var pair=parenthesizedBoxes(opening,closing,bar,staff,width,height);
                if (pair.isEmpty() || !insideInk(falseHead,bar,staff,pair.subList(0,1),gray,width,height))
                    continue;
                float first=left+span*real.get(0).positionInMeasure(),last=left+span*real.get(15).positionInMeasure();
                if (!WrappedMeasureBoundaries.confirmsInitialStaffBar(
                        gray,width,height,regions,measure,first,last)) continue;
                var replacement=printedSixteenthLine(real,pair.get(1),left,span,staff,gray,width,height,false,11);
                if(replacement.isEmpty()) continue;
                if(output==null) output=new ArrayList<>(notes);
                for (int j=9;j<=11;j++) {
                    int owner=notes.indexOf(real.get(j));
                    output.set(owner,replacement.get(j));
                }
                output.set(index,null);
                break;
            }
        }
        return output==null?notes:output.stream().filter(Objects::nonNull).toList();
    }

    private static List<ScoreNoteEvent> printedSixteenthLine(
            List<ScoreNoteEvent> notes,PlayingTechniqueDetector.Word closing,float left,float span,
            PlayingTechniqueDetector.Staff staff,byte[] gray,int width,int height,boolean straight,int closingOwner) {

        return printedSixteenthLine(notes, closing, left, span, staff, gray, width, height, straight, closingOwner, false);
    }

    private static List<ScoreNoteEvent> printedSixteenthLine(
            List<ScoreNoteEvent> notes,PlayingTechniqueDetector.Word closing,float left,float span,
            PlayingTechniqueDetector.Staff staff,byte[] gray,int width,int height,boolean straight,int closingOwner, boolean allowOccludedTail) {
        float gap=staff.gap();
        var heads=new PrintedBeamShafts.Shaft[16];
        var x=new float[16];
        var y=new float[16];
        var budget=new PrintedBeamShafts.Budget(180000);
        for(int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            int ratio=!straight && i>=9 && i<=11?3:1;
            if(n.kind()!=ScoreNoteEvent.Kind.PITCHED || n.staffCount()!=1 || n.staffIndex()!=0
                    || n.visualSize()!=ScoreNoteEvent.VisualSize.FULL || n.stemDirection()!=-1
                    || n.beamCount()!=2 || n.unbeamedDurationBeats()!=0 || n.tupletDivisor()!=ratio
                    || n.tupletNormalNotes()!=(ratio==3?2:1) || n.augmentationDots()!=0
                    || n.leadingRestBeats()!=0 || n.followingRestBeats()!=0 || n.tiedFromPrevious()
                    || n.boundaryTies()!=0 || n.crossStaffBeam()) return List.of();
            x[i]=left+span*n.positionInMeasure();
            y[i]=n.pageY()*height;
            if (i>0 && (x[i]-x[i-1]<gap*1.5f || x[i]-x[i-1]>gap*4)) return List.of();
            var axes=PrintedBeamShafts.at(gray,width,height,x[i],y[i],gap,budget);
            if (axes.size()==1 && axes.get(0).direction()==1) heads[i]=axes.get(0);
            else if (!axes.isEmpty() || i != 13 && !(allowOccludedTail && i == 14)) return List.of();
        }
        float close=(closing.left()+closing.right())*width*.5f;
        if (Math.abs(close-x[closingOwner])>gap*.65f) return List.of();
        for(int i=0;i+1<heads.length;i++) {
            if (heads[i]==null || heads[i+1]==null) {
                int middle=heads[i]==null?i:i+1;
                if (heads[middle-1]==null || heads[middle+1]==null
                          || !PrintedTupletBeamOwner.connectedHeads(gray,width,height,x[i],y[i],x[i+1],y[i+1],gap,-1)
                        || !pairedRails(gray,width,height,heads[middle-1],heads[middle+1],gap))
                    return List.of();
            } else if (i%4==3 ? pairedRails(gray,width,height,heads[i],heads[i+1],gap)
                              : !pairedRails(gray,width,height,heads[i],heads[i+1],gap)) return List.of();
        }
        var result=notes.stream().map(n -> n.tupletDivisor()==3?n.withTupletRatio(1,1):n).toList();
        return closedWrittenSixteenths(result)?result:List.of();
    }

    static boolean pairedBeamRails(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft first,
            PrintedBeamShafts.Shaft second,
            float gap) {
        return first.direction() == second.direction()
                && pairedRails(
                        gray, width, height, first, second, gap, true, true, first.direction());
    }

    private static boolean pairedRails(byte[] gray,int width,int height,
            PrintedBeamShafts.Shaft a,PrintedBeamShafts.Shaft b,float gap) {
        return pairedRails(gray, width, height, a, b, gap, false);
    }

    private static boolean pairedRails(byte[] gray,int width,int height,
            PrintedBeamShafts.Shaft a,PrintedBeamShafts.Shaft b,float gap,boolean narrowSeam) {
        return pairedRails(gray, width, height, a, b, gap, narrowSeam, false);
    }

    private static boolean pairedRails(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft a, PrintedBeamShafts.Shaft b, float gap,
            boolean narrowSeam, boolean fineSeam) {
        return pairedRails(gray, width, height, a, b, gap, narrowSeam, fineSeam, 1);
    }

    private static boolean pairedRails(
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b,
            float gap,
            boolean narrowSeam,
            boolean fineSeam,
            int direction) {
        int x0=(a.left()+a.right())/2,x1=(b.left()+b.right())/2;
        int margin=Math.round(gap*.37f),rad=Math.max(2,Math.round(gap*.14f));
        if(x0+margin>=x1-margin) return false;
        for(int offset=-Math.round(gap*.22f);offset<=Math.round(gap*.45f);offset++)
            for(int separation=Math.round(gap*.55f);separation<=Math.round(gap*.92f);separation++) {
                int first=0,second=0,seam=0,lightSeam=0,linePaper=0;
                for(int x=x0+margin;x<=x1-margin;x++) {
                    int axis =
                            Math.round(
                                            a.endY()
                                                    + (b.endY() - a.endY())
                                                            * (x - x0)
                                                            / (float) (x1 - x0))
                                    - offset * direction;
                    int inner = axis - separation * direction;
                    if (Math.max(axis, inner) + rad >= height || Math.min(axis, inner) - rad < 0)
                        continue;
                    if(verticalInk(gray,width,x,axis,rad)>=2*rad) first++;
                    if (verticalInk(gray, width, x, inner, rad) >= 2 * rad) second++;
                    int trough = axis - separation * direction / 2;
                    if(verticalInk(gray,width,x,trough,1)<=1) seam++;
                    int shade = gray[trough * width + x] & 255;
                    if (narrowSeam && shade >= 185) lightSeam++;
                    if (fineSeam && shade >= 185
                            && (gray[(trough - 1) * width + x] & 255) < 165
                            && (gray[(trough + 1) * width + x] & 255) < 165) linePaper++;
                }
                int count=x1-x0-2*margin+1;
                boolean visibleGap = seam >= count * .28f
                        || narrowSeam && seam >= count * .18f && lightSeam >= count * .50f
                        || fineSeam && lightSeam >= count * .9f && linePaper >= count * .65f;
                if(first>=count*.96f && second>=count*.96f && visibleGap) return true;
            }
        return false;
    }

    private static int verticalInk(byte[] gray,int width,int x,int y,int radius) {
        int dark=0;
        for(int dy=-radius;dy<=radius;dy++) if((gray[(y+dy)*width+x]&255)<165) dark++;
        return dark;
    }

    private static boolean possiblyPrintedText(ScoreNoteEvent n) {
        return n.kind()==ScoreNoteEvent.Kind.PITCHED && n.staffCount()==1 && n.staffIndex()==0
                && n.stemDirection()==0 && n.visualSize()==ScoreNoteEvent.VisualSize.FULL
                && n.staffStep()>=-2 && n.staffStep()<=2
                && n.writtenAccidental()==ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && n.augmentationDots()==0 && n.tupletDivisor()==1 && n.articulations()==0
                && !n.tiedFromPrevious() && n.boundaryTies()==0
                && (n.beamCount()>0 || n.unbeamedDurationBeats()==4);
    }

    private static List<PlayingTechniqueDetector.Word> parenthesizedBoxes(
            List<PlayingTechniqueDetector.Word> openings, List<PlayingTechniqueDetector.Word> closings,
            MeasureRegion measure, PlayingTechniqueDetector.Staff staff, int width, int height) {
        float gap=staff.gap();
        for (var a:openings) {
            if (!OPEN_FINGER.matcher(a.text().strip()).matches()
                    || !insideStaffText(a,measure,staff,width,height)) continue;
            for (var b:closings) {
                float distance=(b.left()-a.right())*width;
                if (distance>=gap*7 && distance<=gap*13
                        && insideStaffText(b,measure,staff,width,height)
                        && Math.abs(a.top()-b.top())*height<gap*.3f
                        && (a.right()-a.left())*width<gap*3
                        && (b.right()-b.left())*width<gap*3) return List.of(a,b);
            }
        }
        return List.of();
    }

    // A lowest staff rule can cross the opening fingering while the final digit lies below it.
    private static List<PlayingTechniqueDetector.Word> isolatedOpeningBox(
            List<PlayingTechniqueDetector.Word> openings, List<PlayingTechniqueDetector.Word> closings,
            MeasureRegion measure, PlayingTechniqueDetector.Staff staff, List<ScoreNoteEvent> music,
            int width, int height) {
        float gap = staff.gap();
        float scale = (measure.right() - measure.left()) * width / gap;
        for (int i = 1; i < music.size(); i++) {
            float dx = (music.get(i).positionInMeasure() - music.get(i - 1).positionInMeasure()) * scale;
            if (dx < 1.3f || dx > 2.4f) return List.of();
        }
        for (var opening : openings) {
            if ((!opening.text().strip().equalsIgnoreCase("(a")
                            && !opening.text().strip().equals("(3"))
                    || !insideStaffText(opening, measure, staff, width, height)
                    || (opening.right() - opening.left()) * width > gap * 3) continue;
            for (var closing : closings) {
                if (!closing.text().strip().equals("3)")) continue;
                float dx = (closing.left() - opening.right()) * width / gap;
                float dy = (closing.top() - opening.top()) * height / gap;
                float h = (closing.bottom() - closing.top()) * height / gap;
                if (dx >= 8 && dx <= 11 && dy >= .8f && dy <= 1.4f
                        && closing.right() <= measure.right()
                        && closing.top() * height >= staff.bottom() - .35f * gap
                        && closing.bottom() * height <= staff.bottom() + 2.6f * gap
                        && (closing.right() - closing.left()) * width <= gap * 3
                        && h >= 1.5f && h <= 2.5f) return List.of(opening);
            }
        }
        return List.of();
    }

    private static boolean insideStaffText(
            PlayingTechniqueDetector.Word word, MeasureRegion region, PlayingTechniqueDetector.Staff staff,
            int width, int height) {
        float gap=staff.gap(), left=word.left()*width, right=word.right()*width;
        float top=word.top()*height, bottom=word.bottom()*height;
        return left>=region.left()*width && right<=region.right()*width
                && top>=staff.bottom()-gap*2.25f && top<=staff.bottom()+gap*.35f
                && bottom>=staff.bottom() && bottom<=staff.bottom()+gap*1.65f
                && bottom-top>=gap*1.15f && bottom-top<=gap*3;
    }

    private static boolean insideInk(ScoreNoteEvent n, MeasureRegion region,
            PlayingTechniqueDetector.Staff staff, List<PlayingTechniqueDetector.Word> words,
            byte[] gray, int width, int height) {
        float x=(region.left()+n.positionInMeasure()*(region.right()-region.left()))*width;
        float y=n.pageY()*height, gap=staff.gap();
        for (var word:words) {
            float t=word.top()*height,b=word.bottom()*height;
            if (x<word.left()*width || x>word.right()*width || y<t || y>b
                    || printedStemBeyondWord(gray,width,height,x,y,t,b,gap)) continue;
            int ink=0;
            for(int yy=Math.max(0,Math.round(y-gap*.33f));yy<=Math.min(height-1,Math.round(y+gap*.33f));yy++) {
                float staffLine=(staff.bottom()-yy)/gap;
                if(staffLine>=-.22f && staffLine<=4.22f
                        && Math.abs(staffLine-Math.round(staffLine))<.22f) continue;
                for(int xx=Math.max(0,Math.round(x-gap*.34f));xx<=Math.min(width-1,Math.round(x+gap*.34f));xx++)
                    if((gray[yy*width+xx]&255)<160) ink++;
            }
            if (ink>=gap*.7f) return true;
        }
        return false;
    }

    private static boolean closedWrittenSixteenths(List<ScoreNoteEvent> music) {
        int count=music.size();
        for (int i=0;i<count;i++) {
            var p=ScoreNoteTiming.writtenPlacement(music.get(i),music,count*.25f).orElse(null);
            if (p==null || p.onsetBeats()!=i*.25f || p.durationBeats()!=.25f) return false;
        }
        return true;
    }

    private static List<Candidate> candidates(
            List<PlayingTechniqueDetector.Word> words, int width, int height) {
        var result = new ArrayList<Candidate>();
        for (var word : words) {
            String text = word.text() == null ? "" : word.text().trim();
            int sequenceLength = DIGIT_SEQUENCE.matcher(text).matches() ? text.length() : 0;
            boolean fingering = isFingering(text);
            if (!fingering && sequenceLength == 0) continue;
            float l = word.left() * width, r = word.right() * width;
            float t = word.top() * height, b = word.bottom() * height;
            if (!Float.isFinite(l + r + t + b)) continue;
            result.add(new Candidate(l, r, t, b, fingering,
                    SINGLE_DIGIT.matcher(text).matches(), sequenceLength));
        }
        return result;
    }

    static boolean isFingering(String text) {
        if (text == null) return false;
        String compact = WHITESPACE.matcher(text.trim()).replaceAll("").toUpperCase(Locale.ROOT);
        return HAND_OR_DIGIT.matcher(compact).matches() || HAND_SEQUENCE.matcher(compact).matches();
    }

    /** OCR can call a notehead and its short ledger a digit. An attached shaft
     * continuing well outside that text box is independent musical evidence. */
    /** A typographic loop across the lowest staff rule can appear as a second head. */
    private record ChordFingering(int owner, int left, int top, int right, int bottom) {}

    private static List<ScoreNoteEvent> removeChordFingeringAliases(List<PlayingTechniqueDetector.Word> words,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, byte[] raw, int width, int height) {
        var proved = chordFingeringThrees(words, measures, notes, gray, raw, width, height);
        if (proved.isEmpty()) return notes;
        var aliases = new BitSet();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.staffIndex() != 0 || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.stemDirection() != 0 || note.beamCount() < 1 || note.beamCount() > 3
                    || note.augmentationDots() != 0 || note.tupletDivisor() != 1
                    || (note.articulations() & NoteOrnament.GRACE) != 0 || note.tiedFromPrevious()) continue;
            var bar = measures.get(note.measureIndex());
            float x = fingeringHeadX(bar, note, width), y = note.pageY() * height;
            for (var mark : proved) {
                if (notes.get(mark.owner()).measureIndex() != note.measureIndex()
                        || x < mark.left() || x > mark.right() || y < mark.top() || y > mark.bottom()) continue;
                aliases.set(i);
            }
        }
        if (aliases.isEmpty()) return notes;
        var result = new ArrayList<ScoreNoteEvent>(notes.size() - aliases.cardinality());
        for (int i = 0; i < notes.size(); i++) if (!aliases.get(i)) result.add(notes.get(i));
        return result;
    }

    static List<PrintedFingeringDigits.Mark> captureChordFingeringThrees(List<PlayingTechniqueDetector.Word> words,
            ScorePageInterpretation score, byte[] gray, byte[] raw, int width, int height) {
        return chordFingeringThrees(words, score.measures(), score.notes(), gray, raw, width, height).stream()
                .map(mark -> new PrintedFingeringDigits.Mark(mark.owner(), 3, PrintedFingeringDigits.Placement.BELOW))
                .distinct().toList();
    }

    private static List<ChordFingering> chordFingeringThrees(List<PlayingTechniqueDetector.Word> words,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height || gray.length != raw.length)
            return List.of();
        var result = new ArrayList<ChordFingering>();
        for (var word : words) {
            if (!"3".equals(word.text()) && !"43".equals(word.text())) continue;
            float wordLeft = word.left() * width, wordRight = word.right() * width;
            int top = Math.round(word.top() * height), bottom = Math.round(word.bottom() * height);
            int left = Math.round("3".equals(word.text()) ? wordLeft
                    : wordRight - .44f * (wordRight - wordLeft));
            int right = Math.round(wordRight);
            float digitX = (left + right) * .5f, digitY = (top + bottom) * .5f;
            ChordFingering match = null;
            boolean ambiguous = false;
            for (int i = 0; i < notes.size(); i++) {
                var lower = notes.get(i);
                if (!chordFingeringHead(lower)) continue;
                var bar = measures.get(lower.measureIndex());
                float gap = (bar.bottom() - bar.top()) * height / 8.2f;
                float x = fingeringHeadX(bar, lower, width), y = lower.pageY() * height;
                if (gap < 12 || gap > 23 || Math.abs(digitX - x) > .48f * gap
                        || digitY - y < 4.85f * gap || digitY - y > 6.65f * gap
                        || right - left < 1.1f * gap || right - left > 1.9f * gap
                        || bottom - top < 1.25f * gap || bottom - top > 2.65f * gap) continue;
                int upper = chordFingeringPartner(notes, i, bar, width, height, gap, x, y);
                if (upper < 0) continue;
                var companion = notes.get(upper);
                float ax = fingeringHeadX(bar, companion, width), ay = companion.pageY() * height;
                var observed = staffRuleThree(gray, width, height, left, top, right, bottom, digitX, digitY, gap);
                if (observed == null || !Arrays.equals(observed,
                        staffRuleThree(raw, width, height, left, top, right, bottom, digitX, digitY, gap))
                        || !ownsFingeringShaft(gray, width, height, x, y, ax, ay, observed[0], gap)
                        || !ownsFingeringShaft(raw, width, height, x, y, ax, ay, observed[0], gap)) continue;
                if (match != null) {
                    ambiguous = true;
                    break;
                }
                match = new ChordFingering(i, left, observed[0], right, observed[1]);
            }
            if (!ambiguous && match != null) result.add(match);
        }
        return result;
    }

    private static boolean chordFingeringHead(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffIndex() == 0 && note.staffCount() == 1
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && note.stemDirection() == -1
                && note.beamCount() >= 2 && note.beamCount() <= 3 && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static int chordFingeringPartner(List<ScoreNoteEvent> notes, int lower, MeasureRegion bar,
            int width, int height, float gap, float x, float y) {
        int result = -1;
        for (int i = Math.max(0, lower - 3); i <= Math.min(notes.size() - 1, lower + 3); i++) {
            var other = notes.get(i);
            float rise = y - other.pageY() * height;
            if (other.measureIndex() != notes.get(lower).measureIndex() || !chordFingeringHead(other)
                    || Math.abs(fingeringHeadX(bar, other, width) - x) > .22f * gap
                    || rise < .79f * gap || rise > 2.85f * gap) continue;
            if (result >= 0) return -1;
            result = i;
        }
        return result;
    }

    private static boolean ownsFingeringShaft(byte[] ink, int width, int height, float x, float y,
            float ax, float ay, int digitTop, float gap) {
        var budget = new PrintedBeamShafts.Budget(70_000);
        for (var shaft : PrintedBeamShafts.at(ink, width, height, x, y, gap, budget)) {
            if (shaft.direction() != 1 || shaft.endY() > digitTop || digitTop - shaft.endY() > 2.45f * gap)
                continue;
            for (var upper : PrintedBeamShafts.at(ink, width, height, ax, ay, gap, budget))
                if (upper.direction() == 1 && upper.left() <= shaft.right() && shaft.left() <= upper.right()
                        && Math.abs(shaft.endY() - upper.endY()) <= 2) return true;
        }
        return false;
    }

    private static int[] staffRuleThree(byte[] ink, int width, int height, int left, int top,
            int right, int bottom, float x, float y, float gap) {
        int reach = Math.round(.30f * gap);
        if (left < reach || right + reach >= width || top < 2 || bottom + 2 >= height
                || printedStemBeyondWord(ink, width, height, x, y, top, bottom, gap)) return null;
        int stride = right - left + 1, rows = bottom - top + 1;
        byte[] glyphInk = new byte[stride * rows];
        for (int row = top; row <= bottom; row++) {
            boolean rule = true;
            for (int dx = 1; dx <= reach; dx++)
                if ((ink[row * width + left - dx] & 255) >= 165
                        || (ink[row * width + right + dx] & 255) >= 165) rule = false;
            int to = (row - top) * stride;
            if (rule) Arrays.fill(glyphInk, to, to + stride, (byte) 255);
            else System.arraycopy(ink, row * width + left, glyphInk, to, stride);
        }
        var lobes = RawNumeralComponents.capture(glyphInk, stride, rows, 0, 0, stride - 1, rows - 1,
                165, () -> {}, () -> {}).components().stream()
                .filter(c -> c.size() >= gap * gap * .07f).sorted(Comparator.comparingInt(c -> c.top)).toList();
        if (lobes.size() != 2) return null;
        var a = lobes.get(0);
        var b = lobes.get(1);
        if (b.top - a.bottom < 2 || b.top - a.bottom > .34f * gap
                || b.bottom - a.top < .9f * gap || b.bottom - a.top > 1.45f * gap
                || a.left > b.right || b.left > a.right || Math.abs(a.right - b.right) > .43f * gap)
            return null;
        return new int[]{top + a.top, top + b.bottom};
    }

    private static List<ScoreNoteEvent> removeStaffRuleFingeringLobes(
            List<PlayingTechniqueDetector.Word> words, List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes, byte[] gray, int width, int height) {
        var digits = words.stream().filter(word -> word.text() != null
                && (word.text().strip().equals("3") || word.text().strip().equals("8"))).toList();
        if (digits.isEmpty()) return notes;
        List<ScoreNoteEvent> removed = new ArrayList<>();
        for (var note : notes) {
            int index = note.measureIndex();
            if (index < 0 || index >= measures.size()
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.staffIndex() != 0 || note.staffCount() != 1 || note.staffStep() != 0
                    || note.stemDirection() != 0 || note.beamCount() != 3 || note.tupletDivisor() != 1
                    || note.augmentationDots() != 0 || note.unbeamedDurationBeats() != 0
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            var measure = measures.get(index);
            float x = fingeringHeadX(measure, note, width), y = note.pageY() * height;
            for (var staff : staffs) {
                float gap = staff.gap();
                if (staff.index() != 0 || staff.count() != 1 || gap < 10 || gap > 25
                        || staff.top() < measure.top() * height || staff.bottom() > measure.bottom() * height
                        || Math.abs(y - staff.bottom()) > gap * .48f) continue;
                for (var digit : digits) {
                    float left = digit.left() * width, right = digit.right() * width;
                    float top = digit.top() * height, bottom = digit.bottom() * height;
                    if (x <= left || x >= right || y <= top || y >= bottom
                            || Math.abs((left + right) * .5f - x) > gap * .23f
                            || right - left < gap * .95f || right - left > gap * 1.75f
                            || bottom - top < gap * 1.2f || bottom - top > gap * 2.15f
                            || printedStemBeyondWord(gray, width, height, x, y, top, bottom, gap)
                            || !twoLobesOnStaffRule(gray, width, height, x, y, gap, left, right, bottom)
                            || !hasIndependentUpperBeam(gray, width, height, measure, notes, note, x, y, gap))
                        continue;
                    removed.add(note);
                    break;
                }
                if (removed.contains(note)) break;
            }
        }
        return removed.isEmpty() ? notes : notes.stream().filter(note -> !removed.contains(note)).toList();
    }

    private static float fingeringHeadX(MeasureRegion measure, ScoreNoteEvent note, int width) {
        return (measure.left() + (measure.right() - measure.left()) * note.positionInMeasure()) * width;
    }

    private static boolean hasIndependentUpperBeam(
            byte[] gray, int width, int height, MeasureRegion measure, List<ScoreNoteEvent> notes,
            ScoreNoteEvent alias, float x, float y, float gap) {
        ScoreNoteEvent left = null, center = null, right = null;
        for (var note : notes) {
            if (note.measureIndex() != alias.measureIndex() || note.staffIndex() != 0
                    || note.staffCount() != 1 || note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.stemDirection() != -1 || note.beamCount() != 2
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            float dx = fingeringHeadX(measure, note, width) - x, dy = y - note.pageY() * height;
            if (dy < gap * 5.1f || dy > gap * 7.7f) continue;
            if (Math.abs(dx) <= gap * .24f) {
                if (center != null) return false;
                center = note;
            } else if (dx >= -gap * 2.35f && dx <= -gap * 1.64f) {
                if (left != null) return false;
                left = note;
            } else if (dx <= gap * 2.35f && dx >= gap * 1.64f) {
                if (right != null) return false;
                right = note;
            }
        }
        if (left == null || center == null || right == null) return false;
        float a = left.pageY() * height, b = center.pageY() * height, c = right.pageY() * height;
        if (b - a < gap * .34f || b - a > gap * .68f
                || c - b < gap * .34f || c - b > gap * .68f) return false;
        float centerX = fingeringHeadX(measure, center, width);
        return PrintedTupletBeamOwner.connectedHeads(gray, width, height,
                    fingeringHeadX(measure, left, width), a, centerX, b, gap, -1)
                && PrintedTupletBeamOwner.connectedHeads(gray, width, height,
                    centerX, b, fingeringHeadX(measure, right, width), c, gap, -1);
    }

    private static boolean twoLobesOnStaffRule(
            byte[] gray, int width, int height, float x, float y, float gap,
            float opticalLeft, float opticalRight, float opticalBottom) {
        int l = Math.round(opticalLeft), r = Math.round(opticalRight);
        int margin = Math.round(gap * .45f);
        int start = Math.round(y - gap * .55f), end = Math.round(y + gap * .18f);
        if (l < margin || r + margin >= width || start < gap || end + gap >= height) return false;
        int lineTop = -1, lineBottom = -1;
        for (int row = start; row <= end; row++) {
            boolean connects = true;
            for (int offset = 1; offset <= margin; offset++) {
                if ((gray[row * width + l-offset] & 255) > 165
                        || (gray[row * width + r+offset] & 255) > 165) connects = false;
            }
            if (!connects) continue;
            if (lineBottom >= 0 && lineBottom + 1 != row) return false;
            if (lineTop < 0) lineTop = row;
            lineBottom = row;
        }
        if (lineTop < 0 || lineBottom - lineTop + 1 > gap * .31f
                || y - lineBottom < .6f || y - lineBottom > gap * .24f) return false;
        int t = lineTop - Math.round(gap * .76f), bottom = Math.round(opticalBottom);
        if (t < 0 || bottom >= height || bottom <= lineBottom) return false;
        var topInk = RawNumeralComponents.capture(gray, width, height, l, t, r, lineTop - 1, () -> {}, () -> {});
        var bottomInk = RawNumeralComponents.capture(
                gray, width, height, l, lineBottom + 1, r, bottom, () -> {}, () -> {});
        var upper = joinedLobe(topInk, l, r, x, gap, .40f, .77f);
        var lower = joinedLobe(bottomInk, l, r, x, gap, .20f, .48f);
        if (upper == null || lower == null || upper.top <= t || upper.bottom != lineTop - 1
                || lower.top != lineBottom + 1 || lower.bottom >= bottom
                || y < lower.top || y > lower.bottom) return false;
        int openingRows = 0;
        for (int row = upper.top + 1; row < upper.bottom; row++) {
            boolean leftInk = false, rightInk = false;
            int white = 0;
            for (int col = upper.left; col <= upper.right; col++) {
                if ((gray[row * width + col] & 255) <= 165) {
                    if (col < x) leftInk = true;
                    else if (col > x) rightInk = true;
                } else if (leftInk && !rightInk) white++;
            }
            if (leftInk && rightInk && white >= 2) openingRows++;
        }
        return openingRows >= 2;
    }

    private static RawNumeralComponents.Component joinedLobe(
            RawNumeralComponents ink, int left, int right, float x, float gap, float minHeight, float maxHeight) {
        RawNumeralComponents.Component found = null;
        for (var shape : ink.components()) {
            float w = shape.right - shape.left + 1, h = shape.bottom - shape.top + 1;
            if (w < gap * .55f || w > gap * .99f || h < gap * minHeight || h > gap * maxHeight
                    || shape.left <= left || shape.right >= right
                    || Math.abs((shape.left + shape.right) * .5f - x) > gap * .22f) continue;
            if (found != null) return null;
            found = shape;
        }
        return found;
    }

    private static boolean printedStemBeyondWord(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float top,
            float bottom,
            float gap) {
        int limit = 170;
        int local =
                BeamInkThreshold.at(
                        gray,
                        width,
                        height,
                        Math.round(x),
                        Math.round(top - gap),
                        Math.round(bottom + gap),
                        gap);
        if (local < 153) limit = Math.min(limit, local + 15);
        for (int direction : new int[] {-1, 1})
            for (int offset = Math.round(gap * .35f); offset <= Math.round(gap * .9f); offset++) {
                int xx = Math.round(x) - direction * offset;
                if (xx < 0 || xx >= width) continue;
                int end =
                        Math.round(
                                direction < 0
                                        ? Math.min(y - gap * 2, top - gap * .7f)
                                        : Math.max(y + gap * 2, bottom + gap * .7f));
                if (end < 0 || end >= height || Math.abs(end - y) > gap * 5) continue;
                int ink = 0, total = 0, run = 0, longestBlank = 0;
                for (int yy = Math.round(y); direction * (end - yy) >= 0; yy += direction) {
                    total++;
                    if ((gray[yy * width + xx] & 255) < limit) {
                        ink++;
                        run = 0;
                    } else {
                        run++;
                        longestBlank = Math.max(longestBlank, run);
                    }
                }
                if (total >= gap * 1.9f
                        && ink >= total * .9f
                        && longestBlank <= Math.max(1, Math.round(gap * .15f))) return true;
            }
        return false;
    }

    private static boolean printedLedgerAcrossWord(
            byte[] gray, int width, int height, float left, float right, float y, float gap) {
        return printedLedgerAcrossWord(gray,width,height,left,right,y,gap,1);
    }

    private static boolean printedLedgerAcrossWord(
            byte[] gray, int width, int height, float left, float right, float y, float gap, int firstRow) {
        int limit = 190;
        int local =
                BeamInkThreshold.at(
                        gray,
                        width,
                        height,
                        Math.round((left + right) * .5f),
                        Math.round(y - gap),
                        Math.round(y + gap),
                        gap);
        if (local < 153) limit = Math.min(limit, local + 15);
        for (int yy = Math.max(firstRow, Math.round(y - gap * .3f));
                yy <= Math.min(height - 2, Math.round(y + gap * .3f));
                yy++) {
            boolean both = true;
            for (int side : new int[] {-1, 1}) {
                int hits = 0, total = 0, center = Math.round(side < 0 ? left : right);
                for (int d = 1; d <= Math.round(gap * .65f); d++) {
                    int x = center + side * d;
                    if (x < 0 || x >= width) {
                        both = false;
                        break;
                    }
                    total++;
                    if ((gray[yy * width + x] & 255) < limit) hits++;
                }
                if (total < 3 || hits < total * .8f) both = false;
            }
            if (both) return true;
        }
        return false;
    }
}
