// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PackedMeterPlacementTest {
    @Test
    public void clippedAndIgnoredPixelsMatchAtTheWordBoundary() {
        var random = new Random(4327);
        for (int width : new int[] {1, 7, 32, 60, 63, 64}) {
            for (int gw : new int[] {1, width, Math.min(64, width + 4)}) {
                int h = 12, gh = 14, sourceCount = 0;
                long[] source = new long[h], valid = new long[h];
                long mask = -1L >>> (64 - width);
                for (int y = 0; y < h; y++) {
                    valid[y] = y % 4 == 0 ? 0 : random.nextLong() & mask;
                    source[y] = valid[y] & random.nextLong();
                    sourceCount += Long.bitCount(source[y]);
                }
                boolean[] ink = new boolean[gw * gh];
                for (int i = 0; i < ink.length; i++) ink[i] = random.nextBoolean();
                var glyph = new MeterGlyphMatcher.Glyph(gw, gh, ink);
                float best = 0;
                for (int dx = -4; dx <= width - gw + 4; dx++) {
                    for (int dy = -4; dy <= h - gh + 4; dy++) {
                        int hits = 0, target = 0;
                        for (int y = 0; y < gh; y++) {
                            if (y + dy < 0 || y + dy >= h) continue;
                            for (int x = 0; x < gw; x++) {
                                int col = x + dx;
                                if (!ink[y * gw + x] || col < 0 || col >= width) continue;
                                target += (valid[y + dy] >>> col) & 1;
                                hits += (source[y + dy] >>> col) & 1;
                            }
                        }
                        best = Math.max(best, 2f * hits / Math.max(1, sourceCount + target));
                    }
                }
                assertEquals(best, MeterGlyphMatcher.bestPacked(glyph, width, sourceCount, source, valid), 0f);
            }
        }
    }
}
