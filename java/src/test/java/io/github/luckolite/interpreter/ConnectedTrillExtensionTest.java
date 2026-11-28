// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConnectedTrillExtensionTest {
    private static final int WIDTH = 360, HEIGHT = 280, HEAD_WIDTH = 25, HEAD_HEIGHT = 20;

    private static List<PortableNoteOrnaments.Found> read(boolean head, boolean wave) {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        if (head)
            for (int y = 104; y < 104 + HEAD_HEIGHT; y++)
                Arrays.fill(ink, y * WIDTH + 168, y * WIDTH + 168 + HEAD_WIDTH, (byte) 0);
        // Continuous tapered ripple joins the last column of the head.
        for (int x = 192; x < 323; x++) {
            int rise = wave ? Math.abs((x - 192) % 12 - 6) / 2 : 0;
            for (int y = 111 + rise; y < 114 + rise; y++) ink[y * WIDTH + x] = 0;
        }
        var glyphs = new PortableOrnamentGlyphs();
        glyphs.add(new byte[HEAD_WIDTH * HEAD_HEIGHT], HEAD_WIDTH, HEAD_HEIGHT, NoteOrnament.TRILL, false);
        var staffs = List.of(new PlayingTechniqueDetector.Staff(150, 214, 16, 0, 1));
        var notes = List.of(new PortableNoteOrnaments.Anchor(180, 173, 16, 0, 0),
                new PortableNoteOrnaments.Anchor(296, 174, 16, 0, 0));
        return PortableNoteOrnaments.detect(glyphs, ink, WIDTH, HEIGHT, staffs, notes);
    }

    @Test
    public void requiresTheBoundedHeadAndAConnectedOscillatingTail() {
        var found = read(true, true);
        assertEquals(1, found.size());
        assertEquals(0, found.get(0).noteIndex());
        assertEquals(NoteOrnament.TRILL, NoteOrnament.type(found.get(0).marks()));
        assertTrue(found.get(0).bounds().trillWaveRight >= 300);
        assertTrue(read(false, true).isEmpty());
        assertTrue(read(true, false).isEmpty());
    }
}
