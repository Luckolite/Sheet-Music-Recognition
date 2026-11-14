// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public final class JoinedNaturalAccidentalOwnershipTest {
    @Test
    public void verifiesAnExactExclusiveMixedLabelUnion() throws Exception {
        var main = new Object();
        var tip = new Object();
        var tail = new Object();
        var recipe = List.of(main, tip, tail);
        var ids = Map.of(main, 0, tip, 1, tail, 2);
        var original =
                Map.of(
                        0,
                        glyph(0, OmrMeasurePostProcessor.CLEF_OR_KEY, 80),
                        1,
                        glyph(1, OmrMeasurePostProcessor.SYMBOL, 5),
                        2,
                        glyph(2, OmrMeasurePostProcessor.SYMBOL, 8));
        assertTrue(proves(recipe, ids, original, Map.of(main, 1, tip, 1, tail, 1)));
        assertFalse(
                "a fragment another note also chose cannot be attached",
                proves(recipe, ids, original, Map.of(main, 1, tip, 2, tail, 1)));
        assertFalse(
                "all constituents must be represented by genuine original traversals",
                proves(
                        recipe,
                        ids,
                        Map.of(0, original.get(0), 1, original.get(1)),
                        Map.of(main, 1, tip, 1, tail, 1)));
        assertFalse(
                "an alternate clef component cannot stand in for a symbol",
                proves(
                        recipe,
                        ids,
                        Map.of(
                                0,
                                original.get(0),
                                1,
                                original.get(1),
                                2,
                                glyph(2, OmrMeasurePostProcessor.CLEF_OR_KEY, 8)),
                        Map.of(main, 1, tip, 1, tail, 1)));
        assertFalse(
                "a competing full symbol cannot be treated as a small repair",
                proves(
                        recipe,
                        ids,
                        Map.of(
                                0,
                                original.get(0),
                                1,
                                original.get(1),
                                2,
                                glyph(2, OmrMeasurePostProcessor.SYMBOL, 48)),
                        Map.of(main, 1, tip, 1, tail, 1)));
    }

    private static SourceLocalAccidental.Glyph glyph(int index, int label, int count) {
        var members =
                java.util.stream.IntStream.range(index * 100, index * 100 + count).boxed().toList();
        return new SourceLocalAccidental.Glyph(index, label, null, members);
    }

    private static boolean proves(
            List<Object> recipe,
            Map<Object, Integer> ids,
            Map<Integer, SourceLocalAccidental.Glyph> originals,
            Map<Object, Integer> claims)
            throws Exception {
        Method method =
                SourceLocalAccidental.class.getDeclaredMethod(
                        "exclusivelyOwnedNaturalUnion",
                        List.class,
                        Map.class,
                        Map.class,
                        Map.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, recipe, ids, originals, claims);
    }
}
