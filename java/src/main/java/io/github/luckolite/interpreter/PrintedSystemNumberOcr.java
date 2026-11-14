// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small system-number crops follow independently verified printed five-line groups. */
final class PrintedSystemNumberOcr {
    record Crop(int left, int top, int right, int bottom, int systemMeasure) {}

    record Raster(byte[] pixels, int width, int height) {}

    record Reading(int value, int systemMeasure) {}

    record Bounds(float left, float top, float right, float bottom) {}

    interface Reader {
        String read(byte[] gray, int width, int height) throws Exception;
    }

    interface TextReader {
        OcrText read(byte[] gray, int width, int height) throws Exception;
    }

    private static final int[] OCR_SCALES = {3, 5, 7};

    private PrintedSystemNumberOcr() {}

    static List<Crop> candidates(
            byte[] labels, byte[] gray, int width, int height, List<MeasureRegion> measures) {
        if (labels == null
                || gray == null
                || width <= 0
                || height <= 0
                || (long) width * height != labels.length
                || gray.length != labels.length
                || measures == null
                || measures.isEmpty()) return List.of();
        Map<Integer, Crop> cropsByMeasure = new LinkedHashMap<>();
        for (var seed : RegionalStaffSeeds.detect(labels, gray, width, height)) {
            var track = RegionalStaffSeeds.track(labels, gray, width, height, seed);
            if (track == null || !track.verified()) continue;
            int first = -1;
            float firstLeft = 0;
            for (int i = 0; i < measures.size(); i++) {
                var measure = measures.get(i);
                if (first >= 0 && !(measure.left() < firstLeft)) continue;
                float x = (measure.left() + measure.right()) * width * .5f;
                float[] frame = track.at(x);
                float middle = frame[0] - frame[1] * 2;
                if (middle < measure.top() * height || middle > measure.bottom() * height) continue;
                first = i;
                firstLeft = measure.left();
            }
            if (first < 0) continue;
            float firstX = firstLeft * width;
            float gap = track.at(firstX)[1];
            int left = Math.max(0, Math.round(firstX - gap * 7.5f));
            int right = Math.min(width, Math.round(firstX - gap * 1.6f));
            float[] frame = track.at((left + right) * .5f);
            float localTop = frame[0] - frame[1] * 4;
            int top = Math.max(0, Math.round(localTop - gap * 4.7f));
            int bottom = Math.min(height, Math.round(localTop - gap * 1.5f));
            if (right - left < 8 || bottom - top < 8) continue;
            // A connected system has one numbering anchor above its highest staff.
            Crop previous = cropsByMeasure.get(first);
            if (previous != null && top >= previous.top) continue;
            Crop crop = includeCompleteLeftInk(gray, width, height,
                    new Crop(left, top, right, bottom, first), gap);
            // Reinsert replacements to preserve encounter order for equal tops.
            cropsByMeasure.remove(first);
            cropsByMeasure.put(first, crop);
        }
        var result = new ArrayList<>(cropsByMeasure.values());
        result.sort(Comparator.comparingInt(Crop::top));
        return List.copyOf(result);
    }

    /**
     * Recover one complete raw-ink component clipped at the left crop edge.
     * Strict <145 matches existing global symbol-component semantics. This
     * changes only crop bounds; OCR thresholds, image samples, and readings stay fixed.
     */
    private static Crop includeCompleteLeftInk(
            byte[] gray, int width, int height, Crop crop, float staffGap) {
        if (staffGap <= 0 || crop.left <= 0) return crop;
        int haloLeft = Math.max(0, crop.left - Math.max(1, (int) Math.ceil(staffGap * 2f)));
        int haloTop = crop.top, haloRight = crop.right, haloBottom = crop.bottom;
        int regionWidth = haloRight - haloLeft, regionHeight = haloBottom - haloTop;
        if (regionWidth <= 0 || regionHeight <= 0) return crop;
        long area = (long) regionWidth * regionHeight;
        if (area > 1_000_000) return crop;
        boolean[] seen = null;
        int[] queue = null;
        int expandedLeft = -1;
        int maxWidth = Math.max(5, Math.round(staffGap * 2f));
        int minHeight = Math.max(5, Math.round(staffGap * .8f));
        int maxHeight = Math.max(minHeight, Math.round(staffGap * 3f));
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        int sx = crop.left - haloLeft;
        // Every 8-connected component crossing the crop edge must visit this column.
        for (int sy = 1; sy < regionHeight - 1; sy++) {
            int start = sy * regionWidth + sx;
            if ((gray[(haloTop + sy) * width + crop.left] & 255) >= 145
                    || (seen != null && seen[start])) continue;
            if (seen == null) {
                seen = new boolean[(int) area];
                queue = new int[(int) area];
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int minX = sx, maxX = sx, minY = sy, maxY = sy;
            boolean touchesHalo = false;
            while (head < tail) {
                int at = queue[head++], x = at % regionWidth, y = at / regionWidth;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (x == 0 || x == regionWidth - 1 || y == 0 || y == regionHeight - 1)
                    touchesHalo = true;
                for (int k = 0; k < dx.length; k++) {
                    int nx = x + dx[k], ny = y + dy[k];
                    if (nx < 0 || ny < 0 || nx >= regionWidth || ny >= regionHeight) continue;
                    int next = ny * regionWidth + nx;
                    if (!seen[next]
                            && (gray[(haloTop + ny) * width + haloLeft + nx] & 255) < 145) {
                        seen[next] = true;
                        queue[tail++] = next;
                    }
                }
            }
            int componentWidth = maxX - minX + 1, componentHeight = maxY - minY + 1;
            boolean crosses = haloLeft + minX < crop.left && haloLeft + maxX >= crop.left;
            if (!touchesHalo
                    && crosses
                    && componentWidth <= maxWidth
                    && componentHeight >= minHeight
                    && componentHeight <= maxHeight) {
                if (expandedLeft >= 0) return crop;
                expandedLeft = haloLeft + minX - 1;
            }
        }
        return expandedLeft >= 0 && expandedLeft < crop.left
                ? new Crop(
                        Math.max(0, expandedLeft),
                        crop.top,
                        crop.right,
                        crop.bottom,
                        crop.systemMeasure)
                : crop;
    }

    static Raster raster(byte[] gray, int width, int height, Crop crop, int scale) {
        if (gray == null
                || width <= 0
                || height <= 0
                || (long) width * height != gray.length
                || crop == null
                || crop.left < 0
                || crop.top < 0
                || crop.right > width
                || crop.bottom > height
                || crop.right <= crop.left
                || crop.bottom <= crop.top
                || scale < 1
                || scale > 7) throw new IllegalArgumentException("Invalid number crop");
        int w = crop.right - crop.left, h = crop.bottom - crop.top, border = 20;
        int outWidth = w * scale + border * 2, outHeight = h * scale + border * 2;
        if ((long) outWidth * outHeight > 2_000_000)
            throw new IllegalArgumentException("Number crop too large");
        byte[] pixels = new byte[outWidth * outHeight];
        Arrays.fill(pixels, (byte) 255);
        int scaledWidth = w * scale;
        int dest = border * outWidth + border;
        for (int y = crop.top; y < crop.bottom; y++) {
            int source = y * width + crop.left;
            int rowEnd = source + w;
            if (scale == 1) {
                System.arraycopy(gray, source, pixels, dest, w);
            } else {
                for (int x = dest; source < rowEnd; source++, x += scale)
                    Arrays.fill(pixels, x, x + scale, gray[source]);
                for (int dy = 1; dy < scale; dy++) {
                    System.arraycopy(pixels, dest, pixels, dest + dy * outWidth, scaledWidth);
                }
            }
            dest += scale * outWidth;
        }
        return new Raster(pixels, outWidth, outHeight);
    }

    private static int positiveNumber(String text) {
        if (text == null) return 0;
        String digits = text.trim();
        if (digits.isEmpty() || digits.length() > 3) return 0;
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            if (digit < 0 || digit > 9) return 0;
            value = 10 * value + digit;
        }
        return value;
    }

    static int agreedNumber(List<String> readings) {
        if (readings == null) return 0;
        Map<Integer, Integer> counts = new HashMap<>();
        for (String text : readings) {
            int value = positiveNumber(text);
            if (value > 0) counts.merge(value, 1, Integer::sum);
        }
        int best = 0, votes = 1;
        boolean tied = false;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > votes) {
                best = entry.getKey();
                votes = entry.getValue();
                tied = false;
            } else if (entry.getValue() == votes) tied = true;
        }
        return tied ? 0 : best;
    }

    static List<Reading> read(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            Reader reader)
            throws Exception {
        if (reader == null) return List.of();
        List<Reading> result = new ArrayList<>();
        for (Crop crop : candidates(labels, gray, width, height, measures)) {
            List<String> readings = new ArrayList<>();
            for (int scale : OCR_SCALES) {
                Raster raster = raster(gray, width, height, crop, scale);
                readings.add(reader.read(raster.pixels, raster.width, raster.height));
            }
            int value = agreedNumber(readings);
            if (value != 0) result.add(new Reading(value, crop.systemMeasure));
        }
        return List.copyOf(result);
    }

    /** Reads source-box evidence for only header crops not already covered by a printed token. */
    static List<SheetInterpreter.NumberToken> supplement(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            List<SheetInterpreter.NumberToken> existing,
            TextReader reader)
            throws Exception {
        long pageArea = (long) width * height;
        if (reader == null
                || labels == null
                || gray == null
                || width <= 0
                || height <= 0
                || pageArea > 20_000_000L
                || pageArea != labels.length
                || gray.length != labels.length) return List.of();
        var occupied = new ArrayList<Bounds>();
        if (existing != null)
            for (var token : existing) {
                if (token == null
                        || !Float.isFinite(token.left())
                        || !Float.isFinite(token.top())
                        || !Float.isFinite(token.right())
                        || !Float.isFinite(token.bottom())
                        || token.left() < 0
                        || token.top() < 0
                        || token.right() > 1
                        || token.bottom() > 1
                        || token.right() <= token.left()
                        || token.bottom() <= token.top()) return List.of();
                occupied.add(new Bounds(token.left(), token.top(), token.right(), token.bottom()));
            }
        var result = new ArrayList<SheetInterpreter.NumberToken>();
        for (Crop crop : candidates(labels, gray, width, height, measures)) {
            float left = crop.left / (float) width, top = crop.top / (float) height;
            float right = crop.right / (float) width, bottom = crop.bottom / (float) height;
            if (occupied.stream()
                    .anyMatch(
                            b ->
                                    overlaps(
                                            left, top, right, bottom, b.left, b.top, b.right,
                                            b.bottom))) continue;
            var reads = new ArrayList<OcrText>();
            for (int scale : OCR_SCALES) {
                Raster raster = raster(gray, width, height, crop, scale);
                reads.add(reader.read(raster.pixels, raster.width, raster.height));
            }
            var reading = resolve(crop, width, height, reads);
            if (reading == null) continue;
            result.add(reading);
            occupied.add(
                    new Bounds(reading.left(), reading.top(), reading.right(), reading.bottom()));
        }
        return List.copyOf(result);
    }

    /** Resolves only a single numeric element with a two-scale value and box consensus. */
    static SheetInterpreter.NumberToken resolve(
            Crop crop, int width, int height, List<OcrText> reads) {
        if (crop == null
                || width <= 0
                || height <= 0
                || (long) width * height > 20_000_000L
                || reads == null
                || reads.size() != 3
                || crop.systemMeasure < 0
                || crop.left < 0
                || crop.top < 0
                || crop.right > width
                || crop.bottom > height
                || crop.right <= crop.left
                || crop.bottom <= crop.top
                || (long) ((crop.right - crop.left) * 7 + 40) * ((crop.bottom - crop.top) * 7 + 40)
                        > 2_000_000L) return null;
        var valid = new ArrayList<SheetInterpreter.NumberToken>();
        for (int i = 0; i < reads.size(); i++) {
            OcrText text = reads.get(i);
            if (text == null
                    || text.text().length() > 256
                    || text.blocks().isEmpty()
                    || text.blocks().size() > 8) continue;
            String fullText = text.text().trim();
            int value = positiveNumber(fullText);
            if (value == 0) continue;
            OcrText.Element element = singleNumberElement(text, fullText);
            if (element == null) continue;
            OcrText.Box box = element.box();
            if (box == null || box.width() <= 0 || box.height() <= 0)
                continue;
            int scale = OCR_SCALES[i], border = 20;
            int contentRight = border + (crop.right - crop.left) * scale;
            int contentBottom = border + (crop.bottom - crop.top) * scale;
            if (box.left < border
                    || box.top < border
                    || box.right > contentRight
                    || box.bottom > contentBottom) continue;
            float left = (crop.left + (box.left - border) / (float) scale) / width;
            float top = (crop.top + (box.top - border) / (float) scale) / height;
            float right = (crop.left + (box.right - border) / (float) scale) / width;
            float bottom = (crop.top + (box.bottom - border) / (float) scale) / height;
            if (!Float.isFinite(left)
                    || !Float.isFinite(top)
                    || !Float.isFinite(right)
                    || !Float.isFinite(bottom)
                    || left < 0
                    || top < 0
                    || right > 1
                    || bottom > 1
                    || right <= left
                    || bottom <= top) continue;
            valid.add(new SheetInterpreter.NumberToken(value, left, top, right, bottom, left));
        }
        for (int i = 0; i < valid.size(); i++)
            for (int j = i + 1; j < valid.size(); j++) {
                var a = valid.get(i);
                var b = valid.get(j);
                if (a.value() != b.value()
                        || !overlaps(
                                a.left(),
                                a.top(),
                                a.right(),
                                a.bottom(),
                                b.left(),
                                b.top(),
                                b.right(),
                                b.bottom())) continue;
                float left = Math.max(a.left(), b.left()), top = Math.max(a.top(), b.top());
                float right = Math.min(a.right(), b.right()),
                        bottom = Math.min(a.bottom(), b.bottom());
                return new SheetInterpreter.NumberToken(a.value(), left, top, right, bottom, left);
            }
        return null;
    }

    private static OcrText.Element singleNumberElement(OcrText text, String fullText) {
        OcrText.Element element = null;
        int lineCount = 0;
        for (var block : text.blocks()) {
            lineCount += block.lines().size();
            if (block.text().length() > 256 || !fullText.equals(block.text().trim())
                    || lineCount > 32) return null;
            for (var line : block.lines()) {
                if (line.text().length() > 256 || !fullText.equals(line.text().trim()))
                    return null;
                if (line.elements().isEmpty()) continue;
                if (element != null || line.elements().size() != 1) return null;
                element = line.elements().get(0);
                if (element.text().length() > 256 || !fullText.equals(element.text().trim()))
                    return null;
            }
        }
        return element;
    }

    private static boolean overlaps(
            float al, float at, float ar, float ab, float bl, float bt, float br, float bb) {
        return Math.max(al, bl) < Math.min(ar, br) && Math.max(at, bt) < Math.min(ab, bb);
    }

    /** Independent physical system starts must agree with actual preceding written bars. */
    static int firstMeasureNumber(List<Reading> readings) {
        if (readings == null) return 0;
        Map<Integer, Set<Integer>> votes = new HashMap<>();
        for (Reading reading : readings) {
            if (reading == null
                    || reading.value <= 0
                    || reading.value > 999
                    || reading.systemMeasure < 0) continue;
            int first = reading.value - reading.systemMeasure;
            if (first < 1) continue;
            votes.computeIfAbsent(first, ignored -> new HashSet<>()).add(reading.systemMeasure);
        }
        int best = 0, support = 1;
        boolean tied = false;
        for (var entry : votes.entrySet()) {
            int count = entry.getValue().size();
            if (count > support) {
                best = entry.getKey();
                support = count;
                tied = false;
            } else if (count == support) tied = true;
        }
        return tied ? 0 : best;
    }

    static int firstMeasureNumber(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            List<MeasureRegion> measures,
            Reader reader)
            throws Exception {
        return firstMeasureNumber(read(labels, gray, width, height, measures, reader));
    }
}
