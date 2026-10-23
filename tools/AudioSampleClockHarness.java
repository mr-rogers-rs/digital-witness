import com.fieldnotes.recorder.capture.AudioSampleClock;
import com.fieldnotes.recorder.capture.AudioTimingException;

public class AudioSampleClockHarness {
    public static void main(String[] args) {
        long base=39L*60*60*1_000_000;
        var clock=new AudioSampleClock(48000,1_500_000);
        long samples=0;
        // Twenty minutes at full PCM rate: callback jitter must not stretch audio.
        for(int i=0;i<56250;i++) {
            long end=base+(samples+1024)*1_000_000/48000;
            long pts=clock.next(1024,end);
            if(pts!=base+samples*1_000_000/48000) throw new AssertionError("PCM timing changed");
            samples+=1024;
        }
        if(clock.getEndUs()!=base+1_200_000_000L) throw new AssertionError("Wrong twenty-minute duration");
        clock=new AudioSampleClock(48000,1_500_000);
        clock.next(4800,base+100_000);
        if(clock.next(4800,base+600_000)!=base+100_000) throw new AssertionError("Buffered PCM was retimed to callback arrival");
        if(clock.next(4800,base+600_000)!=base+200_000) throw new AssertionError("Buffered catch-up lost samples");
        boolean stopped=false;
        try { clock.next(4800,base+60_000_000); } catch(AudioTimingException expected) { stopped=true; }
        if(!stopped) throw new AssertionError("A minute of missing PCM was accepted as continuous sound");
        System.out.println("PASS: twenty-minute PCM clock, buffered catch-up, preserved sample timing and explicit rejection of a long input overrun.");
    }
}
