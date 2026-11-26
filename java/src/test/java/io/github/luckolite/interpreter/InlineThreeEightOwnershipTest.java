package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class InlineThreeEightOwnershipTest {
    private static final int WIDTH = 800, HEIGHT = 360;
    private final byte[] gray = new byte[WIDTH * HEIGHT], labels = new byte[WIDTH * HEIGHT];
    private final List<MeasureRegion> bars = List.of(
            new MeasureRegion(.04f, .24f, 98f / HEIGHT, 226f / HEIGHT),
            new MeasureRegion(.25f, .63f, 98f / HEIGHT, 226f / HEIGHT),
            new MeasureRegion(.64f, .96f, 98f / HEIGHT, 226f / HEIGHT));
    private final List<ScoreNoteEvent> notes = new ArrayList<>();

    public InlineThreeEightOwnershipTest() {
        Arrays.fill(gray, (byte) 255);
        for (int row = 130; row <= 194; row += 16)
            Arrays.fill(gray, row * WIDTH + 20, row * WIDTH + 780, (byte) 0);
        for (int row = 130; row <= 194; row++) gray[row * WIDTH + 200] = 0;
        for (int row = 168; row <= 177; row++)
            for (int x = 291; x <= 300; x++) {
                labels[row * WIDTH + x] = OmrMeasurePostProcessor.NOTEHEAD;
                gray[row * WIDTH + x] = 0;
            }
        notes.add(new ScoreNoteEvent(1, pos(1, 295), 3, 0, 1, 172f / HEIGHT,
                false, 0, 0, 2, 4f));
        for (int bar = 1; bar <= 2; bar++)
            for (int i = 0; i < 6; i++)
                notes.add(new ScoreNoteEvent(bar, pos(bar, (bar == 1 ? 331 : 533) + i * 24),
                        i, 0, 1, 160f / HEIGHT, false, 0, 2, 2));
    }

    private float pos(int bar, int x) {
        var region = bars.get(bar);
        return (x / (float) WIDTH - region.left()) / (region.right() - region.left());
    }

    @Test
    public void twoBoundMeterReadingsOwnTheHeadButKeepWrittenAttacks() {
        var result = MeterNumeralWholeNoteAlias.capture(notes, bars, labels, gray, gray.clone(), WIDTH, HEIGHT,
                (p, w, h, c) -> c.left() < 295 && c.right() < 331 ? "3/8" : "");
        assertEquals(List.of(new ScoreMeterChange(1, 3, 8)), result.inlineMeters());
        assertEquals(notes.subList(1, notes.size()), result.notes());
    }

    @Test
    public void conflictingReadingsPreserveTheOriginalNote() {
        var result = MeterNumeralWholeNoteAlias.capture(notes, bars, labels, gray, gray.clone(), WIDTH, HEIGHT,
                (p, w, h, c) -> p == gray ? "3/8" : "3/4");
        assertTrue(result.inlineMeters().isEmpty());
        assertEquals(notes, result.notes());
    }
}
