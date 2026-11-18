// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

public final class SlurredThirdFingeringTest {
    private static final int WIDTH = 430;
    private static final int HEIGHT = 360;
    private static final MeasureRegion MEASURE = new MeasureRegion(.15f, .90f, .47f, .8256f);

    @Test
    public void assignsOnlyToTheUpperOwnersOfAProvedSlurredPair() {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y = 230; y <= 294; y += 16) ink(gray, 30, 400, y, y + 1);
        ink(gray, 169, 172, 175, 266);
        ink(gray, 209, 212, 191, 266);
        ink(gray, 169, 212, 245, 252);
        ink(gray, 169, 212, 258, 266);
        ink(gray, 171, 192, 171, 179);
        ink(gray, 171, 192, 187, 195);
        ink(gray, 211, 232, 187, 195);
        ink(gray, 211, 232, 203, 211);

        ink(gray, 175, 185, 139, 142);
        ink(gray, 182, 185, 141, 148);
        ink(gray, 177, 185, 147, 150);
        ink(gray, 175, 179, 149, 155);
        ink(gray, 175, 187, 154, 157);
        ink(gray, 217, 222, 154, 171);
        ink(gray, 214, 218, 157, 160);
        ink(gray, 215, 224, 168, 171);

        var words = List.of(word("2", 169, 136, 191, 159), word("1", 212, 152, 228, 173));
        var events = List.of(event(180, 174, 0), event(180, 190, 0),
                event(220, 190, .25), event(220, 206, .25));
        var notes = List.of(note(180, 174, 15), note(180, 190, 13),
                note(220, 190, 13), note(220, 206, 11));
        var start = new ScoreSlurPages.Anchor(0, 0);
        var end = new ScoreSlurPages.Anchor(0, 2);
        var slur = new ScoreSlurPages.Span("slurred-thirds", start, end,
                ScoreSlurSpan.Placement.ABOVE, ScoreSlurSpan.LineType.SOLID,
                List.of(new ScoreSlurSpan.ArcEvidence(0, 180f / WIDTH, 159f / HEIGHT,
                        220f / WIDTH, 174f / HEIGHT)));
        var score = new ScorePageInterpretation(List.of(MEASURE), notes).withSlurSource(
                new ScoreSlurSource(List.of(slur),
                        List.of(Optional.of(start), Optional.empty(), Optional.of(end), Optional.empty())));
        assertEquals(List.of(
                        new PrintedFingeringDigits.Mark(0, 2, PrintedFingeringDigits.Placement.ABOVE),
                        new PrintedFingeringDigits.Mark(2, 1, PrintedFingeringDigits.Placement.ABOVE)),
                PrintedFingeringDigits.capture(gray, WIDTH, HEIGHT, words, score, events));

        assertTrue(PrintedFingeringDigits.capture(gray, WIDTH, HEIGHT, words,
                new ScorePageInterpretation(List.of(MEASURE), notes), events).isEmpty());
        var crowded = List.of(words.get(0), words.get(1), word("4", 169, 136, 191, 159));
        assertTrue(PrintedFingeringDigits.capture(gray, WIDTH, HEIGHT, crowded, score, events).isEmpty());
        byte[] removedRail = gray.clone();
        for (int y = 258; y < 266; y++)
            Arrays.fill(removedRail, y * WIDTH + 180, y * WIDTH + 201, (byte) 255);
        assertTrue(PrintedFingeringDigits.capture(
                gray, removedRail, WIDTH, HEIGHT, words, score, events).isEmpty());
    }

    private static ScoreNoteEvent note(int x, int y, int step) {
        return new ScoreNoteEvent(0, x / (float) WIDTH, step, 0, 1, y / (float) HEIGHT,
                false, 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0f, 1, 0f, 0, 30,
                false, 0f, false, 0, 0, 1, -1, ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL);
    }

    private static Map<String, Object> event(int x, int y, double onset) {
        return Map.of("x", x, "y", y, "startBeat", onset);
    }

    private static SheetInterpreter.Word word(String text, int l, int t, int r, int b) {
        return new SheetInterpreter.Word(text, l / (float) WIDTH, t / (float) HEIGHT,
                r / (float) WIDTH, b / (float) HEIGHT);
    }

    private static void ink(byte[] gray, int left, int right, int top, int bottom) {
        for (int y = top; y < bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right, (byte) 0);
    }
}
