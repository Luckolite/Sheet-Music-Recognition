// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class AccidentalStateTestSupport {
    static List<?> apply(List<?> notes) throws Exception {
        var method = OmrScoreInterpreter.class.getDeclaredMethod(
                "applyAccidentalState",
                List.class, Set.class, Set.class, Set.class, IdentityHashMap.class);
        method.setAccessible(true);
        // Event-only fixtures have no independently captured accidental components.
        var empty = Collections.emptySet();
        return (List<?>) method.invoke(null, notes, empty, empty, empty, new IdentityHashMap<>());
    }
}
