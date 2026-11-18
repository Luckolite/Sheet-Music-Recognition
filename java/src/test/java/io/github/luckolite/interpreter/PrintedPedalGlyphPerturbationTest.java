// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Font-derived scale/contrast controls; no score pixels or supplied candidate boxes. */
public class PrintedPedalGlyphPerturbationTest {
    private static final int W = 300, H = 160;

    private BufferedImage template(int wanted) throws Exception {
        try (var input =
                new DataInputStream(
                        getClass().getResourceAsStream("glyphs/pedal_templates/glyphs.bin"))) {
            assertEquals(0x50444731, input.readInt());
            assertEquals(2, input.readInt());
            for (int i = 0; i < 2; i++) {
                int kind = input.readInt(), width = input.readInt(), height = input.readInt();
                byte[] pixels = input.readNBytes(width * height);
                if (kind == wanted) {
                    var image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
                    image.getRaster().setDataElements(0, 0, width, height, pixels);
                    return image;
                }
            }
        }
        throw new AssertionError("Missing licensed template");
    }

    @Test
    public void scaledAndFadedPedalGlyphsRemainRecognizable() throws Exception {
        for (int kind : new int[] {1, 2}) {
            var source = template(kind);
            for (int height : new int[] {24, 36, 48}) {
                int width = Math.round(source.getWidth() * height / (float) source.getHeight());
                var scaled = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
                var graphics = scaled.createGraphics();
                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, width, height, null);
                graphics.dispose();
                for (int floor : new int[] {0, 60, 110}) {
                    byte[] page = new byte[W * H];
                    Arrays.fill(page, (byte) 255);
                    for (int y = 0; y < height; y++)
                        for (int x = 0; x < width; x++) {
                            int value = scaled.getRaster().getSample(x, y, 0);
                            page[(y + 50) * W + x + 70] =
                                    (byte) (floor + value * (255 - floor) / 255);
                        }
                    var found =
                            GlyphResources.pedals()
                                    .detect(page, W, H, 0, 0, W, H, height / 2.0869565f);
                    String context = "kind=" + kind + " height=" + height + " floor=" + floor;
                    assertEquals(context, 1, found.size());
                    assertEquals(context, kind, found.get(0).kind());
                }
            }
        }
    }

    @Test
    public void croppedScanCanRestartAfterAVisitorStopsEarly() {
        var components = new PrintedInkComponents();
        byte[] gray = new byte[12 * 6];
        Arrays.fill(gray, (byte) 176);
        gray[14] = gray[27] = (byte) 175; // Touch at a diagonal.
        gray[19] = gray[20] = 0;
        var boxes = new ArrayList<List<Integer>>();
        assertFalse(components.scan(gray, 12, 1, 1, 11, 5, 175, (l, t, r, b, ink) -> {
            boxes.add(List.of(l, t, r, b, ink));
            return false;
        }));
        assertEquals(List.of(List.of(2, 1, 4, 3, 2)), boxes);
        boxes.clear();
        assertTrue(components.scan(gray, 12, 3, 1, 10, 4, 175, (l, t, r, b, ink) -> {
            boxes.add(List.of(l, t, r, b, ink));
            return true;
        }));
        assertEquals(List.of(List.of(7, 1, 9, 2, 2),
                List.of(3, 2, 4, 3, 1)), boxes);
    }

    @Test
    public void blankStaffRulesAndDenseBlocksAreNotPedalSymbols() {
        for (int pattern = 0; pattern < 3; pattern++) {
            byte[] page = new byte[W * H];
            Arrays.fill(page, (byte) 255);
            if (pattern == 1)
                for (int row = 0; row < 5; row++)
                    for (int x = 20; x < 280; x++) page[(40 + row * 12) * W + x] = 0;
            if (pattern == 2)
                for (int y = 40; y < 76; y++) for (int x = 50; x < 115; x++) page[y * W + x] = 0;
            assertTrue(
                    "pattern=" + pattern,
                    GlyphResources.pedals().detect(page, W, H, 0, 0, W, H, 17.25f).isEmpty());
        }
    }
}
