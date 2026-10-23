import com.fieldnotes.recorder.capture.NativeCapturePolicy;
import kotlin.Pair;
import java.util.List;

public class NativeCaptureHarness {
    static Pair<Integer,Integer> pair(int a, int b) { return new Pair<>(a,b); }
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) {
        var policy = NativeCapturePolicy.INSTANCE;
        var sizes = List.of(pair(3840,2160), pair(1920,1080), pair(1440,1080), pair(1280,960), pair(640,480));
        check(policy.normalSize(sizes,4.0/3).equals(pair(1440,1080)), "Largest full-aspect native mode, not largest cropped mode");
        boolean rejected = false;
        try { policy.normalSize(List.of(pair(1920,1080)),4.0/3); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected,"Do not silently fall back to a different aspect ratio");
        check(policy.durationSupports(33_333_334L, 30), "Allow integer-rounded 30fps duration");
        check(!policy.durationSupports(40_000_000L, 30), "Reject 30fps for a 25fps-limited stream");
        check(policy.durationSupports(40_000_000L, 24), "Accept native 24fps at that size");
        check(policy.durationSupports(0, 15), "Unknown duration uses advertised AE and codec capabilities");
        check(!policy.durationSupports(-1, 15), "Invalid timing is rejected");
        var ranges = List.of(pair(5,30), pair(15,30), pair(30,30), pair(10,15), pair(15,15));
        check(policy.range(ranges,30).equals(pair(30,30)), "Prefer fixed native 30");
        check(policy.range(ranges,15).equals(pair(15,15)), "Native 15 without 30fps frame dropping");
        check(policy.range(ranges,10) == null, "Do not invent unsupported 10fps");
        check(policy.range(ranges,24) == null, "Do not silently request 30 for 24");
        check(policy.range(List.of(pair(5,15)),15) == null, "Reject AE range allowing fewer than 10fps");
        check(policy.range(List.of(pair(10,15)),15).equals(pair(10,15)), "Advertised variable range respecting minimum");
        check(policy.range(List.of(pair(10,10)),10).equals(pair(10,10)), "Use native 10 when available");
        check(policy.range(ranges,9) == null && policy.range(ranges,60) == null, "Respect 10..30 scope");
        System.out.println("PASS: full native reference, supported camera FPS, minimum 10 and no faster-rate fallback.");
    }
}
