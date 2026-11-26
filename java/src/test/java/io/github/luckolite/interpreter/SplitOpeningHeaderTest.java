// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SplitOpeningHeaderTest {
    private static final int WIDTH = 640, HEIGHT = 515, GAP = 14;
    private static final int[] TOPS = {80,220,360};
    private static final List<MeasureRegion> REGIONS = List.of(region(90,196),region(211,600));

    private static MeasureRegion region(int a, int b) {
        return new MeasureRegion(a/(float)WIDTH,b/(float)WIDTH,55f/HEIGHT,455f/HEIGHT);
    }

    @Test public void alignedMeterCopiesOwnTheFalseDivider() {
        var page = new Page();
        var proof = page.read((raster,w,h,crop) -> "12/8");
        assertEquals(3,proof.size());
        assertEquals(12,proof.get(0).numerator());
        assertEquals(8,proof.get(0).denominator());
    }

    @Test public void protectsRealBarlinePlayableInkAndConflictingMeter() {
        var page = new Page();
        assertTrue(page.read((raster,w,h,crop) -> crop.firstLine()<100 ? "12/8":"10/8").isEmpty());
        assertTrue(page.read((raster,w,h,crop) -> crop.firstLine()<100 ? "12/8":"6/8").isEmpty());
        assertTrue(page.read((raster,w,h,crop) -> crop.firstLine()<100 ? "12/8":"").isEmpty());
        page.paint(page.source,202,204,80,416,0);
        assertTrue(page.read((raster,w,h,crop) -> "12/8").isEmpty());
        page = new Page();
        page.paint(page.labels,143,153,230,241,OmrMeasurePostProcessor.NOTEHEAD);
        assertTrue(page.read((raster,w,h,crop) -> "12/8").isEmpty());
    }

    private static class Page {
        final byte[] gray = new byte[WIDTH*HEIGHT], labels = new byte[gray.length];
        final byte[] source;

        Page() {
            Arrays.fill(gray,(byte)255);
            for (int top : TOPS) {
                for (int line=0;line<5;line++) {
                    paint(gray,58,612,top+line*GAP,top+line*GAP,0);
                    paint(labels,58,612,top+line*GAP,top+line*GAP,OmrMeasurePostProcessor.STAFF);
                }
                paint(gray,115,130,top+1,top+52,0);
                paint(labels,115,130,top+1,top+52,OmrMeasurePostProcessor.CLEF_OR_KEY);
                for (int y : new int[]{top+2, top+30}) {
                    paint(gray,188,191,y,y+21,0);
                    paint(gray,198,217,y,y+4,0);
                    paint(gray,206,215,y+5,y+13,0);
                    paint(gray,198,217,y+15,y+21,0);
                }
                paint(labels,188,217,top+2,top+51,OmrMeasurePostProcessor.SYMBOL);
            }
            source=gray.clone();
        }

        List<SplitOpeningHeader.Meter> read(SplitOpeningHeader.MeterReader meter) {
            return SplitOpeningHeader.prove(REGIONS,labels,gray,source,WIDTH,HEIGHT,meter);
        }

        void paint(byte[] pixels,int left,int right,int top,int bottom,int value) {
            for(int y=top;y<=bottom;y++)
                Arrays.fill(pixels,y*WIDTH+left,y*WIDTH+right+1,(byte)value);
        }
    }
}
