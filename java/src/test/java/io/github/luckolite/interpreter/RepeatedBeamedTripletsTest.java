// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class RepeatedBeamedTripletsTest {
    private static final int WIDTH = 2300;
    private static final int HEIGHT = 220;

    private record Page(ScorePageInterpretation score, byte[] gray) {}

    @Test public void repeatedBarsKeepTheirPrintedTripletPulse() {
        var page = page();
        var recovered = apply(page, null);
        assertEquals(48L, recovered.notes().stream().filter(n -> n.tupletDivisor() == 3).count());
        assertEquals(16, recovered.notes().stream()
                .mapToDouble(ScoreNoteTiming::writtenDurationBeats).sum(), 1e-8);
        assertEquals(0, recovered.meterChanges().size());
    }

    @Test public void otherMeterAndErasedBeamsPreserveOrdinaryEighths() {
        var page = page();
        assertSame(page.score(), apply(page, new ScoreMeterChange(0, 6, 4)));
        for (int y = 54; y <= 57; y++) paint(page.gray(), 670, y, 705, y, (byte) 255);
        assertSame(page.score(), apply(page, null));
    }

    private static ScorePageInterpretation apply(Page page, ScoreMeterChange inherited) {
        return RepeatedBeamedTriplets.apply(page.score(), null, page.gray(), WIDTH, HEIGHT, inherited);
    }

    private static Page page() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 77; y <= 141; y += 16) paint(gray, 65, y, 2150, y, (byte) 0);
        for (int x = 100; x <= 2100; x += 500) paint(gray, x - 1, 77, x + 1, 141, (byte) 0);
        var bars = new ArrayList<MeasureRegion>();
        var notes = new ArrayList<ScoreNoteEvent>();
        for (int m = 0; m < 4; m++) {
            int left = 100 + 500 * m;
            bars.add(new MeasureRegion(left / (float) WIDTH, (left + 500f) / WIDTH,
                    42f / HEIGHT, 174f / HEIGHT));
            for (int i = 0; i < 12; i++) {
                int x = left + 40 + i * 38;
                int step = (m % 2 == 0 ? 3 : 0) + i % 3;
                int y = 141 - step * 8;
                var note = new ScoreNoteEvent(m, (x - left) / 500f, step, 0, 1, y / (float) HEIGHT,
                        false, 0, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0)
                        .withStemDirection(1).withClef(30);
                if ((m == 0 && i < 9) || (m == 2 && i < 3)) note = note.withTupletRatio(3, 2);
                notes.add(note);
                paint(gray, x - 9, y - 3, x + 9, y + 3, (byte) 0);
                paint(gray, x + 8, 54, x + 10, y, (byte) 0);
                if (i % 3 == 0) paint(gray, x + 8, 54, x + 86, 57, (byte) 0);
            }
        }
        return new Page(new ScorePageInterpretation(List.copyOf(bars), List.copyOf(notes)), gray);
    }

    private static void paint(byte[] gray, int x1, int y1, int x2, int y2, byte shade) {
        for (int y = y1; y <= y2; y++) Arrays.fill(gray, y * WIDTH + x1, y * WIDTH + x2 + 1, shade);
    }
}
