// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class TitledOpeningTimelineTest {
    private static final int WIDTH = 1000, HEIGHT = 1400;
    private static final List<MeasureRegion> OPENING = List.of(
            new MeasureRegion(.16f, .42f, .16f, .24f),
            new MeasureRegion(.43f, .92f, .16f, .24f));
    private static final List<ScoreMeterChange> FOUR_FOUR = List.of(new ScoreMeterChange(0, 4, 4));
    private static final PlayingTechniqueDetector.Word TITLE = word("A New Waltz", .36f, .045f, .64f, .078f);
    private static final PlayingTechniqueDetector.Word VIOLIN = word("Violin", .065f, .098f, .16f, .118f);
    private static final PlayingTechniqueDetector.Word ARRANGER = word("arr. Harper Hill and Taylor Jones",
            .62f, .102f, .95f, .122f);
    private static final PlayingTechniqueDetector.Word PIANO_ARRANGER = word("Piano arr. Sylvia Gray",
            .60f, .121f, .95f, .143f);

    @Test
    public void fullTitlePartAndFreshPrintedMeterRestartTheEditionAtOne() {
        var words = List.of(TITLE, VIOLIN);
        int start = read(0, words, raster(words), FOUR_FOUR);
        assertEquals(1, start);
        assertEquals(List.of(new ScorePageTimeline.PageRange(0, 2), new ScorePageTimeline.PageRange(2, 4)),
                ScorePageTimeline.arrangements(new int[] {14, 15, 12, 8}, new int[] {0, 0, start, 0}));
        assertEquals(31, read(31, words, raster(words), FOUR_FOUR));
    }

    @Test
    public void literalEditionSubtitleAndSeparateCreditsAreSupported() {
        var subtitle = word("Piano solo accompaniment", .35f, .096f, .66f, .115f);
        var words = List.of(TITLE, subtitle);
        assertEquals(1, read(0, words, raster(words), FOUR_FOUR));
        words = List.of(TITLE, ARRANGER, PIANO_ARRANGER);
        assertEquals(1, read(0, words, raster(words), FOUR_FOUR));
        var oneCredit = List.of(TITLE, ARRANGER);
        assertEquals(0, read(0, oneCredit, raster(oneCredit), FOUR_FOUR));
    }

    @Test
    public void continuedAndSectionHeadersDoNotCreateAnotherArrangement() {
        var running = word("A New Waltz", .44f, .054f, .56f, .064f);
        var words = List.of(running, VIOLIN);
        assertEquals(0, read(0, words, raster(words), FOUR_FOUR));
        words = List.of(TITLE, VIOLIN);
        assertEquals(0, read(0, words, raster(words), List.of(new ScoreMeterChange(1, 4, 4))));
        assertEquals(0, read(0, words, raster(List.of(TITLE)), FOUR_FOUR));
        var section = word("Second Theme", .37f, .065f, .64f, .092f);
        var tempo = word("Moderately", .15f, .115f, .25f, .136f);
        words = List.of(section, tempo);
        assertEquals(0, read(0, words, raster(words), FOUR_FOUR));
    }

    private static int read(int start, List<PlayingTechniqueDetector.Word> words, byte[] gray,
            List<ScoreMeterChange> meters) {
        return ScorePageTimeline.firstMeasureAfterTitledOpening(start, words, gray, WIDTH, HEIGHT,
                OPENING, meters);
    }

    @Test
    public void centeredPartAboveTitleAllowsOpeningStackedCompoundMeter() {
        var caption = word("Violin", .46f, .010f, .54f, .025f);
        var upper = word("12", .16f, .177f, .195f, .190f);
        var lower = word("8", .16f, .198f, .179f, .210f);
        var staff = new PlayingTechniqueDetector.Staff(235, 315, 20, 0, 1);
        var phantom = new ScoreNoteEvent(0, .06f, 3, 0, 1, .184f, false);
        var attack = new ScoreNoteEvent(0, .18f, 2, 0, 1, .184f, false);
        var notes = List.of(phantom, attack);
        var words = List.of(caption, TITLE, upper, lower);
        var meter = OpeningPartTempo.titledMeter(words, raster(words), WIDTH, HEIGHT,
                OPENING, List.of(staff), notes);
        assertEquals(new ScoreMeterChange(0, 12, 8), meter.change());
        assertEquals(1, read(0, words, raster(words), List.of(meter.change())));
        assertEquals(List.of(attack), meter.withoutMeterHeads(notes, OPENING.get(0)));
        var offset = word("Violin", .20f, .010f, .27f, .025f);
        for (var rejected : List.of(List.of(caption, TITLE, upper), List.of(offset, TITLE, upper, lower))) {
            assertEquals(null, OpeningPartTempo.titledMeter(rejected, raster(rejected), WIDTH, HEIGHT,
                    OPENING, List.of(staff), notes));
        }
    }

    private static PlayingTechniqueDetector.Word word(String text, float l, float t, float r, float b) {
        return new PlayingTechniqueDetector.Word(text, l, t, r, b);
    }

    private static byte[] raster(List<PlayingTechniqueDetector.Word> words) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        // Draw independent ink marks in each synthetic OCR crop.
        for (var word : words)
            for (int y = Math.round(word.top() * HEIGHT); y < Math.round(word.bottom() * HEIGHT); y++)
                for (int x = Math.round(word.left() * WIDTH); x < Math.round(word.right() * WIDTH); x++)
                    if (x % 8 < 3 && y % 6 < 4) gray[y * WIDTH + x] = 0;
        return gray;
    }
}
