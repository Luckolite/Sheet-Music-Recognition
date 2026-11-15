package io.github.luckolite.interpreter;

final class TerminalBeamShaftEnd {
    private TerminalBeamShaftEnd() {}
    static boolean proves(byte[] gray, int width, float gap, int[] fragment, int[] borrowed,
            int[] preceding, int[] terminal) {
        int direction=terminal[2];
        if (preceding[2]!=direction || borrowed[2]!=-direction || Math.abs(direction)!=1) return false;
        int span=terminal[0]-preceding[0];
        if (span<gap*1.9f || span>gap*4f || Math.abs(borrowed[0]-terminal[0])>gap*.17f) return false;
        float slope=(terminal[1]-preceding[1])/(float)span;
        if (Math.abs(slope)>.32f || (terminal[1]-borrowed[1])*direction<gap*4.5f) return false;
        int lo=Integer.MAX_VALUE,hi=Integer.MIN_VALUE,ink=0;
        for (int p:fragment) {
            int x=p%width,y=p/width;
            lo=Math.min(lo,x);hi=Math.max(hi,x);
            if ((gray[p]&255)>=155) continue;
            ink++;
            float tip=terminal[1]+slope*(x-terminal[0]);
            float off=(tip-y)*direction;
            if (x<terminal[0]-gap*.70f || x>terminal[0]+gap*.21f
                    || off < -gap*.15f || off>gap*1.35f) return false;
        }
        if (ink<fragment.length*.88f || hi-lo>gap*.90f || lo>=terminal[0]-gap*.40f
                || hi<terminal[0]-1 || hi>terminal[0]+gap*.25f) return false;
        int near=Math.round(gap*.31f), far=Math.round(gap*1.06f), space=Math.round(gap*.67f);
        int measured=0, paired=0;
        for (int x=preceding[0]+Math.round(gap*.45f);x<lo-Math.round(gap*.25f);x++) {
            int tip=Math.round(terminal[1]+slope*(x-terminal[0]));
            if (tone(gray,width,x,tip-direction*near)<105
                    && tone(gray,width,x,tip-direction*far)<105
                    && tone(gray,width,x,tip-direction*space)>170) paired++;
            measured++;
        }
        if (measured<gap || paired<measured*.72f) return false;
        for (int x=lo;x<=terminal[0]-1;x++) {
            int tip=Math.round(terminal[1]+slope*(x-terminal[0]));
            if (tone(gray,width,x,tip-direction*near)>=125
                    || tone(gray,width,x,tip-direction*far)>=125) return false;
        }
        // The two rail cores finish at the shared shaft; nearby staff rule ink
        // can survive, but neither rail continues to the right as a beam.
        int after=Math.round(gap*.34f);
        for (int d=after;d<after+Math.round(gap*.75f);d++) {
            int x=terminal[0]+d, tip=Math.round(terminal[1]+slope*d);
            if (tone(gray,width,x,tip-direction*near)<120
                    && tone(gray,width,x,tip-direction*far)<120) return false;
        }
        return true;
    }

    /** The merged end of a beam group can borrow ink from the following staff rule. */
    static boolean provesMergedBeforeCadence(
            byte[] gray,
            int width,
            float gap,
            int[] fragment,
            int[] borrowed,
            int[] preceding,
            int[] terminal) {
        if (preceding[2] != 1 || terminal[2] != 1 || borrowed[2] != -1) return false;
        int span = terminal[0] - preceding[0];
        if (span < gap * 2.1f
                || span > gap * 3.6f
                || Math.abs(borrowed[0] - terminal[0]) > gap * .17f) return false;
        float slope = (terminal[1] - preceding[1]) / (float) span;
        if (Math.abs(slope) > .2f || terminal[1] - borrowed[1] < gap * 5.5f) return false;
        int first = terminal[0], last = terminal[0], ink = 0;
        for (int pixel : fragment) {
            int x = pixel % width, y = pixel / width;
            first = Math.min(first, x);
            last = Math.max(last, x);
            if ((gray[pixel] & 255) >= 155) continue;
            ink++;
            float depth = terminal[1] + slope * (x - terminal[0]) - y;
            if (x < terminal[0] - gap * .78f
                    || x > terminal[0] + gap * .24f
                    || depth < gap * .45f
                    || depth > gap * 1.87f) return false;
        }
        if (ink < fragment.length * .90f
                || first >= terminal[0] - gap * .45f
                || last < terminal[0]
                || last - first > gap * .92f) return false;
        int lower = Math.round(gap * .63f);
        int upper = Math.round(gap * 1.35f);
        int seam = Math.round(gap * .94f);
        int size = 0, supported = 0, separated = 0;
        for (int x = preceding[0] + Math.round(gap * .42f); x <= terminal[0] - 2; x++) {
            int baseline = Math.round(terminal[1] + slope * (x - terminal[0]));
            size++;
            if (darkRail(gray, width, x, baseline - lower)
                    && darkRail(gray, width, x, baseline - upper)) {
                supported++;
                if (x < first && tone(gray, width, x, baseline - seam) >= 170) separated++;
            }
        }
        if (size < gap * 1.7f || supported < size * .92f || separated < gap * .55f) return false;
        // Both heavy rails terminate at the shaft. Staff rules may continue through the gap.
        int gapStart = Math.round(gap * .35f);
        for (int d = gapStart; d <= gapStart + Math.round(gap * .65f); d++) {
            int x = terminal[0] + d, baseline = Math.round(terminal[1] + slope * d);
            if (darkRail(gray, width, x, baseline - lower)
                    || darkRail(gray, width, x, baseline - upper)) return false;
        }
        return true;
    }

    private static boolean darkRail(byte[] gray, int width, int x, int center) {
        return tone(gray, width, x, center - 1) < 155
                && tone(gray, width, x, center) < 155
                && tone(gray, width, x, center + 1) < 155;
    }

    private static int tone(byte[] plane,int width,int x,int y) {
        if(x<0 || x>=width || y<0 || y>=plane.length/width) return 255;
        return plane[y*width+x]&255;
    }
}
