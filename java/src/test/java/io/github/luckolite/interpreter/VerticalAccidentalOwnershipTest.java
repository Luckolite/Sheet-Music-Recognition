// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class VerticalAccidentalOwnershipTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final List<MeasureRegion> MEASURES =
            List.of(new MeasureRegion(0, 1, .2f, .755f));
    private static final List<SourceLocalAccidental.PrintedMark> NATURAL =
            List.of(new SourceLocalAccidental.PrintedMark(0, 0));

    @Test
    public void fusedPitchLanesDoNotClaimOneLiteralMark() throws Exception {
        var fixture = new Fixture(List.of(glyph(0, 144, 101), glyph(1, 125, 119)), false, false);
        assertEquals(0, fixture.note.writtenAccidental());
        assertEquals(List.of(), fixture.printed(List.of(fixture.note)));
    }

    @Test
    public void singleGlyphAndCompactOrPartitionedHeadsRetainTheirMark() throws Exception {
        var chosen = glyph(0, 144, 101);
        var other = glyph(1, 125, 119);
        for (var fixture : List.of(new Fixture(List.of(chosen), false, false),
                new Fixture(List.of(chosen, glyph(1, 125, 104)), false, false),
                new Fixture(List.of(chosen, other), true, false),
                new Fixture(List.of(chosen, other), false, true))) {
            assertEquals(NATURAL, fixture.printed(List.of(fixture.note)));
        }
    }

    @Test
    public void independentlyRecognizedSecondHeadRetainsTheOwner() throws Exception {
        var fixture = new Fixture(List.of(glyph(0, 144, 101), glyph(1, 125, 119)), false, false);
        assertEquals(NATURAL, fixture.printed(List.of(fixture.note, note(171, 119, 11))));
    }

    private static final class Fixture {
        private final ScoreNoteEvent note = note(171, 110, 9);
        private final SourceLocalAccidental source;

        Fixture(List<SourceLocalAccidental.Glyph> glyphs, boolean compact, boolean residual)
                throws Exception {
            var pixels = new ArrayList<Integer>();
            int half = compact ? 6 : 19;
            for (int row = 110 - half; row <= 110 + half; row++)
                for (int x = 160; x <= 181; x++) pixels.add(row * WIDTH + x);
            var head = new SourceLocalAccidental.Head(0, pixels,
                    residual ? List.of(110 * WIDTH + 182) : List.of(), "authenticated test footprint");
            var choice = new SourceLocalAccidental.Choice(0, note, 0, 0, 0, 1, 1, false,
                    "MODEL_SUPPORTED_ACTUAL_LOCAL_ACCIDENTAL");
            var ctor = SourceLocalAccidental.class.getDeclaredConstructor(String.class, String.class,
                    int.class, int.class, int.class, int.class, List.class, List.class, List.class,
                    Set.class, Set.class, Map.class, List.class, boolean.class, boolean.class);
            ctor.setAccessible(true);
            source = ctor.newInstance("synthetic", "synthetic", WIDTH, HEIGHT, glyphs.size(), 1,
                    glyphs, List.of(head), List.of(choice), Set.of(), Set.of(), Map.of(), List.of(), true, true);
        }

        List<SourceLocalAccidental.PrintedMark> printed(List<ScoreNoteEvent> notes) {
            return source.printedMarks(notes, notes, MEASURES);
        }
    }

    private static ScoreNoteEvent note(float x, float y, int step) {
        return new ScoreNoteEvent(0, x / WIDTH, step, 0, 1, y / HEIGHT, false, 0, 0, 0, 1);
    }

    private static SourceLocalAccidental.Glyph glyph(int id, int left, int centerY) {
        var pixels = new ArrayList<Integer>();
        for (int y = centerY - 23; y <= centerY + 23; y++)
            for (int x = left; x < left + 14; x++)
                if (x < left + 2 || x >= left + 12
                        || Math.abs(y - centerY + 6) <= 1 || Math.abs(y - centerY - 6) <= 1)
                    pixels.add(y * WIDTH + x);
        var stats = new ExactComponentMembers.Stats(pixels.size(), left, left + 13, centerY - 23,
                centerY + 23, left + 6.5f, centerY);
        return new SourceLocalAccidental.Glyph(id, 3, stats, pixels);
    }
}
