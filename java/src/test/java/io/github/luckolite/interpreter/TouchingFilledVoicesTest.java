// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original touching noteheads with separately engraved opposing stems. */
public class TouchingFilledVoicesTest {
    private static final class Page {
        final int w = 400, h = 280;
        final byte[] labels = new byte[w * h], gray = new byte[w * h];

        Page() {
            Arrays.fill(gray, (byte) 255);
            for (int row = 0; row < 5; row++) rect(20, 80 + row * 16, 360, 1, (byte) 4);
        }

        void rect(int x, int y, int width, int height, byte label) {
            for (int yy = y; yy < y + height; yy++)
                for (int xx = x; xx < x + width; xx++) {
                    labels[yy * w + xx] = label;
                    gray[yy * w + xx] = 0;
                }
        }

        void head(int x, int y) {
            for (int yy = y - 8; yy <= y + 8; yy++)
                for (int xx = x - 11; xx <= x + 11; xx++)
                    if ((xx - x) * (xx - x) / 121f + (yy - y) * (yy - y) / 64f <= 1) {
                        labels[yy * w + xx] = 2;
                        gray[yy * w + xx] = 0;
                    }
        }

        void maskBridge() {
            for (int y = 142; y <= 146; y++) for (int x = 190; x <= 194; x++) labels[y * w + x] = 2;
        }

        List<ScoreNoteEvent> notes() {
            return OmrScoreInterpreter.extract(
                            labels, gray, w, h, List.of(new MeasureRegion(.05f, .95f, .15f, .85f)))
                    .stream()
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                    .toList();
        }
    }

    private static Page voices() {
        Page p = new Page();
        p.rect(169, 144, 2, 60, (byte) 1);
        p.rect(214, 92, 2, 53, (byte) 1);
        p.rect(274, 92, 2, 53, (byte) 1);
        p.rect(214, 92, 62, 4, (byte) 5);
        p.head(180, 144);
        p.head(204, 144);
        p.head(264, 144);
        p.maskBridge();
        return p;
    }

    @Test
    public void touchingFilledVoicesKeepTheirIndividualDurationsAndPositions() {
        var notes = voices().notes();
        assertEquals(3, notes.size());
        assertTrue(notes.stream().allMatch(n -> n.staffStep() == 0));
        assertEquals(1, notes.get(0).unbeamedDurationBeats(), 0);
        assertEquals(0, notes.get(0).beamCount());
        assertTrue(notes.get(1).beamCount() > 0);
        assertTrue(notes.get(1).positionInMeasure() > notes.get(0).positionInMeasure());
        assertTrue(notes.stream().noneMatch(n -> n.unbeamedDurationBeats() == 2));
    }

    @Test
    public void aWideSolidInkBlobDoesNotInventTwoVoices() {
        Page p = voices();
        p.rect(169, 136, 47, 17, (byte) 2);
        assertTrue(p.notes().size() <= 2);
    }

    @Test
    public void missingOpposingStemDoesNotSplitAnAmbiguousWideHead() {
        Page p = voices();
        for (int y = 153; y < 204; y++)
            for (int x = 169; x < 171; x++) {
                p.labels[y * p.w + x] = 0;
                p.gray[y * p.w + x] = (byte) 255;
            }
        assertTrue(p.notes().size() <= 2);
    }

    @Test
    public void displacedSecondKeepsTheRestOfItsChordOnTheSameAttack() {
        Page p = new Page();
        p.rect(192, 78, 2, 132, (byte) 1);
        p.rect(192, 78, 70, 4, (byte) 5);
        p.rect(190, 160, 28, 1, (byte) 5);
        p.head(180, 128);
        p.head(204, 136);
        p.head(204, 168);
        var notes = p.notes();
        assertEquals(3, notes.size());
        float onset = notes.get(0).positionInMeasure();
        assertTrue(
                "Moving the displaced second must not leave its lower chord tone late",
                notes.stream().allMatch(n -> Math.abs(n.positionInMeasure() - onset) < .001));
    }

    @Test
    public void chordAlignmentDoesNotMoveAnEarlierToneForward() {
        Page p = new Page();
        p.rect(192, 78, 2, 132, (byte) 1);
        p.rect(192, 78, 70, 4, (byte) 5);
        p.rect(161, 160, 35, 1, (byte) 5);
        p.head(180, 128);
        p.head(204, 136);
        p.head(176, 168);
        var notes = p.notes();
        assertEquals(3, notes.size());
        assertTrue(
                "Keep the earliest original chord anchor",
                notes.stream().allMatch(n -> n.positionInMeasure() <= .435f));
    }

    @Test public void parallelEighthVoicesShareTheDisplacedSecondsAttack() {
        var notes = parallelEighthVoices(true).notes();
        assertEquals(4, notes.size());
        assertEquals(1, notes.get(0).beamCount());
        assertEquals(1, notes.get(1).beamCount());
        assertEquals(notes.get(0).positionInMeasure(), notes.get(1).positionInMeasure(), .001f);
        assertEquals(notes.get(2).positionInMeasure(), notes.get(3).positionInMeasure(), .001f);
        assertTrue(notes.get(2).positionInMeasure() > notes.get(1).positionInMeasure());
    }

    @Test public void aDisconnectedVoiceRailDoesNotMoveTheOffColumnAttack() {
        var notes = parallelEighthVoices(false).notes();
        assertEquals(4, notes.size());
        assertEquals(1, notes.get(0).beamCount());
        assertEquals(1, notes.get(1).beamCount());
        assertTrue(notes.get(1).positionInMeasure() - notes.get(0).positionInMeasure() > .04f);
    }

    private static Page parallelEighthVoices(boolean connected) {
        Page page = new Page();
        page.rect(159, 74, 2, 55, (byte) 1);
        page.rect(254, 74, 2, 47, (byte) 1);
        page.rect(159, 74, 97, 5, (byte) 5);
        page.rect(165, 136, 2, 61, (byte) 1);
        page.rect(234, 152, 2, 45, (byte) 1);
        page.rect(165, 192, connected ? 71 : 29, 5, (byte) 5);
        if (!connected) page.rect(201, 192, 35, 5, (byte) 5);
        page.head(149, 128);
        page.head(175, 136);
        page.head(244, 120);
        page.head(244, 152);
        return page;
    }

    @Test public void wideSingleBeamKeepsItsDarkBodyThroughAStaffRule() {
        Page page = new Page();
        int[] left = {52, 120, 1};
        int[] right = {160, 109, 1};
        for (int x = left[0]; x <= right[0]; x++) {
            int y = Math.round(118 - 11f * (x - left[0]) / (right[0] - left[0]));
            page.rect(x, y - 2, 1, 5, (byte) 5);
        }
        assertTrue(OmrScoreInterpreter.fullSingleBeamSymbolBand(
                page.labels, page.gray, page.w, page.h, left, right, 16));
        // A staff crossing can still touch the beam on either side of the gap.
        // The remaining staff pixels may not replace the missing printed beam.
        for (int y = 105; y <= 118; y++)
            page.rect(108, y, 7, 1, y == 112 ? (byte) 4 : 0);
        assertFalse(OmrScoreInterpreter.fullSingleBeamSymbolBand(
                page.labels, page.gray, page.w, page.h, left, right, 16));
    }
}
