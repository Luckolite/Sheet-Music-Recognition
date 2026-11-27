// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recovers the third letter of a short OCR reading using the printed ink. */
final class PrintedArcoWord {
    private PrintedArcoWord() {}

    static List<PlayingTechniqueDetector.Word> recover(List<PlayingTechniqueDetector.Word> words,
            byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height) return words;
        List<PlayingTechniqueDetector.Word> corrected = null;
        for (int i = 0; i < words.size(); i++) {
            var word = words.get(i);
            String text = word.text().strip();
            if (!text.equalsIgnoreCase("aro") && !text.equalsIgnoreCase("aco")
                    && !text.equalsIgnoreCase("arc")) continue;
            int left = Math.max(0, Math.round(word.left()*width));
            int right = Math.min(width, Math.round(word.right()*width));
            int top = Math.max(0, Math.round(word.top()*height));
            int bottom = Math.min(height, Math.round(word.bottom()*height));
            if (!letters(gray, width, left, right, top, bottom, 170) && !letters(gray, width, left, right, top, bottom, 158)) continue;
            if (corrected == null) corrected = new ArrayList<>(words);
            corrected.set(i, new PlayingTechniqueDetector.Word(
                    "arco", word.left(), word.top(), word.right(), word.bottom(), word.admission()));
        }
        return corrected == null ? words : List.copyOf(corrected);
    }

    private record Letter(int left, int right, int top, int bottom) {}

    private static boolean letters(byte[] gray, int width, int left, int right, int top, int bottom, int cutoff) {
        int w = right-left, h = bottom-top;
        if (h < 9 || h > 70 || w < 1.65f*h || w > 5.5f*h || w > 210) return false;
        var mask = new boolean[w*h];
        var queue = new int[mask.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                mask[y*w+x] = (gray[(top+y)*width+left+x]&255) < cutoff;
        var glyphs = new ArrayList<Letter>();
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i]) continue;
            int end = 1, minX = i % w, maxX = minX, minY = i / w, maxY = minY;
            mask[i] = false;
            queue[0] = i;
            for (int take = 0; take < end; take++) {
                int x = queue[take] % w, y = queue[take] / w;
                minX = Math.min(minX,x);
                maxX = Math.max(maxX,x);
                minY = Math.min(minY,y);
                maxY = Math.max(maxY,y);
                for (int yy = Math.max(0,y-1); yy <= Math.min(h-1,y+1); yy++)
                    for (int xx = Math.max(0,x-1); xx <= Math.min(w-1,x+1); xx++) {
                        int next = yy*w+xx;
                        if (mask[next]) {
                            mask[next] = false;
                            queue[end++] = next;
                        }
                    }
            }
            if (maxY-minY >= h*.30f && end >= h*.70f)
                glyphs.add(new Letter(left+minX, left+maxX, top+minY, top+maxY));
        }
        if (glyphs.size() != 4) return false;
        glyphs.sort(Comparator.comparingInt(Letter::left));
        var c = glyphs.get(2);
        var o = glyphs.get(3);
        int size = o.bottom()-o.top()+1;
        if (glyphs.stream().anyMatch(g -> Math.abs(g.top()-o.top()) > size*.27f
                || Math.abs(g.bottom()-o.bottom()) > size*.27f)
                || c.right() >= o.left() || c.right()-c.left() < size*.52f
                || o.right()-o.left() < size*.56f) return false;
        for (int band = 2; band <= 3; band++) {
            int y = c.top()+(c.bottom()-c.top())*band/5;
            if (!stroke(gray,width,y,c.left(),c.left()+(c.right()-c.left())/2)
                    || stroke(gray,width,y,c.left()+(c.right()-c.left())*2/3,c.right())) return false;
            y = o.top()+(o.bottom()-o.top())*band/5;
            int third = (o.right()-o.left())/3;
            if (!stroke(gray,width,y,o.left(),o.left()+third)
                    || !stroke(gray,width,y,o.right()-third,o.right())
                    || (gray[y*width+(o.left()+o.right())/2]&255) < 170) return false;
        }
        return true;
    }

    private static boolean stroke(byte[] gray,int width,int y,int left,int right) {
        for (int x = left; x <= right; x++) if ((gray[y*width+x]&255) < 170) return true;
        return false;
    }
}
