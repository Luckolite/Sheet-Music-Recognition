// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RuledCaesuraTest {
    private static final int WIDTH = 480, HEIGHT = 300, TOP = 110, GAP = 16;
    private static final PlayingTechniqueDetector.Staff STAFF =
            new PlayingTechniqueDetector.Staff(TOP, TOP + 4 * GAP, GAP, 0, 1);
    private static final MeasureRegion MEASURE =
            new MeasureRegion(.2f, .82f, .2f, .72f);

    @Test
    public void parallelStrokesAcrossTopRuleProduceACaesura() {
        var mark = detect(.49f, .54f, true);
        assertNotNull(mark);
        assertEquals("caesura", mark.text());
        assertTrue(mark.top() < TOP && mark.bottom() > TOP);
    }

    @Test
    public void rejectsStemsAndLoneSlashes() {
        assertNull(detect(0f, 0f, true));
        assertNull(detect(.52f, -.52f, true));
        assertNull(detect(.52f, .52f, false));
    }

    @Test
    public void ownsOnlyItsRestAliasAndTargetsThePrecedingAttack() {
        var following = new MeasureRegion(.83f, .97f, .2f, .72f);
        var note = new ScoreNoteEvent(
                0, .73f, 0, 0, 1, 174f / HEIGHT, false, 0, 0, 0, 1, 1, 0);
        var alias = new ScoreRestEvent(0, .87f, 108f / HEIGHT, 25f / HEIGHT, 0, 1, .5);
        var real = new ScoreRestEvent(1, .18f, 145f / HEIGHT, 25f / HEIGHT, 0, 1, .5);
        var page = new ScorePageInterpretation(
                List.of(MEASURE, following), List.of(note), 0, List.of(), List.of(), List.of(),
                List.of(alias, real), List.of(), List.of());
        var recognized = ScoreExpressionDetector.apply(page, List.of(), List.of(STAFF),
                raster(.52f, .52f, true), WIDTH, HEIGHT, new PrintedExpressionGlyphs());
        assertEquals(List.of(real), recognized.rests());
        assertEquals(List.of(note), recognized.notes());
        var caesura = recognized.expressiveEvents().stream()
                .filter(e -> e.kind() == ScoreExpressiveEvent.Kind.CAESURA).toList();
        assertEquals(1, caesura.size());
        assertEquals(List.of(0), ScoreExpressionDetector.targetIndices(recognized, caesura.get(0)));
    }

    private static PrintedExpressionGlyphs.Mark detect(float l, float r, boolean includeRight) {
        return PrintedExpressionGlyphs.caesuraAt(raster(l, r, includeRight), WIDTH, HEIGHT, STAFF, MEASURE);
    }

    private static byte[] raster(float l, float r, boolean includeRight) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = TOP; y <= TOP + 4 * GAP; y += GAP)
            Arrays.fill(gray, y * WIDTH + 48, y * WIDTH + 422, (byte) 0);
        for (int y = TOP - 16; y <= TOP + 15; y++) {
            int lx = Math.round(354 - l * (y - TOP));
            Arrays.fill(gray, y * WIDTH + lx - 2, y * WIDTH + lx + 2, (byte) 0);
            if (includeRight) {
                int rx = Math.round(363 - r * (y - TOP));
                Arrays.fill(gray, y * WIDTH + rx - 2, y * WIDTH + rx + 2, (byte) 0);
            }
        }
        return gray;
    }
}
