package io.github.luckolite.interpreter;

final class MeterCounterFlood {
    private MeterCounterFlood() {}

    // The leftmost pixel on the top row of an enclosed 8-connected pocket
    // has ink immediately to its left and in all three positions above it.
    static boolean canEnclosePaper(byte[] gray, int width, int left, int first, int w, int h) {
        for (int y = 1; y < h - 1; y++) {
            int start = (first + y) * width + left;
            for (int at = start + 1, end = start + w - 1; at < end; at++) {
                if ((gray[at - 1] & 255) <= 160 && (gray[at] & 255) > 160
                        && (gray[at - width] & 255) <= 160
                        && (gray[at - width - 1] & 255) <= 160
                        && (gray[at - width + 1] & 255) <= 160) {
                    // Its horizontal run must also end before reaching the crop edge.
                    for (int right = at + 1; right <= end; right++)
                        if ((gray[right] & 255) <= 160) return true;
                    break;
                }
            }
        }
        return false;
    }

    static byte[] paperMask(byte[] gray, int width, int left, int first, int w, int h) {
        int stride = w + 2;
        byte[] paper = new byte[stride * (h + 2)];
        for (int y = 0, at = first * width + left; y < h; y++, at += width) {
            int start = (y + 1) * stride + 1;
            for (int x = 0; x < w; x++)
                if ((gray[at + x] & 255) > 160) paper[start + x] = 1;
        }
        return paper;
    }

    static int drain(byte[] paper, int[] queue, int stride, int seed) {
        int read = 0, write = markRun(paper, queue, seed, 0);
        while (read < write) {
            int left = queue[read++], right = left;
            while (read < write && queue[read] == right + 1) right = queue[read++];
            for (int offset = -stride; offset <= stride; offset += stride * 2) {
                int limit = right + offset + 1;
                for (int next = left + offset - 1; next <= limit; next++) {
                    if (paper[next] != 0) {
                        write = markRun(paper, queue, next, write);
                        next = queue[write - 1];
                    }
                }
            }
        }
        return write;
    }

    private static int markRun(byte[] paper, int[] queue, int seed, int write) {
        int left = seed;
        while (paper[left - 1] != 0) left--;
        for (int at = left; paper[at] != 0; at++) {
            paper[at] = 0;
            queue[write++] = at;
        }
        return write;
    }
}
