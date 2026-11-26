// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PedalWordLedRailTest {
    private static final int WIDTH = 620, HEIGHT = 280;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS = List.of(
            new PlayingTechniqueDetector.Staff(130, 170, 10, 1, 2));

    @Test
    public void printedPedalWordAndRepedalHooksGiveTwoFiniteSpans() {
        assertEquals(List.of(List.of(0d, 2d), List.of(2d, 4d)), playback(true));
    }

    @Test
    public void anOpenLeftRailWithoutThePrintedWordStaysUnbound() {
        assertEquals(List.of(List.of(2d, 4d)), playback(false));
    }

    private static List<List<Double>> playback(boolean printedWord) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        rail(gray, 174, 329, false);
        rail(gray, 340, 492, true);
        var notes = List.of(note(.12f), note(.25f), note(.60f), note(.86f));
        var score = new ScorePageInterpretation(
                List.of(new MeasureRegion(100f / WIDTH, 500f / WIDTH, .38f, .68f)),
                notes, 1, List.of(), List.of(), List.of(), List.of());
        if (printedWord) {
            var cue = new ScoreExpressiveEvent("ped", ScoreExpressiveEvent.Kind.PEDAL_DOWN,
                    Optional.of(new ScoreAnchor(0, 0)), Optional.empty(), ScoreExpressiveEvent.Scope.PART,
                    1, 2, Optional.empty(), ScoreExpressiveEvent.Strength.UNSPECIFIED, "",
                    List.of(new ScoreExpressiveEvent.Evidence(
                            "printed-expression-word", 0, 148f / WIDTH, 1, 2, "Ped.")));
            score = score.withExpressiveEvents(List.of(cue));
        }
        var meter = new ScoreMeterMap(4, List.of());
        var resolved = ScorePedalDetector.resolve(
                ScorePedalDetector.apply(score, gray, WIDTH, HEIGHT, STAFFS), meter);
        var performance = PedalPerformance.resolve(resolved.expressiveEvents(), meter, 1);
        assertTrue(performance.diagnostics().toString(), performance.diagnostics().isEmpty());
        return performance.spans().stream().map(s -> List.of(s.startBeat(), s.endBeat())).toList();
    }

    private static ScoreNoteEvent note(float x) {
        return new ScoreNoteEvent(
                0, x, 0, 1, 2, 170f / HEIGHT, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1);
    }

    private static void rail(byte[] gray, int left, int right, boolean leftHook) {
        Arrays.fill(gray, 220 * WIDTH + left, 220 * WIDTH + right + 1, (byte) 0);
        for (int y = 212; y < 220; y++) {
            if (leftHook) gray[y * WIDTH + left] = 0;
            gray[y * WIDTH + right] = 0;
        }
    }
}
