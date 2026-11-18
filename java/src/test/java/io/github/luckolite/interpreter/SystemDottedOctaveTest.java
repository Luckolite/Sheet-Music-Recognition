// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class SystemDottedOctaveTest {
    private static final int WIDTH = 1240, HEIGHT = 290;
    private static final int RIBBON_Y = 78, STAFF_TOP = 152, GAP = 16;
    private static final int LAST_DOT = 1050;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(STAFF_TOP, STAFF_TOP + 4 * GAP, GAP, 0, 1));

    @Test
    public void systemLineSurvivesIntersectionsAndStopsAtTheRealStaffEdge() {
        var page = engraving();
        staff(page, LAST_DOT + 3);
        assertEquals(List.of(0, 1, 1, 0), shifts(page));

        var missingStaffEnd = engraving();
        staff(missingStaffEnd, LAST_DOT + 85);
        assertEquals(0, (int) shifts(missingStaffEnd).get(2));
    }

    @Test
    public void validatesAnOpenEdgeOnMeasuredLocalStaffInk() {
        var page = engraving();
        staff(page, LAST_DOT + 3);
        var notes = List.of(note(53), note(173), note(1038), note(1077));
        var measures = List.of(new MeasureRegion(0, 1, 0, 1));
        var misaligned = List.of(new PlayingTechniqueDetector.Staff(
                STAFF_TOP + 5, STAFF_TOP + 4 * GAP + 5, GAP, 0, 1));
        var result = OctaveMarkDetector.apply(List.of(), misaligned, measures, notes,
                page, WIDTH, HEIGHT);
        assertEquals(List.of(0, 1, 1, 0), result.stream().map(ScoreNoteEvent::octaveShift).toList());

        ink(page, LAST_DOT + 1, LAST_DOT + 4, STAFF_TOP, STAFF_TOP + 4 * GAP, 255);
        result = OctaveMarkDetector.apply(List.of(), misaligned, measures, notes, page, WIDTH, HEIGHT);
        assertEquals(0, result.get(2).octaveShift());
    }

    @Test
    public void dottedDownTurnEndsTheSystemRangeAtItsOwnPrintedNote() {
        var page = engraving();
        staff(page, LAST_DOT + 85);
        ink(page, LAST_DOT - 1, LAST_DOT + 1, 85, 92, 0);
        ink(page, LAST_DOT - 1, LAST_DOT + 1, 95, 99, 0);
        ink(page, LAST_DOT - 1, LAST_DOT + 1, 102, 106, 0);
        ink(page, LAST_DOT - 1, LAST_DOT + 1, 109, 113, 0);
        assertEquals(List.of(0, 1, 1, 0), shifts(page));

        ink(page, LAST_DOT - 3, LAST_DOT + 3, 83, 115, 255);
        assertEquals(0, (int) shifts(page).get(2));
    }

    @Test
    public void anErasedNumeralOrBrokenMiddleCannotAuthorizeTheFarHalf() {
        var ribbon = engraving();
        staff(ribbon, LAST_DOT + 3);
        ink(ribbon, 583, 676, RIBBON_Y - 2, RIBBON_Y + 6, 255);
        assertEquals(0, (int) shifts(ribbon).get(2));

        var numeral = engraving();
        staff(numeral, LAST_DOT + 3);
        ink(numeral, 72, 100, 71, 99, 255);
        assertEquals(List.of(0, 0, 0, 0), shifts(numeral));
    }

    @Test
    public void openStaffEdgeAndRepeatedEightJoinAcrossAdjacentSystems() {
        var first = engraving();
        var second = engraving();
        staff(first, LAST_DOT + 3);
        staff(second, LAST_DOT + 85);
        ink(second, LAST_DOT - 1, LAST_DOT + 1, 85, 92, 0);
        ink(second, LAST_DOT - 1, LAST_DOT + 1, 95, 99, 0);
        ink(second, LAST_DOT - 1, LAST_DOT + 1, 102, 106, 0);
        ink(second, LAST_DOT - 1, LAST_DOT + 1, 109, 113, 0);
        var page = Arrays.copyOf(first, WIDTH * HEIGHT * 2);
        System.arraycopy(second, 0, page, WIDTH * HEIGHT, second.length);
        var measures = List.of(new MeasureRegion(0, 1, .2f, .43f),
                new MeasureRegion(0, 1, .7f, .93f));
        var staves = List.of(STAFFS.get(0),
                new PlayingTechniqueDetector.Staff(STAFF_TOP + HEIGHT,
                        STAFF_TOP + HEIGHT + 4 * GAP, GAP, 0, 1));
        var notes = List.of(systemNote(0, 173), systemNote(0, 1038),
                systemNote(1, 173), systemNote(1, 1038));
        var spans = new ArrayList<OctaveMarkDetector.StaffSpan>();
        var shifted = OctaveMarkDetector.apply(List.of(), staves, measures, notes,
                page, WIDTH, HEIGHT * 2, spans);
        assertEquals(List.of(1, 1, 1, 1), shifted.stream().map(ScoreNoteEvent::octaveShift).toList());
        assertEquals(List.of(new OctaveMarkDetector.StaffSpan(0, 1, 0, 1, 1)), spans);

        var partial = new ArrayList<>(notes);
        partial.add(systemNote(0, 53));
        spans.clear();
        OctaveMarkDetector.apply(List.of(), staves, measures, partial,
                page, WIDTH, HEIGHT * 2, spans);
        assertEquals(List.of(new OctaveMarkDetector.StaffSpan(1, 1, 0, 1, 1)), spans);
    }

    @Test
    public void mediumDottedRunsJoinAtAProvedStaffEdgeAndTurn() {
        int terminal = LAST_DOT - 52 * 9;
        var start = engraving();
        var finish = engraving();
        ink(start, terminal + 1, WIDTH - 1, RIBBON_Y, RIBBON_Y + 5, 255);
        ink(finish, terminal + 1, WIDTH - 1, RIBBON_Y, RIBBON_Y + 5, 255);
        staff(start, terminal + 3);
        staff(finish, terminal + 85);
        for (int y : new int[] {86, 94, 102, 110})
            ink(finish, terminal - 1, terminal + 1, y, y + 3, 0);

        var page = Arrays.copyOf(start, WIDTH * HEIGHT * 2);
        System.arraycopy(finish, 0, page, WIDTH * HEIGHT, finish.length);
        var measures = List.of(new MeasureRegion(0, 1, .2f, .43f),
                new MeasureRegion(0, 1, .7f, .93f));
        var staves = List.of(STAFFS.get(0), new PlayingTechniqueDetector.Staff(
                STAFF_TOP + HEIGHT, STAFF_TOP + HEIGHT + 4 * GAP, GAP, 0, 1));
        var notes = List.of(systemNote(0, 173), systemNote(0, terminal - 9),
                systemNote(1, 173), systemNote(1, terminal - 9));
        var spans = new ArrayList<OctaveMarkDetector.StaffSpan>();
        OctaveMarkDetector.apply(List.of(), staves, measures, notes, page, WIDTH, HEIGHT * 2, spans);
        assertEquals(List.of(new OctaveMarkDetector.StaffSpan(0, 1, 0, 1, 1)), spans);

        ink(page, terminal + 1, terminal + 4, STAFF_TOP, STAFF_TOP + 4 * GAP, 255);
        spans.clear();
        OctaveMarkDetector.apply(List.of(), staves, measures, notes, page, WIDTH, HEIGHT * 2, spans);
        assertEquals(List.of(new OctaveMarkDetector.StaffSpan(1, 1, 0, 1, 1)), spans);
    }

    @Test
    public void incompleteFinalEventScopeCannotClaimAStaffWideMark() {
        var span = new OctaveMarkDetector.StaffSpan(0, 1, 0, 1, 1);
        var first = shiftedAttack(0, 1);
        var second = shiftedAttack(1, 1);
        assertEquals(List.of(span), Main.retainedStaffOctaves(List.of(span), List.of(first, second), List.of()));
        assertEquals(List.of(), Main.retainedStaffOctaves(List.of(span), List.of(first), List.of()));
        assertEquals(List.of(), Main.retainedStaffOctaves(
                List.of(span), List.of(first, second, shiftedAttack(1, 0)), List.of()));
        assertEquals(List.of(), Main.retainedStaffOctaves(List.of(span), List.of(first, second), List.of(2)));
    }

    private static Map<String, Object> shiftedAttack(int measure, int shift) {
        return Map.of("measureIndex", measure, "staffIndex", 0, "staffCount", 1,
                "midi", 72, "octaveShift", shift);
    }

    private static ScoreNoteEvent systemNote(int measure, int x) {
        return new ScoreNoteEvent(measure, x / (float) WIDTH, 2, 0, 1,
                (184f + measure * HEIGHT) / (HEIGHT * 2),
                false, 0, 1, 2, 0, 1);
    }

    private static byte[] engraving() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        ink(page, 74, 94, 74, 96, 0);
        ink(page, 80, 88, 77, 82, 255);
        ink(page, 79, 88, 87, 92, 255);
        for (int x = LAST_DOT; x > 96; x -= 9)
            ink(page, x - 5, x, RIBBON_Y, RIBBON_Y + 4, 0);
        ink(page, 307, 312, RIBBON_Y - 7, RIBBON_Y + 14, 0);
        ink(page, 361, 366, RIBBON_Y - 7, RIBBON_Y + 14, 0);
        return page;
    }

    private static void staff(byte[] page, int right) {
        for (int y = STAFF_TOP; y <= STAFF_TOP + 4 * GAP; y += GAP)
            ink(page, 40, right, y, y, 0);
        ink(page, right - 1, right, STAFF_TOP, STAFF_TOP + 4 * GAP, 0);
    }

    private static List<Integer> shifts(byte[] page) {
        var notes = List.of(note(53), note(173), note(1038), note(1077));
        return OctaveMarkDetector.apply(List.of(), STAFFS,
                List.of(new MeasureRegion(0, 1, 0, 1)), notes, page, WIDTH, HEIGHT)
                .stream().map(ScoreNoteEvent::octaveShift).toList();
    }

    private static ScoreNoteEvent note(int x) {
        return new ScoreNoteEvent(0, x / (float) WIDTH, 2, 0, 1, 184f / HEIGHT,
                false, 0, 1, 2, 0, 1);
    }

    private static void ink(byte[] page, int left, int right, int top, int bottom, int gray) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) gray);
    }
}
