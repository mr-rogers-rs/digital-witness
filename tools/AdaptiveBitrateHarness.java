import com.fieldnotes.recorder.capture.AdaptiveBitrate;

public class AdaptiveBitrateHarness {
    static void check(boolean v,String m) { if(!v) throw new AssertionError(m); }
    public static void main(String[] args) {
        var policy = new AdaptiveBitrate(2_000_000, 0);
        long produced=0, delivered=0;
        for(long now=15_000;now<=360_000;now+=15_000) {
            produced+=3_750_000; delivered+=500_000;
            int target=policy.update(now,produced-delivered,produced,delivered);
            check(target>=1_500_000 && target<=2_000_000,"Quality bounds");
            if(now<90_000) check(target==2_000_000,"Normal startup must not reduce quality");
        }
        check(policy.getTargetBps()==1_500_000,"Persistent growing queue reaches conservative floor");
        int previous=policy.getTargetBps(); int changes=0;
        for(long now=375_000;now<=660_000;now+=15_000) {
            produced+=3_750_000; delivered=produced;
            int target=policy.update(now,0,produced,delivered);
            if(now<495_000) check(target==previous,"Recovery must wait for sustained low queue");
            if(target!=previous) { changes++; previous=target; }
        }
        check(changes==2 && policy.getTargetBps()==2_000_000,"Gradual recovery to initial quality");
        policy=new AdaptiveBitrate(2_000_000,0);
        for(long now=15_000;now<=600_000;now+=15_000)
            check(policy.update(now,now*250,now*250,0)==2_000_000,"Offline/auth failure must not sacrifice quality");
        policy=new AdaptiveBitrate(2_000_000,0);
        for(long now=15_000;now<=600_000;now+=15_000)
            check(policy.update(now,3_000_000,now*250,now*250)==2_000_000,"Healthy queue must keep original rate");
        check(new AdaptiveBitrate(1_000_000,0).getMinimumBps()==1_000_000,"Respect lower custom ceiling");
        check(new AdaptiveBitrate(8_000_000,0).getMinimumBps()==6_000_000,"Preserve quality for higher custom rates");
        System.out.println("PASS: growing backlog, 1.5 Mb/s floor, slow recovery, healthy/offline queues and custom quality bounds.");
    }
}
