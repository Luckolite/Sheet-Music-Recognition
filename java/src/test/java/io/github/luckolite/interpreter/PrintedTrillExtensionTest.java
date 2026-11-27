// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class PrintedTrillExtensionTest {
    private static final int WIDTH = 360, HEIGHT = 280;
    private static final int HEAD_WIDTH = 25, HEAD_HEIGHT = 20;
    private static final List<PlayingTechniqueDetector.Staff> STAFFS =
            List.of(new PlayingTechniqueDetector.Staff(150, 214, 16, 0, 1));
    private static final List<PortableNoteOrnaments.Anchor> NOTES = List.of(
            new PortableNoteOrnaments.Anchor(180, 173, 16, 0, 0),
            new PortableNoteOrnaments.Anchor(180, 196, 16, 0, 0),
            new PortableNoteOrnaments.Anchor(226, 174, 16, 0, 0));

    @Test
    public void longWavePreservesTheProvedTrillOverTheUpperNote() {
        var found = detect(page(true, true));
        assertEquals(1, found.size());
        assertEquals(0, found.get(0).noteIndex());
        assertEquals(NoteOrnament.TRILL, NoteOrnament.type(found.get(0).marks()));
        assertEquals(168 + HEAD_WIDTH, found.get(0).bounds().right);
    }

    @Test
    public void aBareWaveAndAdjacentStraightInkDoNotSupplyATrill() {
        assertTrue(detect(page(false, true)).isEmpty());
        assertTrue(detect(page(true, false)).isEmpty());
    }

    @Test
    public void staggeredUnisonUsesTheUpperStemForTheExtendedTrill() {
        var voices = List.of(new PortableNoteOrnaments.Anchor(180, 174, 16, 0, 0, -1, 4),
                new PortableNoteOrnaments.Anchor(201, 174, 16, 0, 0, 1, 4));
        var found = detect(page(true, true), voices);
        assertEquals(1, found.size());
        assertEquals(1, found.get(0).noteIndex());
    }

    @Test
    public void laterMatchingUpperVoiceUsesOnlyThePrintedWaveExtent() {
        var voices = List.of(new PortableNoteOrnaments.Anchor(180, 174, 16, 0, 0, 1, 4),
                new PortableNoteOrnaments.Anchor(180, 194, 16, 0, 0, -1, 1),
                new PortableNoteOrnaments.Anchor(220, 174, 16, 0, 1, 1, 4),
                new PortableNoteOrnaments.Anchor(220, 194, 16, 0, 1, -1, 1),
                new PortableNoteOrnaments.Anchor(310, 174, 16, 0, 2, 1, 4));
        assertEquals(List.of(0, 2), detect(page(true, true), voices).stream()
                .map(PortableNoteOrnaments.Found::noteIndex).toList());
    }

    private static List<PortableNoteOrnaments.Found> detect(byte[] ink) {
        return detect(ink, NOTES);
    }

    private static List<PortableNoteOrnaments.Found> detect(
            byte[] ink, List<PortableNoteOrnaments.Anchor> notes) {
        var glyphs = new PortableOrnamentGlyphs();
        glyphs.add(new byte[HEAD_WIDTH * HEAD_HEIGHT], HEAD_WIDTH, HEAD_HEIGHT, NoteOrnament.TRILL, false);
        return PortableNoteOrnaments.detect(glyphs, ink, WIDTH, HEIGHT, STAFFS, notes);
    }

    private static byte[] page(boolean head, boolean wave) {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        if (head)
            for (int y = 104; y < 104 + HEAD_HEIGHT; y++)
                Arrays.fill(ink, y * WIDTH + 168, y * WIDTH + 168 + HEAD_WIDTH, (byte) 0);
        for (int i = 0; i < 6; i++) {
            int left = 194 + i * 17;
            for (int x = left; x < left + 15; x++) {
                int y = wave ? 110 + (x - left) / 2 : 110;
                for (int d = 0; d < (wave ? 2 : 9); d++) ink[(y + d) * WIDTH + x] = 0;
            }
        }
        return ink;
    }
}
