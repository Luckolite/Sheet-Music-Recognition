// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Page-local agreement supplements, but never replaces, the generic glyph evidence. */
final class PageTrillEvidence {
    private record Sample(PortableNoteOrnaments.Bounds bounds, PortableOrnamentGlyphs glyph) {}

    private final List<Sample> samples = new ArrayList<>();
    private final Set<PortableNoteOrnaments.Bounds> confirmed = new HashSet<>();

    PageTrillEvidence(
            PortableOrnamentGlyphs recognizer,
            byte[] gray,
            int width,
            int height,
            List<PortableNoteOrnaments.Bounds> boxes,
            List<PlayingTechniqueDetector.Word> words) {
        Map<PortableNoteOrnaments.Bounds, Boolean> trillBoxes = null;
        for (var word : words) {
            if (!word.text().equals("tr")
                    || !ScoreDynamicsDetector.containsInk(word, gray, width, height)) continue;
            float left = word.left() * width,
                    right = word.right() * width,
                    top = word.top() * height,
                    bottom = word.bottom() * height;
            float wordArea = (right - left) * (bottom - top);
            PortableNoteOrnaments.Bounds best = null;
            float area = 0;
            for (var box : boxes) {
                float size = box.width() * box.height();
                if (size <= area || wordArea > size * 3.5f) continue;
                float overlap =
                        Math.max(0, Math.min(right, box.right) - Math.max(left, box.left))
                                * Math.max(
                                        0, Math.min(bottom, box.bottom) - Math.max(top, box.top));
                if (overlap < size * .9f) continue;
                if (trillBoxes == null) trillBoxes = new HashMap<>();
                Boolean trill = trillBoxes.get(box);
                if (trill == null) {
                    var weak = recognizer.match(gray, width, box);
                    trill = weak.kind() == NoteOrnament.TRILL && !(weak.score() < .30f);
                    trillBoxes.put(box, trill);
                }
                if (!trill) continue;
                area = size;
                best = box;
            }
            if (best == null || !confirmed.add(best) || samples.size() >= 32) continue;
            byte[] crop = new byte[best.width() * best.height()];
            for (int y = 0; y < best.height(); y++)
                System.arraycopy(
                        gray,
                        (best.top + y) * width + best.left,
                        crop,
                        y * best.width(),
                        best.width());
            var glyph = new PortableOrnamentGlyphs();
            glyph.add(crop, best.width(), best.height(), NoteOrnament.TRILL, false);
            samples.add(new Sample(best, glyph));
        }
    }

    boolean confirmed(PortableNoteOrnaments.Bounds box) {
        return confirmed.contains(box);
    }

    boolean canRecognize() {
        return samples.size() >= 2;
    }

    boolean recognizes(
            byte[] gray,
            int width,
            PortableNoteOrnaments.Bounds box,
            PortableOrnamentGlyphs.Match weak) {
        return recognizes(gray, width, box, weak, null);
    }

    boolean recognizes(
            byte[] gray,
            int width,
            PortableNoteOrnaments.Bounds box,
            PortableOrnamentGlyphs.Match weak,
            PortableOrnamentGlyphs.Query query) {
        if (weak.kind() != NoteOrnament.TRILL || weak.score() < .30f || !canRecognize())
            return false;
        int agreements = 0;
        for (int i = 0; i < samples.size() && agreements + (samples.size() - i) >= 2; i++) {
            var sample = samples.get(i);
            // The same OCR hit seen in two crop passes is not independent evidence.
            if (sample.bounds.equals(box)) continue;
            if (query == null) query = new PortableOrnamentGlyphs.Query();
            if (sample.glyph.match(gray, width, box, query).score() >= .82f && ++agreements >= 2)
                return true;
        }
        return false;
    }
}
