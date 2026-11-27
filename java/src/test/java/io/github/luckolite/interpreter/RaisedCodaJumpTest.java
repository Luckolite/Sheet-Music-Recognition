// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static io.github.luckolite.interpreter.ScorePlaybackDirection.Kind.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

/** Original two-system layout with a tall terminal passage. */
public class RaisedCodaJumpTest {
    private static final List<PlayingTechniqueDetector.Staff> STAVES = List.of(
            new PlayingTechniqueDetector.Staff(260, 316, 14, 0, 1),
            new PlayingTechniqueDetector.Staff(570, 626, 14, 0, 1));
    private static final List<MeasureRegion> BARS = List.of(
            new MeasureRegion(.10f, .48f, .23f, .35f),
            new MeasureRegion(.50f, .90f, .23f, .35f),
            new MeasureRegion(.10f, .48f, .54f, .67f),
            new MeasureRegion(.50f, .90f, .54f, .67f));

    @Test
    public void raisedCompleteJumpOwnsItsRightAlignedFinalBar() {
        assertEquals(List.of(new ScorePlaybackDirection(4, DAL_SEGNO_AL_CODA)),
                detect("D.S. al Coda", .74f, .905f, .466f));
        assertEquals(List.of(new ScorePlaybackDirection(4, DA_CAPO_AL_CODA)),
                detect("D.C. al Coda", .74f, .905f, .466f));
    }

    @Test
    public void raisedCompleteTriggerOwnsItsRightAlignedFinalBar() {
        assertEquals(List.of(new ScorePlaybackDirection(4, TO_CODA)),
                detect("To Coda", .78f, .905f, .466f));
    }

    @Test
    public void raisedIncompleteOrInteriorWordsCannotUseTerminalProof() {
        assertTrue(detect("D.S.", .81f, .905f, .466f).isEmpty());
        assertTrue(detect("To Coda", .30f, .47f, .466f).isEmpty());
        assertTrue(detect("Coda", .81f, .905f, .466f).isEmpty());
        assertTrue(detect("D.S. al Coda", .27f, .47f, .466f).isEmpty());
        assertTrue(detect("D.S. al Coda", .57f, .73f, .466f).isEmpty());
    }

    @Test
    public void distinctRaisedCodaSignAndCommandShareAnInternalBarline() {
        var phrase = new PlayingTechniqueDetector.Word("D.S. al Coda", .352f, .438f, .492f, .466f);
        var atBarline = new ScoreNavigationDetector.Glyph(CODA, .490f, .395f, .433f);
        assertEquals(List.of(new ScorePlaybackDirection(3, DAL_SEGNO_AL_CODA),
                        new ScorePlaybackDirection(3, CODA)),
                ScoreNavigationDetector.detect(List.of(phrase), List.of(atBarline), STAVES, BARS, 1000, 1000));
        assertTrue(ScoreNavigationDetector.detect(
                List.of(), List.of(atBarline), STAVES, BARS, 1000, 1000).isEmpty());
        var outsideBarline = new ScoreNavigationDetector.Glyph(CODA, .512f, .395f, .433f);
        assertTrue(ScoreNavigationDetector.detect(
                List.of(phrase), List.of(outsideBarline), STAVES, BARS, 1000, 1000).isEmpty());
        var belowTheWords = new ScoreNavigationDetector.Glyph(CODA, .490f, .435f, .460f);
        assertTrue(ScoreNavigationDetector.detect(
                List.of(phrase), List.of(belowTheWords), STAVES, BARS, 1000, 1000).isEmpty());
    }

    @Test
    public void detachedJumpCannotOwnAStaffOutsideTheRaisedBand() {
        assertTrue(detect("D.S. al Coda", .74f, .905f, .452f).isEmpty());
    }

    private static List<ScorePlaybackDirection> detect(
            String text, float left, float right, float bottom) {
        var word = new PlayingTechniqueDetector.Word(text, left, bottom - .029f, right, bottom);
        return ScoreNavigationDetector.detect(List.of(word), List.of(), STAVES, BARS, 1000, 1000);
    }
}
