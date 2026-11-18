package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PrintedLiteralOctaveSlursTest {
    private static final int WIDTH = 245, HEIGHT = 246, GAP = 16;

    @Test
    public void curvedThreeChordContourRequiresLiteralOctaveDashes() {
        var f = new Fixture();
        f.octave(95, 103, 11, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        f.octave(132, 112, 10, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        f.octave(169, 121, 9, 1, ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        bow(f.raw, 90, 176, 89, 106, 16);
        for (int x = 88; x <= 175; x += 8)
            paint(f.raw, x, x + 3, 72, 74, (byte) 0);

        assertEquals(1, f.capture(f.raw).size());
        var erased = f.raw.clone();
        paint(erased, 85, 181, 70, 75, (byte) 255);
        assertTrue(f.capture(erased).isEmpty());
        erased = f.raw.clone();
        paint(erased, 122, 136, 70, 91, (byte) 255);
        assertTrue(f.capture(erased).isEmpty());
    }

    @Test
    public void straightStrokesBesideANaturalDoNotJoinTheShortBow() {
        var f = new Fixture();
        f.octave(95, 103, 11, 0, ScoreNoteEvent.ACCIDENTAL_FROM_KEY);
        f.octave(139, 112, 10, 0, ScoreNoteEvent.ACCIDENTAL_NATURAL);
        paint(f.raw, 43, 176, 46, 47, (byte) 0);
        paint(f.raw, 43, 176, 66, 67, (byte) 0);
        paint(f.raw, 114, 115, 89, 113, (byte) 0);
        paint(f.raw, 121, 122, 102, 124, (byte) 0);
        paint(f.raw, 114, 122, 104, 107, (byte) 0);
        paint(f.raw, 114, 122, 111, 114, (byte) 0);
        bow(f.raw, 91, 145, 89, 97, 13);

        assertEquals(1, f.capture(f.raw).size());
        byte[] erased = f.raw.clone();
        paint(erased, 110, 123, 75, 88, (byte) 255);
        assertTrue(f.capture(erased).isEmpty());
    }

    private static final class Fixture {
        private final byte[] raw = new byte[WIDTH * HEIGHT], labels = new byte[WIDTH * HEIGHT];
        private final List<ScoreNoteEvent> notes = new ArrayList<>();
        private final List<PrintedSlurOwners.GeometryHead> heads = new ArrayList<>();
        private final StaffPitchTrack track = StaffPitchTrack.fromVerifiedSamples(
                List.of(new float[] {0, 190, GAP}, new float[] {WIDTH - 1, 190, GAP}));

        Fixture() {
            Arrays.fill(raw, (byte) 255);
        }

        void octave(int x, int y, int step, int shift, int accidental) {
            head(x, y, step, shift, accidental);
            head(x, y + 56, step - 7, shift, accidental);
            paint(raw, x - 10, x - 8, y, 222, (byte) 0);
        }

        void head(int x, int y, int step, int shift, int accidental) {
            for (int dy = -9; dy <= 9; dy++)
                for (int dx = -11; dx <= 11; dx++)
                    if (dx * dx * 81 + dy * dy * 121 <= 9801) {
                        int p = (y + dy) * WIDTH + x + dx;
                        raw[p] = 0;
                        labels[p] = OmrMeasurePostProcessor.NOTEHEAD;
                    }
            heads.add(new PrintedSlurOwners.GeometryHead(notes.size(), x, y, 0, 1));
            notes.add(new ScoreNoteEvent(0, x / (float) WIDTH, step, 0, 1, y / (float) HEIGHT,
                    false, 0, 2, accidental, 0).withStemDirection(-1).withOctaveShift(shift));
        }

        List<PrintedSlurContours.Bow> capture(byte[] ink) {
            var frame = new PrintedSlurPageCapture.Frame<>(0, 0, WIDTH - 1, HEIGHT - 1, track, heads);
            var hints = PrintedSlurOwners.headEvidence(heads, ink, WIDTH, HEIGHT, track);
            return PrintedLiteralOctaveSlurs.capture(ink, labels, WIDTH, HEIGHT,
                    frame, notes, hints, List.of());
        }
    }

    private static void paint(byte[] p, int l, int r, int t, int b, byte value) {
        for (int y = t; y <= b; y++) Arrays.fill(p, y * WIDTH + l, y * WIDTH + r + 1, value);
    }

    private static void bow(byte[] p, int left, int right, int startY, int endY, int lift) {
        for (int x = left; x <= right; x++) {
            double t = (x - left) / (double) (right - left);
            int y = (int) Math.round(startY + (endY - startY) * t - 4 * lift * t * (1 - t));
            p[y * WIDTH + x] = 0;
            p[(y + 1) * WIDTH + x] = 0;
        }
    }
}
