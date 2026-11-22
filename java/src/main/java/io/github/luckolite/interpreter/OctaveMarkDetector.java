// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Applies octave directions to sounding pitch while preserving written staff geometry. */
final class OctaveMarkDetector {
    private static final Pattern TEXT_SEPARATORS = Pattern.compile("[\\s()\\[\\].,:;_\\-–—]");
    private static final Pattern PROSE_BODY = Pattern.compile("\\p{L}{4,}");
    private static final Pattern TERMINAL_HOOKS = Pattern.compile("[┘┐」]+$");
    private static final Pattern MEASURE_CONTINUATION =
            Pattern.compile(
                    "^\\s*\\d{1,4}\\s+(\\((?:8v[ab]|15m[ab])\\)[.\\s_\\-–—]*)$",
                    Pattern.CASE_INSENSITIVE);

    private static final class StaffSides {
        private static final boolean[] VALUES = {false, true};
    }

    private static final class DashSkewOffsets {
        private static final int[] VALUES = {-1, 1};
    }

    /** Exact short-dash ink already accepted by the ordinary printed octave-word
     * and dash-chain recognizers. A separate genuine tenuto is never in this set. */
    static java.util.BitSet printedDashInk(
            byte[] gray, int width, int height, List<PlayingTechniqueDetector.Staff> staffs) {
        var result = new java.util.BitSet();
        if (staffs.isEmpty()) return result;
        gray = contrastedInk(gray, width, height);
        for (var word : printedWords(gray, width, height, staffs, true)) {
            int shift = shift(word.text());
            if (shift == 0) continue;
            var staff = owner(word, staffs, shift, height, true, gray, width);
            if (staff == null) continue;
            float gap = staff.gap();
            float textRight =
                    Math.min(
                            word.right() * width,
                            word.left() * width
                                    + Math.max(
                                            gap * 2, (word.bottom() - word.top()) * height * 3.5f));
            float right =
                    dashEnd(
                            gray,
                            width,
                            height,
                            textRight,
                            word.top() * height,
                            word.bottom() * height,
                            gap,
                            3,
                            result);
            if (shift == 1) {
                var ribbon = provedPrintedRibbon(gray, width, height, word, gap, right);
                if (ribbon != null)
                    for (var dot : ribbon.dots)
                        for (int pixel = 0; pixel < dot.size(); pixel++)
                            result.set(dot.pixel(pixel));
            }
        }
        return result;
    }

    private OctaveMarkDetector() {}

    static int shift(String text) {
        if (text == null) return 0;
        String s = TEXT_SEPARATORS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
        // OCR can append the printed octave-line end hook to its direction.
        s = TERMINAL_HOOKS.matcher(s).replaceAll("");
        return switch (s) {
            case "8va", "8vaa", "8vaalta", "ottava" -> 1;
            case "8vb", "8vab", "8vabassa" -> -1;
            case "15ma", "15maa", "15maalta" -> 2;
            case "15mb", "15mab", "15mabassa" -> -2;
            default -> 0;
        };
    }

    /** The loops of a letter in a typeset paragraph can look like a bare octave numeral. */
    private static boolean proseOwned(PlayingTechniqueDetector.Word candidate,
            List<PlayingTechniqueDetector.Word> words, int width, int height) {
        for (var row : words) {
            if (candidate.left() < row.left() || candidate.right() > row.right()
                    || candidate.top() < row.top() || candidate.bottom() > row.bottom()
                    || !proseLine(row, width, height)) continue;
            float rowsHigh = row.bottom() - row.top();
            float leftTolerance = rowsHigh * height / width * .55f;
            for (var neighboring : words) {
                float rowDistance = Math.abs(row.top() - neighboring.top());
                if (rowDistance >= rowsHigh * .75f && rowDistance <= rowsHigh * 1.55f
                        && Math.abs(neighboring.left() - row.left()) <= leftTolerance
                        && neighboring.bottom() - neighboring.top() >= rowsHigh * .65f
                        && neighboring.bottom() - neighboring.top() <= rowsHigh * 1.5f
                        && proseLine(neighboring, width, height)) return true;
            }
        }
        return false;
    }

    private static boolean proseLine(PlayingTechniqueDetector.Word row, int width, int height) {
        float rowWidth = (row.right() - row.left()) * width;
        float rowHeight = (row.bottom() - row.top()) * height;
        if (rowWidth < rowHeight * 7 || rowWidth < width * .12f || rowHeight <= 0
                || row.text() == null) return false;
        var matcher = PROSE_BODY.matcher(row.text());
        return matcher.find() && matcher.find() && matcher.find();
    }

    private record Span(
            PlayingTechniqueDetector.Staff staff,
            float left,
            float right,
            int shift,
            boolean isolated,
            SystemRibbonEnd ribbon,
            boolean printedRange) {}

    private record SystemRibbonEnd(
            float right, boolean continuesAtStaffEdge, boolean closedCrossing, boolean startsAtNumeral) {
        SystemRibbonEnd(float right, boolean continuesAtStaffEdge) {
            this(right, continuesAtStaffEdge, false);
        }

        SystemRibbonEnd(float right, boolean continuesAtStaffEdge, boolean closedCrossing) {
            this(right, continuesAtStaffEdge, closedCrossing, false);
        }
    }

    record Fragment(float left, float right) {}

    record StaffSpan(int firstMeasureIndex, int lastMeasureIndex,
            int staffIndex, int staffCount, int octaveShift, Fragment fragment) {
        StaffSpan(int firstMeasureIndex, int lastMeasureIndex, int staffIndex, int staffCount, int octaveShift) {
            this(firstMeasureIndex, lastMeasureIndex, staffIndex, staffCount, octaveShift, null);
        }
    }

    static List<ScoreNoteEvent> apply(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        return apply(words, staffs, measures, notes, gray, width, height, null);
    }

    static List<ScoreNoteEvent> apply(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height,
            List<StaffSpan> retainedSpans) {
        return apply(words, staffs, measures, notes, gray, width, height, retainedSpans, List.of());
    }

    static List<ScoreNoteEvent> apply(
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height,
            List<StaffSpan> retainedSpans,
            List<OctaveAccidentalCarry> accidentalCarries) {
        if (staffs == null || staffs.isEmpty() || notes.isEmpty()) return notes;
        gray = contrastedInk(gray, width, height);
        List<PlayingTechniqueDetector.Word> combined =
                new ArrayList<>(words == null ? List.of() : words);
        var sloping = new ArrayList<SlopingOctave>();
        var printed = printedWords(gray, width, height, staffs, true, sloping);
        if (words != null && !words.isEmpty())
            printed.removeIf(mark -> proseOwned(mark, words, width, height));
        combined.addAll(printed);
        List<Span> spans = new ArrayList<>();
        for (var direction : sloping) {
            if (combined.stream()
                    .anyMatch(
                            word ->
                                    shift(word.text()) != 0
                                            && Math.abs(
                                                            word.left() * width
                                                                    - direction.numeral.left)
                                                    <= .6f * direction.staff.gap()
                                            && Math.abs(word.top() * height - direction.numeral.top)
                                                    <= .6f * direction.staff.gap())) continue;
            var range =
                    slopingOctaveOwners(gray, width, height, direction, staffs, measures, notes);
            if (range != null) spans.add(range);
        }
        for (var sourceWord : combined) {
            var word = directionAfterMeasureNumber(sourceWord);
            int shift = shift(word.text());
            if (shift == 0) continue;
            var owner =
                    owner(word, staffs, shift, height, printed.contains(sourceWord), gray, width);
            boolean printedWord = printed.contains(sourceWord);
            InkBox numeral = printedWord && Math.abs(shift) == 1
                    ? new InkBox(Math.round(word.left() * width), Math.round(word.top() * height),
                            Math.round(word.right() * width) - 1, Math.round(word.bottom() * height) - 1, 0)
                    : null;
            SystemRibbonEnd offsetRibbon = null;
            if (shift == -1 && numeral != null && owner != null) {
                var next = owner(word, staffs, 1, height, true, gray, width);
                if (next != null && next.top() > owner.bottom()) {
                    offsetRibbon = offsetUpperDottedRibbon(numeral, owner, next, staffs, measures, notes,
                            gray, width, height);
                    if (offsetRibbon != null) {
                        owner = next;
                        shift = 1;
                    }
                }
            }
            var initialUpperOwner = shift == 1 ? owner : null;
            boolean provedNumeral = numeral != null && initialUpperOwner != null
                    && bareEightShape(gray, width, numeral, initialUpperOwner.gap());
            var initialRibbon = offsetRibbon != null ? offsetRibbon : numeral != null && initialUpperOwner != null
                    ? provedSystemRibbonNumeral(gray, width, height, numeral, initialUpperOwner) : null;
            float compactEnd =
                    numeral != null && initialUpperOwner != null && initialRibbon == null
                            ? compactDottedReturn(
                                    gray, width, height, numeral, initialUpperOwner.gap())
                            : -1;
            float downwardEnd = numeral != null && initialUpperOwner != null
                    ? closedCurvedDottedOctave(gray, width, height, numeral, initialUpperOwner.gap()) : -1;
            boolean closedCompact =
                    downwardEnd >= 0 || compactEnd >= 0
                            || provedNumeral
                                    && initialRibbon == null
                                    && completeDottedRibbon(
                                                    gray,
                                                    width,
                                                    height,
                                                    numeral,
                                                    initialUpperOwner.gap())
                                            != null;
            // A full alta suffix may still be engraved below its staff. The actual
            // terminal hook resolves that placement without changing the text parser.
            if (shift > 0 && !closedCompact && (initialRibbon == null || initialRibbon.continuesAtStaffEdge)) {
                var belowOwner =
                        owner(
                                word,
                                staffs,
                                -shift,
                                height,
                                printed.contains(sourceWord),
                                gray,
                                width);
                if (belowOwner != null) {
                    float end =
                            dashEnd(
                                    gray,
                                    width,
                                    height,
                                    word.right() * width,
                                    word.top() * height,
                                    word.bottom() * height,
                                    belowOwner.gap());
                    int hook =
                            hookDirection(
                                    gray,
                                    width,
                                    height,
                                    end,
                                    word.top() * height,
                                    word.bottom() * height,
                                    belowOwner.gap());
                    if (hook == 0)
                        hook =
                                OctaveBracketEnd.direction(
                                        gray,
                                        width,
                                        height,
                                        end,
                                        word.top() * height,
                                        word.bottom() * height,
                                        belowOwner.gap());
                    if (end >= 0 && hook < 0) {
                        owner = belowOwner;
                        shift = -shift;
                    }
                }
            }
            if (owner == null) continue;
            if (shift == -1 && printed.contains(sourceWord)) {
                var nextOwner =
                        openUpperLedgerOwner(
                                word, owner, staffs, measures, notes, gray, width, height);
                if (nextOwner == null && numeral != null)
                    nextOwner = unhookedOpeningRibbonOwner(
                            word, numeral, owner, staffs, measures, notes, printed, gray, width, height);
                if (nextOwner != null) {
                    owner = nextOwner;
                    shift = 1;
                }
            }
            float gap = owner.gap(), left = word.left() * width - gap * .65f;
            float textRight =
                    Math.min(
                            word.right() * width,
                            word.left() * width
                                    + Math.max(
                                            gap * 2, (word.bottom() - word.top()) * height * 3.5f));
            float right =
                    dashEnd(
                            gray,
                            width,
                            height,
                            textRight,
                            word.top() * height,
                            word.bottom() * height,
                            gap);
            SystemRibbonEnd literal = null;
            if (shift == 1 && printedWord) {
                boolean bareEight = bareEightShape(gray, width, numeral, gap);
                var systemRibbon = owner.equals(initialUpperOwner) ? initialRibbon
                        : provedSystemRibbonNumeral(gray, width, height, numeral, owner);
                float crossedStart = earlySlurRibbonEnd(gray, width, height, word, gap);
                if (systemRibbon != null && systemRibbon.right >= right) {
                    right = systemRibbon.right;
                    literal = systemRibbon;
                } else if (compactEnd >= 0 && owner.equals(initialUpperOwner)) {
                    right = compactEnd;
                    left = Math.max(left, numeral.left);
                } else if (crossedStart >= 0) {
                    right = crossedStart;
                } else {
                    var ribbon = provedPrintedRibbon(gray, width, height, word, gap, right);
                    if (ribbon != null) {
                        right = ribbon.right;
                        if (ribbon.closedCrossing) literal = new SystemRibbonEnd(right, false, true);
                    } else if (!bareEight
                            && right >= 0
                            && hookDirection(
                                            gray,
                                            width,
                                            height,
                                            right,
                                            numeral.top,
                                            numeral.bottom,
                                            gap)
                                    == 0
                            && closedWideRibbonEight(gray, width, height, numeral, gap)) {
                        var wideRibbon = completeDottedRibbon(gray, width, height, numeral, gap);
                        if (wideRibbon != null) {
                            right = Math.max(right, wideRibbon.right);
                            if (wideRibbon.closedCrossing) literal = new SystemRibbonEnd(right, false, true);
                        }
                    }
                }
                if (literal == null && right >= 0 && bareEight
                        && bottomAttachedDottedEight(gray, width, height, numeral, gap))
                    literal = new SystemRibbonEnd(right, false, true);
                right = Math.max(right, closedStraightDottedOctave(gray, width, height, numeral, gap));
                if (owner.equals(initialUpperOwner)) right = Math.max(right, downwardEnd);
                if (right >= 0)
                    left = closedBeamStart(gray, width, height, word, owner, staffs, measures, notes, left, right);
                if (right >= 0 && bareEight && literal == null) {
                    var detached = dottedTripletOctaveStart(
                            gray, width, height, numeral, owner, staffs, measures, notes, left, right);
                    if (detached != null) literal = detached;
                }
                if (literal != null && literal.startsAtNumeral) left = Math.max(left, numeral.left);
                if (!bareEight && (closedWideRibbonEight(gray, width, height, numeral, gap)
                        || systemRibbon == null && !canRepairEight(gray, width, numeral, gap)
                                && faintCounterEight(gray, width, numeral, gap) == null
                                && paleCounterShape(gray, width, height, numeral, gap)))
                    left = Math.max(left, numeral.left);
            }
            if (shift > 0 && right >= 0 && compactEnd < 0)
                right =
                        extendOverDottedHook(
                                gray,
                                width,
                                height,
                                right,
                                word.top() * height,
                                word.bottom() * height,
                                gap);
            boolean isolated = right < 0;
            if (isolated) {
                // An isolated octave direction applies to the nearest attack or chord only.
                float first = Float.POSITIVE_INFINITY;
                for (var note : notes)
                    if (note.kind() == ScoreNoteEvent.Kind.PITCHED
                            && onStaff(note, owner, staffs, measures, height)) {
                        float x = x(note, measures, width);
                        if (x >= left && x <= textRight + gap * 1.5f) first = Math.min(first, x);
                    }
                if (!Float.isFinite(first)) continue;
                left = first - gap * .35f;
                right = first + gap * .35f;
            }
            spans.add(new Span(owner, left, right, shift, isolated, literal, printedWord && !isolated));
        }
        if (spans.isEmpty()) return notes;
        List<Span> ambiguous = new ArrayList<>();
        for (var a : spans)
            for (var b : spans)
                if (a != b
                        && a.staff.equals(b.staff)
                        && a.shift != b.shift
                        && Math.abs(a.left - b.left) < a.staff.gap() * .75f) {
                    ambiguous.add(a);
                    ambiguous.add(b);
                }
        spans.removeAll(ambiguous);
        spans.sort(Comparator.comparingDouble(Span::left));
        for (int i = 0; i < spans.size(); i++) {
            var a = spans.get(i);
            float right = a.right;
            for (int j = i + 1; j < spans.size(); j++) {
                var b = spans.get(j);
                if (a.staff.equals(b.staff) && b.left > a.left + a.staff.gap() * .75f)
                    right = Math.min(right, b.left - .01f);
            }
            if (right != a.right)
                spans.set(i, new Span(a.staff, a.left, right, a.shift, a.isolated, null, false));
        }
        List<ScoreNoteEvent> result = new ArrayList<>(notes.size());
        boolean[] carry = new boolean[notes.size()], explicit = new boolean[notes.size()];
        Span[] assigned = retainedSpans != null && spans.stream().anyMatch(span -> span.ribbon != null)
                ? new Span[notes.size()] : null;
        boolean[] printedRange = accidentalCarries.isEmpty() ? null : new boolean[notes.size()];
        for (var note : notes) {

            if (note.kind() != ScoreNoteEvent.Kind.PITCHED) {
                result.add(note);
                continue;
            }
            float x = x(note, measures, width);
            Span selected = null;
            for (var span : spans)
                if (onStaff(note, span.staff, staffs, measures, height)
                        && x >= span.left
                        && x <= span.right) {
                    if (selected == null || span.left > selected.left) selected = span;
                }
            int index = result.size();
            carry[index] = selected != null && selected.isolated;
            explicit[index] = selected != null;
            if (assigned != null) assigned[index] = selected;
            if (printedRange != null) printedRange[index] = selected != null && selected.printedRange;
            result.add(selected == null ? note : note.withOctaveShift(selected.shift));
        }
        carryIsolatedTies(notes, result, carry, explicit);
        if (assigned != null) retainStaffRibbons(retainedSpans, spans, assigned, notes, measures);
        if (printedRange != null)
            OctaveAccidentalCarry.clearAtRangeBoundary(notes, result, printedRange, accidentalCarries);
        return List.copyOf(result);
    }

    private record OwnedRibbon(Span span, int first, int last) {}

    private static void retainStaffRibbons(
            List<StaffSpan> result, List<Span> spans, Span[] assigned, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures) {
        var ribbons = new ArrayList<OwnedRibbon>();
        for (var span : spans) {
            if (span.ribbon == null) continue;
            int first = measures.size(), last = -1;
            for (int i = 0; i < notes.size(); i++) {
                if (assigned[i] != span) continue;
                int measure = notes.get(i).measureIndex();
                first = Math.min(first, measure);
                last = Math.max(last, measure);
            }
            if (last < first || first < 0 || last >= measures.size()) continue;
            var measuresWithNotes = new java.util.BitSet();
            boolean partial = false;
            for (int i = 0; i < notes.size(); i++) {
                var note = notes.get(i);
                int measure = note.measureIndex();
                if (measure < first || measure > last || note.staffIndex() != span.staff.index()
                        || note.staffCount() != span.staff.count()) continue;
                if (assigned[i] != span) {
                    partial = true;
                    break;
                }
                measuresWithNotes.set(measure);
            }
            if (!partial && measuresWithNotes.nextClearBit(first) > last) {
                ribbons.add(new OwnedRibbon(span, first, last));
            } else if (first == last && span.ribbon.closedCrossing) {
                result.add(new StaffSpan(first, last, span.staff.index(), span.staff.count(), span.shift,
                        new Fragment(span.left, span.right)));
            }
        }
        ribbons.sort(Comparator.comparingInt(OwnedRibbon::first));
        for (int i = 0; i < ribbons.size(); i++) {
            var start = ribbons.get(i);
            var end = start;
            while (i + 1 < ribbons.size() && continuesOnNextSystem(end, ribbons.get(i + 1), measures))
                end = ribbons.get(++i);
            result.add(new StaffSpan(start.first, end.last,
                    start.span.staff.index(), start.span.staff.count(), start.span.shift));
        }
    }

    private static boolean continuesOnNextSystem(OwnedRibbon earlier, OwnedRibbon later,
            List<MeasureRegion> measures) {
        return earlier.span.ribbon.continuesAtStaffEdge && earlier.last + 1 == later.first
                && earlier.span.shift == later.span.shift
                && earlier.span.staff.index() == later.span.staff.index()
                && earlier.span.staff.count() == later.span.staff.count()
                && earlier.span.staff.bottom() < later.span.staff.top()
                && measures.get(earlier.last).bottom() < measures.get(later.first).top();
    }

    private record BeamStartHead(ScoreNoteEvent note, float x) {}

    /**
     * A closed bare eight between two independent beams can start over the next beam.
     * The normal text margin otherwise reaches the preceding group's final ledger head.
     */
    private static float closedBeamStart(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Word word,
            PlayingTechniqueDetector.Staff owner,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            float left,
            float right) {
        float gap = owner.gap(), wordLeft = word.left() * width, wordRight = word.right() * width;
        float top = word.top() * height, bottom = word.bottom() * height;
        if (gap < 10 || gap > 28 || wordRight - wordLeft > 1.7f * gap) return left;
        var nearby = new ArrayList<BeamStartHead>();
        for (var note : notes) {
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.staffIndex() != owner.index()
                    || note.staffCount() != owner.count()
                    || note.measureIndex() < 0
                    || note.measureIndex() >= measures.size()) continue;
            float headX = x(note, measures, width);
            if (headX >= wordLeft - 4 * gap
                    && headX <= wordRight + 3.5f * gap
                    && onStaff(note, owner, staffs, measures, height))
                nearby.add(new BeamStartHead(note, headX));
        }
        nearby.sort(Comparator.comparingDouble(BeamStartHead::x));
        for (int i = 1; i + 2 < nearby.size(); i++) {
            var a = nearby.get(i - 1);
            var b = nearby.get(i);
            var c = nearby.get(i + 1);
            var d = nearby.get(i + 2);
            if (b.x < left || b.x > wordLeft - .42f * gap) continue;
            if (b.x - a.x < 1.12f * gap || d.x - c.x < 1.12f * gap
                    || Math.abs(c.x - wordRight) > .32f * gap
                    || wordLeft + wordRight - b.x - c.x < .18f * gap
                    || c.note.pageY() * height - b.note.pageY() * height < 1.25f * gap
                    || d.x >= right
                    || a.note.measureIndex() != b.note.measureIndex()
                    || b.note.measureIndex() != c.note.measureIndex()
                    || c.note.measureIndex() != d.note.measureIndex()) return left;
            var numeral = new InkBox(Math.round(wordLeft), Math.round(top),
                    Math.round(wordRight) - 1, Math.round(bottom) - 1, 0);
            if (!bareEightShape(gray, width, numeral, gap)
                    || hookDirection(gray, width, height, right, top, bottom, gap) <= 0) return left;
            var shafts = new ArrayList<PrintedBeamShafts.Shaft>(4);
            var budget = new PrintedBeamShafts.Budget(180_000);
            for (var head : List.of(a, b, c, d)) {
                var note = head.note;
                if (note.beamCount() != 1 || note.stemDirection() != -1 || note.crossStaffBeam())
                    return left;
                var measured = PrintedBeamShafts.at(gray, width, height, head.x,
                        note.pageY() * height, gap, budget);
                if (measured.size() != 1 || measured.get(0).direction() != 1) return left;
                shafts.add(measured.get(0));
            }
            if (WrappedEighthTriplets.matchesRailBridge(gray, width, height, shafts.get(0), shafts.get(1), gap, true)
                    && WrappedEighthTriplets.matchesRailBridge(gray, width, height, shafts.get(1), shafts.get(2), gap, false)
                    && WrappedEighthTriplets.matchesRailBridge(gray, width, height, shafts.get(2), shafts.get(3), gap, true))
                return (b.x + c.x) * .5f;
            return left;
        }
        return left;
    }

    /** Separate a late ledger attack from the dotted triplet under a new printed octave line. */
    private static SystemRibbonEnd dottedTripletOctaveStart(
            byte[] gray, int width, int height, InkBox numeral, PlayingTechniqueDetector.Staff owner,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, float left, float right) {
        float gap = owner.gap();
        if (gap < 10 || gap > 25 || right - numeral.right < 7.5f * gap
                || right - numeral.right > 14.5f * gap
                || dashEnd(gray, width, height, numeral.right + 1, numeral.top, numeral.bottom, gap, 12) < 0)
            return null;
        var heads = notes.stream()
                .filter(n -> n.kind() == ScoreNoteEvent.Kind.PITCHED
                        && n.measureIndex() >= 0 && n.measureIndex() < measures.size())
                .map(n -> new BeamStartHead(n, x(n, measures, width)))
                .filter(h -> h.x >= numeral.left - 3f * gap && h.x <= numeral.right + 3.25f * gap
                        && onStaff(h.note, owner, staffs, measures, height))
                .sorted(Comparator.comparingDouble(BeamStartHead::x)).toList();
        if (heads.size() != 4) return null;
        var a = heads.get(0); var b = heads.get(1); var c = heads.get(2); var d = heads.get(3);
        if (b.x < left || b.x > numeral.left - .49f * gap || c.x < numeral.right
                || c.x > numeral.right + .83f * gap || c.x - b.x < 1.7f * gap
                || c.x - b.x > 3f * gap
                || b.note.pageY() * height - numeral.bottom < .75f * gap
                || b.note.pageY() * height - numeral.bottom > 1.75f * gap
                || (c.note.pageY() - b.note.pageY()) * height < 1.55f * gap) return null;
        if (a.note.beamCount() != 2 || b.note.beamCount() != 1 || c.note.beamCount() != 1
                || c.note.augmentationDots() != 1 || d.note.beamCount() != 2) return null;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(4);
        var budget = new PrintedBeamShafts.Budget(180_000);
        for (var head : heads) {
            var note = head.note;
            if (note.measureIndex() != b.note.measureIndex() || note.stemDirection() != -1
                    || note.crossStaffBeam() || note.tiedFromPrevious()) return null;
            var found = PrintedBeamShafts.at(gray, width, height, head.x, note.pageY() * height, gap, budget);
            if (found.size() != 1 || found.get(0).direction() != 1 || budget.refused()) return null;
            shafts.add(found.get(0));
        }
        if (!WrappedEighthTriplets.matchesRailBridge(gray, width, height, shafts.get(0), shafts.get(1), gap, true)
                || !WrappedEighthTriplets.matchesRailBridge(gray, width, height, shafts.get(1), shafts.get(2), gap, false)
                || !WrappedEighthTriplets.matchesRailBridge(gray, width, height, shafts.get(2), shafts.get(3), gap, true))
            return null;
        return shortDottedDownturn(gray, width, height, numeral, right, gap)
                ? new SystemRibbonEnd(right, false, true, true) : null;
    }

    /** The horizontal dash becomes a short vertical corner followed by one detached vertical dot. */
    private static boolean shortDottedDownturn(
            byte[] gray, int width, int height, InkBox numeral, float end, float gap) {
        int lo = Math.max(numeral.right + 2, Math.round(end - gap * 1.1f));
        int hi = Math.min(width - 2, Math.round(end - gap * .32f));
        int lower = Math.min(height - 1, numeral.bottom + Math.round(gap * .9f));
        int upstream = Math.round(gap * .25f);
        for (int x = lo; x <= hi; x++) {
            for (int y = numeral.top; y <= numeral.top + Math.round(gap * .75f); y++) {
                if (y < upstream || y + Math.round(gap * 1.55f) > lower
                        || !endsHorizontalDashes(gray, width, x, y, gap)
                        || !darkPair(gray, width, x, y)
                        || darkPair(gray, width, x, y - upstream)) continue;
                int turn = y;
                while (turn < lower && darkPair(gray, width, x, turn)) turn++;
                int dot = turn;
                while (dot < lower && !darkPair(gray, width, x, dot)) dot++;
                int stop = dot;
                while (stop < lower && darkPair(gray, width, x, stop)) stop++;
                if (turn - y >= gap * .40f && turn - y <= gap * .85f
                        && dot - turn >= gap * .09f && dot - turn <= gap * .43f
                        && stop - dot >= gap * .17f && stop - dot <= gap * .46f)
                    return true;
            }
        }
        return false;
    }

    private static boolean darkPair(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) < 155 && (gray[y * width + x + 1] & 255) < 155;
    }

    /** A bare eight can head the first attack of a later system, below the preceding staff. */
    private static PlayingTechniqueDetector.Staff unhookedOpeningRibbonOwner(
            PlayingTechniqueDetector.Word word, InkBox numeral, PlayingTechniqueDetector.Staff preceding,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, List<PlayingTechniqueDetector.Word> printed,
            byte[] gray, int width, int height) {
        var incoming = owner(word, staffs, 1, height, true, gray, width);
        if (incoming == null || incoming.count() != 1 || preceding.count() != 1) return null;
        float gap = incoming.gap();
        float above = (incoming.top() - numeral.bottom) / gap;
        float below = (numeral.top - preceding.bottom()) / gap;
        if (Math.abs(preceding.gap() - gap) > gap * .08f
                || above < 3 || above > 6
                || below < 3 || below > 6
                || Math.abs(above - below) > 2.05f
                || !bareEightShape(gray, width, numeral, gap)) return null;
        var ribbon = fullSystemDottedEnd(gray, width, height, numeral, incoming);
        if (ribbon == null || !ribbon.continuesAtStaffEdge) return null;
        float first = Float.POSITIVE_INFINITY, firstPrior = Float.POSITIVE_INFINITY;
        ScoreNoteEvent firstOwner = null;
        for (var note : notes) {
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            float x = x(note, measures, width);
            if (x < firstPrior && onStaff(note, preceding, staffs, measures, height)) firstPrior = x;
            if (x < first && onStaff(note, incoming, staffs, measures, height)) {
                first = x;
                firstOwner = note;
            }
        }
        if (firstPrior <= numeral.right + gap * 2.75f
                && !alreadyRaisedByOwnRibbon(printed, preceding, firstPrior, gray, width, height))
            return null;
        float alignment = Math.abs(first - numeral.right);
        if (alignment <= gap * .34f && Math.abs(above - below) <= 1.45f) return incoming;
        boolean besideFirstAttack = first >= numeral.right && alignment <= gap * .75f
                && Math.abs(above - below) <= .70f;
        if (firstOwner == null || alignment > gap * .58f && !besideFirstAttack
                || firstOwner.positionInMeasure() > .14f || firstOwner.measureIndex() < 1) return null;
        var incomingMeasure = measures.get(firstOwner.measureIndex());
        var lastPriorMeasure = measures.get(firstOwner.measureIndex() - 1);
        return (incomingMeasure.top() - lastPriorMeasure.bottom()) * height > gap * 3
                ? incoming : null;
    }

    /** The preceding system can have its own independent printed octave ribbon. */
    private static boolean alreadyRaisedByOwnRibbon(
            List<PlayingTechniqueDetector.Word> words, PlayingTechniqueDetector.Staff staff,
            float firstAttack, byte[] gray, int width, int height) {
        float gap = staff.gap();
        for (var word : words) {
            if (shift(word.text()) != 1 || staff.top() - word.bottom() * height < gap * 2
                    || staff.top() - word.bottom() * height > gap * 12
                    || firstAttack < word.left() * width - gap * .65f
                    || firstAttack > word.right() * width + gap * .70f) continue;
            var digit = new InkBox(Math.round(word.left() * width), Math.round(word.top() * height),
                    Math.round(word.right() * width) - 1, Math.round(word.bottom() * height) - 1, 0);
            var ribbon = provedSystemRibbonNumeral(gray, width, height, digit, staff);
            if (ribbon != null && ribbon.continuesAtStaffEdge) return true;
        }
        return false;
    }

    /** An unhooked bare eight can sit inline with the next staff's upper ledger attack. */
    private static PlayingTechniqueDetector.Staff openUpperLedgerOwner(
            PlayingTechniqueDetector.Word word,
            PlayingTechniqueDetector.Staff preceding,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height) {
        var next = owner(word, staffs, 1, height, true, gray, width);
        if (next == null
                || next.count() != 1
                || preceding.count() != 1
                || Math.abs(next.gap() - preceding.gap()) > next.gap() * .1f) return null;
        float gap = next.gap(), left = word.left() * width, right = word.right() * width;
        float top = word.top() * height, bottom = word.bottom() * height;
        float below = (top - preceding.bottom()) / gap;
        float above = (next.top() - bottom) / gap;
        if (below < 3 || above < 3 || above > 6 || Math.abs(below - above) > 1) return null;
        int l = Math.round(left),
                r = Math.round(right) - 1,
                t = Math.round(top),
                b = Math.round(bottom) - 1;
        if (l < 0
                || t < 0
                || r >= width
                || b >= height
                || l >= r
                || t >= b
                || !bareEightShape(gray, width, new InkBox(l, t, r, b, 0), gap)) return null;
        float end = dashEnd(gray, width, height, right, top, bottom, gap);
        if (end - right < gap * 12
                || hookDirection(gray, width, height, end, top, bottom, gap) != 0
                || OctaveBracketEnd.direction(gray, width, height, end, top, bottom, gap) != 0)
            return null;

        ScoreNoteEvent inline = null, first = null, second = null;
        float inlineX = Float.NEGATIVE_INFINITY;
        float firstX = Float.POSITIVE_INFINITY, secondX = Float.POSITIVE_INFINITY;
        float ordinaryLeft = left - gap * 1.35f;
        boolean extraPrecedingHead = false;
        for (var note : notes) {
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || note.staffCount() != 1
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            float xx = x(note, measures, width), y = note.pageY() * height;
            if (xx < left - gap * 1.60f || xx > right + gap * 4) continue;
            if (onStaff(note, preceding, staffs, measures, height) && y > top - gap * 3) {
                if (xx >= ordinaryLeft) return null;
                extraPrecedingHead = true;
            }
            if (!onStaff(note, next, staffs, measures, height)) continue;
            if (xx < left - gap * .65f && xx > inlineX
                    && y >= top && y <= bottom && note.staffStep() >= 12) {
                inline = note;
                inlineX = xx;
            }
            if (xx < left || xx >= secondX) continue;
            if (xx < firstX) {
                second = first;
                secondX = firstX;
                first = note;
                firstX = xx;
            } else if (xx > firstX + gap) {
                second = note;
                secondX = xx;
            }
        }
        if (inline == null
                || first == null
                || second == null
                || firstX > right + gap * .55f
                || first.measureIndex() != second.measureIndex()) return null;
        float separation = secondX - firstX;
        if (inlineX < ordinaryLeft || separation > gap * 2.85f) {
            // The next pitch's sharp can push the first two columns farther apart.
            // Prove that glyph in the space between the two owners.
            if (extraPrecedingHead
                    || inline.measureIndex() != first.measureIndex()
                    || separation > gap * 3.55f
                    || second.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP
                    || !OmrScoreInterpreter.printedSharpBeforeDyad(
                            gray, width, height, Math.round(secondX - gap * 2.25f), secondX,
                            second.pageY() * height, gap)) return null;
        }
        float a = first.pageY() * height - bottom, c = second.pageY() * height - bottom;
        return a >= gap * 1.4f && a <= gap * 4 && c >= gap * 1.4f && c <= gap * 4 ? next : null;
    }

    private static void carryIsolatedTies(
            List<ScoreNoteEvent> source,
            List<ScoreNoteEvent> result,
            boolean[] carry,
            boolean[] explicit) {
        for (int i = 0; i < source.size(); i++) {
            var current = source.get(i);
            if (explicit[i]
                    || current.octaveShift() != 0
                    || !current.tiedFromPrevious()
                    || current.kind() != ScoreNoteEvent.Kind.PITCHED
                    || current.leadingRestBeats() > 0) continue;
            int owner = -1, copies = 0;
            for (int j = i - 1; j >= 0; j--) {
                var earlier = source.get(j);
                int bars = current.measureIndex() - earlier.measureIndex();
                if (bars < 0
                        || bars > 1
                        || earlier.kind() != ScoreNoteEvent.Kind.PITCHED
                        || earlier.staffIndex() != current.staffIndex()
                        || earlier.staffCount() != current.staffCount()
                        || earlier.staffStep() + earlier.clefBottomDiatonic()
                                != current.staffStep() + current.clefBottomDiatonic()
                        || bars == 0
                                && earlier.positionInMeasure()
                                        >= current.positionInMeasure() - .018f) continue;
                if (owner < 0
                        || earlier.measureIndex() > source.get(owner).measureIndex()
                        || earlier.measureIndex() == source.get(owner).measureIndex()
                                && earlier.positionInMeasure()
                                        > source.get(owner).positionInMeasure() + .018f) {
                    owner = j;
                    copies = 1;
                } else if (earlier.measureIndex() == source.get(owner).measureIndex()
                        && Math.abs(
                                        earlier.positionInMeasure()
                                                - source.get(owner).positionInMeasure())
                                <= .018f) copies++;
            }
            if (owner < 0
                    || copies != 1
                    || !carry[owner]
                    || source.get(owner).followingRestBeats() > 0
                    || current.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                            && current.writtenAccidental() != source.get(owner).writtenAccidental())
                continue;
            result.set(i, current.withOctaveShift(result.get(owner).octaveShift()));
            carry[i] = true;
        }
    }

    /** Remove dark paper texture from octave text and its dash evidence together. */
    private static byte[] contrastedInk(byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height || width <= 0 || height <= 0)
            return gray;
        byte[] result = gray;
        final int tile = 64;
        int[] histogram = new int[256];
        for (int top = 0; top < height; top += tile)
            for (int left = 0; left < width; left += tile) {
                int bottom = Math.min(height, top + tile), right = Math.min(width, left + tile);
                int pixels = (bottom - top) * (right - left);
                int target = (pixels * 85 + 99) / 100;
                if (hasBrightBackground(gray, width, top, bottom, left, right, pixels - target + 1))
                    continue;
                java.util.Arrays.fill(histogram, 0);
                for (int y = top; y < bottom; y++)
                    for (int x = left; x < right; x++) histogram[gray[y * width + x] & 255]++;
                int total = 0,
                        background = 255;
                for (int value = 0; value < 256; value++) {
                    total += histogram[value];
                    if (total >= target) {
                        background = value;
                        break;
                    }
                }
                // Ordinary high-contrast scans retain their exact pixels. On darker
                // paper, a stroke must lie below its local paper by the same margin
                // used for the numeral counters, suffixes and dotted-line chain.
                int shift = Math.max(0, 165 - (background - 24));
                if (shift == 0) continue;
                if (result == gray) result = gray.clone();
                for (int y = top; y < bottom; y++)
                    for (int x = left; x < right; x++)
                        result[y * width + x] =
                                (byte) Math.min(255, (gray[y * width + x] & 255) + shift);
            }
        return result;
    }

    private static boolean hasBrightBackground(
            byte[] gray, int width, int top, int bottom, int left, int right, int required) {
        // Enough pixels at or above 189 prove that the 85th percentile needs no shift.
        // Limit the probe on shaded tiles, which still use the exact histogram.
        int end = top + (bottom - top + 3) / 4;
        for (int y = top; y < end; y++) {
            int row = y * width;
            for (int i = row + left; i < row + right; i++)
                if ((gray[i] & 255) >= 189) required--;
            if (required <= 0) return true;
        }
        return false;
    }

    private record InkBox(int left, int top, int right, int bottom, int area) {}

    private record BareOctaveMark(
            PlayingTechniqueDetector.Word word, boolean twoCounters, float end, float gap) {}

    /** Confirmed printed direction text cannot also be a sounding notehead. */
    static boolean containsPrintedMark(
            List<PlayingTechniqueDetector.Word> printedMarks,
            float centerX,
            float centerY,
            int width,
            int height) {
        for (var word : printedMarks)
            if (shift(word.text()) != 0
                    && centerX >= word.left() * width
                    && centerX < word.right() * width
                    && centerY >= word.top() * height
                    && centerY < word.bottom() * height) return true;
        return false;
    }

    /** OCR may join a system number to its parenthesized continuation mark.
     * Keep the direction's own horizontal anchor, not the preceding bar number. */
    private static PlayingTechniqueDetector.Word directionAfterMeasureNumber(
            PlayingTechniqueDetector.Word word) {
        if (word.text() == null) return word;
        var match = MEASURE_CONTINUATION.matcher(word.text());
        if (!match.matches()) return word;
        float left =
                word.left() + (word.right() - word.left()) * match.start(1) / word.text().length();
        return new PlayingTechniqueDetector.Word(
                match.group(1), left, word.top(), word.right(), word.bottom());
    }

    static List<PlayingTechniqueDetector.Word> printedWords(
            byte[] gray, int width, int height, List<PlayingTechniqueDetector.Staff> staffs) {
        return printedWords(gray, width, height, staffs, false);
    }

    private static List<PlayingTechniqueDetector.Word> printedWords(
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            boolean alreadyContrasted) {
        return printedWords(gray, width, height, staffs, alreadyContrasted, null);
    }

    private static List<PlayingTechniqueDetector.Word> printedWords(
            byte[] gray,
            int width,
            int height,
            List<PlayingTechniqueDetector.Staff> staffs,
            boolean alreadyContrasted,
            List<SlopingOctave> sloping) {
        List<PlayingTechniqueDetector.Word> words = new ArrayList<>();
        if (gray == null || gray.length != width * height) return words;
        if (!alreadyContrasted) gray = contrastedInk(gray, width, height);
        var flood = new WordInkRuns();
        var holeScan = new HoleScanScratch();
        var closedMarks = new ArrayList<BareOctaveMark>();
        var closedSystemMarks = new ArrayList<PlayingTechniqueDetector.Word>();
        for (var staff : staffs)
            for (boolean below : StaffSides.VALUES) {
                float gap = staff.gap();
                int top =
                        Math.max(
                                0,
                                Math.round(
                                        below
                                                ? staff.bottom() + gap * .3f
                                                : staff.top() - gap * 12));
                int bottom =
                        Math.min(
                                height - 1,
                                Math.round(
                                        below
                                                ? staff.bottom() + gap * 12
                                                : staff.top() - gap * .3f));
                if (top >= bottom) continue;
                int h = bottom - top + 1;
                int bandSize = width * h, bandOffset = top * width;
                float minimumArea = Math.max(4, gap * gap * .025f);
                var bandInk = WordInkRuns.classify(gray, bandOffset, bandSize);
                // Glyph traversal consumes its copy. The counter flood uses the original ink mask.
                var remaining = (java.util.BitSet) bandInk.clone();
                List<InkBox> boxes = new ArrayList<>();
                List<InkBox> shortFragments = new ArrayList<>();
                for (int origin = remaining.nextSetBit(0); origin >= 0;
                        origin = remaining.nextSetBit(origin + 1)) {
                    flood.first(remaining, width, origin);
                    int count = 0, left = width, right = -1, y1 = origin / width, y2 = y1;
                    while (flood.size > 0) {
                        int index = --flood.size;
                        int start = flood.starts[index], end = flood.ends[index];
                        int rowStart = start - start % width;
                        count += end - start + 1;
                        left = Math.min(left, start - rowStart);
                        right = Math.max(right, end - rowStart);
                        y2 = Math.max(y2, rowStart / width);
                        int from = Math.max(rowStart, start - 1);
                        int to = Math.min(rowStart + width - 1, end + 1);
                        if (rowStart != 0)
                            flood.overlap(remaining, width, from - width, to - width);
                        if (rowStart + width < bandSize)
                            flood.overlap(remaining, width, from + width, to + width);
                    }
                    if (count >= minimumArea
                            && y2 - y1 + 1 >= 2
                            && y2 - y1 + 1 <= gap * .5f
                            && right - left + 1 >= gap * .2f
                            && right - left + 1 <= gap * 1.5f)
                        shortFragments.add(new InkBox(left, top + y1, right, top + y2, count));
                    if (count >= minimumArea
                            && y2 - y1 + 1 >= gap * .55f
                            && y2 - y1 + 1 <= gap * 3.2f
                            && right - left + 1 >= gap * .12f
                            && right - left + 1 <= gap * 8) {
                        boxes.add(new InkBox(left, top + y1, right, top + y2, count));
                    } else if (!below && count >= minimumArea && y2 - y1 + 1 > gap * 3.2f
                            && y2 - y1 + 1 <= gap * 4.35f
                            && right - left + 1 >= gap * .8f && right - left + 1 <= gap * 1.65f) {
                        var stacked = new InkBox(left, top + y1, right, top + y2, count);
                        var numeral = closedStackedRibbonEight(gray, width, height, stacked, staff);
                        if (numeral != null && !centeredInStaff(numeral, staffs)) {
                            var mark = new PlayingTechniqueDetector.Word("8va", numeral.left / (float) width,
                                    numeral.top / (float) height, (numeral.right + 1) / (float) width,
                                    (numeral.bottom + 1) / (float) height);
                            if (!words.contains(mark)) words.add(mark);
                            closedSystemMarks.add(mark);
                        }
                    }
                }
                boxes.sort(Comparator.comparingInt(InkBox::left));
                boolean[] used = new boolean[boxes.size()];
                int componentCount = boxes.size();
                var attachedBoxes = new ArrayList<>(attachedEightBoxes(
                        gray, width, height, top, bottom, gap, below, holeScan, bandInk));
                if (!below) recoverDottedEightBesideSlur(gray, width, height, gap, boxes, attachedBoxes);
                var componentBoxes = attachedBoxes.isEmpty()
                        ? java.util.Set.<InkBox>of()
                        : new java.util.HashSet<>(boxes);
                words.addAll(ledgerAttachedWords(gray, width, height, boxes, gap, below));
                int candidateCount = componentCount + attachedBoxes.size();
                for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                    boolean fromComponent = candidateIndex < componentCount;
                    var box = fromComponent
                            ? boxes.get(candidateIndex) : attachedBoxes.get(candidateIndex - componentCount);
                    boolean isComponent = fromComponent || componentBoxes.contains(box);
                    if (centeredInStaff(box, staffs)) continue;
                    if (sloping != null && !below && fromComponent) {
                        float end = slopingDottedOctaveEnd(gray, width, height, box, staff);
                        if (end >= 0) sloping.add(new SlopingOctave(staff, box, end));
                    }
                    boolean closedEight = bareEightShape(gray, width, box, gap);
                    if (!below && !closedEight && box.right - box.left + 1 > gap * 1.6f) {
                        var clipped = new InkBox(box.left, box.top,
                                box.left + Math.round(gap * 1.6f) - 1, box.bottom, 0);
                        if (bareEightShape(gray, width, clipped, gap)
                                && fullSystemDottedEnd(gray, width, height, clipped, staff) != null) {
                            box = clipped;
                            closedEight = true;
                        }
                    }
                    if (!below && !closedEight && isComponent)
                        closedEight = closedWideRibbonEight(gray, width, height, box, gap);
                    float compactReturn =
                            !below && isComponent
                                    ? compactDottedReturn(gray, width, height, box, gap)
                                    : -1;
                    if (compactReturn >= 0) closedEight = true;
                    var shortStaffEdge =
                            !below && isComponent
                                    ? shortOpenDottedStaffEdge(gray, width, height, box, staff)
                                    : null;
                    if (shortStaffEdge != null) closedEight = true;
                    SystemRibbonEnd paleSystem = null;
                    if (!below && !closedEight && isComponent && paleCounterShape(gray, width, height, box, gap)) {
                        paleSystem = fullSystemDottedEnd(gray, width, height, box, staff);
                        float shortEnd = dashEnd(gray, width, height, box.right + 1, box.top, box.bottom, gap, 8);
                        closedEight = paleSystem != null || shortEnd - box.right >= gap * 8
                                && shortEnd - box.right <= gap * 24
                                && hookDirection(gray, width, height, shortEnd, box.top, box.bottom, gap) == 1;
                    }
                    var attached = !below && !closedEight && isComponent
                            ? verticallyAttachedEight(gray, width, box, gap) : null;
                    if (attached != null) box = attached;
                    boolean brokenCounter =
                            !below
                                    && !closedEight
                                    && attached == null
                                    && canRepairEight(gray, width, box, gap);
                    var faint =
                            !below
                                            && !closedEight
                                            && attached == null
                                            && isComponent
                                            && !brokenCounter
                                    ? faintCounterEight(gray, width, box, gap)
                                    : null;
                    if (faint != null) {
                        box = faint;
                        closedEight = true;
                    }
                    var diagonalRibbon =
                            !below && isComponent
                                    ? diagonalNumeralRibbon(gray, width, height, box, staff)
                                    : null;
                    if (diagonalRibbon != null) closedEight = true;
                    if (!closedEight && attached == null && !brokenCounter) continue;
                    float dashRight =
                            dashEnd(
                                    gray,
                                    width,
                                    height,
                                    box.right + 1,
                                    box.top,
                                    box.bottom,
                                    gap,
                                    6);
                    int hookDirection =
                            dashRight < 0
                                    ? 0
                                    : hookDirection(
                                            gray,
                                            width,
                                            height,
                                            dashRight,
                                            box.top,
                                            box.bottom,
                                            gap);
                    float dottedEnd = closedCurvedDottedOctave(gray, width, height, box, gap);
                    if (dottedEnd >= 0) {
                        dashRight = Math.max(dashRight, dottedEnd);
                        hookDirection = 1;
                    }
                    if (compactReturn >= 0 && hookDirection <= 0) {
                        dashRight = compactReturn;
                        hookDirection = 1;
                    }
                    float crossedStart = !below
                            ? qualifiedEarlySlurRibbonEnd(gray, width, height, box, gap) : -1;
                    if (crossedStart >= 0) {
                        dashRight = crossedStart;
                        hookDirection = 1;
                    }
                    if (!below && !isComponent && closedEight && hookDirection == 0
                            && attachedShortRibbonEnd(gray, width, height, box, gap) >= 0)
                        hookDirection = 1;
                    if (shortStaffEdge != null && shortStaffEdge.right >= dashRight) {
                        dashRight = shortStaffEdge.right;
                        hookDirection = 1;
                    }
                    boolean closedSystem = false;
                    if (diagonalRibbon != null) {
                        dashRight = diagonalRibbon.right;
                        hookDirection = 1;
                        closedSystem = !diagonalRibbon.continuesAtStaffEdge;
                    }
                    if (!below && brokenCounter && hookDirection <= 0 && brokenEightShape(gray, width, box, gap)) {
                        float crossedTerminal = octaveReturnAcrossSlur(gray, width, height, box, gap);
                        if (crossedTerminal >= 0) {
                            dashRight = crossedTerminal;
                            hookDirection = 1;
                            closedSystem = true;
                        }
                    }
                    if (paleSystem != null && paleSystem.right >= dashRight) {
                        dashRight = paleSystem.right;
                        hookDirection = paleSystem.continuesAtStaffEdge ? 0 : 1;
                        closedSystem = !paleSystem.continuesAtStaffEdge;
                    }
                    if (closedEight && !below && hookDirection <= 0) {
                        var ribbon = completeDottedRibbon(gray, width, height, box, gap);
                        if (ribbon != null) {
                            dashRight = ribbon.right;
                            hookDirection = 1;
                            closedSystem = true;
                        }
                    }
                    if (closedEight && !below && hookDirection <= 0) {
                        var systemRibbon = fullSystemDottedEnd(gray, width, height, box, staff);
                        if (systemRibbon != null && systemRibbon.right > dashRight) {
                            dashRight = systemRibbon.right;
                            hookDirection = 1;
                            closedSystem = !systemRibbon.continuesAtStaffEdge;
                        }
                    }
                    if (!below && hookDirection == 0 && dashRight - box.right >= gap * 8
                            && dashRight - box.right <= gap * 28
                            && detachedDownwardDashPair(gray, width, height, dashRight, box.top, box.bottom, gap))
                        hookDirection = 1;
                    if (dashRight < 0 || faint != null && hookDirection <= 0) continue;
                    boolean openEnded =
                            !below && hookDirection == 0 && dashRight - box.right >= gap * 44;
                    if (!closedEight
                            && ((hookDirection <= 0 && !openEnded)
                                    || attached == null
                                            && !brokenEightShape(gray, width, box, gap))) continue;
                    // A bare numeral has no va/vb suffix. Use only the nearest stave;
                    // do not apply the same inter-system mark to both adjacent rows.
                    PlayingTechniqueDetector.Staff nearest = null;
                    float nearestDistance = Float.POSITIVE_INFINITY;
                    boolean nearestBelow = false;
                    for (var candidate : staffs) {
                        float above = (candidate.top() - box.bottom) / candidate.gap();
                        float under = (box.top - candidate.bottom()) / candidate.gap();
                        // Between systems a lower-octave line can be closer to the next treble
                        // stave. Its terminal hook points back toward the actual owning stave.
                        if (hookDirection < 0 && under <= 0 || hookDirection > 0 && above <= 0)
                            continue;
                        float distance =
                                hookDirection < 0
                                        ? under
                                        : hookDirection > 0 ? above : above > 0 ? above : under;
                        if (distance >= .25f && distance <= 12 && distance < nearestDistance) {
                            nearest = candidate;
                            nearestDistance = distance;
                            nearestBelow = under > 0;
                        }
                    }
                    if (!staff.equals(nearest) || below != nearestBelow) continue;
                    // An attached fragment needs either a suffix or a proved upward end hook
                    // before it can establish a lower octave for the preceding staff.
                    if (below && !isComponent && hookDirection >= 0) continue;
                    var word =
                            new PlayingTechniqueDetector.Word(
                                    below ? "8vb" : "8va",
                                    box.left / (float) width,
                                    box.top / (float) height,
                                    (box.right + 1) / (float) width,
                                    (box.bottom + 1) / (float) height);
                    words.add(word);
                    if (closedSystem) closedSystemMarks.add(word);
                    if (!below && hookDirection > 0)
                        closedMarks.add(new BareOctaveMark(word, closedEight, dashRight, gap));
                }
                var members = new ArrayList<InkBox>();
                for (int i = 0; i < boxes.size(); i++) {
                    if (used[i]) continue;
                    var box = boxes.get(i);
                    int left = box.left, right = box.right, a = box.top, b = box.bottom;
                    used[i] = true;
                    members.clear();
                    members.add(box);
                    for (int j = i + 1; j < boxes.size(); j++) {
                        var next = boxes.get(j);
                        if (next.left > right + gap * .7f) break;
                        if (used[j]
                                || next.right < left
                                || Math.min(b, next.bottom) - Math.max(a, next.top) < gap * .3f
                                || Math.max(right, next.right) - left > gap * 8) continue;
                        used[j] = true;
                        right = Math.max(right, next.right);
                        a = Math.min(a, next.top);
                        b = Math.max(b, next.bottom);
                        members.add(next);
                    }
                    int shift = OctaveWordShapes.match(gray, width, left, a, right, b);
                    if (shift == 0 && members.size() >= 4) {
                        InkBox first = members.get(0), last = members.get(members.size() - 1);
                        if (parenthesis(gray, width, first, gap, true)
                                && parenthesis(gray, width, last, gap, false)
                                && Math.abs(first.top - last.top) <= gap * .3f
                                && Math.abs(first.bottom - last.bottom) <= gap * .3f) {
                            int innerLeft = width,
                                    innerRight = -1,
                                    innerTop = height,
                                    innerBottom = -1;
                            for (int j = 1; j < members.size() - 1; j++) {
                                var inner = members.get(j);
                                innerLeft = Math.min(innerLeft, inner.left);
                                innerRight = Math.max(innerRight, inner.right);
                                innerTop = Math.min(innerTop, inner.top);
                                innerBottom = Math.max(innerBottom, inner.bottom);
                            }
                            int innerShift =
                                    OctaveWordShapes.match(
                                            gray,
                                            width,
                                            innerLeft,
                                            innerTop,
                                            innerRight,
                                            innerBottom);
                            if (innerShift != 0
                                    && dashEnd(
                                                    gray,
                                                    width,
                                                    height,
                                                    last.right + 1,
                                                    innerTop,
                                                    innerBottom,
                                                    gap)
                                            >= 0) {
                                shift = innerShift;
                                left = innerLeft;
                                right = innerRight;
                                a = innerTop;
                                b = innerBottom;
                            }
                        }
                    }
                    if (shift == 0 && members.size() >= 2) {
                        InkBox hook = members.get(members.size() - 1);
                        if (shortTerminalHook(gray, width, hook, gap, below)) {
                            int prefixRight = -1, prefixTop = height, prefixBottom = -1;
                            for (int j = 0; j < members.size() - 1; j++) {
                                InkBox member = members.get(j);
                                prefixRight = Math.max(prefixRight, member.right);
                                prefixTop = Math.min(prefixTop, member.top);
                                prefixBottom = Math.max(prefixBottom, member.bottom);
                            }
                            if (hook.left > prefixRight
                                    && hook.top >= prefixTop - gap * .25f
                                    && hook.bottom <= prefixBottom + gap * .25f) {
                                int prefixShift =
                                        OctaveWordShapes.match(
                                                gray,
                                                width,
                                                left,
                                                prefixTop,
                                                prefixRight,
                                                prefixBottom);
                                if (prefixShift != 0
                                        && (below ? prefixShift < 0 : prefixShift > 0)) {
                                    shift = prefixShift;
                                    right = prefixRight;
                                    a = prefixTop;
                                    b = prefixBottom;
                                }
                            }
                        }
                    }
                    if (shift == 0 || (below ? shift > 0 : shift < 0)) continue;
                    if (fragmentedCharacterContext(boxes, shortFragments, left, a, right, b, gap))
                        continue;
                    String text =
                            switch (shift) {
                                case 1 -> "8va";
                                case -1 -> "8vb";
                                case 2 -> "15ma";
                                default -> "15mb";
                            };
                    words.add(
                            new PlayingTechniqueDetector.Word(
                                    text,
                                    left / (float) width,
                                    a / (float) height,
                                    (right + 1) / (float) width,
                                    (b + 1) / (float) height));
                }
            }
        // A repaired outline can borrow the nearby complete numeral's dash chain.
        for (var mark : closedMarks) {
            if (mark.twoCounters) continue;
            var word = mark.word;
            for (var other : closedMarks) {
                if (!other.twoCounters || Math.abs(other.end - mark.end) > mark.gap * .22f)
                    continue;
                float separation = (other.word.left() - word.right()) * width;
                if (separation > 0
                        && separation <= mark.gap
                        && Math.abs((other.word.top() - word.top()) * height) <= mark.gap * .8f) {
                    words.remove(word);
                    break;
                }
            }
        }
        // A proved suffix determines the direction. Do not also interpret its
        // numeral as a bare lower-octave mark for the preceding staff.
        if (words.size() < 2) return words;
        var completeWords = List.copyOf(words);
        words.removeIf(word -> {
            if (shift(word.text()) == -1 && closedSystemMarks.stream().anyMatch(mark ->
                    mark.left() == word.left() && mark.right() == word.right()
                            && mark.top() == word.top() && mark.bottom() == word.bottom())) return true;
            float left = word.left() * width + 1, right = word.right() * width + 2;
            float top = word.top() * height + 1, bottom = word.bottom() * height - 1;
            for (var other : completeWords)
                if (other != word
                        && other.right() * width > right
                        && other.left() * width <= left
                        && other.top() * height <= top
                        && other.bottom() * height >= bottom) return true;
            return false;
        });
        return words;
    }

    /** A long thin ink neck separates a complete octave word from the preceding written note. */
    /** Reuses the frontier for eight-connected ink runs within a staff band. */
    private static final class WordInkRuns {
        int[] starts = new int[128];
        int[] ends = new int[128];
        int size;
        static java.util.BitSet classify(byte[] gray, int offset, int size) {
            return java.util.BitSet.valueOf(PackedGrayInk.classify(gray, offset, size, 165));
        }

        void first(java.util.BitSet remaining, int width, int origin) {
            enqueue(remaining, origin, origin, origin - origin % width + width);
        }

        void overlap(java.util.BitSet remaining, int width, int from, int to) {
            int rowStart = from - from % width, rowEnd = rowStart + width;
            for (int at = remaining.nextSetBit(from); at >= 0 && at <= to;
                    at = remaining.nextSetBit(at + 1)) {
                int first = Math.max(rowStart, remaining.previousClearBit(at - 1) + 1);
                at = enqueue(remaining, first, at, rowEnd);
            }
        }

        private int enqueue(java.util.BitSet remaining, int first, int seed, int rowEnd) {
            int last = Math.min(rowEnd, remaining.nextClearBit(seed)) - 1;
            remaining.clear(first, last + 1);
            if (size == starts.length) {
                starts = java.util.Arrays.copyOf(starts, size * 2);
                ends = java.util.Arrays.copyOf(ends, size * 2);
            }
            starts[size] = first;
            ends[size++] = last;
            return last;
        }
    }

    private static List<PlayingTechniqueDetector.Word> ledgerAttachedWords(
            byte[] gray, int width, int height, List<InkBox> boxes, float gap, boolean below) {
        var words = new ArrayList<PlayingTechniqueDetector.Word>();
        int thickness = Math.max(1, Math.round(gap * .15f));
        ConnectedWordScratch scratch = null;
        for (var parent : boxes) {
            if (parent.right - parent.left < gap * 1.5f || parent.bottom - parent.top < gap * .8f)
                continue;
            int span = parent.right - parent.left + 1;
            int[] leftInk = new int[span + 1];
            for (int y = parent.top; y <= parent.bottom; y++) {
                int row = y * width + parent.left;
                for (int c = 0; c < span; c++)
                    if ((gray[row + c] & 255) < 165) leftInk[c + 1]++;
            }
            for (int c = 0; c < span; c++) leftInk[c + 1] += leftInk[c];
            var tried = new java.util.HashSet<InkBox>();
            int radius = Math.max(thickness + 1, Math.round(gap * .4f));
            int[] first = new int[span], last = new int[span];
            for (int seedRow = parent.top; seedRow <= parent.bottom; seedRow++) {
                java.util.Arrays.fill(last, -1);
                int seedOffset = seedRow * width + parent.left;
                int low = Math.max(parent.top, seedRow - radius);
                int high = Math.min(parent.bottom, seedRow + radius);
                for (int c = 0; c < span; c++) {
                    if ((gray[seedOffset + c] & 255) >= 165) continue;
                    int x = parent.left + c;
                    int top = low, bottom = high;
                    while ((gray[top * width + x] & 255) >= 165) top++;
                    if (seedRow - top >= thickness) continue;
                    while ((gray[bottom * width + x] & 255) >= 165) bottom--;
                    if (bottom - top >= thickness) continue;
                    first[c] = top;
                    last[c] = bottom;
                }
                for (int column = 0; column < span; column++) {
                    if (last[column] < 0) continue;
                    int start = column, end = column, top = first[column], bottom = last[column];
                    while (end + 1 < span
                            && last[end + 1] >= 0
                            && Math.abs(first[end + 1] - top) <= 1
                            && Math.abs(last[end + 1] - bottom) <= 1) end++;
                    column = end;
                    if (end - start + 1 < gap * .65f || span - end < gap * .4f) continue;
                    if (leftInk[start] < gap * gap * .2f) continue;
                    if (scratch == null) scratch = new ConnectedWordScratch();
                    var word =
                            rightConnectedWord(
                                    gray, width, parent, parent.left + end + 1, seedRow, scratch);
                    if (word == null
                            || word.bottom - word.top < gap * .65f
                            || word.bottom - word.top > gap * 2.5f
                            || word.right - word.left < gap * .4f
                            || !tried.add(word)) continue;
                    var reading =
                            completeAttachedWord(
                                    gray, width, height, word, parent, boxes, gap, below);
                    if (reading != null && !words.contains(reading)) words.add(reading);
                }
            }
        }
        return words;
    }

    private static final class ConnectedWordScratch {
        final java.util.BitSet seen = new java.util.BitSet();
        int[] stack = new int[64];
    }

    /** Follow only the connected right-side ink, excluding a separate following notehead. */
    private static InkBox rightConnectedWord(
            byte[] gray, int width, InkBox parent, int cut, int seedY, ConnectedWordScratch scratch) {
        int w = parent.right - cut + 1, h = parent.bottom - parent.top + 1;
        if (w <= 0 || h <= 0) return null;
        if ((gray[seedY * width + cut] & 255) >= 165) {
            if (seedY > parent.top && (gray[(seedY - 1) * width + cut] & 255) < 165) seedY--;
            else if (seedY < parent.bottom && (gray[(seedY + 1) * width + cut] & 255) < 165)
                seedY++;
            else return null;
        }
        var seen = scratch.seen;
        seen.clear();
        int[] stack = scratch.stack;
        int size = 1;
        stack[0] = (seedY - parent.top) * w;
        seen.set(stack[0]);
        int right = cut, top = seedY, bottom = seedY, area = 0;
        while (size > 0) {
            int at = stack[--size], x = at % w, y = at / w;
            area++;
            right = Math.max(right, cut + x);
            top = Math.min(top, parent.top + y);
            bottom = Math.max(bottom, parent.top + y);
            int firstX = Math.max(0, x - 1), lastX = Math.min(w - 1, x + 1);
            int lastY = Math.min(h - 1, y + 1);
            for (int yy = Math.max(0, y - 1); yy <= lastY; yy++) {
                int row = yy * w;
                int source = (parent.top + yy) * width + cut;
                for (int xx = firstX; xx <= lastX; xx++) {
                    int next = row + xx;
                    if ((gray[source + xx] & 255) >= 165 || seen.get(next)) continue;
                    seen.set(next);
                    if (size == stack.length) {
                        stack = java.util.Arrays.copyOf(stack, size + Math.min(size, w * h - size));
                        scratch.stack = stack;
                    }
                    stack[size++] = next;
                }
            }
        }
        return new InkBox(cut, top, right, bottom, area);
    }

    private static PlayingTechniqueDetector.Word completeAttachedWord(
            byte[] gray,
            int width,
            int height,
            InkBox initial,
            InkBox parent,
            List<InkBox> boxes,
            float gap,
            boolean below) {
        int right = initial.right, top = initial.top, bottom = initial.bottom;
        int prefixRight = right, prefixTop = top, prefixBottom = bottom;
        InkBox last = null;
        for (var next : boxes) {
            if (next == parent || next.left <= initial.right) continue;
            if (next.left > right + gap * .7f) break;
            if (next.right < right
                    || Math.min(bottom, next.bottom) - Math.max(top, next.top) < gap * .3f
                    || next.right - initial.left > gap * 8) continue;
            prefixRight = right;
            prefixTop = top;
            prefixBottom = bottom;
            last = next;
            right = next.right;
            top = Math.min(top, next.top);
            bottom = Math.max(bottom, next.bottom);
        }
        int shift = OctaveWordShapes.match(gray, width, initial.left, top, right, bottom);
        if (shift == 0 && last != null && shortTerminalHook(gray, width, last, gap, below)) {
            shift =
                    OctaveWordShapes.match(
                            gray, width, initial.left, prefixTop, prefixRight, prefixBottom);
            if (shift != 0 && (shift < 0) != below) shift = 0;
            if (shift != 0) {
                right = prefixRight;
                top = prefixTop;
                bottom = prefixBottom;
            }
        }
        if (shift == 0) return null;
        String text = shift == 1 ? "8va" : shift == -1 ? "8vb" : shift == 2 ? "15ma" : "15mb";
        return new PlayingTechniqueDetector.Word(
                text,
                initial.left / (float) width,
                top / (float) height,
                (right + 1) / (float) width,
                (bottom + 1) / (float) height);
    }

    /** A thin staff-facing terminal has a horizontal limb and a right shaft, with a clear interior. */
    private static boolean shortTerminalHook(
            byte[] gray, int width, InkBox box, float gap, boolean below) {
        int w = box.right - box.left + 1, h = box.bottom - box.top + 1;
        if (w < gap * .35f
                || w > gap * 1.7f
                || h < gap * .45f
                || h > gap * 1.5f
                || box.area > w * h * .65f) return false;
        int limb = Math.max(1, Math.round(gap * .18f));
        int bestBar = 0, bestShaft = 0, outside = 0;
        for (int y = box.top; y <= box.bottom; y++) {
            int rowInk = 0;
            boolean atBar = below ? y > box.bottom - limb : y < box.top + limb;
            for (int x = box.left; x <= box.right; x++)
                if ((gray[y * width + x] & 255) < 165) {
                    rowInk++;
                    if (!atBar && x <= box.right - limb) outside++;
                }
            if (atBar) bestBar = Math.max(bestBar, rowInk);
        }
        for (int x = Math.max(box.left, box.right - limb + 1); x <= box.right; x++) {
            int columnInk = 0;
            for (int y = box.top; y <= box.bottom; y++)
                if ((gray[y * width + x] & 255) < 165) columnInk++;
            bestShaft = Math.max(bestShaft, columnInk);
        }
        return bestBar >= w * .75f
                && bestShaft >= h * .75f
                && outside <= Math.max(1, box.area * .08f);
    }

    // Staff rules can divide an accidental into two apparent counters. A bare
    // octave numeral belongs outside the staff, while complete suffix words
    // retain their separate recognition and ownership evidence.
    /** Two separated strokes and an aligned next character prove a word continues past a match. */
    private static boolean fragmentedCharacterContext(
            List<InkBox> boxes,
            List<InkBox> fragments,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        for (int side : new int[] {-1, 1})
            for (InkBox next : boxes) {
                int distance = side > 0 ? next.left - right - 1 : left - next.right - 1;
                if (distance < 2
                        || distance > gap * 1.8f
                        || Math.min(bottom, next.bottom) - Math.max(top, next.top) + 1 < gap * .6f)
                    continue;
                int gapLeft = side > 0 ? right + 1 : next.right + 1;
                int gapRight = side > 0 ? next.left - 1 : left - 1;
                for (int i = 0; i < fragments.size(); i++) {
                    InkBox first = fragments.get(i);
                    if (first.left < gapLeft || first.right > gapRight) continue;
                    for (int j = i + 1; j < fragments.size(); j++) {
                        InkBox second = fragments.get(j);
                        if (second.left < gapLeft || second.right > gapRight) continue;
                        int l = Math.min(first.left, second.left),
                                r = Math.max(first.right, second.right);
                        int t = Math.min(first.top, second.top),
                                b = Math.max(first.bottom, second.bottom);
                        int separation =
                                Math.max(first.top, second.top)
                                        - Math.min(first.bottom, second.bottom)
                                        - 1;
                        int overlap =
                                Math.min(first.right, second.right)
                                        - Math.max(first.left, second.left)
                                        + 1;
                        if (l - gapLeft > gap * .3f
                                || gapRight - r > gap * .3f
                                || separation < gap * .25f
                                || b - t + 1 < gap * .65f
                                || b - t + 1 > gap * 1.7f
                                || overlap
                                        < Math.min(
                                                        first.right - first.left + 1,
                                                        second.right - second.left + 1)
                                                * .4f
                                || Math.abs(t - next.top) > gap * .3f
                                || Math.abs(b - next.bottom) > gap * .3f
                                || t < top - gap * .3f
                                || b > bottom + gap * .3f) continue;
                        return true;
                    }
                }
            }
        return false;
    }

    private static boolean centeredInStaff(
            InkBox box, List<PlayingTechniqueDetector.Staff> staffs) {
        float center = (box.top + box.bottom) * .5f;
        for (var staff : staffs) if (center >= staff.top() && center <= staff.bottom()) return true;
        return false;
    }

    /** Balanced thin curved brackets may enclose a printed continuation word. */
    private static boolean parenthesis(
            byte[] gray, int width, InkBox box, float gap, boolean left) {
        int w = box.right - box.left + 1, h = box.bottom - box.top + 1;
        if (w < gap * .25f
                || w > gap * .9f
                || h < gap * 1.3f
                || h > gap * 3.2f
                || box.area > h * gap * .4f) return false;
        float[] centers = new float[3];
        int[] counts = new int[3];
        for (int y = box.top; y <= box.bottom; y++)
            for (int x = box.left; x <= box.right; x++)
                if ((gray[y * width + x] & 255) < 165) {
                    int band = Math.min(2, (y - box.top) * 3 / h);
                    centers[band] += x;
                    counts[band]++;
                }
        for (int band = 0; band < 3; band++) {
            if (counts[band] == 0) return false;
            centers[band] /= counts[band];
        }
        float sign = left ? 1 : -1;
        // Italic brackets lean across their height; compare curvature after removing that slope.
        return ((centers[0] + centers[2]) * .5f - centers[1]) * sign + .5f > gap * .06f
                && Math.abs(centers[0] - centers[2]) < gap * .65f;
    }

    /** A two-counter numeral can touch a taller fingering stack below its own closed ribbon. */
    private static InkBox closedStackedRibbonEight(byte[] gray, int width, int height,
            InkBox stack, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int first = stack.top + Math.round(gap * 1.62f), last = stack.top + Math.round(gap * 2.0f);
        for (int cut = first; cut <= last; cut++) {
            var numeral = new InkBox(stack.left, stack.top, stack.right, cut, 0);
            if (!bareEightShape(gray, width, numeral, gap)) continue;
            var ribbon = fullSystemDottedEnd(gray, width, height, numeral, staff);
            if (ribbon != null && !ribbon.continuesAtStaffEdge) return numeral;
        }
        return null;
    }

    private static SystemRibbonEnd provedSystemRibbonNumeral(byte[] gray, int width, int height,
            InkBox numeral, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        var shortStaffEdge = shortOpenDottedStaffEdge(gray, width, height, numeral, staff);
        if (shortStaffEdge != null) return shortStaffEdge;
        boolean closedNumeral = bareEightShape(gray, width, numeral, gap)
                || paleCounterShape(gray, width, height, numeral, gap);
        if (closedNumeral) {
            var fullSystem = fullSystemDottedEnd(gray, width, height, numeral, staff);
            if (fullSystem != null) return fullSystem;
        }
        var diagonal = diagonalNumeralRibbon(gray, width, height, numeral, staff);
        if (diagonal != null) return diagonal;
        if (!canRepairEight(gray, width, numeral, gap)
                || !brokenEightShape(gray, width, numeral, gap)) return null;
        float crossedTerminal = octaveReturnAcrossSlur(gray, width, height, numeral, gap);
        if (crossedTerminal >= 0) return new SystemRibbonEnd(crossedTerminal, false, true, true);
        if (closedNumeral) return null;
        var ribbon = traceSystemDottedEnd(gray, width, height, numeral, staff, .18f, 44, true);
        return ribbon != null && ribbon.continuesAtStaffEdge ? ribbon : null;
    }

    /** A wider italic eight needs its own regular dotted line and a physical endpoint. */
    private static SystemRibbonEnd diagonalNumeralRibbon(
            byte[] gray, int width, int height, InkBox numeral, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int span = numeral.right - numeral.left + 1;
        int tall = numeral.bottom - numeral.top + 1;
        if (span < gap * 1.55f || span > gap * 1.91f
                || tall < gap * 1.65f || tall > gap * 1.95f
                || staff.count() != 1 || staff.top() - numeral.bottom < gap * 2)
            return null;
        int holes = OctaveClefDigit.holes(gray, width, numeral.left, numeral.top, span, tall);
        if (holes == 2) {
            if (bareEightShape(gray, width, numeral, gap)) return null;
            var closed = completeDottedRibbon(gray, width, height, numeral, gap);
            return closed != null && closed.right - numeral.right >= gap * 18
                    ? new SystemRibbonEnd(closed.right, false, true, true)
                    : null;
        }
        if (holes != 1 || !canRepairEight(gray, width, numeral, gap)
                || !brokenEightShape(gray, width, numeral, gap)) return null;
        var open = traceSystemDottedEnd(gray, width, height, numeral, staff, .30f, 9, true);
        return open != null && open.continuesAtStaffEdge
                ? new SystemRibbonEnd(open.right, true, true, true)
                : null;
    }

    /** Recover two real light-ink counters within a bounded contour. */
    private static boolean paleCounterShape(byte[] gray, int width, int height, InkBox box, float gap) {
        int wide = box.right - box.left + 1, tall = box.bottom - box.top + 1;
        if (wide < gap * 1.1f || wide > gap * 2.15f || tall < gap * 1.42f || tall > gap * 1.98f
                || OctaveClefDigit.holes(gray, width, box.left, box.top, wide, tall) >= 2) return false;
        int pad = Math.round(gap * .13f);
        int left = Math.max(0, box.left - pad), top = Math.max(0, box.top - pad);
        int right = Math.min(width - 1, box.right + pad), bottom = Math.min(height - 1, box.bottom + pad);
        int w = right - left + 1, h = bottom - top + 1;
        var optical = new byte[w * h];
        int dark = 0;
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++)
                if ((gray[y * width + x] & 255) < 165) dark++;
        if (dark < gap * gap * .75f) return false;
        for (int shade : new int[] {205, 235, 253}) {
            for (int y = top; y <= bottom; y++)
                for (int x = left; x <= right; x++)
                    optical[(y - top) * w + x - left] = (gray[y * width + x] & 255) < shade ? 0 : (byte) 255;
            var components = RawNumeralComponents.capture(optical, w, h, 0, 0, w - 1, h - 1, () -> {}, () -> {});
            for (var part : components.components()) {
                int cw = part.right - part.left + 1, ch = part.bottom - part.top + 1;
                if (cw < gap * 1.1f || cw > gap * 2.18f || ch < gap * 1.4f || ch > gap * 2.15f
                        || part.size() < dark || part.size() > cw * ch * .80f) continue;
                if (OctaveClefDigit.holes(optical, w, part.left, part.top, cw, ch) == 2) return true;
            }
        }
        return false;
    }

    /** Trim a thin vertical attachment from a two-counter numeral above the staff. */
    private static InkBox verticallyAttachedEight(byte[] gray, int width, InkBox box, float gap) {
        int w = box.right - box.left + 1, h = box.bottom - box.top + 1;
        if (h < gap * 2.3f || w < gap * .8f || w > gap * 1.9f) return null;
        for (int y = box.top + Math.round(gap * 1.35f); y <= box.top + Math.round(gap * 1.85f); y++) {
            if (box.bottom - y < gap * .65f) break;
            int narrow = 0;
            for (int row = y + 1; row <= box.bottom; row++) {
                int ink = 0;
                for (int x = box.left; x <= box.right; x++)
                    if ((gray[row * width + x] & 255) < 165) ink++;
                if (ink > 0 && ink <= gap * .45f) narrow++;
            }
            if (narrow < (box.bottom - y) * .8f) continue;
            var cap = new InkBox(box.left, box.top, box.right, y, box.area);
            if (bareEightShape(gray, width, cap, gap)) return cap;
        }
        return null;
    }

    /** Both printed counters can be completed by pale ink at the edge of the numeral. */
    private static InkBox faintCounterEight(byte[] gray, int width, InkBox box, float gap) {
        int w = box.right - box.left + 1, h = box.bottom - box.top + 1;
        if (w < gap * 1.0f || w > gap * 1.55f || h < gap * 1.43f || h > gap * 1.85f) return null;
        var contained = faintCounterEight(gray, width, box, gap, box.left, h);
        if (contained != null) return contained;
        return faintCounterEight(
                gray, width, box, gap, Math.max(0, box.left - Math.round(gap * .20f)), h);
    }

    private static InkBox faintCounterEight(
            byte[] gray, int width, InkBox box, float gap, int left, int h) {
        int capWidth = box.right - left + 1;
        byte[] cap = new byte[capWidth * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < capWidth; x++)
                cap[y * capWidth + x] =
                        (byte) ((gray[(box.top + y) * width + left + x] & 255) < 245 ? 0 : 255);
        var components =
                RawNumeralComponents.capture(
                        cap, capWidth, h, 0, 0, capWidth - 1, h - 1, 164, () -> {}, () -> {});
        InkBox accepted = null;
        for (var component : components.components()) {
            if (component.size() < box.area || component.top != 0 || component.bottom < h - 2)
                continue;
            int inkWidth = component.right - component.left + 1;
            if (inkWidth > gap * 1.62f) continue;
            if (OctaveClefDigit.holes(cap, capWidth, component.left, 0, inkWidth, h) != 2) continue;
            if (accepted != null) return null;
            accepted =
                    new InkBox(
                            left + component.left,
                            box.top,
                            left + component.right,
                            box.bottom,
                            component.size());
        }
        return accepted;
    }

    private static boolean canRepairEight(byte[] gray, int width, InkBox box, float gap) {
        int w = box.right - box.left + 1, h = box.bottom - box.top + 1;
        return w >= gap * .8f
                && w <= Math.round(gap * 1.85f)
                && h >= gap * 1.15f
                && h <= gap * 2.3f
                && OctaveClefDigit.holes(gray, width, box.left, box.top, w, h) == 1;
    }

    /** Recover a broken loop only if one short bridge restores the second counter. */
    private static boolean brokenEightShape(byte[] gray, int width, InkBox box, float gap) {
        int w = box.right - box.left + 1, h = box.bottom - box.top + 1;
        var patch = new byte[w * h];
        for (int y = 0; y < h; y++)
            System.arraycopy(gray, (box.top + y) * width + box.left, patch, y * w, w);
        int bridge = Math.max(2, Math.round(gap * .43f));
        for (int y = 0; y < h; y++) {
            for (int x = 1; x < w - 1; x++) {
                int at = y * w + x;
                if ((patch[at - 1] & 255) >= 165 || (patch[at] & 255) < 165) continue;
                int end = x;
                while (end < w && (patch[y * w + end] & 255) >= 165) end++;
                if (end < w && end - x <= bridge)
                    java.util.Arrays.fill(patch, at, y * w + end, (byte) 0);
                x = end;
            }
        }
        if (OctaveClefDigit.holes(patch, w, 0, 0, w, h) == 2) return true;
        for (int y = 0; y < h; y++)
            System.arraycopy(gray, (box.top + y) * width + box.left, patch, y * w, w);
        for (int y = 1; y < h - 1; y++) {
            for (int x = 0; x < w; x++) {
                if ((patch[y * w + x] & 255) < 165) continue;
                int start = y;
                int end = y;
                while (end < h && (patch[end * w + x] & 255) >= 165) end++;
                if ((patch[(start - 1) * w + x] & 255) < 165 && end < h
                        && end - start <= bridge)
                    for (int row = start; row < end; row++) patch[row * w + x] = 0;
            }
        }
        if (OctaveClefDigit.holes(patch, w, 0, 0, w, h) == 2) return true;
        return hasDiagonalLowerLoop(gray, width, box, w, h, bridge);
    }

    /** A broken curved stroke can leave a gap across two adjacent rows. */
    private static boolean hasDiagonalLowerLoop(
            byte[] gray, int stride, InkBox box, int w, int h, int bridge) {
        var patch = new byte[w * h];
        for (int y = 0; y < h; y++)
            System.arraycopy(gray, (box.top + y) * stride + box.left, patch, y * w, w);
        for (int y = h / 2; y < h - 1; y++) {
            for (int x = 0; x < w; x++) {
                if ((patch[y * w + x] & 255) >= 165) continue;
                for (int step = -1; step <= 1; step += 2) {
                    for (int length = 2; length <= bridge; length++) {
                        int end = x + step * length;
                        if (end < 0 || end >= w) break;
                        if ((patch[(y + 1) * w + end] & 255) >= 165) continue;
                        var repaired = patch.clone();
                        for (int i = 1; i < length; i++) {
                            int row = i * 2 < length ? y : y + 1;
                            repaired[row * w + x + step * i] = 0;
                        }
                        if (OctaveClefDigit.holes(repaired, w, 0, 0, w, h) == 2) return true;
                    }
                }
            }
        }
        return false;
    }

    /** Prove a wide bare numeral by both counters and a closed printed ribbon. */
    private static boolean closedWideRibbonEight(byte[] gray, int width, int height, InkBox box, float gap) {
        int wide = box.right - box.left + 1, tall = box.bottom - box.top + 1;
        int nominal = Math.round(gap * 1.6f);
        if (wide <= nominal || wide > gap * 2.1f || tall < gap * 1.15f || tall > gap * 2.25f
                || OctaveClefDigit.holes(gray, width, box.left, box.top, wide, tall) != 2) return false;
        var core = new InkBox(box.left, box.top, box.left + nominal - 1, box.bottom, 0);
        if (!bareEightShape(gray, width, core, gap)) return false;
        float ordinary = dashEnd(gray, width, height, box.right + 1, box.top, box.bottom, gap, 6);
        if (ordinary - box.right >= gap * 8 && ordinary - box.right <= gap * 30
                && hookDirection(gray, width, height, ordinary, box.top, box.bottom, gap) == 1) return true;
        var ribbon = completeDottedRibbon(gray, width, height, box, gap);
        return ribbon != null && ribbon.right - box.right >= gap * 8 && ribbon.right - box.right <= gap * 30;
    }

    private static boolean bareEightShape(byte[] gray, int width, InkBox box, float gap) {
        int height = box.bottom - box.top + 1;
        int span = box.right - box.left + 1;
        if (height < gap * .65f || height > gap * 2.3f || span < gap * .3f) return false;
        int maxWidth = Math.round(Math.min(gap * 1.6f, height / .9f));
        if (span <= maxWidth) {
            return OctaveClefDigit.holes(gray, width, box.left, box.top, span, height) == 2;
        }
        if (span - maxWidth > gap * .75f) return false;
        // The first dash may touch the eight. Only a thin right-hand extension can be trimmed.
        int top = box.bottom, bottom = box.top;
        for (int y = box.top; y <= box.bottom; y++) {
            for (int x = box.left + maxWidth; x <= box.right; x++) {
                if ((gray[y * width + x] & 255) < 165) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return top <= bottom
                && bottom - top + 1 <= gap * .32f
                && OctaveClefDigit.holes(gray, width, box.left, box.top, maxWidth, height) == 2;
    }

    private static final class HoleScanScratch {
        int[] queue = new int[256];
        int[] leftInk = new int[0], rightInk = new int[0];
    }

    private static List<InkBox> attachedEightBoxes(
            byte[] gray, int w, int h, int top, int bottom, float gap, boolean below, HoleScanScratch scratch, java.util.BitSet unavailable) {
        // Every component in a strip this narrow touches a side.
        if (w <= 2) return List.of();
        int band = bottom - top + 1;
        if (scratch.leftInk.length < band) {
            scratch.leftInk = new int[band];
            scratch.rightInk = new int[band];
        }
        // The band mask contains all original dark pixels. Read the
        // original row limits before reusing those bits for visited paper runs.
        int[] leftInk = scratch.leftInk, rightInk = scratch.rightInk;
        int nextInk = unavailable.nextSetBit(0);
        for (int y = 0; y < band; y++) {
            int base = y * w, end = base + w;
            if (nextInk >= 0 && nextInk < end) {
                leftInk[y] = nextInk - base;
                rightInk[y] = unavailable.previousSetBit(end - 1) - base;
                nextInk = unavailable.nextSetBit(end);
            } else {
                leftInk[y] = w;
                rightInk[y] = -1;
            }
        }
        var holes = new ArrayList<InkBox>();
        float minimumArea = gap * gap * .015f, maximumArea = gap * gap * .65f;
        for (int y0 = 0; y0 < band; y0++) {
            if (rightInk[y0] - leftInk[y0] < 2) continue;
            int base = y0 * w;
            for (int seed = unavailable.nextClearBit(base + leftInk[y0] + 1); seed < base + rightInk[y0];
                    seed = unavailable.nextClearBit(seed + 1)) {
                boolean open = false;
                int size = enqueuePaperRun(unavailable, seed, 0, scratch);
                int pixels = 0, x0 = w, x1 = -1, y1 = y0;
                for (int take = 0; take < size; take += 2) {
                    int first = scratch.queue[take], last = scratch.queue[take + 1];
                    int y = first / w, row = y * w, left = first - row, right = last - row;
                    pixels += last - first + 1;
                    x0 = Math.min(x0, left);
                    x1 = Math.max(x1, right);
                    y1 = Math.max(y1, y);
                    for (int direction = -1; direction <= 1; direction += 2) {
                        int nextY = y + direction;
                        if (nextY < 0 || nextY >= band) continue;
                        int nextRow = row + direction * w;
                        if (left < leftInk[nextY] || right > rightInk[nextY]) open = true;
                        int firstNeighbor = Math.max(left, leftInk[nextY] + 1);
                        int lastNeighbor = Math.min(right, rightInk[nextY] - 1);
                        if (firstNeighbor > lastNeighbor) continue;
                        for (int next = unavailable.nextClearBit(nextRow + firstNeighbor);
                                next <= nextRow + lastNeighbor;
                                next = unavailable.nextClearBit(scratch.queue[size - 1] + 1)) {
                            size = enqueuePaperRun(unavailable, next, size, scratch);
                        }
                    }
                }
                if (!open && y0 > 0 && y1 < band - 1
                        && pixels >= minimumArea
                        && pixels <= maximumArea
                        && x1 - x0 < gap
                        && y1 - y0 < gap) holes.add(new InkBox(x0, top + y0, x1, top + y1, pixels));
            }
        }
        if (holes.size() < 2) return List.of();
        var result = new ArrayList<InkBox>();
        int pad = Math.max(2, Math.round(gap * .2f));
        float maximumSeparation = gap * .5f, maximumCenterDifference = gap * 1.1f;
        for (int i = 0; i < holes.size(); i++) {
            var a = holes.get(i);
            // A hole's first row is its first raster seed, so top edges
            // already follow the scan order.
            int low = i + 1, high = holes.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (holes.get(middle).top <= a.bottom) low = middle + 1;
                else high = middle;
            }
            int center = a.left + a.right;
            for (int j = low; j < holes.size(); j++) {
                var b = holes.get(j);
                if (b.top - a.bottom > maximumSeparation) break;
                if (Math.abs(center - b.left - b.right) > maximumCenterDifference) continue;
                int left = Math.max(0, Math.min(a.left, b.left) - pad),
                        right = Math.min(w - 1, Math.max(a.right, b.right) + pad);
                int y0 = Math.max(top, a.top - pad), y1 = Math.min(bottom, b.bottom + pad);
                if (y1 - y0 < gap * .65f || y1 - y0 > gap * 2.3f || right - left > gap * 1.6f)
                    continue;
                var box = new InkBox(left, y0, right, y1, a.area + b.area);
                if (ledgerTouchesLeft(gray, w, h, left, right, y0, y1, gap)
                        || attachedShortRibbonEnd(gray, w, h, box, gap) >= 0
                        || !below && bottomAttachedDottedEight(gray, w, h, box, gap))
                    result.add(box);
            }
        }
        return result;
    }

    private static int enqueuePaperRun(
            java.util.BitSet unavailable, int seed, int size, HoleScanScratch scratch) {
        // Seeds lie strictly between two ink pixels. An entire paper run is claimed
        // at once, so both neighboring set bits are its original ink boundaries.
        int first = unavailable.previousSetBit(seed - 1) + 1;
        int last = unavailable.nextSetBit(seed + 1) - 1;
        unavailable.set(first, last + 1);
        if (size == scratch.queue.length)
            scratch.queue = java.util.Arrays.copyOf(scratch.queue, Math.multiplyExact(size, 2));
        scratch.queue[size] = first;
        scratch.queue[size + 1] = last;
        return size + 2;
    }

    /** Recover a numeral merged with a thin ledger, not a second reading of every
     * isolated numeral or a pair of counters inside neighboring text. */
    /** A short closed octave ribbon can own a numeral touching the preceding head at its lower loop. */
    private static float attachedShortRibbonEnd(byte[] gray, int width, int height, InkBox box, float gap) {
        int tall = box.bottom - box.top + 1;
        if (box.left < 2 || tall < gap * 1.4f || tall > gap * 1.95f
                || !bareEightShape(gray, width, box, gap)) return -1;
        int joined = 0;
        for (int y = box.top + Math.round(tall * .62f); y <= box.bottom; y++)
            if ((gray[y * width + box.left - 1] & 255) < 165
                    && (gray[y * width + box.left] & 255) < 165) joined++;
        if (joined < gap * .16f) return -1;
        float right = dashEnd(gray, width, height, box.right + 1, box.top, box.bottom, gap, 12);
        if (right - box.right < gap * 11 || right - box.right > gap * 24) return -1;
        int end = Math.round(right - gap * .55f);
        int from = Math.max(0, Math.round(box.top - gap * .12f));
        int through = Math.min(height - 1, Math.round(box.bottom + gap * .22f));
        if (end < 3 || end + 2 >= width) return -1;
        for (int y = from; y <= through; y++) {
            if (!endsHorizontalDashes(gray, width, end, y, gap)
                    || y + gap * .45f >= height) continue;
            for (int start = y + Math.round(gap * .14f); start <= y + Math.round(gap * .40f); start++) {
                int x = end + 1;
                if ((gray[start * width + x] & 255) >= 165 || (gray[start * width + x + 1] & 255) >= 165)
                    continue;
                if (hasJoinedDashHook(gray, width, height, x, start, 1, gap)
                        && hasJoinedDashHook(gray, width, height, x + 1, start, 1, gap)
                        && !hasJoinedDashHook(gray, width, height, x, start, -1, gap)) return right;
            }
        }
        return -1;
    }

    /** A slur touching the lower rim can merge an octave numeral into a wider component. */
    private static boolean bottomAttachedDottedEight(
            byte[] gray, int width, int height, InkBox box, float gap) {
        int tall = box.bottom - box.top + 1;
        if (tall < gap * 1.4f || tall > gap * 2.0f || !bareEightShape(gray, width, box, gap))
            return false;
        int reach = Math.round(gap * .6f);
        int left = box.left - reach, right = box.left + reach;
        if (left < 0 || right >= width) return false;
        boolean touchesBottom = false;
        for (int y = box.bottom - Math.round(gap * .12f); y <= box.bottom; y++) {
            int ink = 0;
            for (int x = left; x <= right; x++)
                if ((gray[y * width + x] & 255) < 165) ink++;
            if (ink >= right - left - 1) {
                touchesBottom = true;
                break;
            }
        }
        if (!touchesBottom) return false;
        float end = dashEnd(gray, width, height, box.right + 1, box.top, box.bottom, gap, 10);
        return end - box.right >= gap * 11
                && end - box.right <= gap * 24
                && hookDirection(gray, width, height, end, box.top, box.bottom, gap) == 1;
    }

    private static boolean ledgerTouchesLeft(
            byte[] gray, int w, int h, int left, int right, int top, int bottom, float gap) {
        int reach = Math.max(3, Math.round(gap * .8f));
        if (left < reach + 1) return false;
        int required = (int) Math.ceil((reach + 3) * .85f);
        int maxThickness = Math.max(3, Math.round(gap * .35f));
        for (int y = Math.round(top + (bottom - top) * .3f);
                y <= Math.round(top + (bottom - top) * .7f);
                y++) {
            int needed = required, row = y * w;
            for (int x = left - reach; needed > 0 && needed <= left + 3 - x; x++)
                if ((gray[row + x] & 255) < 165) needed--;
            if (needed > 0) continue;
            int x = left - reach / 2, a = y, b = y;
            while (a > 0 && b - a + 1 <= maxThickness
                    && (gray[(a - 1) * w + x] & 255) < 165) a--;
            while (b + 1 < h && b - a + 1 <= maxThickness
                    && (gray[(b + 1) * w + x] & 255) < 165) b++;
            if (b - a + 1 <= maxThickness) return true;
        }
        return false;
    }

    private static float x(ScoreNoteEvent note, List<MeasureRegion> measures, int width) {
        var m = measures.get(note.measureIndex());
        return (m.left() + note.positionInMeasure() * (m.right() - m.left())) * width;
    }

    private static boolean onStaff(
            ScoreNoteEvent note,
            PlayingTechniqueDetector.Staff staff,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            int height) {
        PlayingTechniqueDetector.Staff nearest = null;
        float best = Float.POSITIVE_INFINITY;
        for (var candidate : staffs)
            if (note.staffIndex() == candidate.index() && note.staffCount() == candidate.count()) {
                float y = note.pageY() * height,
                        distance =
                                Math.max(0, Math.max(candidate.top() - y, y - candidate.bottom()))
                                        / candidate.gap();
                if (distance <= 7 && distance < best) {
                    best = distance;
                    nearest = candidate;
                }
            }
        if (nearest != null && note.staffCount() == 1 && Math.abs(note.staffStep()) > 8) {
            var measure = measures.get(note.measureIndex());
            float top = measure.top() * height, bottom = measure.bottom() * height;
            if (nearest.bottom() < top || nearest.top() > bottom) {
                var ledgerOwner = ledgerStaff(note, staffs, height, top, bottom);
                if (ledgerOwner != null) nearest = ledgerOwner;
            }
        }
        return staff.equals(nearest);
    }

    private static PlayingTechniqueDetector.Staff ledgerStaff(
            ScoreNoteEvent note,
            List<PlayingTechniqueDetector.Staff> staffs,
            int height,
            float top,
            float bottom) {
        PlayingTechniqueDetector.Staff owner = null;
        float y = note.pageY() * height;
        for (var candidate : staffs) {
            float gap = candidate.gap();
            if (candidate.count() != 1
                    || candidate.index() != note.staffIndex()
                    || candidate.top() < top
                    || candidate.bottom() > bottom
                    || Math.abs(y + note.staffStep() * gap * .5f - candidate.bottom()) > gap * .65f
                    || y < candidate.top() - 7 * gap
                    || y > candidate.bottom() + 7 * gap) continue;
            if (owner != null) return null;
            owner = candidate;
        }
        return owner;
    }

    private static PlayingTechniqueDetector.Staff owner(
            PlayingTechniqueDetector.Word word,
            List<PlayingTechniqueDetector.Staff> staffs,
            int shift,
            int height,
            boolean printed,
            byte[] gray,
            int width) {
        PlayingTechniqueDetector.Staff best = null;
        float distance = Float.POSITIVE_INFINITY;
        for (var staff : staffs) {
            float d =
                    shift > 0
                            ? (staff.top() - word.bottom() * height) / staff.gap()
                            : (word.top() * height - staff.bottom()) / staff.gap();
            if (d > 12 || d >= distance) continue;
            if (d < .25f) {
                // Raster boxes round to whole pixels. Only a proved printed mark with
                // a continuing dash chain can use the fractional-pixel clearance band.
                float clearance =
                        shift > 0
                                ? staff.top() - Math.round(word.bottom() * height)
                                : Math.round(word.top() * height) - staff.bottom();
                if (!printed
                        || clearance < 1
                        || clearance < Math.floor(staff.gap() * .25f)
                        || dashEnd(
                                        gray,
                                        width,
                                        height,
                                        word.right() * width,
                                        word.top() * height,
                                        word.bottom() * height,
                                        staff.gap())
                                < word.right() * width + staff.gap() * 2) continue;
            }
            distance = d;
            best = staff;
        }
        return best;
    }

    /** A compact core next to a ledger can be part of the direction at an octave dash origin. */
    static boolean dotBelongsToDashOrigin(
            byte[] gray,
            int width,
            int height,
            float headX,
            float headY,
            WrappedTripletDotInk.Center core,
            float gap) {
        if (core.x() - headX < gap * 1.1f || core.x() - headX > gap * 1.45f) return false;
        int left = Math.round(headX - gap * .6f), right = Math.round(core.x() + gap * 1.05f);
        int top = Math.round(Math.min(headY, core.y()) - gap * .55f);
        int bottom = Math.round(Math.max(headY, core.y()) + gap * 1.25f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var glyphs =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 145, () -> {}, () -> {});
        int seed = core.y() * width + core.x();
        for (var body : glyphs.components()) {
            float widthG = body.right - body.left + 1, heightG = body.bottom - body.top + 1;
            if (body.left <= headX + gap * .85f
                    || body.left > core.x()
                    || body.right < core.x()
                    || body.top >= core.y()
                    || body.bottom <= core.y()
                    || widthG < gap * .65f
                    || widthG > gap * 1.45f
                    || heightG < gap * 1.25f
                    || heightG > gap * 1.9f
                    || body.size() < gap * gap * .43f) continue;
            boolean owns = false;
            for (int i = 0; i < body.size(); i++) owns |= body.pixel(i) == seed;
            if (!owns) continue;
            float end =
                    dashEnd(
                            gray,
                            width,
                            height,
                            body.right - gap * .20f,
                            body.top,
                            body.bottom,
                            gap,
                            5);
            if (end > body.right + gap * 4) return true;
        }
        return false;
    }

    /** Find a horizontal chain of short printed dashes, stopping at its actual end. */
    private record DottedRibbon(float right, List<RawNumeralComponents.Component> dots, boolean closedCrossing) {
        DottedRibbon(float right, List<RawNumeralComponents.Component> dots) {
            this(right, dots, false);
        }
    }

    private static DottedRibbon provedPrintedRibbon(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Word word,
            float gap,
            float ordinaryRight) {
        var box =
                new InkBox(
                        Math.round(word.left() * width),
                        Math.round(word.top() * height),
                        Math.round(word.right() * width) - 1,
                        Math.round(word.bottom() * height) - 1,
                        0);
        if (!bareEightShape(gray, width, box, gap)
                || ordinaryRight >= 0
                        && hookDirection(
                                        gray,
                                        width,
                                        height,
                                        ordinaryRight,
                                        box.top,
                                        box.bottom,
                                        gap)
                                > 0) return null;
        return completeDottedRibbon(gray, width, height, box, gap);
    }

    private static DottedRibbon completeDottedRibbon(
            byte[] gray, int width, int height, InkBox box, float gap) {
        var crossed = dottedRibbonAcrossSlur(gray, width, height, box, gap);
        if (crossed != null) return crossed;
        return longClosedDottedRibbon(gray, width, height, box, gap);
    }

    private record SlopingOctave(PlayingTechniqueDetector.Staff staff, InkBox numeral, float end) {}

    /** The rising lead and level end belong to one continuously printed series of dashes. */
    private static float slopingDottedOctaveEnd(
            byte[] gray, int width, int height, InkBox box, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int wide = box.right - box.left + 1, tall = box.bottom - box.top + 1;
        if (gap < 10
                || gap > 24
                || staff.top() - box.bottom < 2 * gap
                || staff.top() - box.bottom > 4 * gap
                || wide < 1.4f * gap
                || wide > 2.1f * gap
                || tall < 1.45f * gap
                || tall > 2.15f * gap
                || OctaveClefDigit.holes(gray, width, box.left, box.top, wide, tall) != 2)
            return -1;
        int left = box.right, top = Math.round(box.top - gap);
        int right = Math.min(width - 1, left + Math.round(29 * gap));
        int bottom = Math.round(box.top + gap * .95f);
        if (top < 0 || bottom >= height || right - left < 24 * gap) return -1;
        var frame =
                RawNumeralComponents.captureLongWindow(gray, width, height, left, top, right, bottom, 165);
        var dots = dottedComponents(frame, gap, .6f);
        var line = new ArrayList<RawNumeralComponents.Component>();
        for (var dot : dots) {
            float dx = dotX(dot) - left, y = dotY(dot);
            if (line.isEmpty()) {
                if (dx > .72f * gap) return -1;
                if (y >= box.top + .25f * gap && y <= box.top + .72f * gap) line.add(dot);
                continue;
            }
            var prior = line.get(line.size() - 1);
            float advance = dotX(dot) - dotX(prior);
            if (Math.abs(y - dotY(prior)) > .24f * gap) continue;
            if (advance > .80f * gap) {
                if (advance < 4 * gap) return -1;
                break;
            }
            if (advance < .33f * gap) continue;
            line.add(dot);
        }
        if (line.size() < 34) return -1;
        var first = line.get(0);
        var last = line.get(line.size() - 1);
        float advance = dotX(last) - dotX(first), rise = dotY(first) - dotY(last);
        if (advance < 19 * gap || advance > 25 * gap || rise < .55f * gap || rise > 1.1f * gap)
            return -1;
        var tail = line.subList(line.size() / 2, line.size());
        if (tail.stream().anyMatch(dot -> Math.abs(dotY(dot) - dotY(last)) > .18f * gap)
                || hookDirection(
                                gray,
                                width,
                                height,
                                last.right + gap * .55f,
                                last.top,
                                last.bottom,
                                gap)
                        != 0) return -1;
        return last.right;
    }

    private record SlopingDyad(BeamStartHead upper, BeamStartHead lower) {
        float x() {
            return (upper.x + lower.x) * .5f;
        }
    }

    /** An open-looking ink end only closes when two independent beams and both written pitches agree. */
    private static Span slopingOctaveOwners(
            byte[] gray,
            int width,
            int height,
            SlopingOctave direction,
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes) {
        float gap = direction.staff.gap(), start = direction.numeral.left, end = direction.end;
        var heads = new ArrayList<BeamStartHead>();
        for (var n : notes) {
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.measureIndex() < 0
                    || n.measureIndex() >= measures.size()
                    || n.staffIndex() != direction.staff.index()
                    || n.staffCount() != direction.staff.count()) continue;
            float x = x(n, measures, width);
            if (x >= start - 6 * gap
                    && x <= end + 6 * gap
                    && onStaff(n, direction.staff, staffs, measures, height))
                heads.add(new BeamStartHead(n, x));
        }
        heads.sort(Comparator.comparingDouble(BeamStartHead::x));
        if (heads.size() % 2 != 0) return null;
        var dyads = new ArrayList<SlopingDyad>();
        int first = -1, last = -1;
        for (int i = 0; i < heads.size(); i += 2) {
            var a = heads.get(i);
            var b = heads.get(i + 1);
            var pair =
                    a.note.pageY() < b.note.pageY() ? new SlopingDyad(a, b) : new SlopingDyad(b, a);
            float separation = (pair.lower.note.pageY() - pair.upper.note.pageY()) * height;
            if (b.x - a.x > .21f * gap
                    || separation < 3.1f * gap
                    || separation > 3.9f * gap
                    || a.note.measureIndex() != b.note.measureIndex()) return null;
            if (pair.x() >= start && first < 0) first = dyads.size();
            if (pair.x() <= end) last = dyads.size();
            dyads.add(pair);
        }
        if (first < 2
                || last < first + 6
                || last + 2 >= dyads.size()
                || dyads.get(first).x() - start > gap
                || end - dyads.get(last).x() > .85f * gap) return null;
        if (!slopingOctaveBreak(gray, width, height, gap, dyads, first, 1)
                || !slopingOctaveBreak(gray, width, height, gap, dyads, last + 1, -1)) return null;
        return new Span(direction.staff, dyads.get(first).x() - .20f * gap, end, 1, false, null, true);
    }

    private static boolean slopingOctaveBreak(
            byte[] gray,
            int width,
            int height,
            float gap,
            List<SlopingDyad> dyads,
            int index,
            int direction) {
        var left = dyads.get(index - 1);
        var right = dyads.get(index);
        float advance = right.x() - left.x();
        if (advance < 1.6f * gap || advance > 3.6f * gap) return false;
        float upperMove = (right.upper.note.pageY() - left.upper.note.pageY()) * height * direction;
        float lowerMove = (right.lower.note.pageY() - left.lower.note.pageY()) * height * direction;
        if (upperMove < 2.55f * gap
                || upperMove > 3.6f * gap
                || lowerMove < 2.55f * gap
                || lowerMove > 3.6f * gap) return false;
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(4);
        var budget = new PrintedBeamShafts.Budget(180_000);
        for (int i = index - 2; i <= index + 1; i++) {
            var dyad = dyads.get(i);
            if (dyad.upper.note.beamCount() != 2
                    || dyad.lower.note.beamCount() != 2
                    || dyad.upper.note.stemDirection() != -1
                    || dyad.lower.note.stemDirection() != -1) return false;
            var low = dyad.lower;
            var ink =
                    PrintedBeamShafts.at(
                            gray, width, height, low.x, low.note.pageY() * height, gap, budget);
            if (ink.size() != 1 || ink.get(0).direction() != 1) return false;
            shafts.add(ink.get(0));
        }
        return WrappedEighthTriplets.matchesBeamRails(
                        gray, width, height, shafts.get(0), shafts.get(1), gap, 2)
                && WrappedEighthTriplets.matchesBeamRails(
                        gray, width, height, shafts.get(1), shafts.get(2), gap, 0)
                && WrappedEighthTriplets.matchesBeamRails(
                        gray, width, height, shafts.get(2), shafts.get(3), gap, 2);
    }

    /** Confirm a compact horizontal dotted line turning toward its staff at a detached tip. */
    private static float compactDottedReturn(
            byte[] gray, int width, int height, InkBox box, float gap) {
        int span = box.right - box.left + 1, tall = box.bottom - box.top + 1;
        if (gap < 10
                || gap > 24
                || span < gap * 1.12f
                || span > gap * 2.0f
                || tall < gap * 1.40f
                || tall > gap * 2.2f
                || OctaveClefDigit.holes(gray, width, box.left, box.top, span, tall) != 2)
            return -1;
        int left = box.right, top = Math.round(box.top - gap * .25f);
        int right = Math.min(width - 1, left + Math.round(gap * 10));
        int bottom = Math.round(box.top + gap * 2.2f);
        if (top < 0 || bottom >= height || right - left < gap * 5) return -1;
        var components =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 165, () -> {}, () -> {});
        var dots = dottedComponents(components, gap, .74f);
        if (dots.size() < 11) return -1;
        for (var dot : dots) {
            float y = dotY(dot);
            if (dot.left - left > .47f * gap) break;
            if (y < box.top + .21f * gap || y > box.top + .76f * gap) continue;
            var last = dot;
            int count = 1;
            for (var next : dots) {
                if (dotX(next) <= dotX(last) || Math.abs(dotY(next) - y) > gap * .33f) continue;
                float step = dotX(next) - dotX(last);
                if (step > gap * .86f) break;
                if (step < gap * .34f) continue;
                last = next;
                count++;
            }
            float length = last.right - left;
            if (count < 10 || length < gap * 4.7f || length > gap * 9.3f) continue;
            boolean down = false, up = false;
            for (var tooth : dots) {
                if (tooth == last
                        || Math.abs(dotX(tooth) - dotX(last)) > gap * .29f
                        || tooth.bottom - tooth.top + 1 < gap * .24f) continue;
                float offset = dotY(tooth) - dotY(last);
                if (offset >= gap * .39f && offset <= gap * 1.08f && tooth.bottom - y >= gap * .69f)
                    down = true;
                if (offset <= -gap * .39f && offset >= -gap * .99f) up = true;
            }
            if (down && !up) return last.right + gap * .4f;
        }
        return -1;
    }

    /** Dense printed dots may touch in the row scanner. Follow their full ink components. */
    private static DottedRibbon longClosedDottedRibbon(
            byte[] gray, int width, int height, InkBox numeral, float gap) {
        if (gap < 10 || gap > 24) return null;
        int left = numeral.right + 1;
        int top = Math.round(numeral.top - .35f * gap);
        int bottom = Math.round(numeral.top + 1.04f * gap);
        int right = Math.min(width - 1, left + Math.round(gap * 78));
        int leadRight = Math.min(right, left + Math.round(gap * 3.5f));
        if (top < 0 || bottom >= height || leadRight <= left) return null;
        var lead =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, leadRight, bottom, 164, () -> {}, () -> {});
        var earlyDots = dottedComponents(lead, gap, .74f);
        var first =
                earlyDots.stream()
                        .filter(
                                dot ->
                                        dot.left - left <= gap * .48f
                                                && dotY(dot) >= numeral.top + gap * .10f
                                                && dotY(dot) <= numeral.top + gap * .72f)
                        .findFirst()
                        .orElse(null);
        if (first == null || earlyDots.size() < 4) return null;

        var line = new ArrayList<RawNumeralComponents.Component>();
        line.add(first);
        int fused = 0;
        float center = dotY(first);
        for (var dot : wideDottedComponents(gray, width, height, left, top, right, bottom, gap)) {
            float y = dotY(dot);
            var prior = line.get(line.size() - 1);
            int space = dot.left - prior.right - 1;
            if (space < 1
                    || Math.abs(y - center) > gap * .76f
                    || Math.abs(y - dotY(prior)) > gap * .22f) continue;
            if (space > gap * .47f) break;
            if (dot.right - dot.left + 1 > gap * .74f) fused++;
            line.add(dot);
        }
        var last = line.get(line.size() - 1);
        float edge = last.right + gap * .55f;
        if (last.right - first.left < gap * 21)
            return bareEightShape(gray, width, numeral, gap)
                            && (fused == 0 && compactDottedRibbonHasClosedEnd(gray, width, height, line, gap)
                                    || fused == 1 && denseRibbonHasOffsetReturn(gray, width, height, line, gap))
                    ? new DottedRibbon(edge, line)
                    : null;
        if (line.size() < 27 || fused * 8 > line.size()) return null;
        if (hookDirection(gray, width, height, edge, last.top, last.bottom, gap) <= 0) return null;
        return new DottedRibbon(edge, line);
    }

    /** Verify a short, still-open dotted line ends on an independent final staff rule. */
    private static SystemRibbonEnd shortOpenDottedStaffEdge(
            byte[] gray, int width, int height, InkBox box, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int wide = box.right - box.left + 1, tall = box.bottom - box.top + 1;
        if (gap < 10
                || gap > 24
                || box.right < width * .75f
                || wide < gap * 1.1f
                || wide > gap * 2.1f
                || tall < gap * 1.4f
                || tall > gap * 2.2f
                || OctaveClefDigit.holes(gray, width, box.left, box.top, wide, tall) != 2)
            return null;
        var ribbon = traceSystemDottedEnd(gray, width, height, box, staff, .46f, 6, true);
        return ribbon != null && ribbon.continuesAtStaffEdge && ribbon.right - box.right <= 11 * gap
                ? ribbon
                : null;
    }

    /** A densely spaced line may join two horizontal dots before a short, staggered downward return. */
    private static boolean denseRibbonHasOffsetReturn(
            byte[] gray, int width, int height, List<RawNumeralComponents.Component> dots, float gap) {
        var first = dots.get(0);
        var last = dots.get(dots.size() - 1);
        if (dots.size() < 30 || last.right - first.left < gap * 16) return false;
        int fused = 0;
        var prior = first;
        for (int i = 0; i < dots.size(); i++) {
            var dot = dots.get(i);
            int wide = dot.right - dot.left + 1;
            if (wide > gap * .62f
                    && (i < 4 || i + 4 >= dots.size() || wide > gap * 1.12f || ++fused > 1)) return false;
            if (dot.bottom - dot.top + 1 > gap * .43f
                    || Math.abs(dotY(dot) - dotY(first)) > gap * .30f
                    || (i > 0 && dot.left - prior.right - 1 > gap * .35f)) return false;
            prior = dot;
        }
        if (fused != 1 || last.right + gap * 1.2f >= width) return false;
        for (int y = last.top; y <= last.bottom; y++) {
            if (!endsHorizontalDashes(gray, width, last.right, y, gap)) continue;
            boolean open = true;
            for (int x = last.right + 1; x <= last.right + Math.round(gap); x++)
                open &= (gray[y * width + x] & 255) >= 165;
            if (open && joinedDashHookDirection(gray, width, height, last.right, y, y, gap) == 1) return true;
        }
        return false;
    }

    /** Follow the regular dot rail past intersecting notation to its printed endpoint. */
    private static SystemRibbonEnd fullSystemDottedEnd(
            byte[] gray, int width, int height, InkBox numeral, PlayingTechniqueDetector.Staff staff) {
        var ordinary = traceSystemDottedEnd(gray, width, height, numeral, staff, .18f, 44, true);
        if (ordinary != null) return ordinary;
        var closed = traceSystemDottedEnd(gray, width, height, numeral, staff, .46f, 39, false);
        if (closed != null) return closed;
        var continued = traceSystemDottedEnd(gray, width, height, numeral, staff, .31f, 44, true);
        if (continued != null && continued.continuesAtStaffEdge) return continued;
        // One column of sampling can move the tile edge onto a crossing stroke.
        var shifted = traceSystemDottedEnd(gray, width, height, numeral, staff, .31f, 44, true, -1);
        if (shifted != null && shifted.continuesAtStaffEdge) return shifted;
        // A shorter repeated line can still end at a ruled staff edge or at a literal downward turn.
        return bareEightShape(gray, width, numeral, staff.gap())
                ? traceSystemDottedEnd(gray, width, height, numeral, staff, .18f, 24, true)
                : null;
    }

    private static SystemRibbonEnd traceSystemDottedEnd(byte[] gray, int width, int height, InkBox numeral,
            PlayingTechniqueDetector.Staff staff, float initialRadius, int minimumSpan, boolean allowOpen) {
        return traceSystemDottedEnd(gray, width, height, numeral, staff, initialRadius, minimumSpan, allowOpen, 0);
    }

    private static SystemRibbonEnd traceSystemDottedEnd(byte[] gray, int width, int height, InkBox numeral,
            PlayingTechniqueDetector.Staff staff, float initialRadius, int minimumSpan, boolean allowOpen,
            int tileStepAdjustment) {
        float gap = staff.gap();
        if (gap < 10 || gap > 24 || staff.count() != 1
                || staff.top() - numeral.bottom < gap * 2) return null;
        int first = numeral.right + 1;
        int initialRow = numeral.top + Math.round(gap * .29f), row = initialRow;
        int tileWidth = Math.round(gap * 4.25f), step = Math.round(gap * 2.65f) + tileStepAdjustment;
        int radius = Math.round(gap * .18f), drift = Math.round(gap * .65f);
        if (initialRow - drift < 0 || initialRow + drift >= height) return null;
        int last = -1;
        for (int start = first; start + tileWidth < width; start += step) {
            int strongestCount = 0, strongestRow = row, rightmost = -1;
            int range = last < 0 ? Math.round(initialRadius * gap) : radius;
            for (int y = Math.max(initialRow - drift, row - range);
                    y <= Math.min(initialRow + drift, row + range); y++) {
                int before = -1, count = 0, regularSteps = 0, edge = -1;
                for (int x = start, stop = start + tileWidth; x < stop; ) {
                    while (x < stop && (gray[y * width + x] & 255) >= 165) x++;
                    int inkStart = x;
                    while (x < stop && (gray[y * width + x] & 255) < 165) x++;
                    int length = x - inkStart;
                    if (inkStart == start || x == stop
                            || length < Math.round(gap * .19f) || length > gap * .65f) continue;
                    if (before >= 0 && inkStart - before >= gap * .34f && inkStart - before <= gap * .77f)
                        regularSteps++;
                    before = inkStart;
                    count++;
                    edge = x - 1;
                }
                if (regularSteps < 2 || count < strongestCount
                        || count == strongestCount
                                && Math.abs(y - row) >= Math.abs(strongestRow - row)) continue;
                strongestCount = count;
                strongestRow = y;
                rightmost = edge;
            }
            if (strongestCount < 4) {
                // An incomplete final tile may have only three intact dots before the turn.
                if (strongestCount == 3 && last - first >= gap * minimumSpan
                        && rightmost > last && rightmost - last <= gap * 1.8f) {
                    last = rightmost;
                    row = strongestRow;
                }
                break;
            }
            last = Math.max(last, rightmost);
            row = strongestRow;
        }
        if (last - first < gap * minimumSpan) return null;
        float right = last + gap * .55f;
        int hook = hookDirection(gray, width, height, right, row - 2, row + 2, gap);
        if (hook != 0) return hook > 0 ? new SystemRibbonEnd(right, false) : null;
        if (deepDottedTerminal(gray, width, height, last, row, gap)
                || !allowOpen && joinedDeepDottedTerminal(gray, width, height, last, row, gap))
            return new SystemRibbonEnd(right, false);
        return allowOpen && ribbonReachesStaffEdge(gray, width, height, staff, last)
                ? new SystemRibbonEnd(right, true) : null;
    }

    /** Two separate downward dashes can bracket a slur crossing the third terminal dot. */
    private static float octaveReturnAcrossSlur(byte[] gray, int width, int height, InkBox numeral, float gap) {
        if (gap < 10 || gap > 24) return -1;
        float right = dashEnd(gray, width, height, numeral.right + 1, numeral.top, numeral.bottom, gap, 20);
        if (right - numeral.right < gap * 12 || right - numeral.right > gap * 23) return -1;
        int end = Math.round(right - gap * .55f);
        int left = Math.round(end - gap * 2), far = Math.round(end + gap * 2.2f);
        int top = Math.round(numeral.top - gap * .10f), bottom = Math.round(numeral.top + gap * 2.7f);
        if (left < 3 || far >= width || top < 0 || bottom >= height) return -1;
        var field = RawNumeralComponents.capture(gray, width, height, left, top, far, bottom,
                165, () -> {}, () -> {});
        var dots = dottedComponents(field, gap, .46f);
        for (var corner : dots) {
            float x = dotX(corner), y = dotY(corner);
            if (x - end < gap * .18f || x - end > gap * .53f
                    || y - numeral.top < gap * .28f || y - numeral.top > gap * .65f) continue;
            var second = alignedTerminalDot(dots, x, y + gap * .56f, gap);
            var last = alignedTerminalDot(dots, x, y + gap * 1.70f, gap);
            if (second == null || last == null) continue;
            int anchor = Math.round(x), row = Math.round(y);
            if (!crossingBetweenTerminalDots(field, width, anchor, row, gap)) continue;
            return Math.max(right, last.right + gap * .30f);
        }
        return -1;
    }

    private static RawNumeralComponents.Component alignedTerminalDot(
            List<RawNumeralComponents.Component> dots, float x, float y, float gap) {
        return dots.stream().filter(d -> Math.abs(dotX(d) - x) <= gap * .16f
                        && Math.abs(dotY(d) - y) <= gap * .14f
                        && d.bottom - d.top + 1 >= gap * .22f)
                .findFirst().orElse(null);
    }

    private static boolean crossingBetweenTerminalDots(RawNumeralComponents field, int width,
            int x, int row, float gap) {
        int top = row + Math.round(gap * .89f), bottom = row + Math.round(gap * 1.36f);
        for (var component : field.components()) {
            if (component.left >= x - gap * .85f || component.right <= x + gap * .85f) continue;
            int first = field.bottom, last = field.top, ink = 0;
            for (int i = 0; i < component.size(); i++) {
                int pixel = component.pixel(i);
                if (pixel % width != x) continue;
                int y = pixel / width;
                first = Math.min(first, y);
                last = Math.max(last, y);
                ink++;
            }
            if (first >= top && last <= bottom && ink >= gap * .19f
                    && ink <= gap * .44f && ink == last - first + 1) return true;
        }
        return false;
    }

    private static boolean deepDottedTerminal(
            byte[] gray, int width, int height, int end, int row, float gap) {
        int reach = Math.round(gap * .34f), heightReach = Math.round(gap * .18f);
        for (int x = Math.max(3, end - reach); x < Math.min(width - 1, end + reach); x++) {
            for (int y = Math.max(0, row - heightReach); y <= Math.min(height - 1, row + heightReach); y++) {
                if (!endsHorizontalDashes(gray, width, x, y, gap)
                        || hasDottedHook(gray, width, height, x, y, -1, gap)) continue;
                if (hasDottedHook(gray, width, height, x, y, 1, gap, 4, .57f)
                        && hasDottedHook(gray, width, height, x + 1, y, 1, gap, 4, .57f)) return true;
            }
        }
        return false;
    }

    /** Bold printing can join the middle dots within the centre of a four-dash terminal. */
    private static boolean joinedDeepDottedTerminal(
            byte[] gray, int width, int height, int end, int row, float gap) {
        int reach = Math.round(gap * .34f), vertical = Math.round(gap * .22f);
        int maxDistance = Math.round(gap * .25f);
        for (int y = Math.max(0, row - vertical); y <= Math.min(height - 1, row + vertical); y++) {
            int first = -1;
            for (int x = Math.max(3, end - reach); x < Math.min(width - 1, end + reach); x++) {
                if (!endsHorizontalDashes(gray, width, x, y, gap)
                        || hasDottedHook(gray, width, height, x, y, -1, gap)
                        || !hasDottedHook(gray, width, height, x, y, 1, gap, 4, .57f, 2.8f, 1)) continue;
                if (first >= 0 && x - first <= maxDistance) return true;
                first = x;
            }
        }
        return false;
    }

    private static boolean ribbonReachesStaffEdge(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff, int ribbonEnd) {
        if (ribbonEdgeOnRuledFrame(gray, width, height, staff, ribbonEnd)) return true;
        float gap = staff.gap();
        var measured = PrintedDirectionStaff.printedRules(
                gray, width, height, ribbonEnd - gap * 2.25f, staff.bottom(), gap);
        if (measured == null) {
            // The bilateral window at the right edge can fall past the last printed rule.
            // Fit from farther inside the same staff, then verify all its rules at the edge.
            measured = PrintedDirectionStaff.printedRules(
                    gray, width, height, ribbonEnd - gap * 4.35f, staff.bottom(), gap);
        }
        if (measured == null || Math.abs(measured[0] - staff.bottom()) > gap * .38f
                || Math.abs(measured[1] - gap) > gap * .07f) return false;
        var fitted = new PlayingTechniqueDetector.Staff(measured[0] - measured[1] * 4,
                measured[0], measured[1], staff.index(), staff.count());
        return ribbonEdgeOnRuledFrame(gray, width, height, fitted, ribbonEnd);
    }

    private static boolean ribbonEdgeOnRuledFrame(
            byte[] gray, int width, int height, PlayingTechniqueDetector.Staff staff, int ribbonEnd) {
        float gap = staff.gap();
        int top = Math.round(staff.top()), bottom = Math.round(staff.bottom());
        int left = Math.max(1, ribbonEnd - Math.round(gap * .65f));
        int right = Math.min(width - 1, ribbonEnd + Math.round(gap * .85f));
        int offset = Math.round(gap * .85f), length = Math.round(gap * 1.35f);
        int radius = Math.round(gap * .23f);
        if (left <= offset + length || right + offset + length >= width
                || top < radius || bottom + radius >= height) return false;
        for (int x = left; x <= right; x++) {
            int vertical = 0;
            for (int y = top; y <= bottom; y++)
                if ((gray[y * width + x] & 255) < 165) vertical++;
            if (vertical < (bottom - top) * .86f) continue;
            int before = 0, after = 0;
            for (int rule = 0; rule <= 4; rule++) {
                int y = Math.round(staff.top() + rule * gap);
                before += ribbonEdgeRule(gray, width, x - offset - length, length, y, radius) ? 1 : 0;
                after += ribbonEdgeRule(gray, width, x + offset, length, y, radius) ? 1 : 0;
            }
            if (before == 5 && after == 0) return true;
        }
        return false;
    }

    private static boolean ribbonEdgeRule(
            byte[] gray, int width, int left, int length, int y, int radius) {
        for (int row = y - radius; row <= y + radius; row++) {
            int ink = 0;
            for (int x = left; x < left + length; x++)
                if ((gray[row * width + x] & 255) < 165) ink++;
            if (ink >= length * .80f) return true;
        }
        return false;
    }

    /** A short octave line needs a regular rail and a single complete, downward turn. */
    private static boolean compactDottedRibbonHasClosedEnd(
            byte[] gray,
            int width,
            int height,
            List<RawNumeralComponents.Component> dots,
            float gap) {
        var first = dots.get(0);
        var last = dots.get(dots.size() - 1);
        float span = last.right - first.left;
        if (dots.size() < 12 || span < gap * 6.5f) return false;
        int turns = dots.size() < 24 || span < gap * 12 ? 2 : 3;
        var prior = first;
        for (var dot : dots) {
            if (dot.right - dot.left + 1 > gap * .62f
                    || dot.bottom - dot.top + 1 > gap * .43f
                    || Math.abs(dotY(dot) - dotY(first)) > gap * .36f
                    || (dot != first && dot.left - prior.right - 1 > gap * .32f)) return false;
            prior = dot;
        }
        int terminal = -1, columns = 0;
        for (int x = last.left; x <= last.right; x++) {
            boolean down = false;
            for (int y = last.top; y <= last.bottom; y++) {
                if (!endsHorizontalDashes(gray, width, x, y, gap)) continue;
                if (hasDottedHook(gray, width, height, x, y, -1, gap, turns)) return false;
                down |= hasDottedHook(gray, width, height, x, y, 1, gap, turns);
            }
            if (!down) continue;
            if (terminal >= 0 && x != terminal + 1) return false;
            terminal = x;
            columns++;
        }
        return columns >= 2;
    }

    private static List<RawNumeralComponents.Component> wideDottedComponents(
            byte[] gray,
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            float gap) {
        var dots = new java.util.HashMap<InkBox, RawNumeralComponents.Component>();
        int band = 16384 / (bottom - top + 1), overlap = Math.round(gap * 2.4f);
        for (int start = left; start < right; start += band - overlap) {
            int end = Math.min(start + band - 1, right);
            var frame =
                    RawNumeralComponents.capture(
                            gray, width, height, start, top, end, bottom, 164, () -> {}, () -> {});
            for (var dot : dottedComponents(frame, gap, 1.78f))
                dots.putIfAbsent(
                        new InkBox(dot.left, dot.top, dot.right, dot.bottom, dot.size()), dot);
            if (end == right) break;
        }
        return dots.values().stream()
                .sorted(
                        Comparator.comparingInt((RawNumeralComponents.Component d) -> d.left)
                                .thenComparingInt(d -> d.top))
                .toList();
    }

    /** A descending slur may merge several dots while leaving both halves and the end hook intact. */
    private static DottedRibbon dottedRibbonAcrossSlur(
            byte[] gray, int width, int height, InkBox numeral, float gap) {
        if (gap < 10 || gap > 24) return null;
        int left = numeral.right + 1, top = Math.round(numeral.top - .86f * gap);
        int right = Math.min(width - 1, left + Math.round(gap * 10));
        int bottom = Math.round(numeral.top + 1.63f * gap);
        if (left >= right || top < 0 || bottom >= height) return null;
        var around =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 164, () -> {}, () -> {});
        var prefix = new ArrayList<RawNumeralComponents.Component>();
        for (var dot : dottedComponents(around, gap, .70f)) {
            float y = dotY(dot);
            if (prefix.isEmpty()) {
                if (dot.left - left > gap * .42f) return null;
                if (y < numeral.top + gap * .13f || y > numeral.top + gap * .62f) continue;
            } else {
                var prior = prefix.get(prefix.size() - 1);
                float advance = dotX(dot) - dotX(prior);
                if (Math.abs(dotY(prior) - y) > gap * .24f || advance < gap * .38f) continue;
                if (advance > gap * .80f) {
                    if (prefix.size() < 3 || advance > gap * 3.25f) return null;
                    if (crossesSlur(around, prefix, dot, width, gap, false))
                        return finishCrossedRibbon(gray, width, height, prefix, dot, gap, 9, false);
                    if (prefix.size() >= 6 && crossesSlur(around, prefix, dot, width, gap, true))
                        return finishCrossedRibbon(gray, width, height, prefix, dot, gap, 8, false);
                    if (prefix.size() >= 5 && shallowDescendingSlur(around, prior, dot, width, gap))
                        return finishCrossedRibbon(gray, width, height, prefix, dot, gap, 10, true);
                    return null;
                }
            }
            prefix.add(dot);
        }
        return null;
    }

    private static boolean crossesSlur(
            RawNumeralComponents ink,
            List<RawNumeralComponents.Component> prefix,
            RawNumeralComponents.Component resume,
            int width,
            float gap,
            boolean slurEndsAfterCrossing) {
        float priorX = dotX(prefix.get(prefix.size() - 1)), nextX = dotX(resume);
        int center =
                Math.round(
                        (float)
                                prefix.stream()
                                        .mapToDouble(OctaveMarkDetector::dotY)
                                        .average()
                                        .orElseThrow());
        int top = center - Math.round(gap * .51f);
        int bottom = center + Math.round(gap * (slurEndsAfterCrossing ? .23f : .52f));
        for (var component : ink.components()) {
            if (component.top > top || component.bottom < bottom) continue;
            if (slurEndsAfterCrossing
                    && (component.bottom < center + gap * .28f
                            || component.bottom > center + gap * .45f
                            || component.right > nextX - gap * .22f)) continue;
            int topCount = 0, middleCount = 0, bottomCount = 0, topX = 0, bottomX = 0;
            for (int i = 0; i < component.size(); i++) {
                int pixel = component.pixel(i), row = pixel / width;
                if (row == top) {
                    topCount++;
                    topX += pixel % width;
                } else if (row == center) {
                    middleCount++;
                } else if (row == bottom) {
                    bottomCount++;
                    bottomX += pixel % width;
                }
            }
            if (topCount == 0
                    || bottomCount == 0
                    || topCount > gap * .92f
                    || bottomCount > gap * .92f) continue;
            // An ending slur must join a broad run of dots and taper below the rail.
            if (slurEndsAfterCrossing
                    && (middleCount < gap * .9f
                            || middleCount > gap * 2.1f
                            || bottomCount > topCount * .75f)) continue;
            float upper = topX / (float) topCount, lower = bottomX / (float) bottomCount;
            if (upper >= priorX - gap * 1.5f
                    && lower <= nextX + gap * .68f
                    && lower - upper >= gap * .68f
                    && lower - upper <= gap * 3.05f) return true;
        }
        return false;
    }

    /** Count only the local slur between the interrupted and resumed dots. */
    private static boolean shallowDescendingSlur(RawNumeralComponents ink,
            RawNumeralComponents.Component before, RawNumeralComponents.Component after,
            int width, float gap) {
        float first = dotX(before), next = dotX(after);
        int center = Math.round((dotY(before) + dotY(after)) * .5f);
        int high = center - Math.round(gap * .44f), low = center + Math.round(gap * .28f);
        float left = first - gap * .80f, right = next + gap * .85f;
        for (var component : ink.components()) {
            if (component.top > high || component.bottom < low
                    || component.left > first || component.right < next - gap * .3f) continue;
            int upperInk = 0, lowerInk = 0, centerInk = 0, upperX = 0, lowerX = 0;
            for (int i = 0; i < component.size(); i++) {
                int pixel = component.pixel(i);
                int row = pixel / width, column = pixel % width;
                if (column < left || column > right) continue;
                if (row == high) {
                    upperInk++;
                    upperX += column;
                } else if (row == low) {
                    lowerInk++;
                    lowerX += column;
                } else if (row == center) {
                    centerInk++;
                }
            }
            if (upperInk < gap * .38f || upperInk > gap * 1.2f
                    || centerInk < gap * .82f || centerInk > gap * 1.65f
                    || lowerInk < gap * .14f || lowerInk > gap * .48f
                    || lowerInk > upperInk * .65f) continue;
            float upper = (float) upperX / upperInk, lower = (float) lowerX / lowerInk;
            if (Math.abs(upper - first) <= gap * .52f && Math.abs(lower - next) <= gap * .55f
                    && lower - upper >= gap * 1.2f && lower - upper <= gap * 2.65f) return true;
        }
        return false;
    }

    private static DottedRibbon finishCrossedRibbon(
            byte[] gray,
            int width,
            int height,
            List<RawNumeralComponents.Component> prefix,
            RawNumeralComponents.Component resume,
            float gap,
            int minimumTailDots,
            boolean shallowCrossing) {
        float y = dotY(resume);
        int left = resume.left - 1,
                top = Math.round(y - .59f * gap),
                right = Math.min(width - 1, resume.left + Math.round(gap * 15)),
                bottom = Math.round(y + .58f * gap);
        if (left < 0 || left >= right || top < 0 || bottom >= height) return null;
        var ink =
                RawNumeralComponents.capture(
                        gray, width, height, left, top, right, bottom, 164, () -> {}, () -> {});
        var tail = new ArrayList<RawNumeralComponents.Component>();
        for (var dot : dottedComponents(ink, gap, 1.08f)) {
            if (Math.abs(dotY(dot) - y) > gap * .55f) continue;
            if (tail.isEmpty()) {
                if (dot.left != resume.left || dot.right != resume.right) return null;
            } else {
                var preceding = tail.get(tail.size() - 1);
                int space = dot.left - preceding.right - 1;
                if (Math.abs(dotY(dot) - dotY(preceding)) > gap * .23f || space < gap * .06f)
                    continue;
                if (space > gap * .44f) break;
            }
            tail.add(dot);
        }
        if (tail.size() < minimumTailDots) return null;
        var last = tail.get(tail.size() - 1);
        float edge = last.right + gap * .55f;
        boolean fragment = shallowCrossing;
        if (hookDirection(gray, width, height, edge, last.top, last.bottom, gap) <= 0) {
            int terminal = joinedDottedTail(gray, width, height, last, gap);
            if (terminal < 0) return null;
            edge = Math.max(last.right, terminal) + gap * .55f;
            fragment = true;
        }
        var dots = new ArrayList<>(prefix);
        dots.addAll(tail);
        return new DottedRibbon(edge, dots, fragment);
    }

    private static int joinedDottedTail(byte[] gray, int width, int height,
            RawNumeralComponents.Component last, float gap) {
        int end = Math.min(width - 2, last.right + Math.round(gap * .72f));
        for (int x = end; x >= Math.max(last.left, last.right - 3); x--) {
            int top = last.top, bottom = Math.min(last.bottom, top + Math.round(gap * .34f));
            for (int y = top; y <= bottom; y++) {
                if (!endsHorizontalDashes(gray, width, x, y, gap)) continue;
                if (hasJoinedDashHook(gray, width, height, x, y, 1, gap, .72f)
                        && hasJoinedDashHook(gray, width, height, x + 1, y, 1, gap, .72f)
                        && !hasJoinedDashHook(gray, width, height, x, y, -1, gap, .72f)) return x;
            }
        }
        return -1;
    }

    private static float earlySlurRibbonEnd(byte[] gray, int width, int height,
            PlayingTechniqueDetector.Word word, float gap) {
        var box = new InkBox(Math.round(word.left() * width), Math.round(word.top() * height),
                Math.round(word.right() * width) - 1, Math.round(word.bottom() * height) - 1, 0);
        return qualifiedEarlySlurRibbonEnd(gray, width, height, box, gap);
    }

    private static float qualifiedEarlySlurRibbonEnd(byte[] gray, int width, int height,
            InkBox box, float gap) {
        float end = earlySlurRibbonEnd(gray, width, height, box.right, box.top, gap);
        if (end < 0) return -1;
        return bareEightShape(gray, width, box, gap)
                || canRepairEight(gray, width, box, gap) && brokenEightShape(gray, width, box, gap)
                ? end : -1;
    }

    /** A slur can cross at the first dots of an otherwise complete short dotted octave line. */
    static float earlySlurRibbonEnd(byte[] gray, int width, int height,
            int numeralRight, int numeralTop, float gap) {
        if (gray == null || gray.length != (long) width * height || !Float.isFinite(gap)
                || gap < 10 || gap > 24) return -1;
        int left = numeralRight + 1, top = numeralTop - Math.round(gap * .86f);
        int right = left + Math.round(gap * 10), bottom = numeralTop + Math.round(gap * 2.45f);
        if (left < 0 || top < 0 || right >= width || bottom >= height
                || (long) (right - left + 1) * (bottom - top + 1) > 16_384) return -1;
        var ink = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom,
                () -> {}, () -> {});
        RawNumeralComponents.Component prefix = null, last = null;
        int tail = 0;
        for (var dot : dottedComponents(ink, gap, .69f)) {
            float y = dotY(dot);
            if (y < numeralTop + gap * .13f || y > numeralTop + gap * .62f) continue;
            if (prefix == null) {
                if (dot.left - left > gap * .44f) return -1;
                prefix = dot;
                continue;
            }
            if (Math.abs(y - dotY(prefix)) > gap * .20f) continue;
            if (last == null) {
                float step = dotX(dot) - dotX(prefix);
                if (step < gap * 1.32f || step > gap * 2.42f
                        || !slurCrossesInitialDots(ink, prefix, dot, width, gap)) return -1;
            } else {
                float step = dotX(dot) - dotX(last);
                if (step < gap * .38f || step > gap * .74f) return -1;
            }
            last = dot;
            tail++;
        }
        if (tail < 8 || last == null || dottedHookDirection(
                gray, width, height, last.right, last.top, last.bottom, gap) != 1)
            return -1;
        return last.right + gap * .55f;
    }

    private static boolean slurCrossesInitialDots(RawNumeralComponents ink,
            RawNumeralComponents.Component prefix, RawNumeralComponents.Component resume,
            int width, float gap) {
        int center = Math.round(dotY(prefix));
        int above = center - Math.round(gap * .49f), below = center + Math.round(gap * .25f);
        for (var component : ink.components()) {
            if (component.top > above || component.bottom < center + gap * .30f
                    || component.bottom > center + gap * .51f
                    || component.left > prefix.left + gap * .23f
                    || component.right < resume.left - gap * .42f
                    || component.right > resume.left + gap * .17f) continue;
            int upperInk = 0, centerInk = 0, lowerInk = 0, upperX = 0, lowerX = 0;
            for (int i = 0; i < component.size(); i++) {
                int pixel = component.pixel(i), row = pixel / width;
                if (row == above) {
                    upperInk++;
                    upperX += pixel % width;
                } else if (row == center) {
                    centerInk++;
                } else if (row == below) {
                    lowerInk++;
                    lowerX += pixel % width;
                }
            }
            if (upperInk == 0 || lowerInk == 0 || lowerInk > upperInk * .72f
                    || centerInk < gap * .56f || centerInk > gap * 1.40f) continue;
            float slope = lowerX / (float) lowerInk - upperX / (float) upperInk;
            if (slope >= gap * .65f && slope <= gap * 2.15f) return true;
        }
        return false;
    }


    private static List<RawNumeralComponents.Component> dottedComponents(
            RawNumeralComponents field, float gap, float maxWidth) {
        return field.components().stream()
                .filter(
                        dot -> {
                            int w = dot.right - dot.left + 1, h = dot.bottom - dot.top + 1;
                            return dot.left > field.left
                                    && dot.top > field.top
                                    && dot.right < field.right
                                    && dot.bottom < field.bottom
                                    && w >= gap * .16f
                                    && w <= gap * maxWidth
                                    && h >= gap * .13f
                                    && h <= gap * .50f
                                    && dot.size() >= w * h * .46f;
                        })
                .sorted(Comparator.comparingInt(dot -> dot.left))
                .toList();
    }

    private static float dotX(RawNumeralComponents.Component dot) {
        return (dot.left + dot.right) * .5f;
    }

    private static float dotY(RawNumeralComponents.Component dot) {
        return (dot.top + dot.bottom) * .5f;
    }

    private static void recoverDottedEightBesideSlur(
            byte[] gray, int width, int height, float gap, List<InkBox> inkBoxes,
            List<InkBox> candidates) {
        if (gap < 10 || gap > 24) return;
        for (var slur : inkBoxes) {
            if (slur.right - slur.left < gap * 2.15f || slur.right - slur.left > 3.6f * gap
                    || slur.bottom - slur.top < gap * 1.25f || slur.bottom - slur.top > gap * 2.55f)
                continue;
            int left = Math.max(0, Math.round(slur.left - gap));
            int right = Math.min(width - 1, Math.round(slur.right + gap));
            int top = Math.round(slur.top + gap * .75f);
            int bottom = Math.min(height - 1, Math.round(slur.bottom + gap * 1.30f));
            if (left >= right || top >= bottom) continue;
            var dark = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom,
                    105, () -> {}, () -> {});
            for (var component : dark.components()) {
                if (component.left <= left || component.right >= right || component.top <= top
                        || component.bottom >= bottom || component.size() < gap * gap * .26f) continue;
                var eight = new InkBox(component.left, component.top, component.right,
                        component.bottom, component.size());
                float endpoint = closedCurvedDottedOctave(gray, width, height, eight, gap);
                if (endpoint >= 0) candidates.add(eight);
            }
        }
    }

    /** Follow a dense dotted line through a local ink crossing to a proved downward turn. */
    private static float closedCurvedDottedOctave(
            byte[] gray, int width, int height, InkBox eight, float gap) {
        if (gap < 10 || gap > 24 || !bareEightShape(gray, width, eight, gap)
                && !(canRepairEight(gray, width, eight, gap) && brokenEightShape(gray, width, eight, gap))) return -1;
        int left = eight.right;
        // A slur-crossed rail may extend across multiple measures before its printed return.
        int right = Math.min(width - 1, Math.round(left + gap * 42));
        int top = Math.max(0, Math.round(eight.top - gap * 1.55f));
        int bottom = Math.min(height - 1, Math.round(eight.top + gap * 1.25f));
        if (right - left < gap * 6 || bottom <= top) return -1;
        int step = Math.min(Math.round(gap * 10), 16_384 / (bottom - top + 1) - Math.round(gap) - 2);
        var dots = new ArrayList<RawNumeralComponents.Component>();
        // Only collect complete components, with a small shared boundary between windows.
        for (int x = left; x < right; x += step) {
            int edge = Math.min(right, x + step + Math.round(gap));
            var ink = RawNumeralComponents.capture(gray, width, height, x, top, edge, bottom,
                    () -> {}, () -> {});
            for (var dot : dottedComponents(ink, gap, .72f)) {
                if (edge < right && dotX(dot) >= x + step + gap * .5f) continue;
                if (x > left && dotX(dot) < x + gap * .5f) continue;
                dots.add(dot);
            }
        }
        dots.sort(Comparator.comparingDouble(OctaveMarkDetector::dotX));
        float end = -1;
        for (var first : dots) {
            float startX = dotX(first), startY = dotY(first);
            if (startX > left + gap * 1.45f) break;
            if (startY < eight.top + gap * .10f || startY > eight.top + gap * .85f) continue;
            var last = first;
            int count = 1, consecutive = 1, bridges = 0;
            for (var dot : dots) {
                float dx = dotX(dot) - dotX(last), dy = Math.abs(dotY(dot) - dotY(last));
                if (dx < gap * .34f || dy > gap * .22f) continue;
                if (dx > gap * 2.25f) break;
                if (dx > gap * .84f) {
                    if (bridges == 2 || bridges > 0 && consecutive < 3 || !crossesDottedLine(gray, width, height, last, dot, gap))
                        continue;
                    consecutive = 0;
                    bridges++;
                }
                last = dot;
                count++;
                consecutive++;
            }
            if (count < 13 || consecutive < 6 && !(count >= 18 && consecutive == 1 && bridges == 1)) continue;
            for (int x = last.left; x <= last.right; x++)
                for (int y = last.top; y <= last.bottom; y++)
                    if ((gray[y * width + x] & 255) < 165
                            && dottedDownTurn(gray, width, height, x, y, gap))
                        end = Math.max(end, last.right + gap * .42f);
        }
        return end;
    }

    private static boolean crossesDottedLine(
            byte[] gray, int width, int height, RawNumeralComponents.Component before,
            RawNumeralComponents.Component after, float gap) {
        int left = before.right, right = after.left;
        int middle = Math.round((dotY(before) + dotY(after)) * .5f);
        int top = Math.max(0, Math.round(middle - gap));
        int bottom = Math.min(height - 1, Math.round(middle + gap));
        if (left >= right || top >= bottom) return false;
        var ink = RawNumeralComponents.capture(gray, width, height, left, top, right, bottom,
                () -> {}, () -> {});
        for (var component : ink.components())
            if (component.left <= (left + right) * .5f && component.right >= (left + right) * .5f
                    && component.top <= middle + gap * .16f && component.bottom >= middle - gap * .16f
                    && component.bottom - component.top >= gap * .46f) return true;
        return false;
    }

    private static boolean dottedDownTurn(byte[] gray, int width, int height, int x, int y, float gap) {
        // Require two compact runs below the corner in adjacent columns, beyond the last horizontal dot.
        if (x + 1 >= width || y + gap * 1.85f >= height) return false;
        for (int column = x; column <= x + 1; column++) {
            int row = y;
            int limit = Math.round(y + gap * 1.85f);
            while (row < limit && (gray[row * width + column] & 255) < 165) row++;
            if (row - y > gap * .57f) return false;
            for (int run = 0; run < 2; run++) {
                int start = row;
                while (row < limit && (gray[row * width + column] & 255) >= 165) row++;
                if (row == limit || row - start < 2 || row - start > gap * .55f) return false;
                start = row;
                while (row < limit && (gray[row * width + column] & 255) < 165) row++;
                if (row == limit || row - start < 2 || row - start > gap * .57f) return false;
            }
        }
        return true;
    }

    /** A complete dotted line can outlast the row-based trace after a small scan skew. */
    private static float closedStraightDottedOctave(
            byte[] gray, int width, int height, InkBox eight, float gap) {
        if (gap < 10 || gap > 26 || !bareEightShape(gray, width, eight, gap)) return -1;
        int left = eight.right + 1;
        int top = Math.max(0, Math.round(eight.top - gap * .1f));
        int right = Math.min(width - 1, Math.round(left + gap * 25));
        int bottom = Math.min(height - 1, Math.round(eight.top + gap * 1.05f));
        if (left >= right || top >= bottom) return -1;
        var ink = RawNumeralComponents.capture(
                gray, width, height, left, top, right, bottom, () -> {}, () -> {});
        var dots = dottedComponents(ink, gap, .58f);
        int count = 0;
        float railY = -1, lastX = -1;
        RawNumeralComponents.Component last = null;
        for (var dot : dots) {
            float x = dotX(dot), y = dotY(dot);
            if (count == 0) {
                if (x > left + gap * .85f) break;
                if (y < eight.top + gap * .06f || y > eight.top + gap * .80f) continue;
                railY = y;
            } else {
                float step = x - lastX;
                if (Math.abs(y - railY) > gap * .25f || step < gap * .28f) continue;
                if (step > gap * .91f) break;
            }
            count++;
            lastX = x;
            last = dot;
        }
        if (count < 9 || last == null) return -1;
        // The terminal dots must descend from the right edge of this same horizontal line.
        for (int x = last.left; x <= last.right; x++)
            for (int y = last.top; y <= last.bottom; y++)
                if ((gray[y * width + x] & 255) < 165
                        && hasDottedHook(gray, width, height, x, y, 1, gap))
                    return last.right + gap * .55f;
        return -1;
    }

    private static float dashEnd(
            byte[] gray, int width, int height, float start, float top, float bottom, float gap) {
        return dashEnd(gray, width, height, start, top, bottom, gap, 3);
    }

    private static float dashEnd(
            byte[] gray,
            int width,
            int height,
            float start,
            float top,
            float bottom,
            float gap,
            int minimum) {
        return dashEnd(gray, width, height, start, top, bottom, gap, minimum, null);
    }

    private static float dashEnd(
            byte[] gray,
            int width,
            int height,
            float start,
            float top,
            float bottom,
            float gap,
            int minimum,
            java.util.BitSet acceptedInk) {
        if (gray == null || gray.length != width * height) return -1;
        int left = Math.max(0, Math.round(start - gap * .35f));
        int y1 = Math.max(0, Math.round(top - gap * .15f)),
                y2 = Math.min(height - 1, Math.round(bottom + gap * .4f));
        int best = -1;
        for (int y = y1; y <= y2; y++) {
            var runs = acceptedInk == null ? null : new ArrayList<int[]>();
            int[] terminalDash = null;
            int first = -1,
                    last = -1,
                    count = 0,
                    shortDots = 0,
                    interruptions = 0,
                    x = left,
                    scanY = y;
            int bridges = 0;
            boolean skippedSuffix = false;
            while (x < width) {
                int blank = 0;
                while (x < width && (gray[scanY * width + x] & 255) >= 165) {
                    // Once a real dashed chain is established, follow a one-pixel
                    // scan skew between dashes instead of dropping the octave mid-line.
                    boolean found = false;
                    if (count >= 3)
                        for (int offset : DashSkewOffsets.VALUES) {
                            int yy = scanY + offset;
                            if (yy >= 0
                                    && yy < height
                                    && Math.abs(yy - y) <= gap
                                    && x + 1 < width
                                    && (gray[yy * width + x] & 255) < 165
                                    && (gray[yy * width + x + 1] & 255) < 165) {
                                scanY = yy;
                                found = true;
                                break;
                            }
                        }
                    if (found) break;
                    blank++;
                    x++;
                }
                if (blank > gap * (count == 0 ? 2 : 1.6f)) break;
                int a = x;
                while (x < width && (gray[scanY * width + x] & 255) < 165) x++;
                int length = x - a;
                if (length < 2) continue;
                if (length > gap * 1.65f) {
                    if (count < 24 || length > gap * 3 || bridges >= 6
                            || !thinDashBridge(gray, width, height, a, x, scanY, gap)) break;
                    bridges++;
                    continue;
                }
                boolean tall = false;
                for (int cx = a; cx < x; cx++) {
                    int ya = scanY, yb = scanY;
                    while (ya > 0 && (gray[(ya - 1) * width + cx] & 255) < 165) ya--;
                    while (yb + 1 < height && (gray[(yb + 1) * width + cx] & 255) < 165) yb++;
                    if (yb - ya + 1 > Math.max(3, gap * .35f)) {
                        tall = true;
                        break;
                    }
                }
                if (tall) {
                    if (count >= 3
                            && (count < 24 || !thinDashBridge(gray, width, height, a, x, scanY, gap)
                                    || hookDirection(gray, width, height, x - 1 + gap * .55f,
                                            scanY, scanY, gap) != 0)
                            && terminalHook(gray, width, height, a, x - 1, scanY, gap)) {
                        if (acceptedInk != null) terminalDash = new int[] {a, x - 1, scanY};
                        last = x - 1;
                        count++;
                        break;
                    }
                    // A superscript suffix may precede the dash chain of a proved eight.
                    // This fallback still requires six dashes and the two-counter numeral.
                    if (count == 0 && a < start) continue;
                    if (count == 0 && a < start + gap * 2) {
                        skippedSuffix = true;
                        continue;
                    }
                    if (count >= 24 && length <= gap * 1.65f
                            && joinedDashHookDirection(gray, width, height, x - 1,
                                    Math.max(0, scanY - 2), Math.min(height - 1, scanY + 2),
                                    gap) != 0) {
                        if (acceptedInk != null) terminalDash = new int[] {a, x - 1, scanY};
                        last = x - 1;
                        count++;
                        break;
                    }
                    if (count >= 24 && length >= gap * .32f && bridges < 6
                            && thinDashBridge(gray, width, height, a, x, scanY, gap)) {
                        bridges++;
                        continue;
                    }
                    if (count > 0 && ++interruptions <= 1) continue;
                    break;
                }
                if (count >= 24 && joinedDashHookDirection(gray, width, height, x - 1,
                        Math.max(0, scanY - 2), Math.min(height - 1, scanY + 2), gap) != 0) {
                    if (acceptedInk != null) terminalDash = new int[] {a, x - 1, scanY};
                    last = x - 1;
                    count++;
                    break;
                }
                if (runs != null) runs.add(new int[] {a, x - 1, scanY});
                if (length < gap * .18f) shortDots++;
                if (first < 0) first = a;
                last = x - 1;
                count++;
            }
            if (count >= Math.max(minimum, skippedSuffix ? 6 : shortDots > count / 2 ? 5 : 3)
                    && last - first >= gap * 3) {
                best = Math.max(best, last);
                // Retain only the horizontal portion of a dash joined to its terminal hook.
                if (terminalDash != null)
                    for (int yy = Math.max(0, terminalDash[2] - 1);
                            yy <= Math.min(height - 1, terminalDash[2] + 1); yy++)
                        for (int xx = terminalDash[0]; xx <= terminalDash[1]; xx++)
                            if ((gray[yy * width + xx] & 255) < 165)
                                acceptedInk.set(yy * width + xx);
                if (acceptedInk != null)
                    for (int[] run : runs)
                        for (int column = run[0]; column <= run[1]; column++) {
                            // A previously visited seed already owns its full vertical ink segment.
                            if (acceptedInk.get(run[2] * width + column)) continue;
                            int a = run[2], b = a;
                            while (a > 0 && (gray[(a - 1) * width + column] & 255) < 165) a--;
                            while (b + 1 < height && (gray[(b + 1) * width + column] & 255) < 165)
                                b++;
                            for (int yInk = a; yInk <= b; yInk++)
                                acceptedInk.set(yInk * width + column);
                        }
            }
        }
        return best < 0 ? -1 : best + gap * .55f;
    }

    private static boolean thinDashBridge(
            byte[] gray, int width, int height, int left, int right, int y, float gap) {
        int flank = Math.max(3, Math.round(gap * .33f));
        if (y < flank || y + flank >= height) return false;
        int thick = 0;
        for (int x = left; x < right; x++)
            if ((gray[(y - flank) * width + x] & 255) < 165
                    || (gray[(y + flank) * width + x] & 255) < 165) thick++;
        return thick <= Math.max(2, (right - left) * .32f);
    }

    private static boolean terminalHook(
            byte[] gray, int width, int height, int left, int right, int y, float gap) {
        if (right - left + 1 < gap * .3f) return false;
        boolean hook = false;
        for (int x = left; x <= right; x++) {
            int a = y, b = y;
            while (a > 0 && (gray[(a - 1) * width + x] & 255) < 165) a--;
            while (b + 1 < height && (gray[(b + 1) * width + x] & 255) < 165) b++;
            if (b - a + 1 > Math.max(3, gap * .35f)) {
                if (x < right - Math.max(2, gap * .2f) || b - a + 1 > gap * 1.3f) return false;
                hook = true;
            }
        }
        return hook;
    }

    /** The terminal vertical stroke points toward its staff: upward for 8vb, downward for 8va. */
    private static int hookDirection(
            byte[] gray,
            int width,
            int height,
            float dashRight,
            float top,
            float bottom,
            float gap) {
        int end = Math.round(dashRight - gap * .55f), direction = 0;
        int y1 = Math.max(0, Math.round(top - gap * .15f)),
                y2 = Math.min(height - 1, Math.round(bottom + gap * .4f));
        for (int x = Math.max(3, end - 1); x <= Math.min(width - 1, end + 1); x++)
            for (int y = y1; y <= y2; y++) {
                if ((gray[y * width + x] & 255) >= 165
                        || (gray[y * width + x - 2] & 255) >= 165
                        || (gray[y * width + x - 3] & 255) >= 165) continue;
                int a = y, b = y;
                while (a > 0 && (gray[(a - 1) * width + x] & 255) < 165) a--;
                while (b + 1 < height && (gray[(b + 1) * width + x] & 255) < 165) b++;
                int up = y - a, down = b - y;
                if (b - a + 1 > gap * 1.3f) continue;
                int candidate =
                        up >= Math.max(3, gap * .35f) && up > down * 2 + 1
                                ? -1
                                : down >= Math.max(3, gap * .35f) && down > up * 2 + 1 ? 1 : 0;
                if (candidate == 0) continue;
                if (direction != 0 && candidate != direction) return 0;
                direction = candidate;
            }
        if (dashRight < 0) return direction;
        if (direction <= 0 && displacedDownwardDottedHook(gray, width, height, end, y1, y2, gap))
            return 1;
        if (direction != 0) return direction;
        int dotted = dottedHookDirection(gray, width, height, end, y1, y2, gap);
        if (dotted != 0) return dotted;
        return joinedDashHookDirection(gray, width, height, end, y1, y2, gap);
    }

    /** A dotted octave line can turn just to the right of the last horizontal dash. */
    private static boolean displacedDownwardDottedHook(
            byte[] gray, int width, int height, int end, int top, int bottom, float gap) {
        int limit = end + Math.round(gap * .48f);
        if (end < 4 || limit + 1 >= width || gap < 10 || gap > 32) return false;
        for (int y = top; y <= bottom; y++) {
            if (!endsHorizontalDashes(gray, width, end, y, gap)
                    || (gray[y * width + end + 1] & 255) < 165) continue;
            for (int x = end + 2; x < limit; x++) {
                if ((gray[y * width + x] & 255) < 165 || (gray[y * width + x + 1] & 255) < 165)
                    continue;
                if (hasDottedHook(gray, width, height, x, y, 1, gap)
                        && hasDottedHook(gray, width, height, x + 1, y, 1, gap)
                        && !hasDottedHook(gray, width, height, x, y, -1, gap)) return true;
            }
        }
        return false;
    }

    /** The apparent augmentation mark belongs to a downward dotted bracket end. */
    static int hookedOctaveDashBesideHead(
            byte[] gray, int width, int height, float right, float headX, float headY, float gap) {
        int reach = Math.round(gap * .40f);
        int left = Math.round(right) - reach, end = Math.round(right) + reach;
        int top = Math.round(headY - gap * 2.3f), bottom = Math.round(headY - gap * 1.2f);
        if (left < 3 || end >= width || top < 0 || headY + gap >= height) return -1;
        int first = -1, last = -1;
        for (int x = left; x <= end; x++) {
            if (x - headX < gap * 1.5f || x - headX > gap * 2.35f) continue;
            for (int y = top; y <= bottom; y++) {
                if (!endsHorizontalDashes(gray, width, x, y, gap)
                        || !hasJoinedDashHook(gray, width, height, x, y, 1, gap)
                        || hasJoinedDashHook(gray, width, height, x, y, -1, gap)) continue;
                boolean lastDash = false;
                for (int yy = Math.round(headY - gap * .22f);
                        yy <= Math.round(headY + gap * .12f);
                        yy++) if ((gray[yy * width + x] & 255) < 165) lastDash = true;
                if (!lastDash) continue;
                if (first < 0) first = x;
                last = x;
                break;
            }
        }
        return first >= 0 && last - first <= gap * .32f ? (first + last) / 2 : -1;
    }

    private static int joinedDashHookDirection(
            byte[] gray, int width, int height, int end, int top, int bottom, float gap) {
        int direction = 0;
        int radius = Math.max(1, Math.round(gap * .36f));
        for (int x = Math.max(2, end - radius); x <= Math.min(width - 1, end + radius); x++) {
            for (int y = top; y <= bottom; y++) {
                if (!endsHorizontalDashes(gray, width, x, y, gap)) continue;
                for (int step = -1; step <= 1; step += 2) {
                    if (!hasJoinedDashHook(gray, width, height, x, y, step, gap)) continue;
                    if (direction != 0 && direction != step) return 0;
                    direction = step;
                }
            }
        }
        return direction;
    }

    private static boolean hasJoinedDashHook(
            byte[] gray, int width, int height, int x, int y, int step, float gap) {
        return hasJoinedDashHook(gray, width, height, x, y, step, gap, .45f);
    }

    private static boolean hasJoinedDashHook(
            byte[] gray, int width, int height, int x, int y, int step, float gap, float firstRun) {
        if (x < 0 || x >= width) return false;
        int maxRun = Math.max(3, Math.round(gap * .45f));
        int minInk = Math.max(2, Math.round(gap * .1f));
        int limit = y + step * Math.round(gap * 2.25f);
        if (limit < 0 || limit >= height) return false;
        int row = y;
        while (row != limit && (gray[row * width + x] & 255) < 165) row += step;
        if (Math.abs(row - y) > Math.max(3, Math.round(gap * firstRun))) return false;
        // Require separated vertical ink after the horizontal chain's last dash.
        for (int dash = 0; dash < 2; dash++) {
            int start = row;
            while (row != limit && (gray[row * width + x] & 255) >= 165) row += step;
            int space = Math.abs(row - start);
            if (space < 2 || space > maxRun || row == limit) return false;
            start = row;
            while (row != limit && (gray[row * width + x] & 255) < 165) row += step;
            int ink = Math.abs(row - start);
            if (ink < minInk || ink > maxRun * 2 + 1 || row == limit) return false;
        }
        return true;
    }
    private static int dottedHookDirection(
            byte[] gray, int width, int height, int end, int top, int bottom, float gap) {
        int direction = 0;
        int radius = Math.max(1, Math.round(gap * .2f));
        for (int x = Math.max(2, end - radius); x <= Math.min(width - 1, end + radius); x++) {
            for (int y = top; y <= bottom; y++) {
                if (!endsHorizontalDashes(gray, width, x, y, gap)) continue;
                for (int step = -1; step <= 1; step += 2) {
                    if (!hasDottedHook(gray, width, height, x, y, step, gap)) continue;
                    if (direction != 0 && direction != step) return 0;
                    direction = step;
                }
            }
        }
        return direction;
    }

    /** Some printed octave lines turn down in a separate, aligned column of three dots. */
    private static float extendOverDottedHook(
            byte[] gray, int width, int height, float right, float top, float bottom, float gap) {
        if (gap < 10 || gap > 32) return right;
        int end = Math.round(right - gap * .55f);
        int left = end + 2, far = end + Math.round(gap * .97f);
        int topRow = Math.round(top - gap * .15f), bottomRow = Math.round(bottom + gap * .4f);
        if (end < 4 || far >= width || topRow < 0 || bottomRow >= height) return right;
        int anchor = -1, support = 0;
        for (int y = topRow; y <= bottomRow; y++) {
            if (endsHorizontalDashes(gray, width, end, y, gap)
                    && (gray[y * width + end + 1] & 255) >= 165) {
                if (anchor < 0) anchor = y;
                if (y != anchor + support) return right;
                support++;
            }
        }
        if (support < 2 || support > gap * .4f) return right;
        float chainY = anchor + (support - 1) * .5f;
        int firstRow = anchor - Math.round(gap * .2f);
        int foot = anchor + Math.round(gap * 2.12f);
        if (firstRow < 0 || foot >= height) return right;

        var frame =
                RawNumeralComponents.capture(
                        gray, width, height, left, firstRow, far, foot, 164, () -> {}, () -> {});
        var dots = frame.components();
        if (dots.size() != 4) return right;
        var corner = dots.get(0);
        float centerX = (corner.left + corner.right) * .5f;
        float priorY = (corner.top + corner.bottom) * .5f;
        int cornerWidth = corner.right - corner.left + 1,
                cornerHeight = corner.bottom - corner.top + 1;
        if (centerX - end < gap * .22f
                || centerX - end > gap * .68f
                || Math.abs(priorY - chainY) > gap * .15f
                || cornerWidth < gap * .18f
                || cornerWidth > gap * .42f
                || cornerHeight < gap * .13f
                || cornerHeight > gap * .32f
                || corner.left <= left
                || corner.right >= far
                || corner.top <= firstRow) return right;
        int rightmost = corner.right;
        for (int i = 1; i < dots.size(); i++) {
            var dot = dots.get(i);
            int diameterX = dot.right - dot.left + 1, diameterY = dot.bottom - dot.top + 1;
            float x = (dot.left + dot.right) * .5f, y = (dot.top + dot.bottom) * .5f;
            if (diameterX < gap * .17f
                    || diameterX > gap * .39f
                    || diameterY < gap * .21f
                    || diameterY > gap * .44f
                    || dot.size() < diameterX * diameterY * .60f
                    || dot.left <= left
                    || dot.right >= far
                    || dot.top <= firstRow
                    || dot.bottom >= foot
                    || Math.abs(x - centerX) > gap * .14f
                    || y - priorY < gap * (i == 1 ? .27f : .41f)
                    || y - priorY > gap * .68f) return right;
            priorY = y;
            rightmost = Math.max(rightmost, dot.right);
        }
        return Math.max(right, rightmost + gap * .24f);
    }

    /** A downward terminal above an attack binds a bare inter-system numeral to the next staff. */
    private static SystemRibbonEnd offsetUpperDottedRibbon(InkBox numeral,
            PlayingTechniqueDetector.Staff preceding, PlayingTechniqueDetector.Staff incoming,
            List<PlayingTechniqueDetector.Staff> staffs, List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes, byte[] gray, int width, int height) {
        float gap = incoming.gap(), lineGap = preceding.gap();
        if (gap < 10 || gap > 24 || incoming.count() != 1 || preceding.count() != 1
                || Math.abs(preceding.gap() - gap) > gap * .1f
                || incoming.top() - numeral.bottom < gap * 3
                || incoming.top() - numeral.bottom > gap * 7
                || numeral.top - preceding.bottom() < gap * 3
                || !bareEightShape(gray, width, numeral, lineGap)) return null;
        float end = dashEnd(gray, width, height, numeral.right + 1, numeral.top, numeral.bottom, lineGap);
        if (end - numeral.right < gap * 12
                || !offsetDownwardDottedHook(gray, width, height, end, numeral, lineGap)) return null;
        for (var note : notes) {
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1
                    || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            float head = x(note, measures, width);
            if (head >= numeral.left - gap * .35f && head <= numeral.right + gap * 1.20f
                    && note.pageY() * height >= numeral.top + gap * 1.4f
                    && onStaff(note, incoming, staffs, measures, height))
                return new SystemRibbonEnd(end, false);
        }
        return null;
    }

    /** Dense dotted octave lines can turn after the final horizontal dot. */
    private static boolean offsetDownwardDottedHook(byte[] gray, int width, int height,
            float dashRight, InkBox numeral, float gap) {
        int right = Math.round(dashRight - gap * .55f);
        int spread = Math.round(gap * .32f);
        int from = Math.round(numeral.top - gap * .15f);
        int to = Math.round(numeral.bottom + gap * .21f);
        if (right - spread < 6 || right + spread * 2 + 2 >= width
                || from < gap * 2 || to + gap * 2 >= height) return false;
        for (int y = from; y < to; y++) {
            for (int x = right - spread; x <= right + spread; x++) {
                if (!endsHorizontalDashes(gray, width, x, y, gap)
                        || !endsHorizontalDashes(gray, width, x, y + 1, gap)
                        || (gray[y * width + x + 1] & 255) < 165) continue;
                for (int column = x; column <= x + spread; column++) {
                    if (offsetDotPair(gray, width, column, y, gap, 1)
                            && offsetDotPair(gray, width, column + 1, y, gap, 1)
                            && !offsetDotPair(gray, width, column, y, gap, -1)) return true;
                }
            }
        }
        return false;
    }

    private static boolean offsetDotPair(byte[] gray, int width, int column, int baseline,
            float gap, int direction) {
        int reach = Math.round(gap * 2);
        int offset = 0;
        while (offset < reach && (gray[(baseline + direction * offset) * width + column] & 255) >= 165)
            offset++;
        if (offset < gap * .40f || offset > gap * .92f) return false;
        for (int dot = 0; dot < 2; dot++) {
            int start = offset;
            while (offset < reach && (gray[(baseline + direction * offset) * width + column] & 255) < 165)
                offset++;
            if (offset - start < gap * .12f || offset - start > gap * .44f) return false;
            if (dot == 0) {
                int white = offset;
                while (offset < reach && (gray[(baseline + direction * offset) * width + column] & 255) >= 165)
                    offset++;
                if (offset - white < gap * .16f || offset - white > gap * .46f) return false;
            }
        }
        return true;
    }

    /** A short dashed octave line can end with two detached downward dashes. */
    static boolean detachedDownwardDashPair(byte[] gray, int width, int height,
            float dashRight, float top, float bottom, float gap) {
        if (gap < 10 || gap > 26) return false;
        int right = Math.round(dashRight - gap * .55f);
        int from = Math.round(top - gap * .15f), to = Math.round(bottom + gap * .22f);
        if (right < 6 || right + 2 >= width || from < 0 || to + gap * 2.35f >= height) return false;
        for (int y = from; y <= to; y++) {
            if (!endsHorizontalDashes(gray, width, right, y, gap)
                    || !endsHorizontalDashes(gray, width, right, y + 1, gap)
                    || (gray[y * width + right + 1] & 255) < 165) continue;
            for (int x = right - 1; x <= right; x++) {
                if (terminalDashPairColumn(gray, width, x, y, gap, 1)
                        && terminalDashPairColumn(gray, width, x + 1, y, gap, 1)
                        && (y < gap * 2.35f || !terminalDashPairColumn(gray, width, x, y, gap, -1)))
                    return true;
            }
        }
        return false;
    }

    private static boolean terminalDashPairColumn(byte[] gray, int width, int x, int y, float gap, int step) {
        int limit = y + step * Math.round(gap * 2.35f);
        int row = y;
        while (row != limit && (gray[row * width + x] & 255) < 165) row += step;
        if (Math.abs(row - y) > gap * .28f) return false;
        int blank = row;
        while (row != limit && (gray[row * width + x] & 255) >= 165) row += step;
        int jump = Math.abs(row - blank);
        if (jump < gap * .68f || jump > gap * 1.16f || row == limit) return false;
        for (int dash = 0; dash < 2; dash++) {
            int start = row;
            while (row != limit && (gray[row * width + x] & 255) < 165) row += step;
            int size = Math.abs(row - start);
            if (size < gap * .18f || size > gap * .46f || row == limit) return false;
            blank = row;
            while (row != limit && (gray[row * width + x] & 255) >= 165) row += step;
            int pause = Math.abs(row - blank);
            if (dash == 0 && (row == limit || pause < gap * .15f || pause > gap * .43f)) return false;
            if (dash == 1 && (row != limit || pause < gap * .22f)) return false;
        }
        return true;
    }

    private static boolean endsHorizontalDashes(byte[] gray, int width, int x, int y, float gap) {
        int row = y * width;
        int left = Math.max(1, x - Math.round(gap * 1.2f));
        int column = x;
        while (column >= left && (gray[row + column] & 255) < 165) column--;
        if (x - column < 2 || column < left) return false;
        while (column >= left && (gray[row + column] & 255) >= 165) column--;
        return column >= left && (gray[row + column - 1] & 255) < 165;
    }

    private static boolean hasDottedHook(
            byte[] gray, int width, int height, int x, int y, int step, float gap) {
        return hasDottedHook(gray, width, height, x, y, step, gap, 3);
    }

    private static boolean hasDottedHook(
            byte[] gray, int width, int height, int x, int y, int step, float gap, int count) {
        return hasDottedHook(gray, width, height, x, y, step, gap, count, .45f);
    }

    private static boolean hasDottedHook(
            byte[] gray, int width, int height, int x, int y, int step, float gap,
            int count, float inkFraction) {
        return hasDottedHook(gray, width, height, x, y, step, gap, count, inkFraction, 2.25f, 2);
    }

    private static boolean hasDottedHook(
            byte[] gray, int width, int height, int x, int y, int step, float gap,
            int count, float inkFraction, float reach, int minimumSpace) {
        int maxRun = Math.max(3, Math.round(gap * .45f));
        int minInk = Math.max(2, Math.round(gap * .1f));
        int maxInk = Math.max(maxRun, Math.round(gap * inkFraction));
        int limit = y + step * Math.round(gap * reach);
        if (limit < 0 || limit >= height) return false;
        int row = y;
        while (row != limit && (gray[row * width + x] & 255) < 165) row += step;
        if (Math.abs(row - y) > maxRun) return false;
        // Separated vertical dots must continue from the last horizontal dot.
        for (int dash = 0; dash < count; dash++) {
            int start = row;
            while (row != limit && (gray[row * width + x] & 255) >= 165) row += step;
            int space = Math.abs(row - start);
            if (space < minimumSpace || space > maxRun || row == limit) return false;
            start = row;
            while (row != limit && (gray[row * width + x] & 255) < 165) row += step;
            int ink = Math.abs(row - start);
            if (ink < minInk || ink > maxInk || row == limit) return false;
        }
        return true;
    }
}
