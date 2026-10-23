import com.fieldnotes.recorder.capture.MuxSample;
import com.fieldnotes.recorder.capture.SegmentTimeline;
import java.util.List;

public class SegmentTimelineHarness {
    static MuxSample sample(long pts, boolean key) { return new MuxSample(new byte[]{1}, pts, key); }
    static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid timeline accepted");
    }
    public static void main(String[] args) {
        long uptime = 39L * 60 * 60 * 1_000_000;
        var video = List.of(sample(uptime + 500_000, true), sample(uptime + 20_000_000, false));
        var audio = List.of(sample(uptime, false), sample(uptime + 20_000_000, false));
        if (SegmentTimeline.INSTANCE.baseline(audio, video, 22_000_000) != uptime)
            throw new AssertionError("Earlier audio must be preserved on the shared clock");
        reject(() -> SegmentTimeline.INSTANCE.baseline(
            List.of(sample(uptime * 2, false)), video, 22_000_000));
        reject(() -> SegmentTimeline.INSTANCE.baseline(audio,
            List.of(sample(uptime, false)), 22_000_000));
        reject(() -> SegmentTimeline.INSTANCE.baseline(audio,
            List.of(sample(uptime, true), sample(uptime, false)), 22_000_000));
        reject(() -> SegmentTimeline.INSTANCE.baseline(audio,
            List.of(sample(uptime, true), sample(uptime - 1, false)), 22_000_000));
        reject(() -> SegmentTimeline.INSTANCE.baseline(List.of(), List.of(), 22_000_000));
        // A long recording session must not permit disjoint ten-second tracks.
        reject(() -> SegmentTimeline.INSTANCE.baseline(
            List.of(sample(uptime,false),sample(uptime+10_000_000,false)),
            List.of(sample(uptime+70_000_000,true),sample(uptime+80_000_000,false)),1_200_000_000));
        if (SegmentTimeline.INSTANCE.splitIndex(List.of(true, false, false, true, false)) != 3)
            throw new AssertionError("Segment boundary must retain the final GOP");
        if (SegmentTimeline.INSTANCE.splitIndex(List.of(true, false, false)) != 0)
            throw new AssertionError("Must wait for another keyframe");
        if (SegmentTimeline.INSTANCE.baseline(audio, List.of(), 22_000_000) != uptime)
            throw new AssertionError("Audio-only baseline");
        if (SegmentTimeline.INSTANCE.baseline(List.of(), video, 22_000_000) != uptime + 500_000)
            throw new AssertionError("Video-only baseline");
        System.out.println("PASS: timeline cases: 39-hour clock mismatch, disjoint A/V in a twenty-minute session, duration bounds, audio preservation, keyframe cuts, monotonicity and single tracks.");
    }
}
