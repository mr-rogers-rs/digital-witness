import android.content.Context;
import android.content.SharedPreferences;
import com.fieldnotes.recorder.util.Prefs;
import com.fieldnotes.recorder.util.ResolutionPolicy;
import kotlin.Pair;
import java.lang.reflect.Proxy;
import java.util.*;

public class ResolutionPolicyHarness {
    static Pair<Integer, Integer> size(int w, int h) { return new Pair<>(w, h); }
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        var sizes = List.of(size(3840,2160), size(1920,1080), size(1440,1080), size(1280,960), size(1280,720), size(640,480));
        check(ResolutionPolicy.INSTANCE.choose(sizes, 4.0/3, 960).equals(size(1280,960)), "Prefer full sensor shape within balanced cap");
        check(ResolutionPolicy.INSTANCE.choose(sizes, 4.0/3, 1080).equals(size(1440,1080)), "4:3 before larger cropped 16:9");
        check(ResolutionPolicy.INSTANCE.choose(sizes, 4.0/3, 0).equals(size(1440,1080)), "Unlimited pixels must not override sensor shape");
        check(ResolutionPolicy.INSTANCE.choose(sizes, 16.0/9, 720).equals(size(1280,720)), "Native widescreen sensor");
        check(ResolutionPolicy.INSTANCE.choose(List.of(size(1920,1080), size(640,480)), 4.0/3, 960).equals(size(640,480)), "Smaller full view before larger cropped view");
        Map<String, Object> values = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, argv) -> {
                switch (method.getName()) {
                    case "putString", "putBoolean", "putInt": values.put((String)argv[0], argv[1]); return proxy;
                    case "remove": values.remove(argv[0]); return proxy;
                    case "commit": return true;
                    case "apply": return null;
                    default: throw new AssertionError(method.getName());
                }
            });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, argv) -> {
                if (method.getName().equals("edit")) return editor;
                if (Set.of("getString", "getBoolean", "getInt").contains(method.getName()))
                    return values.getOrDefault(argv[0], argv[1]);
                throw new AssertionError(method.getName());
            });
        var cache = Prefs.class.getDeclaredField("cachedPrefs"); cache.setAccessible(true); cache.set(null, prefs);
        Context context = new Context();
        check(Prefs.INSTANCE.videoBitrateMode(context).equals("target") && Prefs.INSTANCE.videoTargetKbps(context) == 2000,
            "Fresh individual recording starts at the balanced 2 Mb/s target");
        check(Prefs.INSTANCE.adaptiveVideoBitrate(context), "Queue-aware bitrate adjustment is available to individual users");
        check(Prefs.INSTANCE.recordingMode(context).equals("evidence") && !Prefs.INSTANCE.isSecureNetworkRequired(context),
            "Fresh installs use evidence mode and allow ordinary connections");
        check(Prefs.INSTANCE.exportMode(context).equals("plaintext"), "Fresh uploads are ordinary playable files");
        Prefs.INSTANCE.setRecordingMode(context, "anonymous");
        Prefs.INSTANCE.setSecureNetworkRequired(context, true);
        Prefs.INSTANCE.setExportPolicy(context, "encrypted", "synthetic public key");
        check(Prefs.INSTANCE.isAnonymousMode(context) && Prefs.INSTANCE.isSecureNetworkRequired(context)
            && Prefs.INSTANCE.exportMode(context).equals("encrypted"),
            "Saved anonymous, VPN-required and recipient-encryption choices survive new defaults");
        values.put("recording_mode", "invalid");
        check(Prefs.INSTANCE.isAnonymousMode(context), "Malformed saved mode retains the private fallback");
        values.put("export_mode", "unconfigured");
        check(Prefs.INSTANCE.exportMode(context).equals("unconfigured"), "Legacy unresolved export policy is not silently weakened");
        Prefs.INSTANCE.setAdaptiveVideoBitrate(context, false);
        check(!Prefs.INSTANCE.adaptiveVideoBitrate(context), "An explicit opt-out of adaptive bitrate persists");
        Prefs.INSTANCE.setVideoBitrateMode(context, "auto");
        check(Prefs.INSTANCE.isAudioEnabled(context), "Existing installs keep audio enabled");
        Prefs.INSTANCE.setAudioEnabled(context, false);
        check(!Prefs.INSTANCE.isAudioEnabled(context), "Silent recording choice persists");
        check(Prefs.INSTANCE.cameraBackend(context).equals("camera2"), "Existing recorder remains default");
        Prefs.INSTANCE.setCameraBackend(context, "camerax");
        check(Prefs.INSTANCE.cameraBackend(context).equals("camerax"), "Explicit CameraX opt-in persists");
        values.put("camera_backend", "invalid");
        check(Prefs.INSTANCE.cameraBackend(context).equals("camera2"), "Invalid backend preference uses default");
        check(Prefs.INSTANCE.isFullCameraView(context), "Full 1x view is the upgrade/fresh-install default");
        Prefs.INSTANCE.setFullCameraView(context, false);
        check(Prefs.INSTANCE.isFullCameraView(context), "Legacy stabilization/crop preference cannot disable full view");
        Prefs.INSTANCE.setCameraSourceResolution(context, "0", size(1440,1080));
        check(Prefs.INSTANCE.cameraSourceResolution(context, "0").equals(size(1440,1080)), "Source saved independently");
        Prefs.INSTANCE.setCameraBackend(context, "camerax");
        check(Prefs.INSTANCE.cameraSourceResolution(context, "0") == null, "Source is qualified separately per backend");
        Prefs.INSTANCE.setCameraBackend(context, "camera2");
        Prefs.INSTANCE.setCalibratedResolution(context, "0", 1440,1080);
        check(Prefs.INSTANCE.calibratedResolution(context, "0").equals(size(1440,1080)), "Save exact width AND height");
        check(Prefs.INSTANCE.calibratedResolution(context, "1") == null, "Do not reuse another camera's test");
        Prefs.INSTANCE.setVideoPreset(context, "balanced");
        Prefs.INSTANCE.setAudioKbps(context, 96);
        Prefs.INSTANCE.setAudioSampleRate(context, 32000);
        Prefs.INSTANCE.setVideoMaxHeight(context, 1080);
        Prefs.INSTANCE.applyVideoPreset(context);
        check(Prefs.INSTANCE.videoFps(context) == 30, "Normal native 30fps");
        check(Prefs.INSTANCE.calibratedResolution(context, "0").equals(size(1440,1080)), "Recording preset must not erase selection");
        Prefs.INSTANCE.applyVideoPreset(context, 24);
        check(Prefs.INSTANCE.videoFps(context) == 24, "Normal honors a phone whose native maximum is 24");
        Prefs.INSTANCE.setVideoPreset(context, "battery");
        Prefs.INSTANCE.applyVideoPreset(context);
        check(Prefs.INSTANCE.videoMaxHeight(context) == 1080 && Prefs.INSTANCE.videoFps(context) == 15,
            "Lower FPS must not lower resolution");
        check(Prefs.INSTANCE.videoBitrateMode(context).equals("auto"), "An explicit resolution-based auto bitrate survives FPS changes");
        check(Prefs.INSTANCE.audioKbps(context) == 96 && Prefs.INSTANCE.audioSampleRate(context) == 32000,
            "Lower FPS must not alter normal audio");
        Prefs.INSTANCE.setVideoBitrateMode(context, "target");
        Prefs.INSTANCE.setVideoTargetKbps(context, 1000);
        Prefs.INSTANCE.applyVideoPreset(context);
        check(Prefs.INSTANCE.videoBitrateMode(context).equals("target") && Prefs.INSTANCE.videoTargetKbps(context) == 1000,
            "Starting a native FPS preset must preserve the selected bitrate");
        var bitrate = com.fieldnotes.recorder.capture.VideoBitratePolicy.INSTANCE;
        check(bitrate.requestedBps(1440, 1080, 30, "target", 1000, 60, "high") == 1000000, "Manual kbps reaches encoder in bps");
        check(bitrate.requestedBps(1440, 1080, 15, "target", 1000, 60, "high") == 1000000, "Explicit bitrate independent of FPS");
        check(bitrate.requestedBps(1440, 1080, 30, "auto", 1000, 60, "high") == 6000000, "Automatic full view bitrate");
        check(bitrate.requestedBps(1440, 1080, 15, "auto", 1000, 60, "high") == 3000000, "Automatic scales with native FPS");
        check(bitrate.requestedBps(640, 480, 10, "bpp", 1000, 60, "high") == 200000, "Legacy per-frame mode and minimum preserved");
        check(bitrate.requestedBps(1440, 1080, 30, "target", Integer.MAX_VALUE, 60, "high") == 50000000, "Target cannot overflow bps");
        Prefs.INSTANCE.setVideoPreset(context, "minimum"); Prefs.INSTANCE.applyVideoPreset(context);
        check(Prefs.INSTANCE.videoFps(context) == 10 && Prefs.INSTANCE.audioKbps(context) == 96, "10fps floor preserves audio");
        check(!Prefs.INSTANCE.isAudioEnabled(context), "FPS presets must not re-enable a disabled microphone");
        Prefs.INSTANCE.setAudioEnabled(context, true);
        check(Prefs.INSTANCE.isAudioEnabled(context), "Audio can be explicitly re-enabled");
        check(Prefs.INSTANCE.verifiedNativeResolution(context,"0","native-fps10") == null, "Legacy GPU selection is never a verified native view");
        Prefs.INSTANCE.setVerifiedNativeResolution(context,"0",size(640,480),"native-fps10");
        check(Prefs.INSTANCE.verifiedNativeResolution(context,"0","native-fps10").equals(size(640,480)), "Explicit native comparison persists");
        check(Prefs.INSTANCE.verifiedNativeResolution(context,"0","native-fps15") == null, "FPS/settings change invalidates native comparison");
        Prefs.INSTANCE.setCameraBackend(context,"camerax");
        check(Prefs.INSTANCE.verifiedNativeResolution(context,"0","native-fps10") == null, "Backend cannot inherit another backend comparison");
        Prefs.INSTANCE.setCameraBackend(context,"camera2");
        Prefs.INSTANCE.clearVerifiedNativeResolution(context,"0");
        check(Prefs.INSTANCE.verifiedNativeResolution(context,"0","native-fps10") == null, "Return to full native reference");
        check(Prefs.INSTANCE.calibratedResolution(context, "0").equals(size(1440,1080)), "Economy keeps explicit camera choice");
        check(Prefs.INSTANCE.cameraSourceResolution(context, "0").equals(size(1440,1080)), "Economy cannot change source framing");
        Prefs.INSTANCE.clearCalibratedResolution(context);
        check(Prefs.INSTANCE.calibratedResolution(context, "0") == null, "Return to automatic selection");
        System.out.println("PASS: full-view resolution selection, exact preference persistence, camera binding and preset/automatic behavior.");
    }
}
