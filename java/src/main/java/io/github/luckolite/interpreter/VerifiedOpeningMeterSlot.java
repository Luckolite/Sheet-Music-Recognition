// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** Removes a clock slot that contains only a verified opening key and time signature. */
final class VerifiedOpeningMeterSlot {
    private VerifiedOpeningMeterSlot() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions,
            byte[] labels,
            byte[] gray,
            byte[] original,
            int width,
            int height) {
        if (regions.size() < 3) return regions;
        List<MeterChangeDetector.Crop> crops = null;
        boolean hasMeterInk = false;
        for (int index = 1; index + 1 < regions.size(); index++) {
            var slot = regions.get(index);
            var next = regions.get(index + 1);
            if (!plausibleSlot(regions.get(index - 1), slot, next, width, height)) continue;
            if (crops == null) crops = MeterChangeDetector.candidates(labels, gray, width, height);
            float gap = (slot.bottom() - slot.top()) * height / 8.2f;
            for (var crop : crops) {
                if (!fitsSlot(
                        crop,
                        slot,
                        slot.left() * width,
                        slot.right() * width,
                        next.left() * width,
                        gap,
                        height)) continue;
                var reading = MeterFontMatcher.readCrop(gray, width, height, crop);
                if (!reading.text().isBlank()
                        && reading.topScore() >= .84f
                        && reading.bottomScore() >= .84f
                        && matches(reading.text(), original, width, height, crop)) {
                    hasMeterInk = true;
                    break;
                }
            }
            if (hasMeterInk) break;
        }
        if (!hasMeterInk) return regions;
        // Re-analyze the retained geometry in the caller so every source-owned mark uses its new
        // measure index.
        var trial = OmrScoreInterpreter.analyze(labels, gray, width, height, regions, original);
        var meters =
                MeterFontMatcher.standaloneReadings(
                        labels, gray, width, height, regions, trial.notes());
        if (meters.isEmpty()) return regions;
        return apply(
                        new ScorePageInterpretation(
                                regions,
                                trial.notes(),
                                0,
                                trial.keyChanges(),
                                List.of(),
                                meters,
                                trial.rests()),
                        labels,
                        gray,
                        original,
                        width,
                        height,
                        crops)
                .measures();
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            byte[] labels,
            byte[] gray,
            byte[] original,
            int width,
            int height) {
        return apply(score, labels, gray, original, width, height, null);
    }

    private static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            byte[] labels,
            byte[] gray,
            byte[] original,
            int width,
            int height,
            List<MeterChangeDetector.Crop> crops) {
        if (score.measures().size() < 3
                || width <= 0
                || height <= 0
                || labels == null
                || gray == null
                || original == null
                || labels.length != (long) width * height
                || gray.length != labels.length
                || original.length != labels.length) return score;
        for (int cut = score.measures().size() - 2; cut > 0; cut--) {
            var bars = score.measures();
            var slot = bars.get(cut);
            var previous = bars.get(cut - 1);
            var music = bars.get(cut + 1);
            float gap = (slot.bottom() - slot.top()) * height / 8.2f;
            float left = slot.left() * width,
                    right = slot.right() * width,
                    next = music.left() * width;
            if (!plausibleSlot(previous, slot, music, width, height)) continue;
            final int index = cut;
            var meter =
                    score.meterChanges().stream()
                            .filter(m -> m.measureIndex() == index + 1)
                            .findFirst()
                            .orElse(null);
            if (meter == null
                    || timedContent(score, index)
                    || score.meterChanges().stream().anyMatch(m -> m.measureIndex() == index))
                continue;
            int fifths = 0;
            for (var key : score.keyChanges()) if (key.measureIndex() <= cut) fifths = key.fifths();
            if (fifths >= 0
                    || fifths < -7
                    || score.keyChanges().stream().anyMatch(k -> k.measureIndex() == index + 1))
                continue;
            float first = Float.POSITIVE_INFINITY;
            for (var note : score.notes()) {
                if (note.measureIndex() != cut + 1) continue;
                if (note.staffIndex() != 0
                        || note.staffCount() != 1
                        || note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_TREBLE) {
                    first = Float.NEGATIVE_INFINITY;
                    break;
                }
                first =
                        Math.min(
                                first,
                                (music.left()
                                                + note.positionInMeasure()
                                                        * (music.right() - music.left()))
                                        * width);
            }
            if (!Float.isFinite(first) || first - next < gap || first - next > gap * 4) continue;
            if (crops == null) crops = MeterChangeDetector.candidates(labels, gray, width, height);
            if (proves(
                    crops, meter, slot, left, right, next, first, gap, -fifths, labels, gray,
                    original, width, height)) score = CourtesyMeasureRemap.remove(score, cut);
        }
        return score;
    }

    private static boolean plausibleSlot(
            MeasureRegion previous,
            MeasureRegion slot,
            MeasureRegion music,
            int width,
            int height) {
        float gap = (slot.bottom() - slot.top()) * height / 8.2f;
        float span = (slot.right() - slot.left()) * width;
        float margin = (music.left() - slot.right()) * width;
        return gap >= 10
                && gap <= 25
                && span >= gap * 1.6f
                && span <= gap * 4.6f
                && (slot.top() - previous.bottom()) * height >= gap * 2
                && Math.abs(slot.top() - music.top()) * height <= gap * .4f
                && Math.abs(slot.bottom() - music.bottom()) * height <= gap * .4f
                && margin >= gap * .4f
                && margin <= gap * 1.5f
                && slot.cadenceQuarterBeats() == 0
                && music.cadenceQuarterBeats() == 0;
    }

    private static boolean timedContent(ScorePageInterpretation score, int cut) {
        return score.notes().stream().anyMatch(n -> n.measureIndex() == cut)
                || score.rests().stream().anyMatch(r -> r.measureIndex() == cut)
                || score.tempoChanges().stream().anyMatch(t -> t.measureIndex() == cut)
                || score.techniqueChanges().stream().anyMatch(t -> t.measureIndex() == cut)
                || score.dynamicChanges().stream()
                        .anyMatch(d -> d.measureIndex() == cut || d.endMeasureIndex() == cut)
                || score.playbackDirections().stream()
                        .anyMatch(
                                d ->
                                        d.measureBoundary() == cut
                                                || d.details()
                                                        .end()
                                                        .filter(a -> a.measureIndex() == cut)
                                                        .isPresent())
                || score.expressiveEvents().stream()
                        .anyMatch(
                                e ->
                                        e.start().filter(a -> a.measureIndex() == cut).isPresent()
                                                || e.end()
                                                        .filter(a -> a.measureIndex() == cut)
                                                        .isPresent())
                || score.multiRestGroups().stream()
                        .anyMatch(
                                g ->
                                        g.firstMeasureIndex() <= cut
                                                && g.firstMeasureIndex() + g.measureCount() > cut);
    }

    private static boolean proves(
            List<MeterChangeDetector.Crop> crops,
            ScoreMeterChange meter,
            MeasureRegion slot,
            float left,
            float right,
            float next,
            float first,
            float gap,
            int flats,
            byte[] labels,
            byte[] gray,
            byte[] original,
            int width,
            int height) {
        var printed = Integer.toString(meter.numerator()) + '/' + meter.denominator();
        MeterChangeDetector.Crop owner = null;
        for (var crop : crops) {
            if (!fitsSlot(crop, slot, left, right, next, gap, height)
                    || first - crop.right() < gap * .8f
                    || !matches(printed, gray, width, height, crop)
                    || !matches(printed, original, width, height, crop)) continue;
            if (owner != null && !owner.equals(crop)) return false;
            owner = crop;
        }
        if (owner == null) return false;
        int top = owner.firstLine(), bottom = Math.round(top + 4 * owner.gap());
        if (top < 4 || bottom + 4 >= height) return false;
        for (int x = owner.left(); x < owner.right(); x++) {
            if (continuousStroke(original, width, x, top, bottom)
                    || continuousStroke(gray, width, x, top, bottom)) return false;
        }

        for (int x = owner.right(); x < first - gap * .55f; x++) {
            if (OmrMeasurePostProcessor.verticalRuleAt(gray, width, x, top, bottom, owner.gap())
                    || OmrMeasurePostProcessor.verticalRuleAt(
                            original, width, x, top, bottom, owner.gap())) return false;
        }
        int firstColumn = Math.max(8, Math.round(left - gap * 11));
        int lastColumn = Math.round(left - gap * 3.5f);
        int margin = Math.round(gap * .58f);
        for (int column = Math.max(margin, firstColumn); column < lastColumn; column++) {
            if (ruleCount(original, width, height, column, top, owner.gap()) < 4
                    || ruleCount(original, width, height, column - margin, top, owner.gap()) > 1)
                continue;
            if (OmrScoreInterpreter.inheritedFlatHeaderStart(
                                    original,
                                    labels,
                                    width,
                                    height,
                                    column,
                                    owner.left(),
                                    bottom,
                                    owner.gap(),
                                    flats)
                            >= 0
                    && OmrScoreInterpreter.inheritedFlatHeaderStart(
                                    gray,
                                    labels,
                                    width,
                                    height,
                                    column,
                                    owner.left(),
                                    bottom,
                                    owner.gap(),
                                    flats)
                            >= 0) return true;
        }
        return false;
    }

    private static boolean fitsSlot(
            MeterChangeDetector.Crop crop,
            MeasureRegion slot,
            float left,
            float right,
            float next,
            float gap,
            int height) {
        return crop.left() >= left + gap * .4f
                && crop.left() <= right - gap * .15f
                && crop.right() >= next - gap * .25f
                && crop.right() <= next + gap * .8f
                && crop.top() >= slot.top() * height
                && crop.bottom() <= slot.bottom() * height
                && Math.abs(crop.gap() - gap) <= gap * .12f;
    }

    private static boolean continuousStroke(byte[] gray, int width, int x, int top, int bottom) {
        for (int y = top + 1; y < bottom; y++) if ((gray[y * width + x] & 255) >= 155) return false;
        return true;
    }

    private static boolean matches(
            String printed, byte[] gray, int width, int height, MeterChangeDetector.Crop crop) {
        var reading = MeterFontMatcher.readCrop(gray, width, height, crop);
        return printed.equals(reading.text())
                && reading.topScore() >= .84f
                && reading.bottomScore() >= .84f;
    }

    private static int ruleCount(byte[] gray, int width, int height, int x, int top, float gap) {
        int count = 0;
        for (int i = 0; i < 5; i++) {
            int center = Math.round(top + i * gap);
            for (int y = center - 2; y <= center + 2; y++) {
                if (y >= 0 && y < height && (gray[y * width + x] & 255) < 155) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }
}
