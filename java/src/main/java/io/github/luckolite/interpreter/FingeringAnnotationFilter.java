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
        if (words == null
                || words.isEmpty()
                || notes.isEmpty()
                || staffs.isEmpty()
                || gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height) return notes;
        notes = annotatedSingleLines(words, staffs, measures, notes, gray, width, height);
        notes = separatedFingeringCaptions(words, staffs, measures, notes, gray, width, height);
        notes = shortFingeringCaptions(words, staffs, measures, notes, gray, width, height);
        notes = compactSegueCaptions(words, staffs, measures, notes, gray, width, height);
        notes = overlappingFingeringCaptions(words, staffs, measures, notes, gray, width, height);
        notes =
                loweredSegueWithSplitFingerings(
                        words, staffs, measures, notes, gray, width, height);
        notes = isolatedParentheticalSixteenths(words, staffs, measures, notes, gray, width, height);
        notes = belowStaffNumerals(words, staffs, measures, notes, gray, width, height);
        notes = removeStaffRuleFingeringLobes(words, staffs, measures, notes, gray, width, height);
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

    private static boolean bracketedByFingerings(
            List<Candidate> words, MeasureRegion measure, float x, float y, float gap, int width) {
        boolean left = false, right = false;
        for (var word : words) {
            if (!word.digit() && word.sequenceLength() == 0
                    || word.left() < measure.left() * width
                    || word.right() > measure.right() * width) continue;
            float dx = (word.left() + word.right()) / 2 - x;
            if (Math.abs(dx) < gap
                    || Math.abs(dx) > 5 * gap
                    || Math.abs((word.top() + word.bottom()) / 2 - y) > .5f * gap) continue;
            if (dx < 0) left = true;
            else right = true;
        }
        return left && right;
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
        int left = Math.max(0, Math.round(l)), right = Math.min(width - 1, Math.round(r));
        int top = Math.max(0, Math.round(t)), bottom = Math.min(height - 1, Math.round(b));
        if (left >= right || top >= bottom) return false;
        var source =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 165, () -> {}, () -> {});
        RawNumeralComponents.Component glyph = null;
        for (var piece : source.components()) {
            if (piece.size() < gap * gap * .05f) continue;
            if (glyph != null) return false;
            glyph = piece;
        }
        if (glyph == null
                || glyph.left <= left
                || glyph.right >= right
                || glyph.top <= top
                || glyph.bottom >= bottom
                || x < glyph.left
                || x > glyph.right
                || y < glyph.top
                || y > glyph.bottom) return false;
        int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
        return h >= gap
                && h <= gap * 1.6f
                && w >= h * .52f
                && w <= h * .85f
                && OctaveClefDigit.holes(gray, width, glyph.left, glyph.top, w, h) == 2;
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

    /** An italic initial alongside fingering digits can own a false head below the staff. */
    private static List<ScoreNoteEvent> compactSegueCaptions(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray, int width, int height) {
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
                if (!beamAlias && !wholeAlias) continue;
                var bar = measures.get(alias.measureIndex());
                float x = (bar.left() + (bar.right() - bar.left()) * alias.positionInMeasure()) * width;
                float y = alias.pageY() * height;
                if (x < caption.left() * width || x > caption.right() * width
                        || y < caption.top() * height || y > caption.bottom() * height) continue;
                var music = notes.stream().filter(n -> n.measureIndex() == alias.measureIndex() && n != alias)
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
                if (music.size() != 12 || music.stream().anyMatch(n -> n.stemDirection() != -1)) continue;
                int firstTriplet = tripletRunStart(music);
                if (firstTriplet == -2 || beamAlias && firstTriplet >= 0) continue;
                var corrected = music.stream().map(n -> n.tupletDivisor() == 3
                        ? n.withTupletRatio(1, 1) : n).toList();
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
                            || !insideInk(alias, bar, staff, List.of(caption), gray, width, height)
                            || !ownsCompactSegueInitial(gray, width, height, staff, caption, digitCount, x, y)) continue;
                    if (!closedCaptionSixteenths(
                            corrected, bar, staff, gray, width, height, 3.9f, true, .95f,
                            beamAlias ? 1 : 0, 3.1f, true)) continue;
                    if (output == null) output = new ArrayList<>(notes);
                    if (firstTriplet >= 0)
                        for (int i = firstTriplet; i < firstTriplet + 3; i++)
                            output.set(notes.indexOf(music.get(i)), corrected.get(i));
                    output.set(notes.indexOf(alias), null);
                    break;
                }
            }
        }
        return output == null ? notes : output.stream().filter(Objects::nonNull).toList();
    }

    private static boolean ownsCompactSegueInitial(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word caption, int digitCount, float x, float y) {
        var graph = captionComponents(gray, width, height, staff, caption);
        if (graph == null) return false;
        float gap = staff.gap();
        var glyphs = graph.components().stream().filter(c ->
                        c.size() >= gap * 3 && c.left > graph.left && c.right < graph.right
                                && c.top > graph.top && c.bottom < graph.bottom
                                && c.right - c.left >= gap * .45f && c.right - c.left <= gap * 2.15f
                                && c.bottom - c.top >= gap * .65f && c.bottom - c.top <= gap * 1.95f)
                .sorted(Comparator.comparingInt(c -> c.left)).toList();
        if (glyphs.size() != digitCount + 5) return false;
        var initial = glyphs.get(digitCount);
        var lastDigit = glyphs.get(digitCount - 1);
        return initial.left - lastDigit.right >= gap * .68f
                && initial.left - lastDigit.right <= gap * 2.3f
                && ownsTextCenter(initial, x, y, width);
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
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffCount() == 1
                && n.staffIndex() == 0
                && n.stemDirection() == 0
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffStep() >= -2
                && n.staffStep() <= 1
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
            if (source.size() != 1 || source.get(0).direction() != 1) return false;
            shafts[i] = source.get(0);
            if (i > 0) {
                boolean rails = pairedRails(gray, width, height, shafts[i - 1], shafts[i], gap, true, fineBeamSeam);
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
        int left = Math.round(word.left() * width) - 2,
                right = Math.round(word.right() * width) + 2;
        int top =
                Math.max(
                        Math.round(word.top() * height) - 2,
                        Math.round(staff.bottom() + staff.gap() * .14f));
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
                var replacement=printedSixteenthLine(real,pair.get(1),left,span,staff,gray,width,height);
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
            PlayingTechniqueDetector.Staff staff,byte[] gray,int width,int height) {
        float gap=staff.gap();
        var heads=new PrintedBeamShafts.Shaft[16];
        var x=new float[16];
        var y=new float[16];
        var budget=new PrintedBeamShafts.Budget(180000);
        for(int i=0;i<notes.size();i++) {
            var n=notes.get(i);
            int ratio=i>=9 && i<=11?3:1;
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
            else if (!axes.isEmpty() || i%4!=1 || i<12) return List.of();
        }
        float close=(closing.left()+closing.right())*width*.5f;
        if (Math.abs(close-x[11])>gap*.65f) return List.of();
        for(int i=0;i+1<heads.length;i++) {
            if (heads[i]==null || heads[i+1]==null) {
                int middle=heads[i]==null?i:i+1;
                if (!PrintedTupletBeamOwner.connectedHeads(gray,width,height,x[i],y[i],x[i+1],y[i+1],gap,-1)
                        || !pairedRails(gray,width,height,heads[middle-1],heads[middle+1],gap))
                    return List.of();
            } else if (i%4==3 ? pairedRails(gray,width,height,heads[i],heads[i+1],gap)
                              : !pairedRails(gray,width,height,heads[i],heads[i+1],gap)) return List.of();
        }
        var result=notes.stream().map(n -> n.tupletDivisor()==3?n.withTupletRatio(1,1):n).toList();
        return closedWrittenSixteenths(result)?result:List.of();
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
        int x0=(a.left()+a.right())/2,x1=(b.left()+b.right())/2;
        int margin=Math.round(gap*.37f),rad=Math.max(2,Math.round(gap*.14f));
        if(x0+margin>=x1-margin) return false;
        for(int offset=-Math.round(gap*.22f);offset<=Math.round(gap*.45f);offset++)
            for(int separation=Math.round(gap*.55f);separation<=Math.round(gap*.92f);separation++) {
                int first=0,second=0,seam=0,lightSeam=0,linePaper=0;
                for(int x=x0+margin;x<=x1-margin;x++) {
                    int axis=Math.round(a.endY()+(b.endY()-a.endY())*(x-x0)/(float)(x1-x0))-offset;
                    if (axis+rad>=height || axis-separation-rad<0) continue;
                    if(verticalInk(gray,width,x,axis,rad)>=2*rad) first++;
                    if(verticalInk(gray,width,x,axis-separation,rad)>=2*rad) second++;
                    int trough = axis - separation / 2;
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
        for (int yy = Math.max(1, Math.round(y - gap * .3f));
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
