// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generated independent pixels and semantic labels exercise actual measure admission. */
public class InteriorStaffAdmissionTest {
    static final int W = 800, H = 700;

    static byte[][] page(boolean printedCue, int cueGap) {
        byte[] l = new byte[W * H], g = new byte[W * H];
        Arrays.fill(g, (byte) 250);
        for (int top : new int[] {100, 300, 500}) {
            int gap = top == 300 ? cueGap : 14;
            for (int n = 0; n < 5; n++)
                for (int x = 25; x < 775; x++) {
                    int i = (top + n * gap) * W + x;
                    l[i] = 4;
                    if (top != 300 || printedCue) g[i] = (byte) (top == 300 ? 246 : 80);
                }
            int cx = 200, cy = top + 3 * gap;
            for (int y = cy - 4; y <= cy + 4; y++)
                for (int x = cx - 7; x <= cx + 7; x++)
                    if ((x - cx) * (x - cx) / 49. + (y - cy) * (y - cy) / 16. <= 1) {
                        l[y * W + x] = 2;
                        g[y * W + x] = 0;
                    }
            for (int y = cy - gap * 3; y < cy; y++) {
                l[y * W + cx + 7] = 1;
                g[y * W + cx + 7] = 0;
            }
        }
        return new byte[][] {l, g};
    }

    @Test
    public void unsupportedCompressedInteriorCannotAddMeasure() {
        var p = page(false, 8);
        assertEquals(2, OmrMeasurePostProcessor.process(p[0], p[1], W, H).size());
    }

    @Test
    public void unsupportedInteriorCannotEmitExtraNote() {
        var p = page(false, 8);
        var m = OmrMeasurePostProcessor.process(p[0], p[1], W, H);
        assertEquals(2, OmrScoreInterpreter.analyze(p[0], p[1], W, H, m).notes().size());
    }

    @Test
    public void faintPrintedSmallStaffSurvives() {
        var p = page(true, 8);
        assertEquals(3, OmrMeasurePostProcessor.process(p[0], p[1], W, H).size());
    }

    @Test
    public void sameScaleObscuredStaffSurvives() {
        var p = page(false, 14);
        assertEquals(3, OmrMeasurePostProcessor.process(p[0], p[1], W, H).size());
    }

    @Test
    public void inputPixelsAndLabelsRemainExact() {
        var p = page(false, 8);
        var l = p[0].clone();
        var g = p[1].clone();
        OmrMeasurePostProcessor.process(p[0], p[1], W, H);
        assertArrayEquals(l, p[0]);
        assertArrayEquals(g, p[1]);
    }

    @Test
    public void provisionalMeasureObserverKeepsReadingOrderAndDoesNotChangeGeometry() {
        var page = page(true, 8);
        var expected = NativeDecoderStages.geometry(page[0], page[1], W, H);
        var observed = new ArrayList<MeasureRegion>();
        var actual =
                NativeDecoderStages.geometry(
                        page[0], page[1], W, H, new SelectedMeasureOrigins(), observed::add);
        assertEquals(3, observed.size());
        assertTrue(observed.get(0).top() < observed.get(1).top());
        assertTrue(observed.get(1).top() < observed.get(2).top());
        assertEquals(expected.measures(), actual.measures());
        assertArrayEquals(expected.labels(), actual.labels());
    }

    @Test
    public void decodePreviewReportsStaffsAndNoteheadsBeforeKeyWorkWithoutChangingNotes() {
        var page = page(true, 8);
        var measures = NativeDecoderStages.geometry(page[0], page[1], W, H).measures();
        var expected = OmrScoreInterpreter.analyzePage(page[0], page[1], W, H, measures, page[1], List.of());
        var events = new ArrayList<String>();
        var actual =
                OmrScoreInterpreter.analyzePage(page[0], page[1], W, H, measures, page[1], List.of(),
                        new OmrScoreInterpreter.DecodePreview() {
                            @Override public void staffs(List<PlayingTechniqueDetector.Staff> staffs) {
                                assertEquals(expected.physicalStaffs(), staffs);
                                assertFalse(staffs.isEmpty());
                                events.add("staves");
                            }
                            @Override public void notehead(int left, int top, int right, int bottom) {
                                assertTrue(left >= 0 && right <= W && right > left);
                                assertTrue(top >= 0 && bottom <= H && bottom > top);
                                events.add("head");
                            }
                            @Override public void noteheadsScanned() { events.add("heads scanned"); }
                            @Override public void readingKeys() { events.add("reading keys"); }
                            @Override public void keySignatures(List<ScoreKeyChange> keys) {
                                events.add("keys checked");
                            }
                        });
        assertEquals(List.of("staves", "head", "head", "head", "heads scanned", "reading keys", "keys checked"),
                events);
        assertEquals(expected.score().notes(), actual.score().notes());
        assertEquals(expected.score().keyChanges(), actual.score().keyChanges());
        assertEquals(expected.score().rests(), actual.score().rests());
    }
}
