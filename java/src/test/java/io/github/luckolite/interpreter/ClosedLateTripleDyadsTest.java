package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ClosedLateTripleDyadsTest {
    private static final int WIDTH = 2100;
    private static final int HEIGHT = 840;
    private static final int GAP = 16;
    private static final ScoreMeterChange COMMON_TIME = new ScoreMeterChange(0, 4, 4);

    @Test
    public void closedSystemsRetainThreeBeatsAndOneQuarter() {
        var fixture = fixture();
        var normalized = ClosedLateTripleDyads.normalize(
                fixture.score(), COMMON_TIME, fixture.gray(), WIDTH, HEIGHT);
        var beats = new float[] {2, 4, 4, 1, 4, 4, 4};
        var cadences = new boolean[beats.length];

        var onsets = ClosedLateTripleDyads.place(
                normalized, COMMON_TIME, fixture.gray(), WIDTH, HEIGHT, beats, cadences);

        assertArrayEquals(new float[] {2, 3, 3, 1, 3, 3, 1}, beats, 0);
        assertEquals(2, normalized.notes().get(0).beamCount());
        assertEquals(0, onsets[0], 0);
        assertEquals(2.75f, onsets[23], 0);
        assertEquals(1, normalized.measures().get(normalized.measures().size() - 1).cadenceQuarterBeats());
        assertEquals(fixture.score().meterChanges(), normalized.meterChanges());
    }

    @Test
    public void aSingleBeamOrClosingRuleCannotProveTheClock() {
        var fixture = fixture();
        var missingBeam = fixture.gray().clone();
        fill(missingBeam, 310, 330, 423, 429, 255);
        assertSame(fixture.score(), ClosedLateTripleDyads.normalize(
                fixture.score(), COMMON_TIME, missingBeam, WIDTH, HEIGHT));

        var missingRule = fixture.gray().clone();
        fill(missingRule, 1934, 1940, 600, 668, 255);
        assertSame(fixture.score(), ClosedLateTripleDyads.normalize(
                fixture.score(), COMMON_TIME, missingRule, WIDTH, HEIGHT));
    }

    private record Fixture(byte[] gray, ScorePageInterpretation score) {}

    private static Fixture fixture() {
        var gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        var bars = new ArrayList<MeasureRegion>();
        var notes = new ArrayList<ScoreNoteEvent>();
        bars.add(region(1730, 1928, 140, 272, 2));
        for (int row = 0; row < 2; row++) {
            int floor = 419 + row * 247;
            int top = floor - 98;
            int bottom = floor + 34;
            for (int k = 0; k < 5; k++)
                fill(gray, 203, 1939, floor - GAP * k, floor - GAP * k + 1, 0);
            for (int x : new int[] {1027, 1763, 1928, 1937})
                fill(gray, x - 1, x + 1, floor - GAP * 4, floor + 1, 0);
            bars.add(region(250, 1018, top, bottom, 0));
            bars.add(region(1036, 1754, top, bottom, 0));
            bars.add(region(1772, 1928, top, bottom, row == 0 ? 1 : 0));
            for (int i = 0; i < 2; i++) {
                int start = i == 0 ? 300 : 1090;
                int bar = row * 3 + i + 1;
                for (int group = 0; group < 3; group++) {
                    int x = start + 232 * group;
                    fill(gray, x - 11, x + 163, floor + 4, floor + 9, 0);
                    fill(gray, x - 11, x + 163, floor + 17, floor + 22, 0);
                }
                for (int j = 0; j < 12; j++)
                    addDyad(gray, notes, bars.get(bar), bar, start + 58 * j, floor,
                            row == 0 && i == 0 && j == 0 ? 1 : 2);
            }
            int bar = row * 3 + 3;
            addDyad(gray, notes, bars.get(bar), bar, 1840, floor, 0);
        }
        return new Fixture(gray, new ScorePageInterpretation(bars, notes));
    }

    private static MeasureRegion region(int left, int right, int top, int bottom, int cadence) {
        return new MeasureRegion(left / (float) WIDTH, right / (float) WIDTH,
                top / (float) HEIGHT, bottom / (float) HEIGHT, cadence);
    }

    private static void addDyad(byte[] gray, List<ScoreNoteEvent> notes,
            MeasureRegion region, int bar, int x, int floor, int beams) {
        int y = floor - 48;
        fill(gray, x - 12, x - 10, y, floor + (beams == 0 ? 24 : 22), 0);
        for (int i = 0; i < 2; i++) {
            int row = y + i * 40;
            fill(gray, x - 12, x + 8, row - 4, row + 4, 0);
            var n = new ScoreNoteEvent(bar, (x / (float) WIDTH - region.left()) / (region.right() - region.left()),
                    6 - i * 5, 0, 1, row / (float) HEIGHT, false, 0, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY,
                    beams == 0 ? 1 : 0, 1, 0, 0, 30, false, 0, false, 0, 0, 1, -1,
                    ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL, 0, null);
            notes.add(n);
        }
    }

    private static void fill(byte[] gray, int left, int right, int top, int bottom, int color) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) color);
    }
}
