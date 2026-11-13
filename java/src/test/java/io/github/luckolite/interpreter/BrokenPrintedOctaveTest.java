/* Copyright 2026 Luckolite
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BrokenPrintedOctaveTest {
    @Test
    public void restoresBrokenLowerLoopWithVerifiedDashChainAndHook() {
        int width = 400, height = 200;
        String[] digit = {
                    "     ####     ",
                    "   ########   ",
                    "  ##########  ",
                    " ###      ### ",
                    " ###      ### ",
                    " ###      ### ",
                    "  ###    ###  ",
                    "   ########   ",
                    "   ########   ",
                    "   ########   ",
                    "  ##########  ",
                    " ###     #### ",
                    " ###      ### ",
                    " ###      ### ",
                    " ###      ### ",
                    "  ##     #### ",
                    "        ####  ",
                    "       ####   ",
                    "      ####    ",
                    "     ###      "
        };
        var raster = new byte[width * height];
        Arrays.fill(raster, (byte) 255);
        for (int y = 0; y < digit.length; y++)
            for (int x = 0; x < digit[y].length(); x++)
                if (digit[y].charAt(x) == '#') raster[(40 + y) * width + 40 + x] = 0;
        for (int x = 60; x < 192; x++)
            if ((x - 60) % 22 < 13) {
                raster[44 * width + x] = 0;
                raster[45 * width + x] = 0;
            }
        var staffs = List.of(new PlayingTechniqueDetector.Staff(96, 152, 14, 0, 1));
        assertEquals(List.of(), OctaveMarkDetector.printedWords(raster, width, height, staffs));
        for (int y = 44; y < 59; y++)
            for (int x = 182; x <= 184; x++) raster[y * width + x] = 0;
        var words = OctaveMarkDetector.printedWords(raster, width, height, staffs);
        assertEquals(List.of("8va"), words.stream().map(PlayingTechniqueDetector.Word::text).toList());
    }
}
