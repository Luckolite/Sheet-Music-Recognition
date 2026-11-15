// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class AccidentalJoinSourceTest {
    private static final Class<?> COMPONENT = internal("Component");
    private static final Class<?> CANDIDATE = internal("AccidentalCandidate");

    @Test
    public void joinedSharpKeepsItsOriginalGlyphInEitherOrder() throws Exception {
        Object original = component(36, 15);
        Object joined = component(39, 15);
        var parents = parents(joined, original);
        assertTrue(sameSource(original, (byte) 3, joined, (byte) 0, parents));
        assertTrue(sameSource(joined, (byte) 0, original, (byte) 3, parents));
    }

    @Test
    public void equalBoundsStillRequireAnExactOriginalAndUnchangedBox() throws Exception {
        Object original = component(36, 15);
        Object joined = component(39, 15);
        Object differentOriginal = component(36, 15);
        assertFalse(sameSource(original, (byte) 3, joined, (byte) 0, Map.of()));
        assertFalse(sameSource(original, (byte) 3, joined, (byte) 0,
                parents(joined, differentOriginal)));
        assertFalse(sameSource(original, (byte) 3, joined, (byte) 3, parents(joined, original)));
        Object wider = component(39, 14);
        assertFalse(sameSource(original, (byte) 3, wider, (byte) 0, parents(wider, original)));
    }

    private static Class<?> internal(String name) {
        try {
            return Class.forName(OmrScoreInterpreter.class.getName() + "$" + name);
        } catch (ClassNotFoundException error) {
            throw new AssertionError(error);
        }
    }

    private static Object component(int area, int minX) throws Exception {
        Constructor<?> c = COMPONENT.getDeclaredConstructor(
                int.class, int.class, int.class, int.class, int.class, float.class, float.class);
        c.setAccessible(true);
        return c.newInstance(area, minX, 22, 25, 44, 18f, 35f);
    }

    private static Map<Object, List<Object>> parents(Object joined, Object source) {
        Map<Object, List<Object>> parents = new IdentityHashMap<>();
        parents.put(joined, List.of(source, new Object()));
        return parents;
    }

    private static boolean sameSource(Object first, byte firstLabel, Object second, byte secondLabel,
            Map<Object, List<Object>> parents) throws Exception {
        Constructor<?> c = CANDIDATE.getDeclaredConstructor(COMPONENT, byte.class);
        c.setAccessible(true);
        Method sameSource = OmrScoreInterpreter.class.getDeclaredMethod(
                "sameSharpSource", CANDIDATE, CANDIDATE, Map.class);
        sameSource.setAccessible(true);
        return (boolean) sameSource.invoke(null, c.newInstance(first, firstLabel),
                c.newInstance(second, secondLabel), parents);
    }
}
