package io.github.luckolite.interpreter;

final class MeterCounterFlood {
    private MeterCounterFlood() {}

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
        int read = 0, write = 1;
        queue[0] = seed;
        paper[seed] = 0;
        while (read < write) {
            int at = queue[read++];
            for (int row = at - stride; row <= at + stride; row += stride) {
                for (int next = row - 1; next <= row + 1; next++) {
                    if (paper[next] == 0) continue;
                    paper[next] = 0;
                    queue[write++] = next;
                }
            }
        }
        return write;
    }
}
