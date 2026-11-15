package io.github.luckolite.interpreter;

final class LegacyTripletDotInk {
    record Core(int left, int right, int top, int bottom) {
        boolean contains(int x, int y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    static boolean singleDotCore(byte[] gray, int width, int height,
            float x, float y, float nextX, float gap) {
        return uniqueDotCore(gray, width, height, x, y, nextX, gap) != null;
    }

    static Core uniqueDotCore(byte[] gray, int width, int height,
            float x, float y, float nextX, float gap) {
        int left = Math.round(x + gap * .7f), right = Math.round(nextX - gap * .55f);
        int top = Math.round(y - gap * .85f), bottom = Math.round(y + gap * .65f);
        if (right <= left || bottom <= top || left < 0 || top < 0 || right >= width
                || bottom >= height || right - left > gap * 2) return null;
        int w = right - left + 1, h = bottom - top + 1;
        boolean[] seen = new boolean[w * h];
        int[] queue = new int[seen.length];
        Core found = null;
        for (int start = 0; start < seen.length; start++) {
            if (seen[start] || (gray[(top + start / w) * width + left + start % w] & 255) >= 90)
                continue;
            seen[start] = true;
            queue[0] = start;
            int count = 1, a = w, b = -1, c = h, d = -1;
            for (int i = 0; i < count; i++) {
                int q = queue[i], xx = q % w, yy = q / w;
                a = Math.min(a, xx);
                b = Math.max(b, xx);
                c = Math.min(c, yy);
                d = Math.max(d, yy);
                for (int ny = Math.max(0, yy - 1); ny <= Math.min(h - 1, yy + 1); ny++)
                    for (int nx = Math.max(0, xx - 1); nx <= Math.min(w - 1, xx + 1); nx++) {
                        int p = ny * w + nx;
                        if (seen[p] || (gray[(top + ny) * width + left + nx] & 255) >= 90) continue;
                        seen[p] = true;
                        queue[count++] = p;
                    }
            }
            int dotW = b - a + 1, dotH = d - c + 1;
            if (a > 0 && b < w - 1 && c > 0 && d < h - 1
                    && dotW >= gap * .22f && dotW <= gap * .6f
                    && dotH >= gap * .22f && dotH <= gap * .6f
                    && Math.max(dotW, dotH) <= 1.5f * Math.min(dotW, dotH)
                    && count >= dotW * dotH * .55f) {
                if (found != null) return null;
                found = new Core(left + a, left + b, top + c, top + d);
            }
        }
        return found;
    }

}