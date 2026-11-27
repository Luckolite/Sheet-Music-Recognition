// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** A complete metronome equation below a printed part header can own the opening staff. */
final class OpeningPartTempo {
    // Reuse ScoreCreditsDetector's instrument/voice label vocabulary and its short part aliases.
    private static final java.util.regex.Pattern INSTRUMENT = java.util.regex.Pattern.compile(
            "(?iu)^(?:for\\h+)?(?:(?:soprano|alto|tenor|bass|treble|descant)\\h+)?(?:violin|viola|cello|piano|harp|guitar|flute|clarinet|oboe|bassoon|trumpet|trombone|horn|tuba|saxophone|recorder|percussion|drums|voices?|vocals?|vln[.]|vla[.]|vc[.]|fl[.]|cl[.]|ob[.]|bsn[.]|pno[.])(?:\\h+(?:solo|duet|part|[1-9]|I{1,3}))?$");

    static boolean part(String value) {
        return value != null && value.length() <= 80 && INSTRUMENT.matcher(value.strip()).matches();
    }

    static boolean valid(PlayingTechniqueDetector.Word w) {
        return w != null
                && w.text() != null
                && w.text().length() <= 300
                && Float.isFinite(w.left() + w.right() + w.top() + w.bottom())
                && w.left() >= 0
                && w.top() >= 0
                && w.right() <= 1
                && w.bottom() <= 1
                && w.left() < w.right()
                && w.top() < w.bottom();
    }

    record TitledMeter(ScoreMeterChange change, PlayingTechniqueDetector.Word upper,
                       PlayingTechniqueDetector.Word lower) {
        List<ScoreNoteEvent> withoutMeterHeads(List<ScoreNoteEvent> notes, MeasureRegion first) {
            return notes.stream().filter(note -> {
                if (note.measureIndex() != 0 || note.staffIndex() != 0 || note.staffCount() != 1)
                    return true;
                float x = first.left() + (first.right() - first.left()) * note.positionInMeasure();
                return !owns(upper, x, note.pageY()) && !owns(lower, x, note.pageY());
            }).toList();
        }

        private static boolean owns(PlayingTechniqueDetector.Word word, float x, float y) {
            return x >= word.left() && x <= word.right() && y >= word.top() && y <= word.bottom();
        }
    }

    /** Recover a stacked meter from two bounded, ink-backed OCR words on a titled solo entry. */
    static TitledMeter titledMeter(
            List<PlayingTechniqueDetector.Word> words, byte[] gray, int width, int height,
            List<MeasureRegion> measures, List<PlayingTechniqueDetector.Staff> staffs,
            List<ScoreNoteEvent> notes) {
        if (!ScorePageTimeline.hasTitledOpening(words, gray, width, height, measures)) return null;
        var first = measures.get(0);
        var staff = staffs.stream().filter(s -> s.count() == 1 && s.index() == 0 && s.gap() >= 8
                        && s.top() >= first.top() * height
                        && s.bottom() <= first.bottom() * height)
                .min(Comparator.comparingDouble(PlayingTechniqueDetector.Staff::top)).orElse(null);
        if (staff == null) return null;
        float gap = staff.gap();
        float mid = (staff.top() + staff.bottom()) * .5f;
        var candidates = new ArrayList<PlayingTechniqueDetector.Word>();
        for (var word : words) {
            if (!valid(word) || !word.text().strip().matches("[0-9]{1,2}")
                    || word.left() * width < first.left() * width - gap
                    || word.right() * width > first.left() * width + gap * 4
                    || word.top() * height < staff.top() - gap * .55f
                    || word.bottom() * height > staff.bottom() + gap * .55f) continue;
            int value = Integer.parseInt(word.text().strip());
            if (value >= 1 && value <= 24
                    && HeaderMeterTokenGeometry.digitSized(value, (word.right() - word.left()) * width,
                            (word.bottom() - word.top()) * height, gap)
                    && printedWord(word, gray, width, height)) candidates.add(word);
        }
        TitledMeter result = null;
        for (var upper : candidates) {
            if ((upper.top() + upper.bottom()) * height * .5f >= mid) continue;
            for (var lower : candidates) {
                int denominator = Integer.parseInt(lower.text().strip());
                if ((denominator & (denominator - 1)) != 0
                        || denominator < 2 || denominator > 16
                        || (lower.top() + lower.bottom()) * height * .5f <= mid
                        || upper.bottom() * height > lower.top() * height + gap * .3f
                        || lower.top() * height - upper.bottom() * height > gap
                        || Math.min(upper.right(), lower.right()) - Math.max(upper.left(), lower.left())
                                < Math.min(upper.right() - upper.left(), lower.right() - lower.left()) * .60f)
                    continue;
                float right = Math.max(upper.right(), lower.right());
                float left = Math.min(upper.left(), lower.left());
                boolean nextHead = false;
                for (var note : notes) {
                    if (note.measureIndex() != 0 || note.staffIndex() != 0 || note.staffCount() != 1
                            || note.kind() != ScoreNoteEvent.Kind.PITCHED) continue;
                    float pos = first.left() + (first.right() - first.left()) * note.positionInMeasure();
                    if (pos < left - gap * .25f / width) return null;
                    if (pos > right && (pos - right) * width <= gap * 3) nextHead = true;
                }
                if (!nextHead) continue;
                if (result != null) return null;
                int numerator = Integer.parseInt(upper.text().strip());
                result = new TitledMeter(new ScoreMeterChange(0, numerator, denominator), upper, lower);
            }
        }
        return result;
    }

    static boolean equationWord(
            PlayingTechniqueDetector.Word word, MeasureNumberReconciler.NumberToken token) {
        return valid(word)
                && token != null
                && token.value() >= 30
                && token.value() <= 400
                && Float.isFinite(
                        token.left()
                                + token.top()
                                + token.right()
                                + token.bottom()
                                + token.annotationLeft())
                && token.left() >= word.left() - .001f
                && token.right() <= word.right() + .001f
                && token.top() >= word.top() - .001f
                && token.bottom() <= word.bottom() + .001f
                && word.text()
                        .strip()
                        .matches("[\u2669\u266a\u266b\u266c\u221aJ.]?\\h*=\\h*" + token.value());
    }

    static boolean printedWord(
            PlayingTechniqueDetector.Word word, byte[] gray, int width, int height) {
        int left = Math.round(word.left() * width),
                right = Math.min(width - 1, Math.round(word.right() * width));
        int top = Math.round(word.top() * height),
                bottom = Math.min(height - 1, Math.round(word.bottom() * height));
        long area = (long) (right - left + 1) * (bottom - top + 1);
        if (area < 9 || area > 500000) return false;
        float inkLimit = area * .75f;
        int ink = 0, minX = right, maxX = left, minY = bottom, maxY = top;
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++)
                if ((gray[y * width + x] & 255) < 165) {
                    if (++ink > inkLimit) return false;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
        return ink >= Math.max(8, area * .015f)
                && maxX - minX >= (right - left) * .5f
                && maxY - minY >= (bottom - top) * .4f;
    }

    static int firstMeasure(List<MeasureRegion> measures) {
        if (measures == null || measures.isEmpty()) return -1;
        int first = 0;
        for (int i = 1; i < measures.size(); i++) {
            var a = measures.get(i);
            var b = measures.get(first);
            if (a.top() < b.top() - .002f
                    || Math.abs(a.top() - b.top()) <= .002f && a.left() < b.left()) first = i;
        }
        return first;
    }

    static int owner(
            PlayingTechniqueDetector.Word equation,
            List<PlayingTechniqueDetector.Word> words,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            Set<PlayingTechniqueDetector.Word> ownedLaterEquations) {
        return owner(equation, words, gray, width, height, measures, ownedLaterEquations, null);
    }

    static int owner(
            PlayingTechniqueDetector.Word equation,
            List<PlayingTechniqueDetector.Word> words,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            Set<PlayingTechniqueDetector.Word> ownedLaterEquations,
            List<RawStaffLineDetector.StaffLines> verifiedStaffs) {
        if (!valid(equation)
                || words == null
                || words.size() > 10000
                || gray == null
                || width < 3
                || height < 3
                || gray.length != (long) width * height
                || measures == null
                || measures.isEmpty()) return -1;
        int first = firstMeasure(measures);
        var opening = measures.get(first);
        var staffs = verifiedStaffs == null
                ? RawStaffLineDetector.detect(gray, width, height) : verifiedStaffs;
        RawStaffLineDetector.StaffLines staff = null;
        for (var s : staffs)
            if (Math.abs(s.top() - opening.top() * height) <= s.gap() * .3f
                    && s.bottom() <= opening.bottom() * height) {
                if (staff != null) return -1;
                staff = s;
            }
        if (staff == null) return -1;
        float gap = staff.gap();
        // A grand-staff/parallel-part header needs its own voice ownership; abstain here.
        for (var s : staffs) if (s != staff && s.top() < staff.bottom() + gap * 6.75f) return -1;
        float rowRight = opening.right();
        for (var m : measures)
            if (Math.abs(m.top() - opening.top()) * height < gap)
                rowRight = Math.max(rowRight, m.right());
        float rowWidth = (rowRight - opening.left()) * width;
        if (equation.bottom() * height >= staff.top() - gap * 2
                || equation.left() * width < opening.left() * width - gap * 2
                || equation.right() > opening.right()) return -1;
        List<PlayingTechniqueDetector.Word> captions = new ArrayList<>();
        for (var w : words)
            if (valid(w)
                    && part(w.text())
                    && w.bottom() < equation.top()
                    && w.left() * width >= opening.left() * width - gap * 10
                    && w.right() <= rowRight
                    && (equation.top() - w.bottom()) * height
                            <= Math.max(gap * 6, (w.bottom() - w.top()) * height * 4)
                    && (w.bottom() - w.top()) * height >= gap * .5f
                    && (w.bottom() - w.top()) <= (equation.bottom() - equation.top()) * 2.5f) {
                if (!captions.contains(w)) captions.add(w);
            }
        if (captions.size() != 1) return -1;
        var caption = captions.get(0);
        if (caption.right() > opening.right() || !printedWord(caption, gray, width, height))
            return -1;
        // Independently observed header rows precede the part caption. No title/artist hints.
        List<PlayingTechniqueDetector.Word> headings = new ArrayList<>();
        for (var w : words)
            if (valid(w)
                    && !part(w.text())
                    && w.bottom() < caption.top()
                    && w.text().codePoints().filter(Character::isLetter).count() >= 3
                    && printedWord(w, gray, width, height)
                    && !w.text().matches(".*[=0-9\u2669\u266a].*")
                    && Math.abs((w.left() + w.right() - opening.left() - rowRight) * width * .5f)
                            <= rowWidth * .2f) headings.add(w);
        boolean pairedHeader = false;
        for (var a : headings)
            for (var b : headings)
                if (a != b
                        && a.bottom() < b.top()
                        && Math.min(a.right(), b.right()) > Math.max(a.left(), b.left()))
                    pairedHeader = true;
        if (!pairedHeader) return -1;
        for (var w : words)
            if (valid(w)
                    && !w.equals(equation)
                    && !w.equals(caption)
                    && (ownedLaterEquations == null || !ownedLaterEquations.contains(w))
                    && w.bottom() > caption.bottom()
                    && (w.top() * height < staff.top() - gap * 2
                            || w.text().codePoints().filter(Character::isLetter).count() >= 2
                                    && w.top() * height < staff.top())
                    && w.right() * width > opening.left() * width - gap * 10
                    && w.left() < rowRight) return -1;
        int left = Math.max(0, Math.round(equation.left() * width)),
                right = Math.min(width - 1, Math.round(equation.right() * width));
        int top = Math.round(equation.bottom() * height) + Math.max(2, Math.round(gap * .25f));
        int bottom = (int) Math.floor(staff.top() - gap * 2);
        if (left >= right
                || top < 0
                || bottom >= height
                || bottom <= top
                || (long) (right - left + 1) * (bottom - top + 1) > 1000000) return -1;
        // Positive, completely clear projected space joins the caption-owned equation to the staff.
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++) if ((gray[y * width + x] & 255) < 225) return -1;
        return first;
    }
}
