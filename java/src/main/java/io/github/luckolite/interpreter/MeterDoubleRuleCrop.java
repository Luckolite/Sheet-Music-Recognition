// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Detaches verified boundary ink from an otherwise unread meter crop. */
final class MeterDoubleRuleCrop {
    private MeterDoubleRuleCrop() {}

    private record Stroke(int left,int right) {}

    static MeterChangeDetector.Crop trim(MeterChangeDetector.Crop crop,byte[] gray,
            int width,int height,float ownerLeft) {
        if (gray==null || gray.length!=(long)width*height || !Float.isFinite(ownerLeft)
                || crop==null || crop.gap()<8 || crop.gap()>32
                || crop.left()<0 || crop.right()>width || crop.top()<0 || crop.bottom()>height)
            return crop;
        float gap=crop.gap();
        if (ownerLeft<crop.left() || ownerLeft>crop.right()) return crop;
        int end=Math.min(crop.right()-Math.round(1.3f*gap),
                Math.min(crop.left()+Math.round(gap*1.28f),Math.round(ownerLeft+gap*.3f)));
        if (end<=crop.left()) return crop;
        int maxSingleWidth = Math.max(2, Math.round(gap * .34f));
        int maxPairedWidth = Math.max(2, Math.round(gap * .43f));
        Stroke first=null,second=null;
        for (int x=crop.left();x<end;) {
            if (!spansStaff(gray,width,height,x,crop.firstLine(),gap)) {
                x++;
                continue;
            }
            int left=x;
            do { x++; }
            while (x<end && spansStaff(gray,width,height,x,crop.firstLine(),gap));
            if (x>=end || x-left<2 || x-left>maxPairedWidth) return crop;
            var stroke=new Stroke(left,x);
            if(first==null) first=stroke;
            else if(second==null) second=stroke;
            else return crop;
        }
        if (first == null || first.left - crop.left() > gap * .42f) return crop;
        int boundary;
        if (second == null) {
            float distance = ownerLeft - first.right;
            if (first.right - first.left > maxSingleWidth
                    || distance < gap * .24f || distance > gap * .76f) return crop;
            boundary = first.right;
        } else {
            if (second.left - first.right < 2
                    || second.left - first.right > gap * .43f
                    || second.right - first.left > gap * .93f) return crop;
            if ((first.right - first.left > maxSingleWidth
                            || second.right - second.left > maxSingleWidth)
                    && (extendsBeyondStaff(gray, width, height, crop, first)
                            || extendsBeyondStaff(gray, width, height, crop, second))) return crop;
            boundary = second.right;
        }
        if (boundary + 2 >= crop.right()) return crop;
        // Separating paper columns rule out trimming the initial stroke of a numeral.
        if (!paperBetweenRules(gray, width, height, boundary, crop.firstLine(), gap)) boundary++;
        if (!paperBetweenRules(gray, width, height, boundary, crop.firstLine(), gap)
                || !paperBetweenRules(gray, width, height, boundary + 1, crop.firstLine(), gap))
            return crop;
        return new MeterChangeDetector.Crop(
                boundary, crop.top(), crop.right(), crop.bottom(), crop.firstLine(), gap);
    }

    private static boolean extendsBeyondStaff(
            byte[] gray, int width, int height, MeterChangeDetector.Crop crop, Stroke stroke) {
        int middle = (stroke.left + stroke.right) / 2;
        return DottedDyadMeasureSeparator.continuesBeyondStaff(
                gray, width, height, middle, crop.firstLine(),
                Math.round(crop.firstLine() + 4 * crop.gap()), crop.gap());
    }

    private static boolean spansStaff(byte[] gray,int width,int height,int x,int top,float gap) {
        for (int space=0;space<4;space++) {
            int first=Math.round(top+(space+.22f)*gap);
            int last=Math.round(top+(space+.80f)*gap);
            if (first<0 || last>=height) return false;
            int hit=0;
            for (int y=first;y<=last;y++) if ((gray[y*width+x]&255)<155) hit++;
            if (hit<(last-first+1)*.86f) return false;
        }
        return true;
    }

    private static boolean paperBetweenRules(byte[] gray,int width,int height,int x,int top,float gap) {
        if (x<0 || x>=width) return false;
        int ink=0,checked=0;
        for(int space=0;space<4;space++) {
            int first=Math.round(top+(space+.22f)*gap);
            int last=Math.round(top+(space+.80f)*gap);
            if (first<0 || last>=height) return false;
            for (int y=first;y<=last;y++) {
                checked++;
                if ((gray[y*width+x]&255)<155) ink++;
            }
        }
        return ink<=checked*.10f;
    }
}
