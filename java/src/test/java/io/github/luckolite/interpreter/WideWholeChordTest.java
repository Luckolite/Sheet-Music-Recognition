// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WideWholeChordTest {
    private List<ScoreNoteEvent> notes(boolean hollow) {
        int w = 400, h = 240;
        byte[] labels = new byte[w * h], gray = new byte[w * h];
        Arrays.fill(gray, (byte) 255);
        for (int y = 96; y <= 160; y += 16)
            for (int x = 20; x < 380; x++) {
                labels[y * w + x] = 4;
                gray[y * w + x] = 0;
            }
        // Three broad whole-note ovals, with their model masks joined at the necks.
        for (int cy : new int[] {64, 80, 96}) {
            for (int y = cy - 8; y <= cy + 8; y++)
                for (int x = 185; x <= 215; x++) {
                    double outer = (x - 200) * (x - 200) / 225d + (y - cy) * (y - cy) / 64d;
                    if (outer <= 1) {
                        labels[y * w + x] = 2;
                        if (!hollow || (x - 200) * (x - 200) / 49d + (y - cy) * (y - cy) / 25d >= 1)
                            gray[y * w + x] = 0;
                    }
                }
            for (int x = 178; x <= 222; x++) gray[cy * w + x] = 0;
        }
        for (int y = 56; y <= 104; y++) for (int x = 189; x <= 211; x++) labels[y * w + x] = 2;
        return OmrScoreInterpreter.extract(
                labels, gray, w, h, List.of(new MeasureRegion(.3f, .94f, .15f, .85f)));
    }

    @Test
    public void broadHollowTriadRetainsEveryWholeNote() {
        var result = notes(true);
        assertEquals(3, result.size());
        assertEquals(
                List.of(8, 10, 12),
                result.stream().map(ScoreNoteEvent::staffStep).sorted().toList());
        for (var n : result) assertEquals(4, ScoreNoteTiming.writtenDurationBeats(n), .0001);
    }

    @Test
    public void solidStackIsNotInventedAsThreeWholeNotes() {
        assertFalse(notes(false).stream().filter(n -> n.unbeamedDurationBeats() == 4).count() == 3);
    }

    @Test public void displacedWholeSecondJoinsTheTwoOvalsInItsColumn() {
        for (boolean mirrored : new boolean[] {false, true}) {
            var notes = staggeredNotes(mirrored, false);
            assertEquals(3, notes.size());
            assertEquals(List.of(3, 4, 6), notes.stream().map(ScoreNoteEvent::staffStep).sorted().toList());
            for (var note : notes) assertEquals(4, note.unbeamedDurationBeats(), 0);
            assertFalse(staggeredNotes(mirrored, true).stream()
                    .filter(n -> n.unbeamedDurationBeats() == 4).count() == 3);
        }
    }

    private static List<ScoreNoteEvent> staggeredNotes(boolean mirrored, boolean solidLeaf) {
        int w = 400, h = 240;
        byte[] labels = new byte[w * h], gray = new byte[w * h];
        Arrays.fill(gray, (byte) 255);
        for (int y = 96; y <= 160; y += 16)
            for (int x = 20; x < 380; x++) {
                labels[y * w + x] = OmrMeasurePostProcessor.STAFF;
                gray[y * w + x] = 0;
            }
        int column = mirrored ? 213 : 185;
        int leaf = mirrored ? 185 : 213;
        wholeOval(labels, gray, w, column, 112, false, false);
        wholeOval(labels, gray, w, column, 128, false, false);
        wholeOval(labels, gray, w, leaf, 136, true, solidLeaf);
        return OmrScoreInterpreter.extract(labels, gray, w, h,
                List.of(new MeasureRegion(.3f, .94f, .15f, .85f)));
    }

    private static void wholeOval(byte[] labels, byte[] gray, int width, int cx, int cy,
            boolean displaced, boolean filled) {
        for (int dy = -8; dy <= 8; dy++)
            for (int dx = -15; dx <= 15; dx++) {
                if (dx * dx / 225d + dy * dy / 64d > 1) continue;
                int at = (cy + dy) * width + cx + dx;
                labels[at] = OmrMeasurePostProcessor.NOTEHEAD;
                double insideX = dx - (displaced ? -.42 : .42) * dy;
                if (filled || insideX * insideX / 25d + dy * dy / 42d >= 1)
                    gray[at] = 0;
            }
    }
}
