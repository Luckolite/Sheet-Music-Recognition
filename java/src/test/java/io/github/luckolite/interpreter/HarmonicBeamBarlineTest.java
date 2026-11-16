// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.junit.Test;

public class HarmonicBeamBarlineTest {
    private static final int WIDTH = 620;
    private static final int HEIGHT = 240;
    private static final int SEAM = 284;
    private static final int STAFF_TOP = 92;
    private static final int STAFF_BOTTOM = 156;
    private static final List<MeasureRegion> BARS = List.of(region(70, 275), region(293, 490));

    @Test
    public void hollowTouchShaftWithTwoUnbrokenRailsCanRejoinTheBar() {
        assertEquals(List.of(region(70, 490)), joined(page()));
    }

    @Test
    public void missingTouchOrSecondRailKeepsTheObservedDivider() {
        var missingTouch = page();
        paint(missingTouch.gray, 290, 306, 80, 106, 255);
        assertSame(BARS, joined(missingTouch));
        var missingBeam = page();
        paint(missingBeam.gray, 250, 270, 152, 163, 255);
        assertSame(BARS, joined(missingBeam));
    }

    @Test
    public void secondVerticalStrokeAndMissingRightDoubleRuleAreConservative() {
        var anotherStroke = page();
        paint(anotherStroke.gray, 275, 277, STAFF_TOP, STAFF_BOTTOM, 0);
        assertSame(BARS, joined(anotherStroke));

        var noRightClose = page();
        paint(noRightClose.gray, 498, 506, STAFF_TOP, STAFF_BOTTOM, 255);
        assertSame(BARS, joined(noRightClose));
    }

    @Test
    public void terminalEighthOwnsTheActualClosingClock() {
        var tail = terminalNotes();
        var notes = new ArrayList<ScoreNoteEvent>();
        notes.add(beam(.2f, 2));
        notes.addAll(tail);
        var events = events();
        HarmonicBeamBarline.placeTails(
                List.of(tail), notes, events, new float[] {4}, new double[] {0});
        assertEquals(2.25, (Double) events.get(1).get("startBeat"), 0);
        var finalPlacement =
                (ScoreNoteTiming.WrittenPlacement) events.get(6).get("writtenPlacement");
        assertEquals(3.5, finalPlacement.onsetBeats(), 0);
        assertEquals(.5, finalPlacement.durationBeats(), 0);
        assertEquals(2.0, (Double) events.get(0).get("startBeat"), 0);
    }

    @Test
    public void priorSoundingOverlapAndDuplicateOwnersKeepTheOriginalPlacement() {
        var tail = terminalNotes();
        var notes = new ArrayList<ScoreNoteEvent>();
        notes.add(beam(.2f, 2));
        notes.addAll(tail);
        var events = events();
        events.get(0).put("durationBeats", 1.5);
        HarmonicBeamBarline.placeTails(
                List.of(tail), notes, events, new float[] {4}, new double[] {0});
        assertEquals(3.75, (Double) events.get(6).get("startBeat"), 0);
        notes.set(0, tail.get(0));
        events = events();
        HarmonicBeamBarline.placeTails(
                List.of(tail), notes, events, new float[] {4}, new double[] {0});
        assertEquals(3.75, (Double) events.get(6).get("startBeat"), 0);
    }

    private static List<ScoreNoteEvent> terminalNotes() {
        return List.of(
                beam(.43f, 2),
                beam(.51f, 2),
                beam(.59f, 2),
                beam(.67f, 2),
                beam(.75f, 2),
                beam(.83f, 1));
    }

    private static ScoreNoteEvent beam(float position, int count) {
        return new ScoreNoteEvent(
                0, position, 6, 0, 1, .5f, false, 0, count, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0);
    }

    private static List<Map<String, Object>> events() {
        var result = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 7; i++) {
            var map = new HashMap<String, Object>();
            map.put("startBeat", i == 0 ? 2d : 2.25 + i * .25);
            map.put("durationBeats", i == 6 ? .5 : .25);
            result.add(map);
        }
        return result;
    }

    private static List<MeasureRegion> joined(Page page) {
        return HarmonicBeamBarline.join(BARS, page.labels, page.gray, page.gray, WIDTH, HEIGHT);
    }

    private static MeasureRegion region(int left, int right) {
        return new MeasureRegion(
                left / (float) WIDTH, right / (float) WIDTH, 58f / HEIGHT, 190f / HEIGHT);
    }

    private static Page page() {
        var gray = new byte[WIDTH * HEIGHT];
        var labels = new byte[gray.length];
        Arrays.fill(gray, (byte) 255);
        paint(gray, 235, 333, 137, 147, 100);
        paint(gray, 235, 333, 152, 162, 100);
        for (int y = STAFF_TOP; y <= STAFF_BOTTOM; y += 16) paint(gray, 36, 504, y, y + 1, 0);
        paint(gray, 492, 495, STAFF_TOP, STAFF_BOTTOM, 0);
        paint(gray, 499, 504, STAFF_TOP, STAFF_BOTTOM, 0);
        paint(gray, SEAM - 1, SEAM + 1, 90, 162, 0);
        int x = 294;
        for (int yy = STAFF_TOP - 11; yy <= STAFF_TOP + 11; yy++) {
            for (int xx = x - 11; xx <= x + 11; xx++) {
                int distance = Math.abs(xx - x) + Math.abs(yy - STAFF_TOP);
                if (distance >= 7 && distance <= 11) {
                    gray[yy * WIDTH + xx] = 0;
                    labels[yy * WIDTH + xx] = OmrMeasurePostProcessor.SYMBOL;
                }
            }
        }
        return new Page(gray, labels);
    }

    private static void paint(byte[] plane, int left, int right, int top, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(plane, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }

    private record Page(byte[] gray, byte[] labels) {}
}
