// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;

public final class JoinedCancellationSharpTest {
    private static final int WIDTH = 278, HEIGHT = 245;
    private static final float GAP = 16;

    @Test
    public void closelySpacedAccidentalsRequireEachOfTheThreePrintedSigns() throws Exception {
        requiresAllSigns(117);
    }

    @Test
    public void compoundCropMayIncludeAnExtraColumnBesideThePrefix() throws Exception {
        requiresAllSigns(116);
    }

    private static void requiresAllSigns(int compoundLeft) throws Exception {
        byte[] ink = sheet();
        assertTrue(owned(ink, compoundLeft));
        for (int[] cut : new int[][]{{97,104,117,159}, {115,121,101,146},
                {133,141,105,149}, {147,157,153,182}}) {
            byte[] missing = ink.clone();
            fill(missing, cut[0], cut[1], cut[2], cut[3], (byte) 255);
            assertFalse(owned(missing, compoundLeft));
        }
    }

    private static byte[] sheet() {
        byte[] ink = new byte[WIDTH * HEIGHT];
        Arrays.fill(ink,(byte)255);
        for (int y : new int[]{102,118,134,150,166})
            fill(ink,48,222,y-1,y+1,(byte)0);
        natural(ink,100,142);
        natural(ink,118,126);
        fill(ink,134,135,103,149,(byte)0);
        fill(ink,143,144,103,149,(byte)0);
        fill(ink,131,147,113,115,(byte)0);
        fill(ink,131,147,130,132,(byte)0);
        ellipse(ink,160,126);
        ellipse(ink,160,142);
        fill(ink,149,152,128,198,(byte)0);
        return ink;
    }

    private static void natural(byte[] ink, int x, int y) {
        fill(ink,x,x+2,y-22,y+15,(byte)0);
        fill(ink,x+9,x+11,y-11,y+23,(byte)0);
        slopingBar(ink,x,x+10,y-6);
        slopingBar(ink,x,x+10,y+14);
    }

    private static void slopingBar(byte[] ink, int first, int last, int y) {
        for (int x=first;x<=last;x++) {
            int row=y-Math.round((x-first)*.30f);
            fill(ink,x,x,row-2,row+2,(byte)0);
        }
    }

    private static void ellipse(byte[] ink, int cx, int cy) {
        for (int y=cy-9;y<=cy+9;y++) for (int x=cx-11;x<=cx+11;x++) {
            float u=(x-cx)/11f, v=(y-cy)/8.5f;
            if (u*u+v*v+u*v*.38f<=1) ink[y*WIDTH+x]=0;
        }
    }

    private static void fill(byte[] ink, int left, int right, int top, int bottom, byte value) {
        for (int y=top;y<=bottom;y++) Arrays.fill(ink,y*WIDTH+left,y*WIDTH+right+1,value);
    }

    private static boolean owned(byte[] ink, int compoundLeft) throws Exception {
        var type=Class.forName(OmrScoreInterpreter.class.getName()+"$Component");
        Constructor<?> component=type.getDeclaredConstructor(int.class,int.class,int.class,
                int.class,int.class,float.class,float.class);
        component.setAccessible(true);
        Object lower=component.newInstance(270,149,171,134,151,160f,142f);
        Object upper=component.newInstance(270,149,171,118,135,160f,126f);
        Object natural=component.newInstance(340,99,114,121,166,106.4f,142.8f);
        Object compound=component.newInstance(750,compoundLeft,151,105,151,132.9f,126.1f);
        Method owns=OmrScoreInterpreter.class.getDeclaredMethod("ownsCancelledSharpInThird",
                byte[].class,int.class,int.class,type,type,type,type,float.class);
        owns.setAccessible(true);
        return (boolean) owns.invoke(null, ink, WIDTH, HEIGHT, lower, upper, natural, compound, GAP);
    }
}
