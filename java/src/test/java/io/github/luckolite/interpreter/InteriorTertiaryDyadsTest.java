// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Original five-column dyads with independently drawn staff rules, shafts, rails and hooks. */
public final class InteriorTertiaryDyadsTest {
    private static final int WIDTH = 400, HEIGHT = 230, GAP = 18, TIP = 174;
    private record Scene(byte[] gray, List<OwnedTertiaryBeamlets.Head> heads) {}

    @Test
    public void interiorHookNeedsBothPlanesAndBothChordMembers() {
        var scene = scene(false);
        assertEquals(List.of(4, 5), thirds(scene.gray, scene.gray, scene.heads));
        var missing = new ArrayList<>(scene.heads);
        missing.remove(4);
        assertEquals(List.of(), thirds(scene.gray, scene.gray, missing));
    }

    @Test
    public void missingDottedRailUsesBothNeighboringOwnedHooks() {
        var scene = scene(true);
        assertEquals(List.of(4, 5), seconds(scene.gray, scene.gray, scene.heads));
        byte[] broken = scene.gray.clone();
        rectangle(broken, 215, 150, 222, 174, 255);
        assertEquals(List.of(), seconds(broken, scene.gray, scene.heads));
        assertEquals(List.of(), seconds(scene.gray, broken, scene.heads));
    }

    @Test
    public void aThirdChordMemberBlocksRecovery() {
        var scene = scene(false);
        var ambiguous = new ArrayList<>(scene.heads);
        var old = ambiguous.get(4);
        ambiguous.add(6, new OwnedTertiaryBeamlets.Head(10, old.note(), old.x(), old.y() - GAP, GAP));
        assertEquals(List.of(), thirds(scene.gray, scene.gray, ambiguous));
    }

    @Test
    public void tupletAndOpposingStemPreserveTheirOwnRhythm() {
        var scene = scene(false);
        var heads = new ArrayList<>(scene.heads);
        var original = heads.get(4);
        heads.set(4, new OwnedTertiaryBeamlets.Head(4, original.note().withTupletRatio(3, 2),
                original.x(), original.y(), GAP));
        assertEquals(List.of(), thirds(scene.gray, scene.gray, heads));
        heads.set(4, new OwnedTertiaryBeamlets.Head(4, original.note().withStemDirection(1),
                original.x(), original.y(), GAP));
        assertEquals(List.of(), thirds(scene.gray, scene.gray, heads));
    }

    @Test
    public void onePlaneHookErasureRejectsTheShortDuration() {
        var scene = scene(false);
        byte[] erased = scene.gray.clone();
        rectangle(erased, 174, 132, 187, 146, 255);
        assertEquals(List.of(), thirds(erased, scene.gray, scene.heads));
        assertEquals(List.of(), thirds(scene.gray, erased, scene.heads));
    }

    @Test
    public void existingThirdRailsAndDottedPeersArePreserved() {
        var scene = scene(false);
        var heads = new ArrayList<>(scene.heads);
        for (int at : new int[]{4, 5}) {
            var h = heads.get(at);
            var n = note(h.x(), Math.round(h.y()), 3, 0);
            heads.set(at, new OwnedTertiaryBeamlets.Head(h.index(), n, h.x(), h.y(), GAP));
        }
        assertEquals(List.of(), thirds(scene.gray, scene.gray, heads));
        assertEquals(List.of(), seconds(scene.gray, scene.gray, heads));
    }

    private static List<Integer> thirds(byte[] raw, byte[] normalized, List<OwnedTertiaryBeamlets.Head> heads) {
        return OwnedTertiaryBeamlets.recoverInteriorDyads(raw, normalized, WIDTH, HEIGHT, heads);
    }

    private static List<Integer> seconds(byte[] raw, byte[] normalized, List<OwnedTertiaryBeamlets.Head> heads) {
        return OwnedTertiaryBeamlets.recoverDottedDyadRails(raw, normalized, WIDTH, HEIGHT, heads);
    }

    private static Scene scene(boolean missingDottedRail) {
        byte[] gray = new byte[WIDTH * HEIGHT];
        Arrays.fill(gray, (byte) 255);
        for (int y : new int[]{72, 90, 108, 126, 144}) rectangle(gray, 20, y, 380, y, 0);
        rectangle(gray, 90, TIP - 7, 292, TIP, 0);
        rectangle(gray, 90, TIP - 23, 292, TIP - 14, 0);
        var heads = new ArrayList<OwnedTertiaryBeamlets.Head>();
        for (int column = 0; column < 5; column++) {
            int x = 100 + column * 50;
            boolean dotted = (column % 2 == 1) != missingDottedRail;
            int beams = dotted ? (column == 2 ? 1 : 2) : (column == 2 ? 2 : 3);
            rectangle(gray, x - 10, 90, x - 8, TIP, 0);
            if (!dotted) rectangle(gray, x - 25, TIP - 40, x - 8, TIP - 29, 0);
            for (int y : new int[]{90, 108}) {
                oval(gray, x, y);
                if (dotted) rectangle(gray, x + 14, y - 1, x + 16, y + 1, 0);
                heads.add(new OwnedTertiaryBeamlets.Head(heads.size(), note(x, y, beams, dotted ? 1 : 0),
                        x, y, GAP));
            }
        }
        return new Scene(gray, heads);
    }

    private static ScoreNoteEvent note(float x, int y, int beams, int dots) {
        return new ScoreNoteEvent(0, x / WIDTH, y == 90 ? 6 : 4, 0, 1, y / (float) HEIGHT,
                false, dots, beams, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0).withStemDirection(-1);
    }

    private static void oval(byte[] gray, int centerX, int centerY) {
        for (int y = -5; y <= 5; y++)
            for (int x = -10; x <= 10; x++)
                if (x * x + 4 * y * y <= 100) gray[(centerY + y) * WIDTH + centerX + x] = 0;
    }

    private static void rectangle(byte[] gray, int left, int top, int right, int bottom, int value) {
        for (int y = top; y <= bottom; y++)
            Arrays.fill(gray, y * WIDTH + left, y * WIDTH + right + 1, (byte) value);
    }
}
