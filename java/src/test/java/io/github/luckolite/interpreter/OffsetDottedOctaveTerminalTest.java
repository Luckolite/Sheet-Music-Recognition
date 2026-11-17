// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OffsetDottedOctaveTerminalTest {
    private static final int WIDTH = 608, HEIGHT = 385, GAP = 16;
    private static final int LINE_RIGHT = 425;

    @Test
    public void detachedDownwardDotsOwnTheFollowingStaff() {
        var page = engraving();
        var spans = new ArrayList<OctaveMarkDetector.StaffSpan>();
        assertEquals(List.of(0, 0, 1, 1, 0), shifts(page, spans));
        assertEquals(List.of(new OctaveMarkDetector.StaffSpan(1, 1, 0, 1, 1)), spans);

        ink(page, LINE_RIGHT + 2, LINE_RIGHT + 4, 192, 195, 255);
        spans.clear();
        assertEquals(List.of(-1, -1, 0, 0, 0), shifts(page, spans));
        assertEquals(List.of(), spans);
    }

    private static byte[] engraving() {
        var page = new byte[WIDTH * HEIGHT];
        Arrays.fill(page, (byte) 255);
        ink(page, 87, 107, 166, 188, 0);
        ink(page, 93, 101, 169, 174, 255);
        ink(page, 92, 101, 179, 184, 255);
        for (int x = LINE_RIGHT; x > 110; x -= 8) ink(page, x - 3, x, 174, 177, 0);
        ink(page, LINE_RIGHT + 2, LINE_RIGHT + 4, 184, 187, 0);
        ink(page, LINE_RIGHT + 2, LINE_RIGHT + 4, 192, 195, 0);
        return page;
    }

    private static List<Integer> shifts(byte[] page, List<OctaveMarkDetector.StaffSpan> spans) {
        var staffs = List.of(new PlayingTechniqueDetector.Staff(36, 100, GAP, 0, 1),
                new PlayingTechniqueDetector.Staff(278, 342, GAP, 0, 1));
        var measures = List.of(new MeasureRegion(0, 1, 36f / HEIGHT, 100f / HEIGHT),
                new MeasureRegion(0, 1, 278f / HEIGHT, 342f / HEIGHT),
                new MeasureRegion(0, 1, 278f / HEIGHT, 342f / HEIGHT));
        var notes = List.of(note(0, 114, 80), note(0, 380, 80),
                note(1, 114, 264), note(1, 380, 270), note(2, 473, 279));
        return OctaveMarkDetector.apply(List.of(), staffs, measures, notes, page, WIDTH, HEIGHT, spans)
                .stream().map(ScoreNoteEvent::octaveShift).toList();
    }

    private static ScoreNoteEvent note(int bar, int x, int y) {
        return new ScoreNoteEvent(bar, x / (float) WIDTH, 2, 0, 1, y / (float) HEIGHT,
                false, 0, 1, 2, 0, 1);
    }

    private static void ink(byte[] page, int left, int right, int top, int bottom, int gray) {
        for (int y = top; y <= bottom; y++) {
            Arrays.fill(page, y * WIDTH + left, y * WIDTH + right + 1, (byte) gray);
        }
    }
}
