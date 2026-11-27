// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public final class PrintedRepeatDocumentContinuityTest {
    private static final String DOCUMENT = "a".repeat(64);

    @Test
    public void boundAdjacentPagesCanDisagreeOnTwoRegionsAndStillResolveTheVoltas() {
        var result = PrintedRepeatDocument.assemble(pages(3, 1, DOCUMENT, true));
        assertEquals(4, result.directions().size());
        var plan = ScoreNavigationPlan.create(8, result.directions());
        assertEquals(List.of(0, 1, 2, 3, 4, 1, 2, 3, 5, 6, 7), plan.sourceMeasures());
        assertTrue(plan.traversal().diagnostics().isEmpty());
    }

    @Test
    public void aPhysicalGapAnotherDocumentUnboundInputAndAResetAllRefuseTheException() {
        for (var rejected : List.of(pages(3, 2, DOCUMENT, true), pages(3, 1, "b".repeat(64), true),
                pages(3, 1, DOCUMENT, false), pages(1, 1, DOCUMENT, true), pages(8, 1, DOCUMENT, true)))
            assertTrue(PrintedRepeatDocument.assemble(rejected).directions().isEmpty());
    }

    private static List<ScorePageInterpretation> pages(int first, int index, String other, boolean bound) {
        var a = page(1, 0, DOCUMENT, bound, sign("printed-repeat-start:v1:", 0, 1, -1, ""));
        var b = page(first, index, other, bound,
                sign("printed-repeat-end:v1:", index, 1, -1, ""),
                sign("printed-ending-span:v1:false:false:", index, 0, 1, "1."),
                sign("printed-ending-span:v1:true:false:", index, 1, 2, "2."));
        return List.of(a, b);
    }

    private static ScorePageInterpretation page(
            int start, int index, String document, boolean bound, ScoreExpressiveEvent... marks) {
        var page = new ScorePageInterpretation(Collections.nCopies(4, new MeasureRegion(.1f, .9f, .1f, .9f)),
                List.of(), start, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
                .withExpressiveEvents(List.of(marks));
        if (!bound) return page;
        var origin = new ScoreClockContextSource.Origin(document, index, 3, 10, 10, "c".repeat(64), "");
        return page.withClockContextSource(new ScoreClockContextSource(
                origin, 0, 4, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()));
    }

    private static ScoreExpressiveEvent sign(String type, int page, int from, int to, String text) {
        String id = (type.startsWith("printed-ending") ? "unresolved-printed-ending:"
                : "unresolved-printed-repeat:") + page + ":" + from;
        return new ScoreExpressiveEvent(id, ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                Optional.of(new ScoreAnchor(from, 0)), to < 0 ? Optional.empty() : Optional.of(new ScoreAnchor(to, 0)),
                ScoreExpressiveEvent.Scope.UNRESOLVED, 0, 2, Optional.empty(),
                ScoreExpressiveEvent.Strength.UNSPECIFIED, "unresolved",
                List.of(new ScoreExpressiveEvent.Evidence(type + id, page, .5f, 0, 2, text)));
    }
}
