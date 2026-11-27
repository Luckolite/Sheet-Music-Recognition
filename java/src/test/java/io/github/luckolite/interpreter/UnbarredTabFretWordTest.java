// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class UnbarredTabFretWordTest {
    @Test
    public void ignoresSingleOcrDigitReportedAcrossSeveralFretPositions() {
        var tab = new TablatureDecoder.Staff(64, 64, -1, List.of(), List.of());
        var wideWord = new TablatureDecoder.Word("0", .20f, .27f, .75f, .34f);
        var actualZero = new TablatureDecoder.Word("0", .23f, .27f, .26f, .34f);
        var actualTwelve = new TablatureDecoder.Word("12", .70f, .27f, .76f, .34f);
        var rows = TablatureDecoder.withWords(List.of(tab), List.of(wideWord, actualZero, actualTwelve), 1200, 600);
        assertEquals(List.of(0, 12), rows.get(0).frets().stream().map(TablatureDecoder.Fret::fret).toList());
    }
}
