import android.content.Context;
import android.content.SharedPreferences;
import com.fieldnotes.recorder.util.Prefs;
import com.fieldnotes.recorder.upload.UploadReadiness;
import com.fieldnotes.recorder.R;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Uses only synthetic credentials; never reads a phone or real preferences. */
public class PinVerificationHarness {
    static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        String v1 = Files.readString(Path.of(args[0], "v1.txt"));
        String v2 = Files.readString(Path.of(args[0], "v2.txt"));
        Map<String, String> values = new HashMap<>();
        var prefs = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getString"))
                    return values.getOrDefault((String) arguments[0], (String) arguments[1]);
                throw new AssertionError("Verification unexpectedly mutated/read preferences: " + method.getName());
            });
        var cache = Prefs.class.getDeclaredField("cachedPrefs");
        cache.setAccessible(true);
        cache.set(null, prefs);
        var unlockKey = Prefs.class.getDeclaredField("KEY_UNLOCK_CODE");
        var duressKey = Prefs.class.getDeclaredField("KEY_DURESS_CODE");
        unlockKey.setAccessible(true); duressKey.setAccessible(true);
        String unlock = (String) unlockKey.get(null), duress = (String) duressKey.get(null);
        Context context = new Context(); // Host-only stub; no storage methods available.
        // Require rejection before decoding/deriving a hash. SunJCE accepts
        // empty passwords, whereas the phone's provider throws, so merely
        // asserting a false comparison on the desktop would miss the bug.
        android.util.Base64.forbidDecode = true;
        for (String stored : new String[]{"v2:" + v2, "v1:" + v1, "", "7391", "v2:invalid"}) {
            values.put(unlock, stored); values.put(duress, stored);
            check(!Prefs.INSTANCE.verifyUnlockCode(context, ""), "Blank unlock rejected for " + stored.substring(0, Math.min(3, stored.length())));
            check(!Prefs.INSTANCE.verifyDuressCode(context, ""), "Blank duress rejected");
        }
        android.util.Base64.forbidDecode = false;
        values.put(unlock, "v2:" + v2); values.put(duress, "v2:" + v2);
        check(Prefs.INSTANCE.verifyUnlockCode(context, "7391"), "Existing v2 unlock still works");
        check(Prefs.INSTANCE.verifyDuressCode(context, "7391"), "Existing v2 duress still works");
        check(!Prefs.INSTANCE.verifyUnlockCode(context, "7392"), "Wrong unlock rejected");
        check(!Prefs.INSTANCE.verifyDuressCode(context, "7392"), "Wrong duress rejected");

        var verify = Prefs.class.getDeclaredMethod("verifyHash", String.class, String.class, int.class);
        verify.setAccessible(true);
        check((boolean) verify.invoke(Prefs.INSTANCE, "7391", v1, 100000), "Existing v1 hash still works");
        check(!(boolean) verify.invoke(Prefs.INSTANCE, "7392", v1, 100000), "Wrong v1 PIN rejected");
        android.util.Base64.forbidDecode = true;
        check(!(boolean) verify.invoke(Prefs.INSTANCE, "", v2, 600000), "Private hash guard rejects blank");
        android.util.Base64.forbidDecode = false;
        for (String bad : new String[]{"not-base64!", "", "AA=="})
            check(!(boolean) verify.invoke(Prefs.INSTANCE, "7391", bad, 600000), "Malformed hash rejected");
        values.put("export_mode", "unconfigured");
        check(UploadReadiness.INSTANCE.blocker(context) == R.string.upload_block_choice,
            "Fresh setup identifies missing export choice before any network work");
        values.put("export_mode", "plaintext");
        check(UploadReadiness.INSTANCE.blocker(context) == R.string.upload_block_destination,
            "Plaintext is a valid upload choice; only the absent destination blocks it");
        values.put("export_mode", "encrypted");
        values.put("recipient_public_key", "not a public key");
        check(UploadReadiness.INSTANCE.blocker(context) == R.string.upload_block_key,
            "Recipient encryption with an invalid key has an actionable blocker");
        System.out.println("PASS: empty PIN regression, unlock/duress rejection, v1/v2 compatibility, wrong PIN and malformed hash checks.");
        System.out.println("PASS: missing upload choice, plaintext acceptance and invalid recipient-key readiness checks.");
    }
}
