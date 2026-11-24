package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;

final class HarmonicFingeringPair {
    private HarmonicFingeringPair() {}

    record Fingers(int touch, int base) {}

    static Fingers read(byte[] ink,int width,int height,float x,float near,float far,float gap,boolean trimVerifiedStem) {
        int left=Math.round(x-.86f*gap),right=Math.round(x+.86f*gap);
        int top=Math.round(Math.min(near,far)),bottom=Math.round(Math.max(near,far));
        if(left<0||right>=width||top<0||bottom>=height||bottom-top>gap*4.6f) return null;
        var glyphs=new ArrayList<RawNumeralComponents.Component>();
        for(var glyph:RawNumeralComponents.captureLongWindow(ink,width,height,left,top,right,bottom).components()){
            float center=(glyph.left+glyph.right)*.5f;
            int h=glyph.bottom-glyph.top+1;
            if((glyph.top>top||trimVerifiedStem&&glyph.top==top)&&glyph.bottom<bottom&&Math.abs(center-x)<gap*.35f&&h>=gap*.67f&&h<=gap*1.36f)
                glyphs.add(glyph);
        }
        if(glyphs.size()!=2)return null;
        glyphs.sort(Comparator.comparingInt(glyph->glyph.top));
        var first=glyphs.get(0);var second=glyphs.get(1);
        if(second.top-first.bottom<gap*.12f||second.top-first.bottom>gap*.64f)return null;
        int touch=digit(ink,width,height,first),base=digit(ink,width,height,second);
        return base>=0&&touch>base&&touch<=4?new Fingers(touch,base):null;
    }

    private static int digit(byte[] ink,int width,int height,RawNumeralComponents.Component glyph){
        if(TripletRhythmDetector.fingerOne(ink,width,glyph))return 1;
        if(TripletRhythmDetector.isIsolatedThree(glyph,width))return 3;
        var d=JoinedDigitTemplates.inspect(ink,width,height,new BitSet(),glyph.left,glyph.top,glyph.right,glyph.bottom,()->{});
        return d==null?-1:d.digit();
    }
}
