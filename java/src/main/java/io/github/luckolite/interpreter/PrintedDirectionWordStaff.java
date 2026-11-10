// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** Bounded word-interior fallback; both positions must independently prove the same five rails. */
final class PrintedDirectionWordStaff {
    static PlayingTechniqueDetector.Staff find(
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Word word) {
        float left = word.left() * width, right = word.right() * width;
        if (!Float.isFinite(left) || !Float.isFinite(right) || right <= left) return null;
        var center =
                PrintedDirectionStaff.at(
                        staffs,
                        measures,
                        notes,
                        gray,
                        width,
                        height,
                        (left + right) * .5f,
                        word.top() * height,
                        word.bottom() * height);
        if (center == null || right - left > center.gap() * 6 || right - left < center.gap() * .5f)
            return null;
        var edge =
                PrintedDirectionStaff.at(
                        staffs,
                        measures,
                        notes,
                        gray,
                        width,
                        height,
                        right,
                        word.top() * height,
                        word.bottom() * height);
        if (edge == null
                || center.index() != edge.index()
                || center.count() != edge.count()
                || Math.abs(center.bottom() - edge.bottom()) > center.gap() * .2f
                || Math.abs(center.gap() - edge.gap()) > center.gap() * .08f) return null;
        return center;
    }

    /** A short tempo instruction can straddle an interruption in the staff scan. */
    static PlayingTechniqueDetector.Staff nearTempoWord(
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Word word) {
        float left = word.left() * width, span = (word.right() - word.left()) * width;
        if (!Float.isFinite(left) || !Float.isFinite(span) || span <= 0) return null;
        float top = word.top() * height, bottom = word.bottom() * height;
        PlayingTechniqueDetector.Staff proof = null;
        float firstX = 0;
        boolean corroborated = false;
        for (float offset : new float[] {.5f, .625f, .125f, .875f, 1, -.5f, -.75f}) {
            float x = left + span * offset;
            var owner = PrintedDirectionStaff.at(
                    staffs, measures, notes, gray, width, height, x, top, bottom);
            if (owner == null || span > owner.gap() * 8 || span < owner.gap() * .5f
                    || bottom > owner.top() - owner.gap() * .25f) continue;
            if (proof == null) {
                proof = owner;
                firstX = x;
                continue;
            }
            if (proof.index() != owner.index() || proof.count() != owner.count()
                    || Math.abs(proof.bottom() - owner.bottom()) > proof.gap() * .35f
                    || Math.abs(proof.gap() - owner.gap()) > proof.gap() * .08f) return null;
            if (Math.abs(x - firstX) >= proof.gap() * .5f) corroborated = true;
        }
        return corroborated ? proof : null;
    }
}
