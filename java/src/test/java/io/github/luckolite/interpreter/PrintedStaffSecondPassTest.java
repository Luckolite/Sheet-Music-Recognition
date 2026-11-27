// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class PrintedStaffSecondPassTest {
    @Test
    public void literalUpperStaffEntrancesNeedTheirOwnPrintedRails() {
        int width = 800, height = 500;
        byte[] gray = new byte[width * height];
        Arrays.fill(gray, (byte) 255);
        var staffs = new ArrayList<PlayingTechniqueDetector.Staff>();
        var words = new ArrayList<PlayingTechniqueDetector.Word>();
        for (int index = 0; index < 4; index++) {
            int top = 100 + 75 * index;
            int gap = index == 3 ? 16 : 10;
            staffs.add(new PlayingTechniqueDetector.Staff(top, top + 4 * gap, gap, index, 4));
            String text = index == 1 ? "play both times" : "2nd time only";
            words.add(
                    new PlayingTechniqueDetector.Word(
                            text, .14f, (top - 19f) / height, .30f, (top - 8f) / height));
            for (int line = 0; line < 5; line++)
                Arrays.fill(
                        gray,
                        (top + line * gap) * width + 95,
                        (top + line * gap) * width + (index == 2 ? 393 : 750),
                        (byte) 0);
        }
        var measures =
                List.of(
                        new MeasureRegion(.12f, .49f, .15f, .8f),
                        new MeasureRegion(.51f, .93f, .15f, .8f));
        var score = new ScorePageInterpretation(measures, List.of());
        var marked = PrintedStaffSecondPass.capture(score, words, staffs, gray, width, height, 0);
        var scopes = marked.expressiveEvents();
        assertEquals(List.of(0, 2), scopes.stream().map(ScoreExpressiveEvent::staffIndex).toList());
        assertEquals(
                List.of(2, 1),
                scopes.stream().map(event -> event.end().orElseThrow().measureIndex()).toList());
        assertEquals(
                List.of("2nd time only", "2nd time only"),
                scopes.stream().map(event -> event.evidence().get(0).printedText()).toList());
    }
}
