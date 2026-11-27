// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class InteriorEndingRouteTest {
    @Test
    public void sharedEndingCapturesAmpersandAndProvedHook() {
        var marks = optical("|2. & 3.", 306, false);
        assertEquals(1, marks.size());
        assertEquals(List.of(2, 3), marks.get(0).passes());
        assertEquals("2. & 3.", marks.get(0).text());
        assertTrue(optical("|2. & 3.", 328, false).isEmpty());
        assertTrue(PrintedEndingBrackets.passes("|2. & 3.").isEmpty());
    }

    @Test
    public void broadSelectorBoxMayIncludePreviousClosingHook() {
        var marks = optical("2.&3.", 292, true);
        assertEquals(1, marks.size());
        assertEquals(1, marks.get(0).start());
        assertEquals(List.of(2, 3), marks.get(0).passes());
    }

    @Test
    public void interiorAlternativesRejoinAndSharedFinalEndingExitsRepeat() {
        var result = assemble(List.of(
                ending(2, 3, List.of(1)), ending(3, 4, List.of(2, 3)),
                ending(7, 8, List.of(1)), ending(8, 9, List.of(2, 3))));
        assertTrue(result.unresolved().isEmpty());
        assertEquals(6, result.directions().size());
        var plan = ScoreNavigationPlan.create(11, result.directions());
        assertEquals(List.of(0, 1, 2, 4, 5, 6, 7, 0, 1, 3, 4, 5, 6, 8, 9, 10), plan.sourceMeasures());
        assertTrue(plan.traversal().diagnostics().isEmpty());
    }

    @Test
    public void incompleteInternalOrPrematureLastEndingAbstains() {
        for (var endings : List.of(
                List.of(ending(2, 3, List.of(1)), ending(7, 8, List.of(1)),
                        ending(8, 9, List.of(2, 3))),
                List.of(ending(7, 8, List.of(2)), ending(8, 9, List.of(1, 3))))) {
            var result = assemble(endings);
            assertTrue(result.directions().isEmpty());
            assertFalse(result.unresolved().isEmpty());
        }
    }

    private static PrintedRepeatNavigation.Result assemble(List<PrintedEndingBrackets.Mark> endings) {
        return PrintedRepeatNavigation.assemble(List.of(
                new PrintedRepeatGlyphs.Mark(0, false, .1f, 0, 1),
                new PrintedRepeatGlyphs.Mark(8, true, .8f, 0, 1)), endings, 0);
    }

    private static PrintedEndingBrackets.Mark ending(int start, int end, List<Integer> passes) {
        return new PrintedEndingBrackets.Mark(start, end, passes, "", .2f, 0, 1);
    }

    private static List<PrintedEndingBrackets.Mark> optical(
            String text, int wordLeft, boolean adjacentHook) {
        int width = 600, height = 240;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        for (int y = 40; y < 42; y++) {
            Arrays.fill(gray, y * width + 306, y * width + 552, (byte) 0);
            if (adjacentHook) Arrays.fill(gray, y * width + 150, y * width + 294, (byte) 0);
        }
        for (int y = 40; y < 90; y++) {
            Arrays.fill(gray, y * width + 306, y * width + 308, (byte) 0);
            if (adjacentHook) Arrays.fill(gray, y * width + 292, y * width + 294, (byte) 0);
        }
        if (adjacentHook)
            for (int y = 50; y < 65; y++)
                Arrays.fill(gray, y * width + 323, y * width + 329, (byte) 0);
        return PrintedEndingBrackets.detect(
                gray, width, height, List.of(new PlayingTechniqueDetector.Staff(120, 168, 12, 0, 1)),
                List.of(new MeasureRegion(.1f, .5f, .45f, .8f),
                        new MeasureRegion(.51f, .92f, .45f, .8f)),
                List.of(new PlayingTechniqueDetector.Word(text, wordLeft / (float) width,
                        (adjacentHook ? 40 : 45) / (float) height, 360f / width, 70f / height)));
    }
}
