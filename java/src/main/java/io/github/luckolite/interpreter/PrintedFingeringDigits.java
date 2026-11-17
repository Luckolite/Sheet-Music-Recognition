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
        if (words.isEmpty() || score.notes().size() != events.size()) return List.of();
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
        assignStacks(gray, normalized, width, height, score, heads, widestGap, boxes, candidates);
        assignSlurredThirdFingers(gray, normalized, width, height, score, heads, boxes, candidates);
        assignOppositeSides(gray, normalized, width, height, score, heads, boxes, candidates);
        var marks = new ArrayList<Mark>();
        for (int i = 0; i < candidates.length; i++)
            if (candidates[i] != null && !ambiguous[i]) marks.add(candidates[i]);
        return List.copyOf(marks);
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
