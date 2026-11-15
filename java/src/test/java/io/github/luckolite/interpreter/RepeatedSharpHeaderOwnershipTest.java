package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RepeatedSharpHeaderOwnershipTest {
    @Test
    public void aPartialRepeatedSignatureOwnsOnlyItsInteriorInk() throws Exception {
        var clef = component(700, 7, 24, 50, 150, 18, 100);
        var staff = create("Staff", types(float.class, float.class, float.class), 80f, 144f, 16f);
        var raw = List.of(sharp(42, 80), sharp(78, 72), sharp(98, 96), sharp(117, 120));
        var headerFragment = component(235, 90, 106, 80, 119, 98, 100);
        var localFlat = component(210, 139, 151, 80, 119, 145, 100);
        var candidates = List.of(accidental(headerFragment), accidental(localFlat));
        var roles = new ArrayList<>();
        var read = method("retainRepeatedSharpHeaderOwners", List.class, List.class, List.class,
                nested("Component"), nested("Staff"), int.class, float.class, float.class);
        read.invoke(null, roles, candidates, raw, clef, staff, 5, 133f, 160f);
        assertEquals(List.of(headerFragment), roles);

        roles.clear();
        read.invoke(null, roles, candidates, raw.subList(0, 3), clef, staff, 5, 133f, 160f);
        read.invoke(null, roles, candidates, raw, clef, staff, 4, 133f, 160f);
        read.invoke(null, roles, candidates, raw, clef, staff, 5, 104f, 160f);
        assertTrue(roles.isEmpty());
    }

    private static Object sharp(int x, int y) throws Exception {
        var body = component(400, x - 8, x + 8, y - 20, y + 20, x, y);
        return create("SignatureGlyph", types(float.class, int.class, float.class, nested("Component")),
                (float) x, ScoreNoteEvent.ACCIDENTAL_SHARP, (float) y, body);
    }

    private static Object accidental(Object body) throws Exception {
        return create("AccidentalCandidate", types(nested("Component"), byte.class), body,
                OmrMeasurePostProcessor.SYMBOL);
    }

    private static Object component(int area, int left, int right, int top, int bottom, float x, float y)
            throws Exception {
        return create("Component", types(int.class, int.class, int.class, int.class, int.class,
                float.class, float.class), area, left, right, top, bottom, x, y);
    }

    private static Class<?> nested(String type) throws Exception {
        return Class.forName(OmrScoreInterpreter.class.getName() + "$" + type);
    }

    private static Class<?>[] types(Class<?>... types) {
        return types;
    }

    private static Object create(String type, Class<?>[] parameters, Object... args) throws Exception {
        Constructor<?> constructor = nested(type).getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private static Method method(String name, Class<?>... args) throws Exception {
        var method = OmrScoreInterpreter.class.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

}
