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
    private static int tone(byte[] plane,int width,int x,int y) {
        if(x<0 || x>=width || y<0 || y>=plane.length/width) return 255;
        return plane[y*width+x]&255;
    }
}
