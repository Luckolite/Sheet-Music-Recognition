// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Original ordinary-beam and independent sustained-head notation with frozen complete outputs. */
@RunWith(Parameterized.class)
public final class CompleteOwnedBeamValueOriginalTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(
                new Object[][] {
                    {
                        "paperCounter",
                        "44b7d4429aa28736124c399fba8309c6cb2646560282df521867a66821ed364d",
                        2,
                        0d,
                        true
                    },
                    {
                        "paperCounterDown",
                        "64f7848dd1a1a5e4f1e3b2e866b8a2be71a595d9b336f821d94bbae8043ae7d6",
                        2,
                        0d,
                        true
                    },
                    {
                        "paperCounterSlope",
                        "6d8f68c05e6b91448da70713039d7c237721a33b254d03dafa2992fcd1d6af10",
                        2,
                        0d,
                        true
                    },
                    {
                        "paleCounter230",
                        "06fc66c2e63796b74c217619b0ba2577ee78bbff15a487c42a27e3ccebfdfe33",
                        2,
                        0d,
                        false
                    },
                    {
                        "paleCounter245",
                        "988ac76df196b6d855d6b5838761d9b9d267df60a45198464e57a08408952259",
                        2,
                        0d,
                        true
                    },
                    {
                        "sustainedHalfBesideForeignGroup",
                        "7d200280c7e2b6d3cbbb38548513512ca32d0f7320ae03686d91acc903bca000",
                        0,
                        2d,
                        false
                    },
                    {
                        "sustainedWholeBesideForeignGroup",
                        "7f7ff498879eb5308a22c2a6833051a33dcb23380cedd0499c13ce48b0132b49",
                        0,
                        4d,
                        false
                    }
                });
    }

    private final String name, expected;
    private final int beams;
    private final double base;
    private final boolean recoveredOpen;

    public CompleteOwnedBeamValueOriginalTest(
            String name, String expected, int beams, double base, boolean recoveredOpen) {
        this.name = name;
        this.expected = expected;
        this.beams = beams;
        this.base = base;
        this.recoveredOpen = recoveredOpen;
    }

    @Test
    public void completeOwnershipArbitratesAppearanceWithoutRewritingDetectorReceipt()
            throws Exception {
        var a = CompleteRailOriginalScene.scene(name);
        assertEquals(beams, a.notes().get(0).beamCount());
        assertEquals(base, a.notes().get(0).unbeamedDurationBeats(), 0);
        var producer =
                a.clockProducerSource().printedValues().stream()
                        .filter(v -> v.note() == 0)
                        .findFirst()
                        .orElseThrow();
        if (recoveredOpen) {
            assertEquals("OPEN_WITH_STEM_HALF", producer.detectorExit().toString());
            assertEquals(2d, producer.detectorValue(), 0);
            assertEquals(2, producer.detectorBeams());
            assertEquals(2, producer.finalBeams());
            assertEquals(0d, producer.finalValue(), 0);
            assertEquals(
                    List.of(
                            "COMPLETE_OWNED_ORDINARY_BEAM_VALUE_OVERRIDE",
                            "POST_DETECTOR_VALUE_OR_BEAM_CHANGED",
                            "FINAL_EVENT_VALUE_OR_BEAM_DIFFERS_FROM_DETECTOR"),
                    producer.admissions());
            var context =
                    a.clockContextSource().printedValues().stream()
                            .filter(v -> v.noteIndex() == 0)
                            .findFirst()
                            .orElseThrow();
            assertEquals(2, context.admissionFlags());
            assertEquals(2, context.beams());
            assertEquals(0d, context.baseBeats(), 0);
            var original =
                    a.clockProducerSource().localAccidentals().choices().stream()
                            .filter(v -> v.note() == 0)
                            .findFirst()
                            .orElseThrow()
                            .originalNote();
            assertEquals(2, original.beamCount());
            assertEquals(0d, original.unbeamedDurationBeats(), 0);
        } else
            assertFalse(
                    producer.admissions().contains("COMPLETE_OWNED_ORDINARY_BEAM_VALUE_OVERRIDE"));
        var canonical = OriginalBeamAnalysisSnapshot.json(a);
        assertEquals(
                name + " complete Analysis",
                expected,
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(canonical.getBytes(StandardCharsets.UTF_8))));
    }
}
