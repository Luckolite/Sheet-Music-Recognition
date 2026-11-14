// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Original broad beam ownership scenes; no downloaded or commercial raster.
 * Fingerprints freeze already-reviewed synthetic Analysis outputs, not outputs recomputed
 * by the implementation under test. Readable rhythm/lineage assertions explain each gain.
 * Complete owned ordinary beams arbitrate head appearance; these cases do not assert new tremolo recall.
 */
@RunWith(Parameterized.class)
public final class CompleteRailOwnershipOriginalTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(
                new Object[][] {
                    {
                        "positiveUp",
                        "fba186193a9a89824d8291f5189d699b76b34b66ebe7cf26eac938e07c17e416",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "positiveDown",
                        "53c895b01e2b16c9626dc0552390b42a677329986e0ddb8c2dc28f314072fbf3",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "positiveSlope",
                        "bc29ecb711d788ee51b144b7e20a67932918c30d8ef94a1cb1574f39389dc9e0",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "compactControl",
                        "69aa4bb9dbc5c6cff1e2a1626d3619d06a40043424bf73f5de3322c1bfe25f19",
                        3,
                        2,
                        0.0f,
                        false
                    },
                    {
                        "nearLeft1",
                        "bb4a1745db35a50d16b79ece02c68dad26db1fee180a7978e53b1853e22d76a1",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "nearLeft2",
                        "599e350658d890d4a8da4399b12968d946a80e99ca177fdcbb652677a84a0490",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "nearRight1",
                        "47e66475f97557612558e66a60233b56574302333fe1a78fce383256861fa4b7",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "nearRight2",
                        "4b6b52775d401a1d783fe4e26dad7377b8c002ed7d1ea3888ad2f64f6e66842e",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "brokenInterior",
                        "ce5c769b97b1ae045f69d893ad0b32aeb222b146ff42104ae03f96ac780353f4",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "staffBridge",
                        "64c4913e6060c13341fda783f5f03c01fafc677e2c4131054627829d42e0fd05",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "slurBridge",
                        "a38fa97546a617b86e8a5edc294ac018a2af78f8c9f39a9798e732140dd67ef6",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "otherRailOnly",
                        "ce5c769b97b1ae045f69d893ad0b32aeb222b146ff42104ae03f96ac780353f4",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "foreignStem",
                        "8b11e852b0106937b8e2bbe39d76db1c90aea1ab07e9a257bfc4a14e258d0596",
                        3,
                        1,
                        0.0f,
                        false
                    },
                    {
                        "omittedOwner",
                        "0682a9c2940774fff64a344b8d1110642afafcd120705f850bb47c428c4bd920",
                        2,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "crossBar",
                        "e9edfb208a6111611a1a6c535f6bb6a0763873be490a03b298aa6e5f8dc8fb32",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "dualStem",
                        "75aef863905a0bcbee8673dde5b7b9344c5f9fc71684be1507d2c2afc8ae8651",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "whole",
                        "db57e322c4a18ff7aeb5013530e4a4998c8948232be4183991aa3e97dd00d9e6",
                        3,
                        0,
                        4.0f,
                        false
                    },
                    {
                        "half",
                        "183164cd0cbe10bd7adbf22553561b9f43515c3aa972297d5270739c3f507dc1",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "paperCounter",
                        "44b7d4429aa28736124c399fba8309c6cb2646560282df521867a66821ed364d",
                        3,
                        2,
                        0.0f,
                        false
                    },
                    {
                        "separateGroups",
                        "8c72a4986593afdcb6e9db6b1b8203a1270f6440abca628cde0c23de4fb6c5ff",
                        6,
                        2,
                        0.0f,
                        false
                    },
                    {
                        "attachedTremolo",
                        "98a9c1efee6111b180ba992ed813c03a5de56ea54e996b3e3f257956c9e2a741",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "detachedTremolo",
                        "97b2d4f043b1333ee3655f7ff401774d58037c39a359a95f88033960f7b7e7e8",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "pairedTremolo",
                        "8f860dfdc9ce617c2bf546c065d9d5286b68e6d6fc566c2aa05d6ef9f0d08eba",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "pairedTremolo3",
                        "2ff25b65f50406f40bd1c360f960755e223d75d90ef52a633be73ec1c28c1fa9",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "sharedShaft",
                        "95780dd6f612d08a77d4a23e699ad659a114c3b7ec568e6e3974208510cbbe42",
                        4,
                        2,
                        0.0f,
                        false
                    },
                    {
                        "competingHead",
                        "1c87a6d97bdaefc584eeacf058dc8e9fd1899c64e40ce4b62e1e30645ff1b1b7",
                        4,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "railLabelSymbol",
                        "5ff22ad50749237bf30af1f3dd9aeb5553fa8fbe88dc66b16ee0d896f461775c",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "railLabelBackground",
                        "9d1fbb44ce4982e54fec312143585e0300a335182f39039ce68481d0dedd21ce",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "thinInteriorSymbol",
                        "cc9c0ae0254ae6623419305dc55c2eabb99e285cc8a9d019354c54bf6ee368a8",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "thinInteriorBackground",
                        "68ed447cf3075c8dc149c31a3798edaa0fa4d49874d7df3f42a29453e9d0f9a7",
                        3,
                        2,
                        0.0f,
                        true
                    },
                    {
                        "ordinaryTwoShaft",
                        "e510b84c244ad83156f49002e1596ac9c5794549506a277e803aaf2a4ff1bc7b",
                        2,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "chordTwoShaft",
                        "046670e4d7637738c175d941c149ff5b59b7f743cd37ce66c38ba4c12b284922",
                        3,
                        0,
                        2.0f,
                        false
                    },
                    {
                        "pairedIssuerControl",
                        "074d340e427a961a67fd135f6fd3d74491e2c036c5ce1725ad16e103b9c64bbf",
                        2,
                        0,
                        1.0f,
                        false
                    },
                });
    }

    private final String name, expected;
    private final int count, beams;
    private final float base;
    private final boolean gain;

    public CompleteRailOwnershipOriginalTest(
            String name, String expected, int count, int beams, float base, boolean gain) {
        this.name = name;
        this.expected = expected;
        this.count = count;
        this.beams = beams;
        this.base = base;
        this.gain = gain;
    }

    @Test
    public void originalNotationAndEntireAnalysisStayQualified() throws Exception {
        var analysis = CompleteRailOriginalScene.scene(name);
        assertEquals(name + " note identities", count, analysis.notes().size());
        if (count > 0) {
            var note = analysis.notes().get(0);
            assertEquals(name + " first written beams", beams, note.beamCount());
            assertEquals(name + " first written base", base, note.unbeamedDurationBeats(), 0);
        }
        if (gain) {
            var context =
                    analysis.clockContextSource().printedValues().stream()
                            .filter(v -> v.noteIndex() == 0)
                            .findFirst()
                            .orElseThrow();
            assertEquals(2, context.beams());
            assertEquals(0, context.baseBeats(), 0);
            assertEquals(0, context.admissionFlags());
            var producer =
                    analysis.clockProducerSource().printedValues().stream()
                            .filter(v -> v.note() == 0)
                            .findFirst()
                            .orElseThrow();
            assertEquals(2, producer.detectorBeams());
            assertEquals(0, producer.detectorValue(), 0);
            assertEquals(2, producer.finalBeams());
            assertEquals(0, producer.finalValue(), 0);
            for (String stale :
                    List.of(
                            "CLOSED_WRITTEN_POCKET_HALF_OVERRIDE",
                            "POST_DETECTOR_VALUE_OR_BEAM_CHANGED",
                            "FINAL_EVENT_VALUE_OR_BEAM_DIFFERS_FROM_DETECTOR"))
                assertFalse(
                        name + " stale lineage " + stale, producer.admissions().contains(stale));
            var original =
                    analysis.clockProducerSource().localAccidentals().choices().stream()
                            .filter(v -> v.note() == 0)
                            .findFirst()
                            .orElseThrow()
                            .originalNote();
            assertEquals(2, original.beamCount());
            assertEquals(0, original.unbeamedDurationBeats(), 0);
        }
        String canonical = OriginalBeamAnalysisSnapshot.json(analysis);
        String actual =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        if (!expected.equals(actual)) {
            var diagnostic = Files.createTempFile("beam-" + name + "-", ".json");
            Files.writeString(diagnostic, canonical, StandardCharsets.UTF_8);
            fail(
                    name
                            + " Analysis changed; notes="
                            + analysis.notes()
                            + "; canonical JSON="
                            + diagnostic
                            + "; expected="
                            + expected
                            + " actual="
                            + actual);
        }
    }
}
