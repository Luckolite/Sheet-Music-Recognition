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
        for (int width : new int[] {1, 7, 32, 60, 63, 64, 65, 67, 96, 127, 128}) {
            int words = (width + Long.SIZE - 1) / Long.SIZE;
            int[] glyphWidths = words == 1
                    ? new int[] {1, width, Math.min(Long.SIZE, width + 4)}
                    : new int[] {1, Long.SIZE, Long.SIZE + 1, width, Math.min(2 * Long.SIZE, width + 4)};
            for (int gw : glyphWidths) {
                int h = 12, gh = 14, sourceCount = 0;
                long[] source = new long[h * words], valid = new long[h * words];
                for (int y = 0; y < h; y++) {
                    for (int word = 0; word < words; word++) {
                        int index = y * words + word;
                        int visible = Math.min(Long.SIZE, width - word * Long.SIZE);
                        long mask = -1L >>> (Long.SIZE - visible);
                        valid[index] = y % 4 == 0 ? 0 : random.nextLong() & mask;
                        source[index] = valid[index] & random.nextLong();
                        sourceCount += Long.bitCount(source[index]);
                    }
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
                                int index = (y + dy) * words + col / Long.SIZE;
                                target += (valid[index] >>> col) & 1;
                                hits += (source[index] >>> col) & 1;
                            }
                        }
                        best = Math.max(best, 2f * hits / Math.max(1, sourceCount + target));
                    }
                }
                float packed = words == 1
                        ? MeterGlyphMatcher.bestPacked(glyph, width, sourceCount, source, valid)
                        : MeterGlyphMatcher.bestPackedWide(glyph, width, sourceCount, source, valid);
                assertEquals(best, packed, 0f);
            }
        }
    }
}
