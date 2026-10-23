import com.fieldnotes.recorder.capture.FrameMeasurements;

public class FrameMeasurementsHarness {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        FrameMeasurements frames = new FrameMeasurements();
        check(frames.fps() == 0, "No rate before samples");
        // Large monotonic clock origin must not affect measured frame rate.
        long origin = 800_000_000_000L;
        frames.add(origin);
        check(frames.fps() == 0, "One sample cannot establish a rate");
        for (int i = 1; i <= 150; i++) frames.add(origin + Math.round(i * 1_000_000.0 / 15));
        check(Math.abs(frames.fps() - 15) < 0.0001, "Ten seconds at 15 fps");
        check(frames.getCount() == 151, "Count includes first frame");
        frames.add(origin + 11_000_000);
        check(frames.getMaxGapUs() == 1_000_000, "A one-second stall is visible");
        frames.add(origin + 11_000_000);
        frames.add(origin + 10_900_000);
        check(frames.getDiscontinuities() == 2, "Duplicate and backward timestamps are visible");
        check(frames.getMaxGapUs() == 1_000_000, "Bad timestamps do not erase largest gap");
        System.out.println("PASS: measured FPS, clock offset, stalls and timestamp discontinuities.");
    }
}
