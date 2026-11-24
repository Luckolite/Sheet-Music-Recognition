package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class LocalOctaveSlurWindowTest {
    private static final int WIDTH = 245, HEIGHT = 246, GAP = 16;

    @Test
    public void localBowedInkNeedsTwoOctaveColumnsAndACompleteContour() throws Exception {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink, (byte) 255);
        oval(ink, 95, 103);
        oval(ink, 133, 113);
        oval(ink, 95, 159);
        oval(ink, 133, 169);
        line(ink, 85, 87, 103, 216, (byte) 0);
        line(ink, 123, 125, 113, 222, (byte) 0);
        for (int x = 91; x <= 139; x++) {
            double t = (x - 91) / 48d;
            int y = (int) Math.round(88 + 7 * t - 52 * t * (1 - t));
            ink[y * WIDTH + x] = 0;
        }
        var heads = List.of(
                new PrintedSlurOwners.GeometryHead(0, 95, 103, 0, 1),
                new PrintedSlurOwners.GeometryHead(1, 95, 159, 0, 1),
                new PrintedSlurOwners.GeometryHead(2, 133, 113, 0, 1),
                new PrintedSlurOwners.GeometryHead(3, 133, 169, 0, 1));
        var notes = List.of(note(95, 103, 11), note(95, 159, 4),
                note(133, 113, 10), note(133, 169, 3));
        var track = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[] {0, 190, GAP}, new float[] {WIDTH - 1, 190, GAP}));
        assertEquals(1, bows(ink, heads, notes, track).size());
        assertEquals(0, bows(ink, heads.subList(0, 3), notes, track).size());

        var absent = ink.clone();
        line(absent, 110, 121, 64, 94, (byte) 255);
        assertEquals(0, bows(absent, heads, notes, track).size());
    }

    private static List<?> bows(
            byte[] ink, List<PrintedSlurOwners.GeometryHead> heads,
            List<ScoreNoteEvent> notes, StaffPitchTrack track) throws Exception {
        var frame = new PrintedSlurPageCapture.Frame<>(
                0, 0, WIDTH - 1, HEIGHT - 1, track, heads);
        var hints = PrintedSlurOwners.headEvidence(heads, ink, WIDTH, HEIGHT, track);
        var window = PrintedSlurPageCapture.class.getDeclaredMethod(
                "octaveSlursInLocalWindows", byte[].class, byte[].class, int.class, int.class,
                PrintedSlurPageCapture.Frame.class, List.class,
                PrintedSlurContours.HeadEvidence.class, List.class, long[].class);
        window.setAccessible(true);
        return (List<?>) window.invoke(null, ink, null, WIDTH, HEIGHT, frame, notes, hints, List.of(), new long[]{640_000});
    }

    private static ScoreNoteEvent note(int x, int y, int staffStep) {
        return new ScoreNoteEvent(0, x / (float) WIDTH, staffStep, 0, 1, y / (float) HEIGHT,
                false, 0, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0).withStemDirection(-1);
    }

    private static void line(byte[] ink, int x0, int x1, int y0, int y1, byte value) {
        for (int y = y0; y <= y1; y++) Arrays.fill(ink, y * WIDTH + x0, y * WIDTH + x1 + 1, value);
    }

    private static void oval(byte[] ink, int cx, int cy) {
        for (int y = -9; y <= 9; y++)
            for (int x = -11; x <= 11; x++)
                if (x * x * 81 + y * y * 121 <= 9801) ink[(cy + y) * WIDTH + cx + x] = 0;
    }
}
