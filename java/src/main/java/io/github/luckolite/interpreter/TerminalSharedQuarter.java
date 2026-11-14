// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Probe the terminal double-stop shaft before changing either written note's rhythm. */
final class TerminalSharedQuarter {
    private TerminalSharedQuarter() {}

    static boolean proves(byte[] labels, byte[] gray, int width, int height,
            float leftX, float lowerY, float rightX, float upperY, float gap, float staffTop) {
        if (leftX >= rightX || lowerY <= upperY || rightX-leftX > gap*1.9f
                || rightX-leftX < gap*.65f || lowerY-upperY > gap*2.5f
                || lowerY-upperY < gap*.75f) return false;
        int lo = Math.round(Math.max(leftX+gap*.31f, rightX-gap*1.13f));
        int hi = Math.round(Math.min(rightX-gap*.31f, leftX+gap*1.13f));
        for (int shaft = lo; shaft <= hi; shaft++) {
            if (!sameShaft(labels,gray,width,height,shaft,lowerY,upperY,gap,staffTop)) continue;
            if (!headJoins(labels,gray,width,height,shaft,-1,leftX,lowerY,gap)
                    || !headJoins(labels,gray,width,height,shaft,1,rightX,upperY,gap)) continue;
            if (anotherStem(labels,width,height,leftX, lowerY, shaft, -1, gap)
                    || anotherStem(labels,width,height,rightX, upperY, shaft, 1, gap)) continue;
            if (!unflaggedTip(labels,gray,width,height,shaft,staffTop,gap)) continue;
            return true;
        }
        return false;
    }

    private static boolean sameShaft(byte[] labels, byte[] gray, int w, int h, int x,
            float lowerY, float upperY, float gap, float staffTop) {
        int start = Math.round(Math.min(upperY - gap*1.4f, staffTop - gap*.45f));
        int end = Math.round(lowerY-gap*.44f);
        int bad = 0, missingSemantics = 0;
        for (int y=start; y<=end; y++) {
            if (!ink(gray,w,h,x,y)) bad++;
            if (semantic(labels,w,h,x,y)!=OmrMeasurePostProcessor.STEM_OR_REST) missingSemantics++;
        }
        return end > start && bad <= Math.max(1, Math.round(gap*.10f))
                && missingSemantics <= Math.round((end-start+1)*.22f);
    }

    private static boolean headJoins(byte[] labels, byte[] gray, int w,int h, int shaft,
            int side, float x,float y,float gap) {
        int headX=Math.round(x), radius=Math.round(gap*.30f);
        int foundHead=0;
        for (int zy=Math.round(y)-radius; zy<=Math.round(y)+radius; zy++)
            for (int zx=headX-radius; zx<=headX+radius; zx++)
                if (semantic(labels,w,h,zx,zy)==OmrMeasurePostProcessor.NOTEHEAD
                        && ink(gray,w,h,zx,zy)) foundHead++;
        if (foundHead < gap*gap*.19f) return false;
        int flank=Math.round(gap*.30f);
        for (int zy=Math.round(y)-radius; zy<=Math.round(y)+radius; zy++) {
            if (!ink(gray,w,h,shaft,zy)) continue;
            boolean continuous=true;
            for (int dx=1; dx<=flank; dx++) {
                if (!ink(gray,w,h,shaft+dx*side,zy)) {
                    continuous=false;
                    break;
                }
            }
            if (continuous
                    && semantic(labels,w,h,shaft+flank*side,zy)==OmrMeasurePostProcessor.NOTEHEAD)
                return true;
        }
        return false;
    }

    private static boolean anotherStem(byte[] labels,int w,int h,
            float headX,float headY,int shaft,int side,float gap) {
        int from = Math.round(gap*.33f), to = Math.round(gap*.95f), length=Math.round(gap*1.35f);
        for (int shift=from; shift<=to; shift++) {
            int x=Math.round(headX)+shift*side;
            if (Math.abs(x-shaft)<gap*.3f) continue;
            for (int direction : new int[]{-1,1}) {
                int hits=0;
                for (int dy=Math.round(gap*.6f); dy<Math.round(gap*.6f)+length; dy++)
                    if (semantic(labels,w,h,x,Math.round(headY)+direction*dy)==OmrMeasurePostProcessor.STEM_OR_REST) hits++;
                if (hits>=length*.8f) return true;
            }
        }
        return false;
    }

    /** The shared shaft reaches above the top rule and has no attached beam or flag there. */
    private static boolean unflaggedTip(byte[] labels,byte[] gray,int w,int h,int shaft,float top,float gap) {
        int tip=Math.round(top-gap*.65f), edge=Math.round(top-gap*.19f);
        for (int y=tip; y<=edge; y++) {
            if (semantic(labels,w,h,shaft,y)!=OmrMeasurePostProcessor.STEM_OR_REST || !ink(gray,w,h,shaft,y)) continue;
            for (int direction : new int[] {-1,1}) {
                int attached=0;
                for (int dx=1; dx<=Math.round(gap*.72f); dx++) {
                    if (!ink(gray,w,h,shaft+direction*dx,y)) break;
                    attached++;
                }
                if (attached>=gap*.32f) return false;
            }
        }
        return true;
    }

    private static int semantic(byte[] labels,int w,int h,int x,int y) {
        return x>=0 && x<w && y>=0 && y<h ? labels[y*w+x]&255 : 0;
    }

    private static boolean ink(byte[] gray,int w,int h,int x,int y) {
        return x>=0 && x<w && y>=0 && y<h && (gray[y*w+x]&255)<155;
    }
}
