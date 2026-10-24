import com.fieldnotes.recorder.capture.CaptureStatus;

/** Exercises the production state reducer without a camera or personal footage. */
public class CaptureStatusHarness {
    static final CaptureStatus status = CaptureStatus.INSTANCE;
    static void expect(CaptureStatus.State expected, long now, boolean video) {
        var actual = status.state(now, video);
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        expect(CaptureStatus.State.IDLE, 1000, true);
        if (status.recordingControlEnabled(false, true) || status.recordingControlEnabled(true, false))
            throw new AssertionError("A new recording bypassed recovery/start gating");
        if (!status.recordingControlEnabled(false, false))
            throw new AssertionError("Ready recording cannot start");
        status.start(1000);
        if (!status.recordingControlEnabled(false, true) || !status.recordingControlEnabled(true, true))
            throw new AssertionError("Recovery or stale start state disabled Stop during capture");
        expect(CaptureStatus.State.STARTING, 2000, true);
        expect(CaptureStatus.State.STALLED, 11000, true);
        status.setLastAudio(12000);
        expect(CaptureStatus.State.AUDIO_ONLY, 12000, true);
        expect(CaptureStatus.State.RECORDING, 12000, false);
        status.setLastVideo(12000);
        expect(CaptureStatus.State.RECORDING, 12000, true);
        expect(CaptureStatus.State.STALLED, 17000, true);
        status.setLastVideo(18000);
        expect(CaptureStatus.State.VIDEO_ONLY, 18000, true);
        expect(CaptureStatus.State.STALLED, 18000, false);
        status.setStopping(true);
        if (status.recordingControlEnabled(false, true))
            throw new AssertionError("A duplicate Stop remained enabled during sealing");
        expect(CaptureStatus.State.STOPPING, 18000, true);
        status.setRunning(false);
        expect(CaptureStatus.State.IDLE, 18000, true);
        status.setProblem("synthetic failure"); status.setSavedSegments(4);
        status.start(20000);
        expect(CaptureStatus.State.STARTING, 20000, true);
        if (status.getProblem() != null || status.getSavedSegments() != 0 || status.getLastAudio() != 0)
            throw new AssertionError("New session retained stale capture evidence");
        status.setLastVideo(31000);
        if (status.state(31000, true, false) != CaptureStatus.State.RECORDING)
            throw new AssertionError("Intentional silent video reported as microphone failure");
        if (status.state(31000, true, true) != CaptureStatus.State.VIDEO_ONLY)
            throw new AssertionError("Unexpected microphone failure was hidden");
        if (status.state(36000, true, false) != CaptureStatus.State.STALLED)
            throw new AssertionError("Silent recording hid stale video");
        status.setLastAudio(36000);
        if (status.state(36000, false, true) != CaptureStatus.State.RECORDING)
            throw new AssertionError("Audio-only recording regressed");
        if (status.state(36000, false, false) == CaptureStatus.State.RECORDING)
            throw new AssertionError("No enabled tracks reported as recording");
        status.setTorchAvailable(true); status.setTorchEnabled(true); status.setTorchProblem("synthetic failure");
        status.start(40000);
        if (status.getTorchAvailable() || status.getTorchEnabled() || status.getTorchProblem() != null)
            throw new AssertionError("New session retained stale torch state");
        System.out.println("PASS: capture state, Stop remains available during recovery, start/seal gating, silent video, microphone failure, stale video, audio-only and torch reset.");
    }
}
