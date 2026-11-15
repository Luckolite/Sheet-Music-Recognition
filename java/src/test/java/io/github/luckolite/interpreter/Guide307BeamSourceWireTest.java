// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public final class Guide307BeamSourceWireTest {
    private static ScoreBeamSource source(ScoreBeamSource.TupletMark mark) {
        var anchors = List.of(
                new ScoreBeamSource.Anchor(0, 0),
                new ScoreBeamSource.Anchor(0, 1),
                new ScoreBeamSource.Anchor(0, 2));
        var columns = anchors.stream().map(anchor -> new ScoreBeamSource.Column(List.of(anchor))).toList();
        return new ScoreBeamSource(List.of(new ScoreBeamSource.Group(
                "original", columns, List.of(1, 1), List.of(), mark)), List.of(),
                anchors.stream().map(Optional::of).toList());
    }

    @Test
    public void explicitGuide307FramingCarriesBothMarksAndUnknown() throws IOException {
        for (var mark : new ScoreBeamSource.TupletMark[]{
                null, ScoreBeamSource.TupletMark.NUMBERED, ScoreBeamSource.TupletMark.UNNUMBERED}) {
            var source = source(mark);
            byte[] frame = ScoreBeamSourceWire.encode(source, true);
            assertEquals(source, ScoreBeamSourceWire.decode(frame, 3, true));
            assertArrayEquals(frame, ScoreBeamSourceWire.encode(ScoreBeamSourceWire.decode(frame, 3, true), true));
            assertThrows(IOException.class, () -> ScoreBeamSourceWire.decode(frame, 3));
            if (mark != null) assertThrows(IOException.class, () -> ScoreBeamSourceWire.encode(source));
            frame[76] = 3;
            assertThrows(IOException.class, () -> ScoreBeamSourceWire.decode(frame, 3, true));
        }
    }

    @Test
    public void historicalOverloadsKeepTheirOriginalFrame() throws IOException {
        var source = source(null);
        assertArrayEquals(ScoreBeamSourceWire.encode(source, false), ScoreBeamSourceWire.encode(source));
        assertEquals(source, ScoreBeamSourceWire.decode(ScoreBeamSourceWire.encode(source), 3));
    }
}
