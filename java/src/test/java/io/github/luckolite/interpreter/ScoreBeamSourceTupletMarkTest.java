// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.List;
import org.junit.Test;

public class ScoreBeamSourceTupletMarkTest {
    @Test public void engravingKeepsPhysicalOwnershipAndLegacyBytes() throws Exception {
        var columns = List.of(
                new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, 0))),
                new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, 1))),
                new ScoreBeamSource.Column(List.of(new ScoreBeamSource.Anchor(0, 2))));
        var legacy = new ScoreBeamSource.Group("source", columns, List.of(1, 1), List.of());
        var source = ScoreBeamSource.capture(0, 3, List.of(legacy), List.of());
        assertFalse(Main.json(source).contains("tupletMark"));
        assertEquals(source, ScoreBeamSourceWire.decode(ScoreBeamSourceWire.encode(source), 3));
        for (var mark : ScoreBeamSource.TupletMark.values()) {
            var group = new ScoreBeamSource.Group("source", columns, List.of(1, 1), List.of(), mark);
            var relocated = ScoreBeamSource.capture(0, 3, List.of(group), List.of()).onPage(7);
            assertEquals(mark, relocated.groups().get(0).tupletMark());
            assertEquals(new ScoreBeamSource.Anchor(7, 1), relocated.groups().get(0).columns().get(1).members().get(0));
            assertTrue(Main.json(relocated).contains("\"tupletMark\":\"" + mark.name() + "\""));
            assertThrows(IOException.class, () -> ScoreBeamSourceWire.encode(relocated));
            assertThrows(IllegalArgumentException.class,
                    () -> new ScoreBeamSource.Group("source", columns, List.of(3, 1), List.of(), mark));
        }
    }
}
