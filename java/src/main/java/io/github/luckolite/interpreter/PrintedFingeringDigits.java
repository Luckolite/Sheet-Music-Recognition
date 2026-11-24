// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Source ownership of printed fingering digits on pitched attacks. */
final class PrintedFingeringDigits {
    enum Placement {
        ABOVE,
        BELOW
    }

    record Mark(int noteIndex, int digit, Placement placement) {}

    private record Head(
            int index, ScoreNoteEvent note, double x, double y, double onset, double top,
            double bottom, double gap, boolean harmonic) {}

    private record WordBox(
            int digit, double left, double top, double right, double bottom, double x, double y) {}

    private record Octave(Head upper, Head lower, Placement placement) {}

    private record Stack(Octave octave, int upper, int lower) {}

    private PrintedFingeringDigits() {}

    static List<Mark> capture(
            byte[] gray, int width, int height, List<SheetInterpreter.Word> words,
            ScorePageInterpretation score, List<Map<String, Object>> events) {
        return capture(gray, gray, width, height, words, score, events);
    }

    static List<Mark> capture(
            byte[] gray, byte[] normalized, int width, int height,
            List<SheetInterpreter.Word> words, ScorePageInterpretation score,
            List<Map<String, Object>> events) {
        if (score.notes().size() != events.size()
                || words.isEmpty() && score.beamSource().groups().isEmpty()) return List.of();
        var heads = new ArrayList<Head>();
        double widestGap = 0;
        for (int index = 0; index < score.notes().size(); index++) {
            var note = score.notes().get(index);
            var region = score.measures().get(note.measureIndex());
            var event = events.get(index);
            double top = (double) region.top() * height, bottom = (double) region.bottom() * height;
            double gap = (bottom - top) / 8;
            if (gap <= 0) continue;
            heads.add(new Head(index, note, ((Number) event.get("x")).doubleValue(),
                    ((Number) event.get("y")).doubleValue(),
                    ((Number) event.get("startBeat")).doubleValue(), top, bottom, gap,
                    note.openStringTouch() != null || note.harmonicSemitones() != 0
                            || event.containsKey("harmonicTouchMidi")));
            widestGap = Math.max(widestGap, gap);
        }
        heads.sort(Comparator.comparingDouble(Head::x));
        var boxes = words.stream()
                .map(word -> toPixels(word, width, height))
                .sorted(Comparator.comparingDouble(WordBox::x)).toList();
        Mark[] candidates = new Mark[score.notes().size()];
        boolean[] ambiguous = new boolean[candidates.length];
        for (var box : boxes) {
            if (box.digit() < 0 || box.digit() > 4) continue;
            var head = uniqueOffer(box, heads, widestGap);
            if (head == null || !isolated(box, head, heads, boxes)
                    || !retainsDigitInk(gray, width, height, box, head.gap())) continue;
            int index = head.index();
            if (candidates[index] != null) ambiguous[index] = true;
            candidates[index] = new Mark(index, box.digit(),
                    box.y() < head.y() ? Placement.ABOVE : Placement.BELOW);
        }
        if (!words.isEmpty()) {
            assignRuleCrossingTwos(gray, normalized, width, height, score, heads, widestGap, boxes, candidates);
            assignStacks(gray, normalized, width, height, score, heads, widestGap, boxes, candidates);
            assignUpstemOctaveTripletStacks(gray, normalized, width, height, score, heads, widestGap, boxes, candidates, ambiguous);
            assignSlurredThirdFingers(gray, normalized, width, height, score, heads, boxes, candidates);
            assignOppositeSides(gray, normalized, width, height, score, heads, boxes, candidates);
            assignTripletFingeringOnes(gray, normalized, width, height, score, boxes, candidates, ambiguous);
            assignStaffCrossedTripletThrees(gray, normalized, width, height, words, score, heads, candidates);
            assignHighTripletFingerings(gray, normalized, width, height, score, heads, boxes, candidates, ambiguous);
        }
        assignBelowThirdStacks(gray, normalized, width, height, score, heads, candidates, ambiguous);
        assignAttachedOctaveStacks(gray, normalized, width, height, score, heads, candidates, ambiguous);
        for (var mark : ClosedSixteenthDyads.captureCrossCutOpeningFingerings(score, gray, normalized, width, height)) {
            if (!ambiguous[mark.noteIndex()] && (candidates[mark.noteIndex()] == null
                    || candidates[mark.noteIndex()].equals(mark))) candidates[mark.noteIndex()] = mark;
        }
        assignSlurredSixthFingeringStacks(gray, normalized, width, height, score, heads, candidates, ambiguous);
        assignDottedSixthFingerings(gray, normalized, width, height, score, heads, candidates, ambiguous);
        assignHighStraightDyadFingerings(gray, normalized, width, height, words, score, heads,
                candidates, ambiguous);
        for (var mark : FingeringAnnotationFilter.captureChordFingeringThrees(
                words.stream().map(SheetInterpreter.Word::internal).toList(), score, gray, normalized,
                width, height)) {
            if (candidates[mark.noteIndex()] == null && !ambiguous[mark.noteIndex()])
                candidates[mark.noteIndex()] = mark;
        }
        var marks = new ArrayList<Mark>();
        for (int i = 0; i < candidates.length; i++)
            if (candidates[i] != null && !ambiguous[i]) marks.add(candidates[i]);
        return List.copyOf(marks);
    }

    private static void assignHighStraightDyadFingerings(byte[] raw, byte[] normalized, int width, int height,
            List<SheetInterpreter.Word> words, ScorePageInterpretation score, List<Head> heads,
            Mark[] marks, boolean[] ambiguous) {
        var hints = words.stream().filter(word -> switch (word.text()) {
            case "2", "3", "2 2" -> true;
            default -> false;
        }).toList();
        if (hints.isEmpty() || raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length) return;
        var indexed = new Head[score.notes().size()];
        for (var head : heads) indexed[head.index()] = head;
        var upper = new ArrayList<Head>();
        for (var beam : score.beamSource().resolve().groups()) {
            var group = beam.source();
            if (group.tupletMark() != null || !group.bridges().equals(List.of(3, 3, 3))
                    || !group.hooks().isEmpty() || beam.columns().stream().anyMatch(
                            column -> !column.usable() || column.owners().size() != 2)) continue;
            for (var column : beam.columns()) {
                var first = indexed[column.owners().get(0)];
                var second = indexed[column.owners().get(1)];
                if (first == null || second == null) continue;
                var high = first.y() < second.y() ? first : second;
                var low = high == first ? second : first;
                if (high.note().measureIndex() != low.note().measureIndex() || high.onset() != low.onset()
                        || high.note().staffStep() - low.note().staffStep() != 5
                        || high.note().stemDirection() != 1 || low.note().stemDirection() != 1
                        || Math.abs(high.x() - low.x()) > .22 * high.gap() || high.harmonic() || low.harmonic()) continue;
                if (ClosedMonophonicSixteenths.regularQuartetHead(high.note())
                        && ClosedMonophonicSixteenths.regularQuartetHead(low.note())) upper.add(high);
            }
        }
        for (var hint : hints) {
            var box = toPixels(hint, width, height);
            int digit = hint.text().charAt(0) - '0';
            int expected = hint.text().length() == 1 ? 1 : 2;
            var offers = new ArrayList<Head>();
            for (var head : upper) {
                double gap = head.gap(), rise = (head.y() - box.y()) / gap;
                if (gap < 10 || gap > 24 || rise < 3.6 || rise > 5.6 || box.bottom() > head.top() + .6 * gap
                        || head.x() < box.left() || head.x() > box.right()
                        || box.right() - box.left() > (expected == 1 ? 1.8 : 4.5) * gap) continue;
                var glyph = detachedOcrFinger(raw, width, height, box, head, digit);
                if (glyph != null && glyph.equals(detachedOcrFinger(normalized, width, height, box, head, digit)))
                    offers.add(head);
            }
            if (offers.size() != expected) continue;
            if (expected == 2) {
                var a = offers.get(0);
                var b = offers.get(1);
                if (a.note().measureIndex() != b.note().measureIndex()
                        || Math.abs(a.x() - b.x()) < a.gap() * 1.45
                        || Math.abs(a.x() - b.x()) > a.gap() * 2.65) continue;
            }
            for (var head : offers) {
                int owner = head.index();
                if (marks[owner] == null && !ambiguous[owner]) marks[owner] = new Mark(owner, digit, Placement.ABOVE);
            }
        }
    }

    private static SixthFinger detachedOcrFinger(byte[] ink, int width, int height, WordBox box,
            Head head, int digit) {
        double gap = head.gap();
        int left = Math.max((int) Math.floor(box.left()), (int) Math.round(head.x() - .95 * gap));
        int right = Math.min((int) Math.ceil(box.right()), (int) Math.round(head.x() + .95 * gap));
        int top = (int) Math.floor(box.top()), bottom = (int) Math.ceil(box.bottom());
        if (left < 0 || right >= width || top < 0 || bottom >= height || right <= left || bottom <= top
                || bottom - top > 2.75 * gap) return null;
        SixthFinger found = null;
        for (var glyph : RawNumeralComponents.capture(ink, width, height, left, top, right, bottom,
                165, () -> {}, () -> {}).components()) {
            int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
            if (glyph.left <= left || glyph.right >= right || glyph.top <= top || glyph.bottom >= bottom
                    || w < .55 * gap || w > 1.15 * gap || h < .9 * gap || h > 1.35 * gap
                    || Math.abs((glyph.left + glyph.right) * .5 - head.x()) > .35 * gap) continue;
            var reading = JoinedDigitTemplates.inspect(ink, width, height, new BitSet(), glyph.left, glyph.top,
                    glyph.right, glyph.bottom, () -> {});
            if (reading == null || reading.scores()[digit] < .58f || reading.scores()[digit] != reading.best()
                    || reading.best() - reading.runner() < .035f) continue;
            if (found != null) return null;
            found = new SixthFinger(glyph.left, glyph.top, glyph.right, glyph.bottom);
        }
        return found;
    }

    private record SixthFinger(int left, int top, int right, int bottom) {}

    private static void assignDottedSixthFingerings(byte[] raw, byte[] normalized, int w, int h,
            ScorePageInterpretation score, List<Head> heads, Mark[] marks, boolean[] ambiguous) {
        if (raw == null || normalized == null || raw.length != (long) w * h
                || normalized.length != raw.length) return;
        var phraseStarts = new java.util.HashSet<ScoreSlurPages.Anchor>();
        for (var span : score.slurSource().spans())
            if (span.id().contains("-descending-portato-")) phraseStarts.add(span.start());
        var byIndex = new Head[score.notes().size()];
        for (var head : heads) byIndex[head.index()] = head;
        for (var beam : score.beamSource().resolve().groups()) {
            var group = beam.source();
            if (group.tupletMark() != null || !group.bridges().equals(List.of(3, 3, 3))
                    || !group.hooks().equals(List.of(
                            new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                            new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD)))) continue;
            for (var slot : beam.columns()) {
                if (slot.ambiguous() || slot.owners().size() != 2) continue;
                var a = byIndex[slot.owners().get(0)];
                var b = byIndex[slot.owners().get(1)];
                if (a == null || b == null) continue;
                var high = a.y() < b.y() ? a : b;
                var low = high == a ? b : a;
                var barIndex = high.note().measureIndex();
                float gap = (float) high.gap() * 8 / 8.2f;
                if (barIndex != low.note().measureIndex() || high.onset() != low.onset()
                        || high.note().staffStep() - low.note().staffStep() != 5
                        || high.note().stemDirection() != -1 || low.note().stemDirection() != -1
                        || Math.abs(high.x() - low.x()) > .24f * gap || gap < 12 || gap > 24) continue;
                boolean phraseStart = score.slurSource().noteOrigins().get(low.index())
                        .filter(phraseStarts::contains).isPresent();
                if (!phraseStart) {
                    if (((high.note().articulations() | low.note().articulations()) & NoteArticulation.STACCATO) == 0)
                        offerSixthFinger(high, 3, Placement.ABOVE, raw, normalized, w, h,
                                high.y() - 2.56 * gap, high.y() - .70 * gap, gap, marks, ambiguous);
                    continue;
                }
                offerSixthFinger(high, 4, Placement.ABOVE, raw, normalized, w, h,
                        high.y() - 3.55 * gap, high.y() - 1.90 * gap, gap, marks, ambiguous);
                var bar = score.measures().get(barIndex);
                var staff = WrappedMeasureBoundaries.verifiedStaffPockets(raw, w, h, bar, true);
                var other = WrappedMeasureBoundaries.verifiedStaffPockets(normalized, w, h, bar, true);
                if (staff == null || other == null) continue;
                float rule = staff.at((float) low.x())[0];
                if (Math.abs(rule - other.at((float) low.x())[0]) > .12f * gap) continue;
                offerSixthFinger(low, 3, Placement.BELOW, raw, normalized, w, h,
                        rule + .27 * gap, rule + 1.89 * gap, gap, marks, ambiguous);
            }
        }
    }

    private static void offerSixthFinger(Head head, int digit, Placement placement,
            byte[] raw, byte[] normalized, int w, int h, double top, double bottom, float gap,
            Mark[] marks, boolean[] ambiguous) {
        int owner = head.index();
        var candidate = new Mark(owner, digit, placement);
        if (ambiguous[owner] || marks[owner] != null && !candidate.equals(marks[owner])) return;
        var detected = sixthFinger(raw, w, h, digit, head.x(), top, bottom, gap);
        if (detected != null && detected.equals(sixthFinger(normalized, w, h, digit, head.x(), top, bottom, gap)))
            marks[owner] = candidate;
    }

    private static SixthFinger sixthFinger(byte[] gray, int w, int h, int digit,
            double headX, double top, double bottom, float gap) {
        int l = (int) Math.round(headX - .83 * gap), r = (int) Math.round(headX + .83 * gap);
        int t = (int) Math.round(top), b = (int) Math.round(bottom);
        if (l < 0 || r >= w || t < 0 || b >= h) return null;
        var region = RawNumeralComponents.capture(gray, w, h, l, t, r, b, 155, () -> {}, () -> {});
        SixthFinger result = null;
        for (var main : region.components()) {
            if (main.bottom - main.top + 1 < .97 * gap || main.bottom - main.top + 1 > 1.36 * gap
                    || main.size() < gap * gap * .40) continue;
            int minX = main.left;
            int maxX = main.right;
            // A worn lower-left terminal can separate from the rest of a printed 3.
            if (digit == 3) {
                for (var serif : region.components()) {
                    if (serif == main || serif.size() < .034 * gap * gap || serif.size() > .12 * gap * gap
                            || serif.right >= (main.left + main.right) * .5
                            || serif.right < main.left - .22 * gap
                            || serif.top < main.top + .52 * gap || serif.bottom >= main.bottom) continue;
                    minX = Math.min(minX, serif.left);
                }
            }
            if (minX <= l || maxX >= r || main.top <= t || main.bottom >= b
                    || maxX - minX + 1 < .66 * gap || maxX - minX + 1 > 1.16 * gap
                    || Math.abs((minX + maxX) * .5 - headX) > .23 * gap) continue;
            var box = new SixthFinger(minX, main.top, maxX, main.bottom);
            var read = JoinedDigitTemplates.inspect(gray, w, h, new BitSet(), minX, main.top,
                    maxX, main.bottom, () -> {});
            if (read == null || (digit == 4 ? read.digit() != 4
                    : !isOpenFingeringThree(gray, w, box, read))) continue;
            if (result != null) return null;
            result = box;
        }
        return result;
    }

    private static boolean isOpenFingeringThree(byte[] gray, int w, SixthFinger box,
            JoinedDigitTemplates.Reading reading) {
        var scores = reading.scores();
        if (scores[3] < .63f || scores[3] - Math.max(Math.max(scores[0], scores[1]),
                Math.max(scores[2], scores[4])) < .044f) return false;
        int width = box.right - box.left + 1;
        int height = box.bottom - box.top + 1;
        boolean upperNotch = false;
        boolean lowerNotch = false;
        // Both mouths must open from the left onto a continuous right-side stroke.
        for (int y = height / 4; y <= height * 2 / 3; y++) {
            int start = (y + box.top) * w + box.left;
            int first = 0;
            while (first < width && (gray[start + first] & 255) >= 155) first++;
            int body = 0;
            for (int x = first; x < width; x++) if ((gray[start + x] & 255) < 155) body++;
            if (first < .43f * width || body < .27f * width) continue;
            if (y <= height * .43f) upperNotch = true;
            if (y >= height * .52f) lowerNotch = true;
        }
        return upperNotch && lowerNotch;
    }

    private static void assignSlurredSixthFingeringStacks(byte[] gray, byte[] normalized, int width,
            int height, ScorePageInterpretation score, List<Head> heads, Mark[] marks, boolean[] ambiguous) {
        if (gray == null || normalized == null || gray.length != (long) width * height
                || normalized.length != gray.length) return;
        var phrased = new java.util.HashSet<ScoreSlurPages.Anchor>();
        for (var span : score.slurSource().spans())
            if (span.id().startsWith("printed-sixth-phrase:")) phrased.add(span.start());
        var measures = new java.util.HashSet<Integer>();
        var byIndex = new Head[score.notes().size()];
        for (var head : heads) {
            byIndex[head.index()] = head;
            if (score.slurSource().noteOrigins().get(head.index()).filter(phrased::contains).isPresent())
                measures.add(head.note().measureIndex());
        }
        if (measures.isEmpty()) return;
        for (var group : score.beamSource().resolve().groups()) {
            if (!group.source().bridges().equals(List.of(3, 3, 3)) || !group.source().hooks().isEmpty()
                    || group.source().tupletMark() != null) continue;
            for (var column : group.columns()) {
                if (column.ambiguous() || column.owners().size() != 2) continue;
                var a = byIndex[column.owners().get(0)];
                var b = byIndex[column.owners().get(1)];
                if (a == null || b == null) continue;
                var upper = a.y() < b.y() ? a : b;
                var lower = upper == a ? b : a;
                var note = upper.note();
                if (!measures.contains(note.measureIndex()) || note.measureIndex() != lower.note().measureIndex()
                        || note.staffStep() - lower.note().staffStep() != 5
                        || note.stemDirection() != -1 || lower.note().stemDirection() != -1
                        || ambiguous[upper.index()] || ambiguous[lower.index()]) continue;
                var high = new Mark(upper.index(), 4, Placement.ABOVE);
                var low = new Mark(lower.index(), 3, Placement.ABOVE);
                if (marks[upper.index()] != null && !high.equals(marks[upper.index()])
                        || marks[lower.index()] != null && !low.equals(marks[lower.index()])) continue;
                if (sixthStackInk(gray, width, height, upper) && sixthStackInk(normalized, width, height, upper)) {
                    marks[upper.index()] = high;
                    marks[lower.index()] = low;
                }
            }
        }
    }

    private static boolean sixthStackInk(byte[] plane, int width, int height, Head over) {
        double gap = over.gap() * 8 / 8.2;
        if (gap < 12 || gap > 24) return false;
        int left = (int) Math.round(over.x() - gap * .84), right = (int) Math.round(over.x() + gap * .84);
        int top = (int) Math.round(over.y() - gap * 4.37), bottom = (int) Math.round(over.y() - gap * .46);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var components = RawNumeralComponents.capture(plane, width, height, left, top, right, bottom,
                165, () -> {}, () -> {}).components().stream().filter(c -> c.left > left && c.right < right
                        && c.top > top && c.bottom < bottom && c.bottom - c.top + 1 >= gap * .58
                        && c.bottom - c.top + 1 <= gap * 1.37).toList();
        int readings = 0;
        for (var low : components) {
            var reading = JoinedDigitTemplates.inspect(plane, width, height, new BitSet(),
                    low.left, low.top, low.right, low.bottom, () -> {});
            if (!clearlyFingeringThree(reading) || over.y() - low.bottom < .54 * gap
                    || over.y() - low.bottom > 1.25 * gap) continue;
            for (int i = 0; i < components.size(); i++) {
                var high = components.get(i);
                if (sixthStackFour(plane, width, height, gap, low, high.left, high.top, high.right, high.bottom))
                    readings++;
                for (int j = i + 1; j < components.size(); j++) {
                    var other = components.get(j);
                    if (Math.min(high.bottom, other.bottom) - Math.max(high.top, other.top) < .30 * gap
                            || Math.abs(high.left - other.left) > .61 * gap) continue;
                    if (sixthStackFour(plane, width, height, gap, low,
                            Math.min(high.left, other.left), Math.min(high.top, other.top),
                            Math.max(high.right, other.right), Math.max(high.bottom, other.bottom))) readings++;
                }
            }
        }
        return readings == 1;
    }

    private static boolean clearlyFingeringThree(JoinedDigitTemplates.Reading reading) {
        if (reading == null) return false;
        var scores = reading.scores();
        return scores[3] >= .68f && scores[3] - Math.max(Math.max(scores[0], scores[1]),
                Math.max(scores[2], scores[4])) >= .09f;
    }

    private static boolean sixthStackFour(byte[] plane, int width, int height, double gap,
            RawNumeralComponents.Component low, int left, int top, int right, int bottom) {
        if (low.top - bottom < gap * .10 || low.top - bottom > gap * .81 || bottom - top + 1 < gap * .80
                || bottom - top + 1 > gap * 1.37 || right - left + 1 < gap * .61
                || right - left + 1 > gap * 1.18
                || Math.abs(left + right - low.left - low.right) > gap * .55) return false;
        var reading = JoinedDigitTemplates.inspect(plane, width, height, new BitSet(),
                left, top, right, bottom, () -> {});
        return reading != null && reading.digit() == 4;
    }

    private static void assignAttachedOctaveStacks(byte[] raw, byte[] normalized, int width, int height,
            ScorePageInterpretation score, List<Head> heads, Mark[] marks, boolean[] ambiguous) {
        var source = score.beamSource();
        var byAnchor = new HashMap<ScoreBeamSource.Anchor, Head>();
        for (var head : heads)
            source.noteOrigins().get(head.index()).ifPresent(a -> byAnchor.put(a, head));
        for (var group : source.groups()) {
            if (!group.id().startsWith("beam-page:") || !group.bridges().equals(List.of(3, 3, 3))
                    || group.tupletMark() != null || !group.hooks().isEmpty()
                    || group.columns().stream().anyMatch(c -> c.members().size() != 2)) continue;
            var column = group.columns().get(3).members();
            var a = byAnchor.get(column.get(0));
            var b = byAnchor.get(column.get(1));
            if (a == null || b == null || a.note().measureIndex() != b.note().measureIndex()
                    || a.note().staffIndex() != b.note().staffIndex()
                    || a.note().staffCount() != 1 || b.note().staffCount() != 1) continue;
            var upper = a.y() < b.y() ? a : b;
            var lower = a.y() < b.y() ? b : a;
            if (ambiguous[upper.index()] || ambiguous[lower.index()]
                    || !compatibleAttachedMark(marks[upper.index()], 3)
                    || !compatibleAttachedMark(marks[lower.index()], 1)) continue;
            float gap = (float) upper.gap() * 8f / 8.2f;
            if (!attachedOctaveStack(raw, width, height, upper, lower, gap)
                    || !attachedOctaveStack(normalized, width, height, upper, lower, gap)) continue;
            marks[upper.index()] = new Mark(upper.index(), 3, Placement.BELOW);
            marks[lower.index()] = new Mark(lower.index(), 1, Placement.BELOW);
        }
    }

    private static boolean compatibleAttachedMark(Mark existing, int digit) {
        return existing == null || existing.placement() == Placement.BELOW && existing.digit() == digit;
    }

    private static boolean attachedOctaveStack(
            byte[] ink, int width, int height, Head upper, Head lower, float gap) {
        return ClosedSixteenthDyads.retainsTerminalOctaveFingering(ink, width, height,
                upper.index(), upper.note(), (float) upper.x(), (float) upper.y(),
                lower.index(), lower.note(), (float) lower.x(), (float) lower.y(), gap);
    }

    private record FingerMask(int left, int top, int right, int bottom, BitSet bits) {
        float centerX() { return (left + right) * .5f; }
    }

    private record FingerReference(int digit, FingerMask raw, FingerMask normalized) {}

    private static void assignHighTripletFingerings(byte[] raw, byte[] normalized, int width, int height,
            ScorePageInterpretation score, List<Head> heads, List<WordBox> boxes, Mark[] marks, boolean[] ambiguous) {
        var source = score.beamSource();
        var owners = new HashMap<ScoreBeamSource.Anchor, Integer>();
        for (int i=0; i<source.noteOrigins().size(); i++) {
            var anchor = source.noteOrigins().get(i);
            if (anchor.isPresent()) owners.merge(anchor.get(), i, (first, second) -> -1);
        }
        var locations = new Head[marks.length];
        for (var head : heads) locations[head.index()] = head;
        List<FingerReference> references = null;
        var ruledOffers = new ArrayList<RuledFingerOffer>();
        for (var group : source.groups()) {
            if (group.tupletMark() == null || !group.bridges().equals(List.of(1,1)) || !group.hooks().isEmpty()
                    || group.columns().stream().anyMatch(c -> c.members().size() != 1)) continue;
            var members = new ArrayList<Head>(3);
            for (var column : group.columns()) {
                int owner = owners.getOrDefault(column.members().get(0), -1);
                if (owner >= 0 && locations[owner] != null) members.add(locations[owner]);
            }
            if (members.size() != 3 || members.stream().anyMatch(h -> h.harmonic()
                    || h.note().kind() != ScoreNoteEvent.Kind.PITCHED
                    || h.note().visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || h.note().tupletDivisor() != 3 || h.note().tupletNormalNotes() != 2
                    || h.note().beamCount() != 1 || h.note().crossStaffBeam()
                    || h.note().measureIndex() != members.get(0).note().measureIndex())) continue;
            boolean numbered = group.tupletMark().numbered();
            if (!numbered) {
                if (references == null)
                    references = highFingerReferences(raw, normalized, width, height, heads, boxes, marks, ambiguous);
                assignUnnumberedTerminalFinger(raw, normalized, width, height, score, members,
                        heads, boxes, marks, ambiguous, references, ruledOffers);
            }
            if (numbered && members.stream().anyMatch(h -> h.note().stemDirection() != 1)) continue;
            for (int i = numbered ? 0 : 1; i < (numbered ? 3 : 2); i++) {
                var head=members.get(i);
                if (marks[head.index()] != null || ambiguous[head.index()]
                        || !numbered && head.note().stemDirection() != -1) continue;
                float gap = (float) head.gap() * 8f / 8.2f;
                if (gap < 10 || gap > 26) continue;
                WordBox word = null;
                if (numbered) {
                    for (var box : boxes) {
                        if ((box.digit() != 2 && box.digit() != 4) || Math.abs(box.x()-head.x()) > .35f*gap
                                || head.y()-box.y() < 4.2f*gap || head.y()-box.y() > 7.2f*gap) continue;
                        if (word != null) { word = null; break; }
                        word = box;
                    }
                    if (word == null) continue;
                }
                var a=highTripletDigit(raw,width,height,members,i,gap,word);
                var b=highTripletDigit(normalized,width,height,members,i,gap,word);
                if (a == null || b == null || a.left()!=b.left() || a.right()!=b.right()
                        || a.top()!=b.top() || a.bottom()!=b.bottom()) continue;
                if (references == null)
                    references=highFingerReferences(raw,normalized,width,height,heads,boxes,marks,ambiguous);
                int digit = word == null ? 1 : word.digit();
                if (matchesKnownFinger(a,references,digit,false) && matchesKnownFinger(b,references,digit,true))
                    marks[head.index()] = new Mark(head.index(),digit,Placement.ABOVE);
            }
        }
        for (var offer : ruledOffers) {
            if (marks[offer.owner()] != null || ambiguous[offer.owner()]) continue;
            var peers = ruledOffers.stream().filter(peer -> peer.measure() == offer.measure()
                    && peer.digit() == offer.digit() && peer.strong()
                    && peer.owner() != offer.owner()).toList();
            if (peers.stream().anyMatch(peer -> peer.raw().matches(offer.raw())
                    && peer.normalized().matches(offer.normalized())))
                marks[offer.owner()] = new Mark(offer.owner(), offer.digit(), Placement.BELOW);
        }
    }

    private record TerminalDigit(int digit, int left, int top, int right, int bottom,
            boolean accepted, boolean strong, RuledFingerInk ruled) {
        boolean sameGlyph(TerminalDigit other) {
            return other != null && digit == other.digit && left == other.left && top == other.top
                    && right == other.right && bottom == other.bottom;
        }
    }

    private record RuledFingerOffer(int owner, int measure, int digit, boolean strong,
            RuledFingerInk raw, RuledFingerInk normalized) {}

    private record RuledFingerInk(BitSet visible, BitSet pixels) {
        boolean matches(RuledFingerInk other) {
            var observed = (BitSet) visible.clone();
            observed.and(other.visible);
            if (observed.cardinality() < 440) return false;
            var p = (BitSet) pixels.clone();
            p.and(observed);
            var q = (BitSet) other.pixels.clone();
            q.and(observed);
            var union = (BitSet) p.clone();
            union.or(q);
            p.and(q);
            return p.cardinality() >= union.cardinality() * .78f;
        }
    }

    private static void assignUnnumberedTerminalFinger(byte[] raw, byte[] normalized, int width, int height,
            ScorePageInterpretation score, List<Head> group, List<Head> heads, List<WordBox> boxes,
            Mark[] marks, boolean[] ambiguous, List<FingerReference> references, List<RuledFingerOffer> ruledOffers) {
        var head = group.get(2);
        var note = head.note();
        if (marks[head.index()] != null || ambiguous[head.index()] || note.staffCount() != 1
                || Math.abs(note.stemDirection()) != 1
                || group.stream().anyMatch(h -> h.note().stemDirection() != note.stemDirection())) return;
        float gap = (float) head.gap() * 8f / 8.2f;
        if (gap < 10 || gap > 25) return;
        boolean above = note.stemDirection() == -1 && group.stream().allMatch(h -> h.note().staffStep() >= 10);
        for (int i = firstHeadAt(heads, head.x() - gap * .95f);
                i < heads.size() && heads.get(i).x() <= head.x() + gap * .95f; i++) {
            var rival = heads.get(i);
            if (rival.index() != head.index() && Math.abs(rival.y() - head.y()) < gap * 5.5f) return;
        }
        var tokens = boxes.stream().filter(box -> Math.abs(box.x() - head.x()) <= gap * .55f
                && (above ? head.y() - box.y() : box.y() - head.y()) >= gap * .68f
                && Math.abs(box.y() - head.y()) <= gap * 4.25f).toList();
        if (tokens.size() > 1) return;
        var token = tokens.isEmpty() ? null : tokens.get(0);
        if (token != null && (token.digit() < 1 || token.digit() > 4) || token == null && !above) return;
        var region = score.measures().get(note.measureIndex());
        var a = terminalDigit(raw, width, height, region, head, token, gap, above, references, false);
        var b = terminalDigit(normalized, width, height, region, head, token, gap, above, references, true);
        if (a == null || !a.sameGlyph(b)) return;
        if (a.accepted() && b.accepted())
            marks[head.index()] = new Mark(head.index(), a.digit(), above ? Placement.ABOVE : Placement.BELOW);
        if (a.ruled() != null && b.ruled() != null)
            ruledOffers.add(new RuledFingerOffer(head.index(), note.measureIndex(), a.digit(),
                    a.strong() && b.strong(), a.ruled(), b.ruled()));
    }

    private static TerminalDigit terminalDigit(byte[] ink, int width, int height, MeasureRegion bar,
            Head head, WordBox word, float gap, boolean above, List<FingerReference> references, boolean normalized) {
        int left = (int) Math.round(head.x() - gap * .97f), right = (int) Math.round(head.x() + gap * .97f);
        int top = (int) Math.round(head.y() - gap * 2.9f), bottom = (int) Math.round(head.y() - gap * .8f);
        var excluded = new BitSet();
        if (!above && head.note().stemDirection() == -1) {
            var shafts = PrintedBeamShafts.at(ink, width, height, (float) head.x(), (float) head.y(),
                    gap, new PrintedBeamShafts.Budget(50_000));
            if (shafts.size() != 1 || shafts.get(0).direction() != 1) return null;
            top = shafts.get(0).endY() + 1;
            bottom = (int) Math.round(Math.min(word.bottom(), head.y() + gap * 4.8f));
        }
        if (!above && head.note().stemDirection() == 1) {
            left = (int) Math.round(word.x() - gap * .77f);
            right = (int) Math.round(word.x() + gap * .77f);
            top = (int) Math.round(Math.max(word.top(), head.y() + gap * .86f));
            bottom = (int) Math.round(Math.min(word.bottom(), head.y() + gap * 2.72f));
            if (!maskFingeringStaffRules(ink, width, height, bar, head, gap, left, top, right, bottom, excluded))
                return null;
        }
        if (left < 0 || right >= width || top < 0 || bottom >= height || bottom <= top) return null;
        int l = right, r = left, t = bottom, b = top, area = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int pixel = y * width + x;
                if (excluded.get(pixel) || (ink[pixel] & 255) > 165) continue;
                l = Math.min(l, x); r = Math.max(r, x);
                t = Math.min(t, y); b = Math.max(b, y);
                area++;
            }
        }
        boolean boundedBeforeGlyph = above || head.note().stemDirection() == 1;
        if (l <= left || r >= right || boundedBeforeGlyph && t <= top || b >= bottom || b - t < gap * .63f || b - t > gap * 1.26f
                || r - l < gap * .36f || r - l > gap * 1.1f || area < gap * gap * .22f
                || Math.abs((l + r) * .5 - head.x()) > gap * .60f) return null;
        var reading = JoinedDigitTemplates.inspect(ink, width, height, excluded, l, t, r, b, () -> {});
        if (reading == null) return null;
        int digit = word == null ? reading.digit() : word.digit();
        boolean accepted = true, strong = false;
        RuledFingerInk ruled = null;
        if (word == null) {
            var mask = fingerMask(ink, width, height, l - 1, t - 1, r + 1, b + 1, gap);
            int match = -1;
            if (mask != null) for (int n = 1; n <= 4; n++) {
                if (!matchesKnownFinger(mask, references, n, normalized)) continue;
                if (match >= 0) return null;
                match = n;
            }
            if (match >= 0) digit = match;
            else if (digit < 1 || digit > 4 || reading.best() < .65f || reading.best() - reading.runner() < .085f)
                return null;
        } else {
            float[] scores = reading.scores();
            float rival = 0;
            for (int n = 0; n < scores.length; n++) if (n != digit) rival = Math.max(rival, scores[n]);
            accepted = scores[digit] >= .60f && scores[digit] - rival >= .035f;
            strong = accepted && scores[digit] - rival >= .085f;
            if (excluded.isEmpty()) {
                if (!accepted) return null;
            } else {
                if (!accepted && (scores[digit] < .58f || scores[digit] < rival - .025f)) return null;
                ruled = ruledFingerInk(ink, width, excluded, l, t, r, b);
            }
        }
        return new TerminalDigit(digit, l, t, r, b, accepted, strong, ruled);
    }

    private static RuledFingerInk ruledFingerInk(byte[] ink, int width, BitSet excluded,
            int l, int t, int r, int b) {
        var visible = new BitSet(640);
        var pixels = new BitSet(640);
        for (int dy = 0; dy < 32; dy++) for (int dx = 0; dx < 20; dx++) {
            int x = l + Math.min(r - l, (2 * dx + 1) * (r - l + 1) / 40);
            int y = t + Math.min(b - t, (2 * dy + 1) * (b - t + 1) / 64);
            int at = y * width + x;
            int index = dy * 20 + dx;
            if (excluded.get(at)) continue;
            visible.set(index);
            if ((ink[at] & 255) <= 165) pixels.set(index);
        }
        return new RuledFingerInk(visible, pixels);
    }

    private static boolean maskFingeringStaffRules(byte[] ink, int width, int height, MeasureRegion bar,
            Head head, float gap, int left, int top, int right, int bottom, BitSet excluded) {
        int flank = Math.round(gap * .6f);
        if (left - flank < 0 || right + flank >= width || top < 0 || bottom >= height) return false;
        var staff = WrappedMeasureBoundaries.verifiedStaffPockets(ink, width, height, bar, true);
        if (staff == null) return false;
        var frame = staff.at((float) head.x());
        for (int line = 0; line < 5; line++) {
            int nominal = Math.round(frame[0] - line * frame[1]), band = Math.round(gap * .22f);
            int linked = 0;
            for (int y = Math.max(top, nominal - band); y <= Math.min(bottom, nominal + band); y++) {
                int before = 0, after = 0;
                for (int n = 1; n <= flank; n++) {
                    if ((ink[y * width + left - n] & 255) <= 165) before++;
                    if ((ink[y * width + right + n] & 255) <= 165) after++;
                }
                if (Math.min(before, after) >= flank * .72f) {
                    if (++linked > gap * .28f) return false;
                    excluded.set(y * width + left, y * width + right + 1);
                }
            }
        }
        return true;
    }

    private static FingerMask highTripletDigit(byte[] image, int width, int height,
            List<Head> group, int index, float gap, WordBox word) {
        var h = group.get(index);
        if (word == null) {
            var stem=PrintedBeamShafts.at(image,width,height,(float)h.x(),(float)h.y(),gap,
                    new PrintedBeamShafts.Budget(50_000));
            if (stem.size()!=1 || stem.get(0).direction()!=1) return null;
            int l=(int)Math.round(h.x()-.52f*gap), r=(int)Math.round(h.x()+.52f*gap);
            int t=(int)Math.round(h.y()-4.45f*gap), b=(int)Math.round(h.y()-2.35f*gap);
            if (l<0 || r>=width || t<0 || b>=height) return null;
            var bits=RawNumeralComponents.capture(image,width,height,l,t,r,b,165,()->{},()->{});
            var one=bits.components().stream().filter(g -> g.size()>=gap*gap*.16f
                    && g.left>l && g.right<r && g.top>t && g.bottom<b
                    && UnnumberedTripletInk.isUnambiguousOne(image,width,g)).toList();
            if (one.size()!=1) return null;
            var g=one.get(0);
            return fingerMask(image,width,height,g.left-1,g.top-1,g.right+1,g.bottom+1,gap);
        }
        var a=OpeningShaftTrace.scan(image,width,height,(float)group.get(0).x(),
                (float)group.get(0).y(),gap,-1);
        var c=OpeningShaftTrace.scan(image,width,height,(float)group.get(group.size() - 1).x(),
                (float)group.get(group.size() - 1).y(),gap,-1);
        if (a==null || c==null) return null;
        var stem=index==0?a:index==2?c:OpeningShaftTrace.anchored(image,width,height,
                (float)h.x(),(float)h.y(),gap,a,c);
        if (stem==null) return null;
        var ink=fingerMask(image,width,height,(int)Math.round(word.left()),(int)Math.round(word.top()),
                (int)Math.round(word.right()),(int)Math.round(word.bottom()),gap);
        if (ink==null || Math.abs(ink.centerX()-h.x()) > .35f*gap
                || stem.endY()-ink.bottom()<gap*.5f || stem.endY()-ink.bottom()>gap*2.1f) return null;
        return ink;
    }

    private static List<FingerReference> highFingerReferences(byte[] raw, byte[] normalized, int width, int height,
            List<Head> heads, List<WordBox> boxes, Mark[] marks, boolean[] ambiguous) {
        var result = new ArrayList<FingerReference>();
        for (var box : boxes) {
            if (box.digit() < 1 || box.digit() > 4) continue;
            int matches=0;
            float gap=0;
            for (int i=firstHeadAt(heads,box.x()-14);
                    i<heads.size() && heads.get(i).x()<=box.x()+14; i++) {
                var h=heads.get(i);
                var mark=marks[h.index()];
                double distance=h.y()-box.y();
                if (mark == null || ambiguous[h.index()] || mark.digit()!=box.digit()
                        || Math.abs(box.x()-h.x()) > .36f*h.gap()
                        || Math.abs(distance)<h.gap()*1.15f || Math.abs(distance)>h.gap()*3.3f
                        || (distance>0)!=(mark.placement()==Placement.ABOVE)) continue;
                gap=(float)h.gap();
                matches++;
            }
            if (matches!=1) continue;
            int l=(int)Math.round(box.left()),t=(int)Math.round(box.top());
            int r=(int)Math.round(box.right()),b=(int)Math.round(box.bottom());
            var a=fingerMask(raw,width,height,l,t,r,b,gap);
            var n=fingerMask(normalized,width,height,l,t,r,b,gap);
            if (a!=null && n!=null) result.add(new FingerReference(box.digit(),a,n));
        }
        return List.copyOf(result);
    }

    private static boolean matchesKnownFinger(FingerMask mask, List<FingerReference> references, int digit,
            boolean normalized) {
        float best = 0, rival = 0;
        int agreeing = 0;
        for (var reference : references) {
            var other=normalized?reference.normalized():reference.raw();
            var shared=(BitSet)mask.bits().clone();
            shared.and(other.bits());
            int overlap=shared.cardinality();
            float similarity=overlap/(float)(mask.bits().cardinality()+other.bits().cardinality()-overlap);
            if (reference.digit()==digit) {
                best=Math.max(best,similarity);
                if (similarity>=.74f) agreeing++;
            } else rival=Math.max(rival,similarity);
        }
        return agreeing>=2 && best>=.78f && best-rival>=.10f;
    }

    private static FingerMask fingerMask(byte[] gray,int w,int h,int left,int top,int right,int bottom,float gap) {
        if (left<0 || top<0 || right>=w || bottom>=h || right<=left || bottom<=top
                || right-left>gap*2.3f || bottom-top>gap*2.9f) return null;
        int l=right, r=left, t=bottom, b=top;
        for (int y=top;y<=bottom;y++) for (int x=left;x<=right;x++) {
            if ((gray[y*w+x]&255)>165) continue;
            l=Math.min(l,x); r=Math.max(r,x); t=Math.min(t,y); b=Math.max(b,y);
        }
        if (l<=left || r>=right || t<=top || b>=bottom || b-t<gap*.65f || b-t>gap*1.65f
                || r-l<gap*.30f || r-l>gap*1.15f) return null;
        var bits=new BitSet(640);
        for (int dy=0;dy<32;dy++) for (int dx=0;dx<20;dx++) {
            int x=l+Math.min(r-l, (2*dx+1)*(r-l+1)/40);
            int y=t+Math.min(b-t, (2*dy+1)*(b-t+1)/64);
            if ((gray[y*w+x]&255)<=165) bits.set(dy*20+dx);
        }
        return bits.isEmpty()?null:new FingerMask(l,t,r,b,bits);
    }

    private static void assignStaffCrossedTripletThrees(
            byte[] raw, byte[] normalized, int width, int height, List<SheetInterpreter.Word> words,
            ScorePageInterpretation score, List<Head> heads, Mark[] candidates) {
        var tokens = words.stream().map(word -> new PlayingTechniqueDetector.Word(
                word.text(), word.left(), word.top(), word.right(), word.bottom())).toList();
        for (var token : tokens) {
            if (!token.text().equals("8") && !token.text().equals("9")) continue;
            float x = (token.left() + token.right()) * width * .5f;
            float y = (token.top() + token.bottom()) * height * .5f;
            var nearby = heads.stream().filter(head -> {
                var note = head.note();
                double gap = head.gap();
                return candidates[head.index()] == null && note.kind() == ScoreNoteEvent.Kind.PITCHED
                        && note.stemDirection() == -1 && note.staffIndex() == 0 && note.staffCount() == 1
                        && note.beamCount() == 1 && note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2
                        && !head.harmonic() && !note.crossStaffBeam()
                        && Math.abs(head.x() - x) <= gap * .25 && y - head.y() >= gap * 4.2
                        && y - head.y() <= gap * 8 && y <= head.bottom();
            }).toList();
            if (nearby.size() != 1) continue;
            var head = nearby.get(0);
            var bar = score.measures().get(head.note().measureIndex());
            if (!FingeringAnnotationFilter.crossedFingeringThreeInk(tokens, bar, raw, normalized,
                    width, height, x, y)) continue;

            // Both planes must preserve the real downstem and a clear gap from its beam to the numeral.
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            var printed = PairedTremoloInk.stem(raw, width, height, (float) head.x(), (float) head.y(), gap, -1);
            var corrected = PairedTremoloInk.stem(normalized, width, height,
                    (float) head.x(), (float) head.y(), gap, -1);
            if (printed == null || corrected == null || Math.abs(printed.x() - corrected.x()) > gap * .12f
                    || Math.abs(printed.endY() - corrected.endY()) > gap * .17f
                    || printed.endY() > y - gap * 1.15f || corrected.endY() > y - gap * 1.15f) continue;
            candidates[head.index()] = new Mark(head.index(), 3, Placement.BELOW);
        }
    }

    private static void assignTripletFingeringOnes(
            byte[] raw, byte[] normalized, int width, int height, ScorePageInterpretation score,
            List<WordBox> boxes, Mark[] candidates, boolean[] ambiguous) {
        var beamSource = score.beamSource();
        var owners = new HashMap<ScoreBeamSource.Anchor, Integer>();
        var origins = beamSource.noteOrigins();
        for (int i = 0; i < origins.size(); i++)
            if (origins.get(i).isPresent()) owners.merge(origins.get(i).get(), i, (first, second) -> -1);
        for (var group : beamSource.groups()) {
            if (group.tupletMark() != ScoreBeamSource.TupletMark.UNNUMBERED
                    || !group.hooks().isEmpty() || group.columns().stream().anyMatch(c -> c.members().size() != 1))
                continue;
            int owner = owners.getOrDefault(group.columns().get(1).members().get(0), -1);
            if (owner < 0 || candidates[owner] != null || ambiguous[owner]) continue;
            var note = score.notes().get(owner);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.tupletDivisor() != 3 || note.beamCount() != 1 || note.crossStaffBeam()
                    || note.harmonicSemitones() != 0 || note.openStringTouch() != null) continue;
            var region = score.measures().get(note.measureIndex());
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            if (gap < 10 || gap > 26) continue;
            float x = (region.left() + note.positionInMeasure() * (region.right() - region.left())) * width;
            float y = note.pageY() * height;
            var a = isolatedTripletOne(raw, width, height, x, y, gap, note.stemDirection());
            if (a == null) continue;
            var b = raw == normalized ? a
                    : isolatedTripletOne(normalized, width, height, x, y, gap, note.stemDirection());
            if (b == null || a.left != b.left || a.right != b.right || a.top != b.top || a.bottom != b.bottom)
                continue;
            if (boxes.stream().anyMatch(word -> word.digit() != 1 && word.right() > a.left
                    && word.left() <= a.right && word.bottom() > a.top && word.top() <= a.bottom)) continue;
            candidates[owner] = new Mark(owner, 1, Placement.ABOVE);
        }
    }

    private static RawNumeralComponents.Component isolatedTripletOne(
            byte[] plane, int width, int height, float x, float y, float gap, int stemDirection) {
        var shafts = PrintedBeamShafts.at(plane, width, height, x, y, gap, new PrintedBeamShafts.Budget(50_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != -stemDirection) return null;
        float anchor = Math.min(y, shafts.get(0).endY());
        int left = Math.round(x - .8f * gap), right = Math.round(x + .8f * gap);
        int top = Math.round(anchor - 3.6f * gap), bottom = Math.round(anchor - .28f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var symbols = RawNumeralComponents.captureLongWindow(plane, width, height, left, top, right, bottom, 165);
        RawNumeralComponents.Component found = null;
        for (var symbol : symbols.components()) {
            int tall = symbol.bottom - symbol.top + 1, wide = symbol.right - symbol.left + 1;
            float clearance = anchor - symbol.bottom;
            if (symbol.left <= left || symbol.right >= right || symbol.top <= top || symbol.bottom >= bottom
                    || Math.abs((symbol.left + symbol.right) * .5f - x) > .36f * gap
                    || clearance < (stemDirection > 0 ? .36f : .86f) * gap || clearance > 2.2f * gap
                    || tall < .8f * gap || tall > 1.7f * gap || wide < .35f * gap || wide > .77f * tall
                    || !UnnumberedTripletInk.isUnambiguousOne(plane, width, symbol)) continue;
            var match = JoinedDigitTemplates.inspect(plane, width, height, new BitSet(),
                    symbol.left, symbol.top, symbol.right, symbol.bottom, () -> {});
            if (match == null || match.digit() != 1 || match.best() < .64f
                    || match.best() - match.runner() < .12f) continue;
            if (found != null) return null;
            found = symbol;
        }
        return found;
    }

    private static void assignRuleCrossingTwos(byte[] raw, byte[] normalized, int width, int height,
            ScorePageInterpretation score, List<Head> heads, double widestGap,
            List<WordBox> boxes, Mark[] candidates) {
        double radius = .33 * widestGap;
        for (var box : boxes) {
            if (box.digit() != 2) continue;
            Head owner = null;
            for (int index = firstHeadAt(heads, box.x() - radius);
                    index < heads.size() && heads.get(index).x() <= box.x() + radius; index++) {
                var head = heads.get(index);
                var region = score.measures().get(head.note().measureIndex());
                if (!ordinaryAttack(head) || !BeamedStaffFingeringTwo.owns(head.note(), region,
                        normalized, raw, width, height, (float) box.left(), (float) box.top(),
                        (float) box.right(), (float) box.bottom())) continue;
                if (owner != null) {
                    owner = null;
                    break;
                }
                owner = head;
            }
            if (owner != null && candidates[owner.index()] == null)
                candidates[owner.index()] = new Mark(owner.index(), 2, Placement.BELOW);
        }
    }

    /** Above a pair of beamed thirds, the printed slur can identify both upper fingerings. */
    private static void assignSlurredThirdFingers(
            byte[] gray, byte[] normalized, int width, int height, ScorePageInterpretation score,
            List<Head> heads, List<WordBox> boxes, Mark[] candidates) {
        if (score.slurSource().spans().isEmpty()) return;
        var byOwner = new Head[candidates.length];
        for (var head : heads) byOwner[head.index()] = head;
        for (var slur : score.slurSource().resolve().resolved()) {
            if (slur.placement() != ScoreSlurSpan.Placement.ABOVE
                    || slur.lineType() != ScoreSlurSpan.LineType.SOLID || slur.evidence().size() != 1)
                continue;
            var start = byOwner[slur.startOwner()];
            var end = byOwner[slur.endOwner()];
            if (start == null || end == null || start.note().measureIndex() != end.note().measureIndex()
                    || candidates[start.index()] != null || candidates[end.index()] != null
                    || Math.abs(end.onset() - start.onset() - .25) > 1.0 / 96) continue;
            var firstLower = lowerThird(start, heads);
            var lastLower = lowerThird(end, heads);
            if (firstLower == null || lastLower == null) continue;

            var ink = slur.evidence().get(0);
            var first = slurredThirdFinger(boxes, start, ink.startX() * width, ink.startY() * height, 2);
            var last = slurredThirdFinger(boxes, end, ink.endX() * width, ink.endY() * height, 1);
            if (first == null || last == null
                    || !retainsDigitInk(gray, width, height, first, start.gap())
                    || !retainsDigitInk(gray, width, height, last, end.gap())
                    || !linkedThirdFingeringShafts(gray, width, height, start, firstLower, end, lastLower)
                    || (gray != normalized
                            && (!retainsDigitInk(normalized, width, height, first, start.gap())
                                    || !retainsDigitInk(normalized, width, height, last, end.gap())
                                    || !linkedThirdFingeringShafts(normalized, width, height,
                                            start, firstLower, end, lastLower)))) continue;
            candidates[start.index()] = new Mark(start.index(), 2, Placement.ABOVE);
            candidates[end.index()] = new Mark(end.index(), 1, Placement.ABOVE);
        }
    }

    private record ThirdDigits(int lower, int upper) {}

    private static void assignBelowThirdStacks(
            byte[] raw, byte[] normalized, int width, int height, ScorePageInterpretation score,
            List<Head> heads, Mark[] candidates, boolean[] ambiguous) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || score.beamSource().groups().isEmpty()) return;
        var byAnchor = new HashMap<ScoreBeamSource.Anchor, Head>();
        var origins = score.beamSource().noteOrigins();
        if (origins.size() != candidates.length) return;
        var duplicateAnchors = new java.util.HashSet<ScoreBeamSource.Anchor>();
        for (var head : heads) origins.get(head.index()).ifPresent(anchor -> {
            if (byAnchor.putIfAbsent(anchor, head) != null) duplicateAnchors.add(anchor);
        });
        duplicateAnchors.forEach(byAnchor::remove);
        for (var group : score.beamSource().groups()) {
            if (group.columns().size() != 4 || !group.hooks().isEmpty()
                    || group.columns().stream().anyMatch(column -> column.members().size() != 2)
                    || group.bridges().stream().anyMatch(bridge -> bridge != 3)) continue;
            var first = group.columns().get(0).members();
            var next = group.columns().get(1).members();
            var a = byAnchor.get(first.get(0));
            var b = byAnchor.get(first.get(1));
            var c = byAnchor.get(next.get(0));
            var d = byAnchor.get(next.get(1));
            if (a == null || b == null || c == null || d == null) continue;
            var upper = a.y() < b.y() ? a : b;
            var lower = upper == a ? b : a;
            var nextUpper = c.y() < d.y() ? c : d;
            var nextLower = nextUpper == c ? d : c;
            int measure = upper.note().measureIndex();
            if (nextUpper.note().measureIndex() != measure || lowerThird(upper, heads) != lower
                    || lowerThird(nextUpper, heads) != nextLower
                    || Math.abs(nextUpper.onset() - upper.onset() - .25) > 1.0 / 96) continue;
            if (List.of(upper, lower, nextUpper, nextLower).stream()
                    .anyMatch(head -> candidates[head.index()] != null || ambiguous[head.index()])) continue;
            var region = score.measures().get(measure);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float firstBottom = (float) lower.y() + lower.note().staffStep() * gap * .5f;
            float nextBottom = (float) nextLower.y() + nextLower.note().staffStep() * gap * .5f;
            if (gap < 10 || gap > 24 || Math.abs(firstBottom - nextBottom) > .35f * gap) continue;
            var x = (float) lower.x();
            var z = (float) nextLower.x();
            // Both columns must carry separate, descending pairs. The four glyphs
            // have to survive the same ownership test in each source plane.
            var firstMark = belowThirdDigits(raw, width, height, x, firstBottom, gap);
            var nextMark = belowThirdDigits(raw, width, height, z, nextBottom, gap);
            if (firstMark == null || nextMark == null || firstMark.lower() != nextMark.lower() + 1
                    || firstMark.upper() != nextMark.upper() + 1
                    || !firstMark.equals(belowThirdDigits(normalized, width, height, x, firstBottom, gap))
                    || !nextMark.equals(belowThirdDigits(normalized, width, height, z, nextBottom, gap))) continue;
            var staff = WrappedMeasureBoundaries.verifiedTripletStaff(normalized, width, height, region, true);
            if (staff == null || !staff.verifiedAt(x) || !staff.verifiedAt(z)
                    || Math.abs(staff.at(x)[0] - firstBottom) > .28f * gap
                    || Math.abs(staff.at(z)[0] - nextBottom) > .28f * gap) continue;
            candidates[lower.index()] = new Mark(lower.index(), firstMark.lower(), Placement.BELOW);
            candidates[upper.index()] = new Mark(upper.index(), firstMark.upper(), Placement.BELOW);
            candidates[nextLower.index()] = new Mark(nextLower.index(), nextMark.lower(), Placement.BELOW);
            candidates[nextUpper.index()] = new Mark(nextUpper.index(), nextMark.upper(), Placement.BELOW);
        }
    }

    private static ThirdDigits belowThirdDigits(
            byte[] ink, int width, int height, float x, float floor, float gap) {
        int left = Math.round(x - .82f * gap), right = Math.round(x + .82f * gap);
        int top = Math.round(floor), bottom = Math.round(floor + 3.24f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        var glyphs = new ArrayList<RawNumeralComponents.Component>(2);
        for (var symbol : RawNumeralComponents.captureLongWindow(
                ink, width, height, left, top, right, bottom, 192).components()) {
            int tall = symbol.bottom - symbol.top + 1;
            if (tall < .75f * gap) continue;
            if (tall > 1.34f * gap || symbol.left <= left || symbol.right >= right
                    || symbol.top <= top || symbol.bottom >= bottom
                    || Math.abs((symbol.left + symbol.right) * .5f - x) > .36f * gap)
                return null;
            glyphs.add(symbol);
        }
        if (glyphs.size() != 2) return null;
        glyphs.sort(Comparator.comparingInt(glyph -> glyph.top));
        var near = glyphs.get(0);
        var far = glyphs.get(1);
        if (near.top - floor > .55f * gap || far.top - near.bottom < .18f * gap
                || far.top - near.bottom > .53f * gap) return null;
        int lower = stoppedFinger(ink, width, height, near);
        int upper = stoppedFinger(ink, width, height, far);
        return lower > 0 && upper == lower + 2 ? new ThirdDigits(lower, upper) : null;
    }

    private static int stoppedFinger(
            byte[] ink, int width, int height, RawNumeralComponents.Component glyph) {
        var reading = JoinedDigitTemplates.inspect(ink, width, height, new BitSet(),
                glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
        if (reading == null || reading.independentInk() < 100) return -1;
        if (reading.digit() >= 0) return reading.digit() <= 4 ? reading.digit() : -1;
        // A filled counter can raise the generic eight template on these small
        // digits. Closed alternatives still compete against the violin range.
        var scores = reading.scores();
        int best = 0;
        float runner = 0;
        for (int digit = 1; digit <= 4; digit++) {
            if (scores[digit] > scores[best]) {
                runner = scores[best];
                best = digit;
            } else runner = Math.max(runner, scores[digit]);
        }
        return (best == 2 || best == 3) && scores[8] == reading.best()
                && scores[best] >= .59f && scores[best] - runner >= .028f
                && reading.best() - scores[best] <= .03f ? best : -1;
    }

    private static Head lowerThird(Head upper, List<Head> heads) {
        if (!ordinaryAttack(upper) || upper.note().staffCount() != 1
                || upper.note().stemDirection() != -1 || upper.note().beamCount() != 2) return null;
        double reach = upper.gap() * .24;
        Head mate = null;
        for (int i = firstHeadAt(heads, upper.x() - reach);
                i < heads.size() && heads.get(i).x() <= upper.x() + reach; i++) {
            var lower = heads.get(i);
            if (lower == upper || lower.note().measureIndex() != upper.note().measureIndex()
                    || lower.note().staffIndex() != upper.note().staffIndex()
                    || Math.abs(lower.onset() - upper.onset()) > 1.0 / 96) continue;
            if (mate != null || !ordinaryAttack(lower) || lower.note().stemDirection() != -1
                    || lower.note().beamCount() != 2 || upper.note().staffStep() - lower.note().staffStep() != 2
                    || lower.y() - upper.y() < upper.gap() * .80
                    || lower.y() - upper.y() > upper.gap() * 1.24) return null;
            mate = lower;
        }
        return mate;
    }

    private static WordBox slurredThirdFinger(
            List<WordBox> boxes, Head head, double arcX, double arcY, int digit) {
        double gap = head.gap();
        if (Math.abs(head.x() - arcX) > .30 * gap
                || head.y() - arcY < .64 * gap || head.y() - arcY > 1.29 * gap) return null;
        WordBox found = null;
        for (var box : boxes) {
            if (Math.abs(box.x() - head.x()) > .36 * gap
                    || Math.abs(box.bottom() - arcY) > .22 * gap
                    || box.bottom() - box.top() < .92 * gap || box.bottom() - box.top() > 1.83 * gap
                    || box.right() - box.left() < .63 * gap || box.right() - box.left() > 1.70 * gap)
                continue;
            if (found != null || box.digit() != digit) return null;
            found = box;
        }
        return found;
    }

    private static boolean linkedThirdFingeringShafts(
            byte[] gray, int width, int height, Head a, Head b, Head c, Head d) {
        var budget = new PrintedBeamShafts.Budget(60_000);
        var first = thirdFingeringShaft(gray, width, height, a, b, budget);
        var last = thirdFingeringShaft(gray, width, height, c, d, budget);
        float gap = (float) a.gap();
        return first != null && last != null
                && WrappedEighthTriplets.matchesBeamRails(gray, width, height, first, last, gap, 2);
    }

    private static PrintedBeamShafts.Shaft thirdFingeringShaft(
            byte[] gray, int width, int height, Head upper, Head lower, PrintedBeamShafts.Budget budget) {
        float gap = (float) upper.gap();
        var a = PrintedBeamShafts.at(gray, width, height, (float) upper.x(), (float) upper.y(), gap, budget);
        var b = PrintedBeamShafts.at(gray, width, height, (float) lower.x(), (float) lower.y(), gap, budget);
        if (a.size() != 1 || b.size() != 1) return null;
        var top = a.get(0);
        var bottom = b.get(0);
        return top.direction() == 1 && bottom.direction() == 1
                && Math.abs(top.left() - bottom.left()) <= 2 && Math.abs(top.endY() - bottom.endY()) <= 2
                ? top : null;
    }

    private static void assignOppositeSides(
            byte[] gray, byte[] normalized, int width, int height, ScorePageInterpretation score,
            List<Head> heads, List<WordBox> boxes, Mark[] candidates) {
        if (score.slurSource().spans().isEmpty() || score.beamSource().groups().isEmpty()) return;
        var incoming = new HashMap<Integer, List<ScoreSlurSpan>>();
        for (var slur : score.slurSource().resolve().resolved())
            if (slur.placement() == ScoreSlurSpan.Placement.ABOVE
                    && slur.lineType() == ScoreSlurSpan.LineType.SOLID && slur.evidence().size() == 1)
                incoming.computeIfAbsent(slur.endOwner(), ignored -> new ArrayList<>()).add(slur);
        if (incoming.isEmpty()) return;

        var byOwner = new Head[candidates.length];
        for (var head : heads) byOwner[head.index()] = head;
        var staffTracks = new HashMap<Integer, StaffPitchTrack>();
        for (var beam : score.beamSource().resolve().groups()) {
            if (!beam.source().bridges().equals(List.of(3, 3, 3)) || !beam.source().hooks().isEmpty())
                continue;
            for (int i = 1; i < beam.columns().size() - 1; i++) {
                if (beam.bridgeMask(i - 1) != 3 || beam.bridgeMask(i) != 3) continue;
                var column = beam.columns().get(i);
                if (column.source().members().size() != 2 || column.owners().size() != 2) continue;
                var upper = byOwner[column.owners().get(0)];
                var lower = byOwner[column.owners().get(1)];
                if (upper == null || lower == null) continue;
                if (upper.y() > lower.y()) {
                    var swap = upper;
                    upper = lower;
                    lower = swap;
                }
                var a = upper.note();
                var b = lower.note();
                int measure = a.measureIndex();
                if (candidates[upper.index()] != null || candidates[lower.index()] != null
                        || !ordinaryAttack(upper) || !ordinaryAttack(lower)
                        || a.measureIndex() != b.measureIndex() || a.staffCount() != 1 || b.staffCount() != 1
                        || a.staffStep() - b.staffStep() != 5
                        || a.stemDirection() != -1 || b.stemDirection() != -1
                        || a.octaveShift() != 1 || b.octaveShift() != 1
                        || a.beamCount() != 2 || b.beamCount() != 2
                        || a.tupletDivisor() != 1 || b.tupletDivisor() != 1
                        || (a.articulations() & NoteArticulation.STACCATO) == 0
                        || (b.articulations() & NoteArticulation.STACCATO) == 0
                        || Math.abs(upper.x() - lower.x()) > upper.gap() * .24
                        || Math.abs(upper.onset() - lower.onset()) > 1.0 / 96) continue;
                var arcs = incoming.getOrDefault(upper.index(), List.of());
                if (arcs.size() != 1) continue;
                var slur = arcs.get(0);
                var preceding = beam.columns().get(i - 1).owners();
                if (preceding.size() != 2 || !preceding.contains(slur.startOwner())) continue;
                var first = byOwner[slur.startOwner()];
                int other = preceding.get(0) == slur.startOwner() ? preceding.get(preceding.size() - 1) : preceding.get(0);
                if (first == null || byOwner[other] == null || first.y() >= byOwner[other].y()) continue;
                var arc = slur.evidence().get(0);
                if (arc.pageIndex() != column.source().members().get(0).pageIndex()) continue;

                var staff = staffTracks.computeIfAbsent(measure, key ->
                        WrappedMeasureBoundaries.verifiedTripletStaff(
                                gray, width, height, score.measures().get(key), true));
                if (staff == null || !staff.verifiedAt((float) lower.x())) continue;
                float[] track = staff.at((float) lower.x());
                float gap = track[1];
                if (gap < 8 || gap > 32 || Math.abs(upper.x() - arc.endX() * width) > gap * .24)
                    continue;
                var glyph = lowerThree(boxes, lower, track[0], gap);
                if (glyph == null || !retainsDigitInk(gray, width, height, glyph, gap)
                        || !upperFour(gray, width, height, upper, arc.endY() * height, gap)) continue;
                if (gray != normalized
                        && (!retainsDigitInk(normalized, width, height, glyph, gap)
                                || !upperFour(normalized, width, height, upper, arc.endY() * height, gap)))
                    continue;
                candidates[upper.index()] = new Mark(upper.index(), 4, Placement.ABOVE);
                candidates[lower.index()] = new Mark(lower.index(), 3, Placement.BELOW);
            }
        }
    }

    private static WordBox lowerThree(List<WordBox> boxes, Head lower, float staffBottom, float gap) {
        WordBox found = null;
        for (var box : boxes) {
            if (box.digit() != 3 || Math.abs(box.x() - lower.x()) > gap * .40
                    || box.top() <= staffBottom || box.y() < staffBottom + gap * .60
                    || box.y() > staffBottom + gap * 1.55
                    || box.bottom() - box.top() < gap * .85 || box.bottom() - box.top() > gap * 2.25
                    || box.right() - box.left() < gap * .65 || box.right() - box.left() > gap * 2.05)
                continue;
            if (found != null) return null;
            found = box;
        }
        return found;
    }

    private static boolean upperFour(byte[] gray, int width, int height, Head upper, float arcY, float gap) {
        double rise = (upper.y() - arcY) / gap;
        if (rise < .80 || rise > 1.60) return false;
        int leftFlank = (int) Math.round(upper.x() - gap * 2.8),
                rightFlank = (int) Math.round(upper.x() + gap * 2.8);
        int from = (int) Math.floor(arcY - gap * 1.65), to = (int) Math.floor(arcY - gap * .83);
        if (leftFlank < 0 || rightFlank >= width || from < 0 || to >= height) return false;
        int leftStop = (int) Math.round(upper.x() - gap), rightStart = (int) Math.round(upper.x() + gap);
        int dashBottom = -1;
        for (int y = from; y <= to; y++)
            if (dashPair(gray, y * width, leftFlank, leftStop, gap)
                    && dashPair(gray, y * width, rightStart, rightFlank, gap)) dashBottom = y;
        if (dashBottom < 0) return false;
        // Sample the unaltered numeral between the independently visible dashed line and slur.
        int left = (int) Math.round(upper.x() - gap * .72);
        int right = (int) Math.round(upper.x() + gap * .48);
        int top = dashBottom + Math.max(1, Math.round(gap * .115f));
        int bottom = (int) Math.floor(arcY - gap * .20f);
        if (bottom - top + 1 < gap * .80 || bottom - top + 1 > gap * 1.30) return false;
        var reading = JoinedDigitTemplates.inspect(
                gray, width, height, new BitSet(), left, top, right, bottom, () -> {});
        return reading != null && reading.digit() == 4;
    }

    private static boolean dashPair(byte[] gray, int offset, int left, int right, float gap) {
        int previous = Integer.MIN_VALUE;
        for (int x = left; x <= right; ) {
            if ((gray[offset + x] & 255) >= 155) {
                x++;
                continue;
            }
            int start = x++;
            while (x <= right && (gray[offset + x] & 255) < 155) x++;
            int length = x - start;
            if (start == left || x > right || length < gap * .13 || length > gap * .42) continue;
            if (previous != Integer.MIN_VALUE && start - previous >= gap * .35 && start - previous <= gap * .73)
                return true;
            previous = start;
        }
        return false;
    }


    private static void assignStacks(
            byte[] gray, byte[] normalized, int width, int height, ScorePageInterpretation score,
            List<Head> heads, double widestGap, List<WordBox> boxes, Mark[] candidates) {
        var stacks = new ArrayList<Stack>();
        int[] claims = new int[candidates.length];
        for (var box : boxes) {
            if (box.digit() < 10) continue;
            var octave = stackOwner(box, heads, widestGap, score.measures(), height);
            if (octave == null || candidates[octave.upper().index()] != null
                    || candidates[octave.lower().index()] != null) continue;
            var region = score.measures().get(octave.upper().note().measureIndex());
            var rows = UnnumberedTripletInk.sourceRows(region, score.measures(), height);
            var glyphs = stackGlyphs(gray, width, height, box, octave);
            if (!insideSystem(glyphs, rows)) continue;
            if (normalized != gray) {
                var normalizedGlyphs = stackGlyphs(normalized, width, height, box, octave);
                if (!insideSystem(normalizedGlyphs, rows) || !sameStack(glyphs, normalizedGlyphs))
                    continue;
            }
            stacks.add(new Stack(octave, box.digit() / 10, box.digit() % 10));
            claims[octave.upper().index()]++;
            claims[octave.lower().index()]++;
        }
        for (var stack : stacks) {
            var octave = stack.octave();
            int upper = octave.upper().index(), lower = octave.lower().index();
            if (claims[upper] != 1 || claims[lower] != 1) continue;
            candidates[upper] = new Mark(upper, stack.upper(), octave.placement());
            candidates[lower] = new Mark(lower, stack.lower(), octave.placement());
        }
    }

    private static void assignUpstemOctaveTripletStacks(
            byte[] raw, byte[] normalized, int width, int height, ScorePageInterpretation score,
            List<Head> heads, double widestGap, List<WordBox> boxes, Mark[] marks, boolean[] ambiguous) {
        var verified = new ArrayList<Stack>();
        for (var box : boxes) {
            if (box.digit() != 41 && box.digit() != 4) continue;
            var octave = stackOwner(box, heads, widestGap, score.measures(), height);
            if (octave == null || octave.placement() != Placement.ABOVE) continue;
            var upper = octave.upper();
            var lower = octave.lower();
            if (ambiguous[upper.index()] || ambiguous[lower.index()]
                    || !matchesAboveFingering(marks[upper.index()], 4)
                    || !matchesAboveFingering(marks[lower.index()], 1)
                    || !upstemTriplet(upper) || !upstemTriplet(lower)) continue;
            var rows = UnnumberedTripletInk.sourceRows(
                    score.measures().get(upper.note().measureIndex()), score.measures(), height);
            double topFringe = box.digit() == 4 ? upper.gap() * .5 : 0;
            if (box.top() <= rows.first() - topFringe || box.bottom() >= upper.y()) continue;
            if (!upstemOctaveTripletInk(raw, width, height, box, upper)
                    || !upstemOctaveTripletInk(normalized, width, height, box, upper)) continue;
            verified.add(new Stack(octave, box.digit(), 1));
        }
        for (var stack : verified) {
            var upper = stack.octave().upper();
            if (stack.upper() == 4 && verified.stream().noneMatch(other -> {
                var prior = other.octave().upper();
                return other.upper() == 41 && prior.note().measureIndex() == upper.note().measureIndex()
                        && prior.note().staffIndex() == upper.note().staffIndex()
                        && Math.abs(upper.onset() - prior.onset() - 1.0 / 3.0) <= 1.0 / 96.0;
            })) continue;
            int low = stack.octave().lower().index();
            marks[upper.index()] = new Mark(upper.index(), 4, Placement.ABOVE);
            marks[low] = new Mark(low, 1, Placement.ABOVE);
        }
    }

    private static boolean matchesAboveFingering(Mark existing, int digit) {
        return existing == null || existing.digit() == digit && existing.placement() == Placement.ABOVE;
    }

    private static boolean upstemTriplet(Head head) {
        var note = head.note();
        return note.stemDirection() == 1 && note.beamCount() == 1 && !note.crossStaffBeam()
                && note.tupletDivisor() == 3 && note.tupletNormalNotes() == 2;
    }

    private static boolean upstemOctaveTripletInk(
            byte[] ink, int width, int height, WordBox box, Head head) {
        // The upper digit can fracture while the slur and beam cross the space between digits.
        double gap = head.gap();
        int left = (int) Math.floor(box.left()), right = (int) Math.ceil(box.right());
        int top = (int) Math.floor(box.top());
        int bottom = (int) Math.ceil(Math.min(box.bottom(), top + 1.75 * gap));
        if (gap < 8 || gap > 32 || right - left < gap || right - left > 2.2 * gap
                || left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var upperInk = RawNumeralComponents.capture(ink, width, height, left, top, right, bottom,
                165, () -> {}, () -> {}).components().stream()
                .filter(c -> c.size() >= 12 && c.left > left && c.right < right
                        && c.top > top && c.bottom < bottom).toList();
        if (upperInk.isEmpty() || upperInk.size() > 2) return false;
        int l = upperInk.stream().mapToInt(c -> c.left).min().orElseThrow();
        int r = upperInk.stream().mapToInt(c -> c.right).max().orElseThrow();
        int t = upperInk.stream().mapToInt(c -> c.top).min().orElseThrow();
        int b = upperInk.stream().mapToInt(c -> c.bottom).max().orElseThrow();
        if (b - t + 1 < .83 * gap || b - t + 1 > 1.35 * gap
                || Math.abs((l + r) * .5 - head.x()) > .48 * gap) return false;
        var four = JoinedDigitTemplates.inspect(ink, width, height, new BitSet(), l, t, r, b, () -> {});
        if (four == null || four.digit() != 4 || four.scores()[4] < .63) return false;

        int lowLeft = (int) Math.floor((l + r) * .5 - .63 * gap);
        int lowRight = (int) Math.ceil((l + r) * .5 + .63 * gap);
        int lowTop = (int) Math.floor(b + .45 * gap), lowBottom = (int) Math.ceil(b + 2.05 * gap);
        double clearance = (head.y() - lowBottom) / gap;
        if (lowLeft < 0 || lowRight >= width || lowBottom >= height || clearance < 2.7 || clearance > 5)
            return false;
        var lowerInk = stackComponents(ink, width, height, lowLeft, lowTop, lowRight, lowBottom, gap, 165);
        return lowerInk.stream().filter(c -> stackDigit(
                ink, width, height, lowLeft, lowTop, lowRight, lowBottom, c, gap, head.x(), 1)).count() == 1;
    }

    private static int stackCode(String text) {
        if (text.length() != 2) return -1;
        int upper = text.charAt(0) - '0', lower = text.charAt(1) - '0';
        return upper <= 4 && lower >= 0 && upper > lower ? upper * 10 + lower : -1;
    }

    private static Octave stackOwner(
            WordBox box, List<Head> heads, double widestGap, List<MeasureRegion> measures, int height) {
        double radius = widestGap * .44;
        Head upper = null, lower = null;
        for (int i = firstHeadAt(heads, box.x() - radius);
                i < heads.size() && heads.get(i).x() <= box.x() + radius; i++) {
            var head = heads.get(i);
            double gap = head.gap();
            if (Math.abs(head.x() - box.x()) > gap * .44
                    || Math.abs(head.y() - box.y()) > gap * 9.6) continue;
            var rows = UnnumberedTripletInk.sourceRows(
                    measures.get(head.note().measureIndex()), measures, height);
            if (box.y() <= rows.first() || box.y() >= rows.last()) continue;
            if (lower != null) return null;
            if (upper == null) upper = head;
            else if (head.y() < upper.y()) {
                lower = upper;
                upper = head;
            } else lower = head;
        }
        if (upper == null || lower == null || !ordinaryAttack(upper) || !ordinaryAttack(lower))
            return null;
        var a = upper.note();
        var b = lower.note();
        double gap = upper.gap(), pitchSeparation = (lower.y() - upper.y()) / gap;
        if (a.measureIndex() != b.measureIndex() || a.staffIndex() != b.staffIndex()
                || a.staffCount() != b.staffCount() || a.staffStep() - b.staffStep() != 7
                || pitchSeparation < 2.85 || pitchSeparation > 3.8
                || Math.abs(upper.x() - lower.x()) > .24 * gap
                || Math.abs(upper.onset() - lower.onset()) > 1.0 / 96
                || a.stemDirection() == 0 || a.stemDirection() != b.stemDirection())
            return null;
        if (box.bottom() < upper.y()) return new Octave(upper, lower, Placement.ABOVE);
        if (box.top() > lower.y()) return new Octave(upper, lower, Placement.BELOW);
        return null;
    }

    private static boolean ordinaryAttack(Head head) {
        var note = head.note();
        return note.kind() == ScoreNoteEvent.Kind.PITCHED
                && note.visualSize() == ScoreNoteEvent.VisualSize.FULL && !head.harmonic()
                && !note.tiedFromPrevious() && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    /** A detached terminal of an engraved 2 can look like a distant staccato. */
    static boolean ownsTwoTerminal(
            byte[] gray, int width, int height, float x, float y, float gap,
            int left, int top, int right, int bottom) {
        float rise = (y - (top + bottom) * .5f) / gap;
        if (gap < 8 || gap > 32 || rise < 2.7f || rise > 4.3f || right >= x) return false;
        int l = Math.round(x - gap * .92f), r = Math.round(x + gap * .85f);
        int t = Math.round(top - gap * .48f), b = Math.round(y - gap * .80f);
        if (l < 0 || r >= width || t < 0 || b >= height || t >= b) return false;
        var crop = RawNumeralComponents.capture(gray, width, height, l, t, r, b, () -> {}, () -> {});
        RawNumeralComponents.Component two = null, zero = null;
        boolean terminal = false;
        for (var part : crop.components()) {
            int partHeight = part.bottom - part.top + 1;
            if (partHeight < gap * .70f) {
                terminal |= part.left <= left && part.right >= right
                        && part.top <= top && part.bottom >= bottom
                        && partHeight <= gap * .57f && part.right - part.left + 1 <= gap * .57f;
                continue;
            }
            if (part.top <= top && part.bottom >= bottom && part.left <= right
                    && right <= (part.left + part.right) * .5f && part.right > right) {
                if (two != null) return false;
                two = part;
            } else if (part.top > bottom) {
                if (zero != null) return false;
                zero = part;
            } else return false;
        }
        if (!terminal || two == null || zero == null) return false;
        float split = (zero.top - two.bottom) / gap;
        return split >= .12f && split <= .58f && top - two.top <= gap * .35f
                && bottom <= (two.top + two.bottom) * .5f
                && stackDigit(gray, width, height, l, t, r, b, two, gap, x, 2)
                && stackDigit(gray, width, height, l, t, r, b, zero, gap, x, 0);
    }

    private static List<RawNumeralComponents.Component> stackGlyphs(
            byte[] gray, int width, int height, WordBox box, Octave octave) {
        var head = octave.placement() == Placement.ABOVE ? octave.upper() : octave.lower();
        double gap = head.gap(), w = (box.right() - box.left()) / gap, h = (box.bottom() - box.top()) / gap;
        int l = (int) Math.round(box.left()), t = (int) Math.round(box.top());
        int r = (int) Math.round(box.right()) - 1, b = (int) Math.round(box.bottom()) - 1;
        if (w < 1 || w > 2.45 || h < 2.15 || h > 4 || l < 0 || t < 0
                || r >= width || b >= height || (r - l + 1L) * (b - t + 1) > 16_384) return null;
        var glyphs = stackComponents(gray, width, height, l, t, r, b, gap, 165);
        var verified = verifyStack(gray, width, height, box, octave, glyphs, l, t, r, b);
        if (verified != null || box.digit() != 31 || glyphs.isEmpty() || glyphs.size() > 2)
            return verified;

        // A gray fringe can close a lobe or fuse two otherwise independent digits.
        var dark = verifyStack(gray, width, height, box, octave,
                stackComponents(gray, width, height, l, t, r, b, gap, 125), l, t, r, b);
        if (dark == null) return null;
        var core = verifyStack(gray, width, height, box, octave,
                stackComponents(gray, width, height, l, t, r, b, gap, 135), l, t, r, b);
        if (!sameStack(dark, core)) return null;
        if (dark.stream().mapToInt(RawNumeralComponents.Component::size).sum()
                < glyphs.stream().mapToInt(RawNumeralComponents.Component::size).sum() * .75)
            return null;
        if (glyphs.size() == 2) return sameStack(glyphs, core) ? core : null;
        var joined = glyphs.get(0);
        var upper = core.get(0);
        var lower = core.get(core.size() - 1);
        if (joined.left <= l || joined.right >= r || joined.top <= t || joined.bottom >= b
                || upper.top - joined.top > 2 || joined.bottom - lower.bottom > 2
                || Math.min(upper.left, lower.left) - joined.left > 2
                || joined.right - Math.max(upper.right, lower.right) > 2) return null;
        for (int y = upper.bottom + 1; y < lower.top; y++) {
            int ink = 0;
            for (int x = joined.left; x <= joined.right; x++)
                if ((gray[y * width + x] & 255) <= 165) ink++;
            if (ink > 1) return null;
        }
        return core;
    }

    private static List<RawNumeralComponents.Component> stackComponents(
            byte[] gray, int width, int height, int l, int t, int r, int b,
            double gap, int threshold) {
        return RawNumeralComponents.capture(
                        gray, width, height, l, t, r, b, threshold, () -> {}, () -> {})
                .components().stream()
                .filter(c -> c.size() >= 12 && c.bottom - c.top + 1 >= gap * .70)
                .sorted(Comparator.comparingInt(c -> c.top)).toList();
    }

    private static List<RawNumeralComponents.Component> verifyStack(
            byte[] gray, int width, int height, WordBox box, Octave octave,
            List<RawNumeralComponents.Component> glyphs, int l, int t, int r, int b) {
        var head = octave.placement() == Placement.ABOVE ? octave.upper() : octave.lower();
        double gap = head.gap();
        if (glyphs.size() != 2) return null;
        var upper = glyphs.get(0);
        var lower = glyphs.get(1);
        double separation = (lower.top - upper.bottom) / gap;
        if (separation < .10 || separation > .65
                || Math.abs(upper.left + upper.right - lower.left - lower.right) > .56 * gap)
            return null;
        boolean above = octave.placement() == Placement.ABOVE;
        double clearance = (above ? head.y() - lower.bottom : upper.top - head.y()) / gap;
        boolean towardStem = head.note().stemDirection() == (above ? 1 : -1);
        if (clearance < (towardStem ? 2.7 : 1.05) || clearance > 6.3
                || towardStem && head.note().tupletDivisor() != 1) return null;
        return stackDigit(gray, width, height, l, t, r, b, upper, gap, head.x(), box.digit() / 10)
                        && stackDigit(gray, width, height, l, t, r, b, lower, gap, head.x(), box.digit() % 10)
                ? glyphs : null;
    }

    private static boolean stackDigit(
            byte[] gray, int width, int height, int l, int t, int r, int b,
            RawNumeralComponents.Component glyph, double gap, double x, int digit) {
        double h = (glyph.bottom - glyph.top + 1) / gap;
        if (glyph.left <= l || glyph.right >= r || glyph.top <= t || glyph.bottom >= b
                || h < .83 || h > 1.35 || Math.abs((glyph.left + glyph.right) * .5 - x) > .48 * gap)
            return false;
        if (digit == 1) return TripletRhythmDetector.fingerOne(gray, width, glyph);
        if (digit == 3) return TripletRhythmDetector.isIsolatedThree(glyph, width);
        var reading = JoinedDigitTemplates.inspect(
                gray, width, height, new BitSet(), glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
        return reading != null && reading.digit() == digit;
    }

    record HarmonicStack(int touch, int stopped, boolean provedTouch,
            List<RawNumeralComponents.Component> raw, List<RawNumeralComponents.Component> normalized) {
        java.util.Map<String, Object> fingering(String placement) {
            return java.util.Map.of("base", stopped, "touch", touch, "placement", placement);
        }
    }

    /** Two vertically separate retained fingerings in the OCR extent of one harmonic column. */
    static HarmonicStack harmonicStack(List<PlayingTechniqueDetector.Word> words,
            byte[] original, byte[] normalized, int width, int height,
            float x, float top, float bottom, float gap) {
        var nearby = words.stream().filter(word -> word.text() != null && word.text().matches("[1-4]{1,2}")
                        && Math.abs((word.left() + word.right()) * width * .5f - x) <= gap * .54f
                        && (word.top() + word.bottom()) * height * .5f >= top
                        && (word.top() + word.bottom()) * height * .5f <= bottom)
                .sorted(Comparator.comparingDouble(PlayingTechniqueDetector.Word::top)).toList();
        String text = String.join("", nearby.stream().map(PlayingTechniqueDetector.Word::text).toList());
        int code = stackCode(text);
        if (code < 11 || code % 10 < 1) return null;
        int l = Math.round(nearby.stream().map(PlayingTechniqueDetector.Word::left).min(Float::compare).orElseThrow() * width);
        int r = Math.round(nearby.stream().map(PlayingTechniqueDetector.Word::right).max(Float::compare).orElseThrow() * width) - 1;
        int t = Math.round(nearby.get(0).top() * height);
        int b = Math.round(nearby.stream().map(PlayingTechniqueDetector.Word::bottom).max(Float::compare).orElseThrow() * height) - 1;
        return readHarmonicStack(original, normalized, width, height, x, gap, l, t, r, b, code);
    }

    /** Independently recognize the matching pair on a proved harmonic shaft when OCR omitted it. */
    static HarmonicStack matchingHarmonicStack(byte[] original, byte[] normalized, int width, int height,
            float x, float top, float bottom, float gap, HarmonicStack precedent) {
        if (precedent == null || !precedent.provedTouch()) return null;
        int code = precedent.touch() * 10 + precedent.stopped();
        return readHarmonicStack(original, normalized, width, height, x, gap,
                Math.round(x - 1.15f * gap), Math.round(top), Math.round(x + 1.15f * gap),
                Math.round(bottom), code);
    }

    private static HarmonicStack readHarmonicStack(byte[] original, byte[] normalized, int width, int height,
            float x, float gap, int l, int t, int r, int b, int code) {
        if (l < 0 || r >= width || t < 0 || b >= height || r - l < gap || r - l > gap * 2.45
                || b - t < gap * 2.15 || b - t > gap * 4 || (r - l + 1L) * (b - t + 1) > 16_384) return null;
        var raw = stackComponents(original, width, height, l, t, r, b, gap, 154);
        var norm = stackComponents(normalized, width, height, l, t, r, b, gap, 154);
        if (code / 10 == 4 && raw.size() == 3 && norm.size() == 3) {
            raw = stackComponents(original, width, height, l, t, r, b, gap, 175);
            norm = stackComponents(normalized, width, height, l, t, r, b, gap, 175);
        }
        if (raw.size() != 2 || norm.size() != 2 || !sameStack(raw, norm)) return null;
        var upper = raw.get(0);
        var lower = raw.get(1);
        if (lower.top - upper.bottom < gap * .10 || lower.top - upper.bottom > gap * .65
                || Math.abs(upper.left + upper.right - lower.left - lower.right) > .56 * gap
                || raw.stream().anyMatch(c -> !withinHarmonicStack(c, l, t, r, b, gap, x))
                || norm.stream().anyMatch(c -> !withinHarmonicStack(c, l, t, r, b, gap, x))) return null;
        int stopped = code % 10, touch = code / 10;
        if (!harmonicStackDigit(original, width, height, l, t, r, b, gap, x, raw, 1, stopped)
                || !harmonicStackDigit(normalized, width, height, l, t, r, b, gap, x, norm, 1, stopped))
            return null;
        boolean provedTouch = harmonicStackDigit(original, width, height, l, t, r, b, gap, x, raw, 0, touch)
                && harmonicStackDigit(normalized, width, height, l, t, r, b, gap, x, norm, 0, touch);
        return new HarmonicStack(touch, stopped, provedTouch, raw, norm);
    }

    private static boolean withinHarmonicStack(RawNumeralComponents.Component glyph, int l, int t, int r, int b,
            double gap, double x) {
        double h = (glyph.bottom - glyph.top + 1) / gap;
        return glyph.left > l && glyph.right < r && glyph.top > t && glyph.bottom < b
                && h >= .83 && h <= 1.35 && Math.abs((glyph.left + glyph.right) * .5 - x) <= .48 * gap;
    }

    private static boolean harmonicStackDigit(byte[] ink, int width, int height, int l, int t, int r, int b,
            double gap, double x, List<RawNumeralComponents.Component> owners, int row, int digit) {
        for (int threshold : new int[] {154, 165, 135, 125}) {
            var glyphs = threshold == 154 ? owners : stackComponents(ink, width, height, l, t, r, b, gap, threshold);
            if (glyphs.size() != 2 || !sameStack(owners, glyphs)) continue;
            var glyph = glyphs.get(row);
            if (!withinHarmonicStack(glyph, l, t, r, b, gap, x)) continue;
            if (stackDigit(ink, width, height, l, t, r, b, glyph, gap, x, digit)) return true;
            if (digit != 1) continue;
            var reading = JoinedDigitTemplates.inspect(ink, width, height, new BitSet(),
                    glyph.left, glyph.top, glyph.right, glyph.bottom, () -> {});
            if (reading != null && reading.digit() == 1) return true;
        }
        return false;
    }

    /** Match the curled copy of a three against a separately verified three in this pair of chords. */
    static boolean repeatedHarmonicThree(HarmonicStack a, HarmonicStack b, int width) {
        if (a.touch() != 3 || b.touch() != 3 || !(a.provedTouch() || b.provedTouch())) return false;
        return repeatedThree(a.raw().get(0), b.raw().get(0), width)
                && repeatedThree(a.normalized().get(0), b.normalized().get(0), width);
    }

    private static boolean repeatedThree(
            RawNumeralComponents.Component a, RawNumeralComponents.Component b, int sourceWidth) {
        var left = harmonicGlyphMask(a, sourceWidth);
        var right = harmonicGlyphMask(b, sourceWidth);
        for (int shift = -1; shift <= 1; shift++) {
            int matches = 0;
            for (int y = 0; y < 24; y++)
                for (int x = 0; x < 16; x++) {
                    int other = x + shift;
                    if (other >= 0 && other < 16 && left.get(y * 16 + x) && right.get(y * 16 + other))
                        matches++;
                }
            if (matches * 2 >= (left.cardinality() + right.cardinality()) * .89) return true;
        }
        return false;
    }

    private static BitSet harmonicGlyphMask(RawNumeralComponents.Component glyph, int sourceWidth) {
        int w = glyph.right - glyph.left + 1, h = glyph.bottom - glyph.top + 1;
        var source = new BitSet(w * h);
        for (int i = 0; i < glyph.size(); i++) {
            int pixel = glyph.pixel(i);
            source.set((pixel / sourceWidth - glyph.top) * w + pixel % sourceWidth - glyph.left);
        }
        var normalized = new BitSet(16 * 24);
        for (int y = 0; y < 24; y++)
            for (int x = 0; x < 16; x++)
                if (source.get((2 * y + 1) * h / 48 * w + (2 * x + 1) * w / 32))
                    normalized.set(y * 16 + x);
        return normalized;
    }

    private static boolean insideSystem(
            List<RawNumeralComponents.Component> glyphs, UnnumberedTripletInk.Rows rows) {
        return glyphs != null && glyphs.get(0).top > rows.first()
                && glyphs.get(glyphs.size() - 1).bottom < rows.last();
    }

    private static boolean sameStack(
            List<RawNumeralComponents.Component> original, List<RawNumeralComponents.Component> normalized) {
        if (normalized == null) return false;
        for (int i = 0; i < original.size(); i++) {
            var a = original.get(i);
            var b = normalized.get(i);
            if (Math.abs(a.left - b.left) > 2 || Math.abs(a.top - b.top) > 2
                    || Math.abs(a.right - b.right) > 2 || Math.abs(a.bottom - b.bottom) > 2)
                return false;
        }
        return true;
    }

    private static WordBox toPixels(SheetInterpreter.Word word, int width, int height) {
        int digit = switch (word.text()) {
            case "0" -> 0;
            case "1" -> 1;
            case "2" -> 2;
            case "3" -> 3;
            case "4" -> 4;
            default -> stackCode(word.text());
        };
        double l = (double) word.left() * width, t = (double) word.top() * height;
        double r = (double) word.right() * width, b = (double) word.bottom() * height;
        return new WordBox(digit, l, t, r, b, (l + r) / 2, (t + b) / 2);
    }

    private static Head uniqueOffer(WordBox box, List<Head> heads, double widestGap) {
        double radius = widestGap * .52;
        Head owner = null;
        for (int i = firstHeadAt(heads, box.x() - radius);
                i < heads.size() && heads.get(i).x() <= box.x() + radius; i++) {
            var head = heads.get(i);
            double gap = head.gap(), dy = Math.abs(box.y() - head.y()) / gap;
            double w = (box.right() - box.left()) / gap, h = (box.bottom() - box.top()) / gap;
            if (Math.abs(box.x() - head.x()) > .52 * gap || dy < 1.2 || dy > 4.2
                    || w < .66 || w > 3.6 || h < .75 || h > 2.9
                    || box.y() < head.top() - 5 * gap || box.y() > head.bottom() + 5 * gap) continue;
            if (owner != null) return null;
            owner = head;
        }
        return owner;
    }

    private static boolean isolated(WordBox box, Head head, List<Head> heads, List<WordBox> words) {
        var note = head.note();
        double gap = head.gap(), h = (box.bottom() - box.top()) / gap;
        if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || (note.articulations() & NoteOrnament.GRACE) != 0 || head.harmonic()
                || h < .9 || h > 2.2 || Math.abs(box.x() - head.x()) > .36 * gap
                || Math.abs(box.y() - head.y()) > 3.3 * gap) return false;
        // A genuine 3 can own a group. Admit it as fingering only on the head side.
        if (box.digit() == 3 && note.stemDirection() * (box.y() - head.y()) <= 0) return false;
        for (int i = firstHeadAt(heads, box.x() - gap);
                i < heads.size() && heads.get(i).x() <= box.x() + gap; i++) {
            var other = heads.get(i);
            if (other == head || other.note().measureIndex() != note.measureIndex()) continue;
            if (Math.abs(other.x() - box.x()) <= .74 * gap && Math.abs(other.y() - box.y()) <= 4.8 * gap)
                return false;
            if (Math.abs(other.x() - head.x()) <= .60 * gap
                    && (Math.abs(other.onset() - head.onset()) <= 1.0 / 48
                        || Math.abs(other.y() - head.y()) <= 5.5 * gap)) return false;
        }
        int left = 0, right = words.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (words.get(mid).x() < box.x() - .8 * gap) left = mid + 1;
            else right = mid;
        }
        for (int i = left; i < words.size() && words.get(i).x() < box.x() + .8 * gap; i++) {
            var other = words.get(i);
            if (!other.equals(box) && Math.abs(other.x() - box.x()) < .8 * gap
                    && Math.abs(other.y() - box.y()) < 2.2 * gap) return false;
        }
        return true;
    }

    private static int firstHeadAt(List<Head> heads, double x) {
        int left = 0, right = heads.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (heads.get(mid).x() < x) left = mid + 1;
            else right = mid;
        }
        return left;
    }

    private static boolean retainsDigitInk(byte[] gray, int width, int height, WordBox box, double gap) {
        int l = (int) Math.round(box.left()), t = (int) Math.round(box.top());
        int r = (int) Math.round(box.right()) - 1, b = (int) Math.round(box.bottom()) - 1;
        if (l > r || t > b || (long) (r - l + 1) * (b - t + 1) > 16384) return false;
        var source = RawNumeralComponents.capture(gray, width, height, l, t, r, b, 139, () -> {}, () -> {});
        RawNumeralComponents.Component glyph = null;
        for (var component : source.components()) {
            int glyphHeight = component.bottom - component.top + 1;
            if (component.size() < 12 || glyphHeight <= .5 * gap || glyphHeight >= 1.75 * gap) continue;
            if (glyph != null) return false;
            glyph = component;
        }
        if (glyph == null || glyph.left == l || glyph.right == r || glyph.top == t || glyph.bottom == b)
            return false;
        double aspect = (glyph.right - glyph.left + 1.0) / (glyph.bottom - glyph.top + 1);
        if (!(aspect > .27 && aspect < 1.25)) return false;
        if (box.digit() == 3) return TripletRhythmDetector.isIsolatedThree(glyph, width);
        return box.digit() != 1 || (aspect >= .35 && aspect <= .72);
    }
}
