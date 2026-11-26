// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InlineDoubleRuleMeterCropTest {
    private static final int WIDTH = 400, HEIGHT = 230;
    private static final List<MeasureRegion> BARS = List.of(
            new MeasureRegion(.1f, .345f, 53f / HEIGHT, 170f / HEIGHT),
            new MeasureRegion(.385f, .9f, 53f / HEIGHT, 170f / HEIGHT));

    @Test
    public void localRailsAndCompleteDoubleRuleRecoverTheIsolatedNumeralSlot() {
        var crops = crops(true, true, 206);
        assertEquals(1, crops.size());
        var crop = crops.get(0);
        assertEquals(153, crop.left());
        assertEquals(80, crop.firstLine());
        assertEquals(1, MeterChangeDetector.followingMeasure(crop, WIDTH, HEIGHT, BARS));
    }

    @Test
    public void requiresBothRulesBothNumeralHalvesAndSpaceBeforeTheAttack() {
        assertTrue(crops(false, true, 206).isEmpty());
        assertTrue(crops(true, false, 206).isEmpty());
        assertTrue(crops(true, true, 184).isEmpty());
    }

    private static List<MeterChangeDetector.Crop> crops(
            boolean twoRules, boolean twoNumerals, int attack) {
        var labels = new byte[WIDTH * HEIGHT];
        var gray = new byte[labels.length];
        Arrays.fill(gray, (byte) 255);
        for (int line = 0; line < 5; line++)
            Arrays.fill(gray, (80 + line * 14) * WIDTH + 32, (80 + line * 14) * WIDTH + 380, (byte) 0);
        for (int y = 80; y <= 136; y++) {
            if (twoRules) {
                gray[y * WIDTH + 145] = 0;
                gray[y * WIDTH + 146] = 0;
            }
            gray[y * WIDTH + 151] = 0;
            gray[y * WIDTH + 152] = 0;
        }
        for (int y = 83; y < (twoNumerals ? 135 : 105); y++) {
            if (y >= 105 && y < 110) continue;
            Arrays.fill(labels, y * WIDTH + 159, y * WIDTH + 171, OmrMeasurePostProcessor.SYMBOL);
            Arrays.fill(gray, y * WIDTH + 159, y * WIDTH + 171, (byte) 0);
        }
        var note = new ScoreNoteEvent(1, (attack - 154f) / 206f, 7,
                0, 1, 87f / HEIGHT, false, 0, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 1);
        return MeterDoubleRuleCrop.supplement(List.of(), labels, gray, WIDTH, HEIGHT, BARS, List.of(note));
    }
}
