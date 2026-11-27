// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** Corrects decoder paper and faded ink while leaving the segmentation raster authoritative. */
public final class PaperBackgroundWhitening {
    public static final String MODEL_ASSET = "paper_field_v1.onnx";
    public static final String MODEL_SHA256 =
            "dc27aec9f1d29cc423510277992523991fbbb568770a3acf73875107476ab460";
    public static final String RASTER_POLICY = "original-segmentation-paper-field-decoder-v2";
    private static final int MAX_PIXELS = 20_000_000;
    private static final int FIELD_SIDE = 960;
    private static final int RADIUS = 12;
    private static final int MIN_FADED_TONE = 96;
    private static final int MAX_FADED_TONE = 220;
    private static final int MIN_PAPER_CONTRAST = 32;

    public interface Inference {
        Fields run(float[] features, int width, int height) throws Exception;
    }

    public record Fields(float[][] paper, float[][] contrast) {}

    public record Result(
            byte[] decoderGray, boolean changed, String rawSha256, String decoderSha256) {}

    private PaperBackgroundWhitening() {}

    public static Result apply(byte[] original, int width, int height, Inference inference)
            throws Exception {
        return apply(original, null, width, height, inference);
    }

    public static Result apply(
            byte[] original, byte[] labels, int width, int height, Inference inference)
            throws Exception {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(inference, "inference");
        long size = (long) width * height;
        if (width < 1 || height < 1 || size > MAX_PIXELS || size != original.length) {
            throw new IllegalArgumentException("Invalid grayscale dimensions");
        }
        if (labels != null && labels.length != size) {
            throw new IllegalArgumentException("Notation labels must match grayscale dimensions");
        }
        String rawHash = rasterSha256(original, width, height);
        byte[] fullPaper = bright(original) ? null : close(original, width, height);
        if (fullPaper == null || bright(fullPaper)) {
            byte[] output = darkenFadedInk(original, labels, width, height, fullPaper);
            return result(original, output, width, height, rawHash);
        }
        double scale = Math.min(1., FIELD_SIDE / (double) Math.max(width, height));
        int fieldWidth = Math.max(8, (int) Math.rint(width * scale));
        int fieldHeight = Math.max(8, (int) Math.rint(height * scale));
        float[] small = resizeArea(original, width, height, fieldWidth, fieldHeight);
        float[] paperEstimate = close(small, fieldWidth, fieldHeight);
        int fieldSize = fieldWidth * fieldHeight;
        float[] features = new float[2 * fieldSize];
        for (int i = 0; i < fieldSize; i++) {
            features[i] = small[i] / 255f;
            features[fieldSize + i] = paperEstimate[i] / 255f;
        }
        Fields fields = inference.run(features, fieldWidth, fieldHeight);
        validate(fields.paper(), fieldWidth, fieldHeight, .23f);
        validate(fields.contrast(), fieldWidth, fieldHeight, .35f);
        byte[] output = new byte[original.length];
        int[] left = new int[width];
        int[] right = new int[width];
        float[] fraction = new float[width];
        for (int x = 0; x < width; x++) {
            double position = Math.max(0., (x + .5) * fieldWidth / width - .5);
            left[x] = (int) position;
            right[x] = Math.min(fieldWidth - 1, left[x] + 1);
            fraction[x] = (float) (position - left[x]);
        }
        for (int y = 0; y < height; y++) {
            double position = Math.max(0., (y + .5) * fieldHeight / height - .5);
            int top = (int) position, bottom = Math.min(fieldHeight - 1, top + 1);
            float fy = (float) (position - top);
            float[] paperTop = fields.paper()[top], paperBottom = fields.paper()[bottom];
            float[] contrastTop = fields.contrast()[top], contrastBottom = fields.contrast()[bottom];
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                float paper = interpolate(paperTop, paperBottom, left[x], right[x], fraction[x], fy);
                float contrast = interpolate(contrastTop, contrastBottom, left[x], right[x], fraction[x], fy);
                float ink = Math.max(0f, 1f - (original[index] & 255) / (255f * paper));
                float restored = 255f * (1f - ink / contrast);
                int value = Math.max(0, Math.min(255, (int) Math.rint(restored)));
                output[index] = (byte) value;
            }
        }
        return result(
                original, darkenFadedInk(output, labels, width, height, null), width, height, rawHash);
    }

    private static Result result(byte[] original, byte[] output, int width, int height, String rawHash)
            throws Exception {
        if (output == original || Arrays.equals(output, original)) {
            return new Result(original, false, rawHash, rawHash);
        }
        return new Result(output, true, rawHash, rasterSha256(output, width, height));
    }

    private static byte[] darkenFadedInk(
            byte[] gray, byte[] labels, int width, int height, byte[] paper) {
        if (labels == null) return gray;
        byte[] output = gray;
        // Bound speculative work on dense pages; the full close handles the remainder.
        int localChecks = 1 + gray.length / 256;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int label = labels[index] & 255;
                int tone = gray[index] & 255;
                // The five foreground classes provide notation evidence. A nearby black
                // core means this gray is likely the antialiased edge of a crisp stroke.
                if (label < 1 || label > 5 || tone < MIN_FADED_TONE || tone >= MAX_FADED_TONE) {
                    continue;
                }
                int core = darkestNeighbor(gray, width, height, x, y);
                if (core < MIN_FADED_TONE) continue;
                if (paper == null && (localChecks-- == 0
                        || !oppositePaper(gray, width, height, x, y, tone + MIN_PAPER_CONTRAST)))
                    paper = close(gray, width, height);
                if (paper != null && (paper[index] & 255) - tone < MIN_PAPER_CONTRAST) continue;
                int gain = limitFadedInkGain(tone, core - MIN_FADED_TONE + 1);
                if (output == gray) output = gray.clone();
                output[index] = (byte) (tone - gain);
            }
        }
        return output;
    }

    static byte[] darkenFadedInk(byte[] gray, byte[] labels, int width, int height) {
        return darkenFadedInk(gray, labels, width, height, null);
    }

    static boolean hasFadedInkCandidates(byte[] gray, int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int tone = gray[y * width + x] & 255;
                if (tone >= MIN_FADED_TONE && tone < MAX_FADED_TONE
                        && darkestNeighbor(gray, width, height, x, y) >= MIN_FADED_TONE) return true;
            }
        }
        return false;
    }

    // Every dilation window participating in this pixel's closing contains the
    // pixel. Along its row (or column), that window includes the whole radius
    // on at least one side. Bright witnesses on both sides prove the threshold.
    private static boolean oppositePaper(byte[] gray, int width, int height, int x, int y,
            int threshold) {
        int index = y * width + x;
        return (paperOnRay(gray, index, -1, Math.min(RADIUS, x), threshold)
                        && paperOnRay(gray, index, 1, Math.min(RADIUS, width - 1 - x), threshold))
                || (paperOnRay(gray, index, -width, Math.min(RADIUS, y), threshold)
                        && paperOnRay(gray, index, width, Math.min(RADIUS, height - 1 - y), threshold));
    }

    private static boolean paperOnRay(byte[] gray, int index, int step, int length, int threshold) {
        for (int i = 0; i < length; i++) {
            index += step;
            if ((gray[index] & 255) >= threshold) return true;
        }
        return false;
    }

    static int limitFadedInkGain(int tone, int gain) {
        if (tone < MIN_FADED_TONE || tone >= MAX_FADED_TONE) return 0;
        return Math.min(gain, Math.min(MAX_FADED_TONE - tone, (255 - tone) / 2));
    }

    private static int darkestNeighbor(byte[] gray, int width, int height, int x, int y) {
        int darkest = 255;
        for (int row = Math.max(0, y - 1); row <= Math.min(height - 1, y + 1); row++) {
            for (int col = Math.max(0, x - 1); col <= Math.min(width - 1, x + 1); col++) {
                darkest = Math.min(darkest, gray[row * width + col] & 255);
            }
        }
        return darkest;
    }

    public static String rasterSha256(byte[] gray, int width, int height) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array());
        byte[] hash = digest.digest(gray);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : hash) {
            int unsigned = value & 255;
            hex.append(Character.forDigit(unsigned >>> 4, 16));
            hex.append(Character.forDigit(unsigned & 15, 16));
        }
        return hex.toString();
    }

    private static void validate(float[][] field, int width, int height, float minimum) {
        if (field == null || field.length != height) {
            throw new IllegalArgumentException("Invalid paper field height");
        }
        for (float[] row : field) {
            if (row == null || row.length != width) {
                throw new IllegalArgumentException("Invalid paper field width");
            }
            for (float value : row) {
                if (!Float.isFinite(value) || value < minimum - .00001f || value > 1.00001f) {
                    throw new IllegalArgumentException("Invalid paper field value");
                }
            }
        }
    }

    private static boolean bright(byte[] gray) {
        int[] histogram = new int[256];
        for (byte value : gray) histogram[value & 255]++;
        return quantile(histogram, gray.length, .1) > .94
                && quantile(histogram, gray.length, .5) > .98;
    }

    private static double quantile(int[] histogram, int count, double fraction) {
        double position = (count - 1) * fraction;
        int low = (int) position;
        int high = (int) Math.ceil(position);
        int cumulative = 0;
        float lower = 0f;
        for (int value = 0; value < 256; value++) {
            int previous = cumulative;
            cumulative += histogram[value];
            if (previous <= low && cumulative > low) lower = value / 255f;
            if (cumulative > high) {
                return lower + (value / 255f - lower) * (position - low);
            }
        }
        throw new IllegalArgumentException("Empty grayscale histogram");
    }

    private static byte[] close(byte[] source, int width, int height) {
        byte[] temporary = new byte[source.length];
        byte[] result = new byte[source.length];
        filter(source, temporary, width, height, false, true);
        filter(temporary, result, width, height, true, true);
        filter(result, temporary, width, height, false, false);
        filter(temporary, result, width, height, true, false);
        return result;
    }

    private static float[] close(float[] source, int width, int height) {
        float[] temporary = new float[source.length];
        float[] result = new float[source.length];
        filter(source, temporary, width, height, false, true);
        filter(temporary, result, width, height, true, true);
        filter(result, temporary, width, height, false, false);
        filter(temporary, result, width, height, true, false);
        return result;
    }

    private static void filter(
            byte[] source,
            byte[] output,
            int width,
            int height,
            boolean vertical,
            boolean maximum) {
        int length = vertical ? height : width;
        int lines = vertical ? width : height;
        int step = vertical ? width : 1;
        int[] queue = new int[length];
        for (int line = 0; line < lines; line++) {
            int base = vertical ? line : line * width;
            int head = 0, tail = 0, next = 0;
            for (int position = 0; position < length; position++) {
                int right = Math.min(length - 1, position + RADIUS);
                while (next <= right) {
                    int value = source[base + next * step] & 255;
                    while (tail > head) {
                        int previous = source[base + queue[tail - 1] * step] & 255;
                        if (maximum ? previous > value : previous < value) break;
                        tail--;
                    }
                    queue[tail++] = next++;
                }
                while (queue[head] < position - RADIUS) head++;
                output[base + position * step] = source[base + queue[head] * step];
            }
        }
    }

    private static void filter(
            float[] source,
            float[] output,
            int width,
            int height,
            boolean vertical,
            boolean maximum) {
        int length = vertical ? height : width;
        int lines = vertical ? width : height;
        int step = vertical ? width : 1;
        int[] queue = new int[length];
        for (int line = 0; line < lines; line++) {
            int base = vertical ? line : line * width;
            int head = 0, tail = 0, next = 0;
            for (int position = 0; position < length; position++) {
                int right = Math.min(length - 1, position + RADIUS);
                while (next <= right) {
                    float value = source[base + next * step];
                    while (tail > head) {
                        float previous = source[base + queue[tail - 1] * step];
                        if (maximum ? previous > value : previous < value) break;
                        tail--;
                    }
                    queue[tail++] = next++;
                }
                while (queue[head] < position - RADIUS) head++;
                output[base + position * step] = source[base + queue[head] * step];
            }
        }
    }

    private static float[] resizeArea(
            byte[] source, int width, int height, int targetWidth, int targetHeight) {
        float[] result = new float[targetWidth * targetHeight];
        double scaleX = width / (double) targetWidth;
        double scaleY = height / (double) targetHeight;
        for (int y = 0; y < targetHeight; y++) {
            double top = y * scaleY, bottom = (y + 1) * scaleY;
            for (int x = 0; x < targetWidth; x++) {
                double left = x * scaleX, right = (x + 1) * scaleX;
                double sum = 0.;
                for (int sy = (int) top; sy < Math.min(height, Math.ceil(bottom)); sy++) {
                    double wy = Math.min(bottom, sy + 1.) - Math.max(top, sy);
                    for (int sx = (int) left; sx < Math.min(width, Math.ceil(right)); sx++) {
                        double wx = Math.min(right, sx + 1.) - Math.max(left, sx);
                        sum += (source[sy * width + sx] & 255) * wx * wy;
                    }
                }
                result[y * targetWidth + x] = (float) (sum / (scaleX * scaleY));
            }
        }
        return result;
    }

    private static float interpolate(float[] top, float[] bottom, int left, int right, float fx, float fy) {
        float upper = top[left] * (1f - fx) + top[right] * fx;
        float lower = bottom[left] * (1f - fx) + bottom[right] * fx;
        return upper * (1f - fy) + lower * fy;
    }
}
