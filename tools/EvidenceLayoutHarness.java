import com.fieldnotes.recorder.upload.EvidenceLayout;

public class EvidenceLayoutHarness {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var layout = EvidenceLayout.INSTANCE;
        for (String media : new String[]{"id.mp4", "id.mp4.enc", "id.mp4.jwe", "id.MP4.enc"})
            check(layout.folderFor(media).equals(""), "Media stays at root: " + media);
        for (String proof : new String[]{"id.json.enc", "id.ots", "id.ots.jwe", "id.mp4.telemetry.json.enc", "unknown.bin"})
            check(layout.folderFor(proof).equals("Evidence"), "Sidecar grouped: " + proof);
        check(layout.path("/Documents/", "Evidence").equals("/Documents/Evidence"), "Nextcloud configured root preserved");
        check(layout.path("/", "Evidence").equals("/Evidence"), "Provider root supported");
        check(layout.path("/Backups/", "").equals("/Backups"), "Legacy bound retry stays at root");
        boolean rejected = false;
        try { layout.path("/Backups", "../elsewhere"); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Reject arbitrary path in delivery state");
        System.out.println("PASS: media/sidecar routing, encrypted names, stable roots and traversal rejection.");
    }
}
