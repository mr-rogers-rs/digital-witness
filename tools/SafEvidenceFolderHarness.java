import androidx.documentfile.provider.DocumentFile;
import com.fieldnotes.recorder.upload.SafEvidenceFolder;

public class SafEvidenceFolderHarness {
    static class Doc extends DocumentFile {
        boolean directory = true, readable = true, writable = true, allowCreation = true;
        Doc child;
        int creations;
        public boolean isDirectory() { return directory; }
        public boolean canRead() { return readable; }
        public boolean canWrite() { return writable; }
        public DocumentFile findFile(String name) { return child; }
        public DocumentFile createDirectory(String name) {
            creations++;
            return allowCreation ? (child = new Doc()) : null;
        }
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var resolver = SafEvidenceFolder.INSTANCE;
        var root = new Doc();
        check(resolver.resolve(root, "") == root && root.creations == 0, "Videos stay at selected root");
        var first = resolver.resolve(root, "Evidence");
        check(first == root.child && root.creations == 1, "Create sidecar subfolder once");
        check(resolver.resolve(root, "Evidence") == first && root.creations == 1, "Reuse same folder on retry");
        root.child.directory = false;
        check(resolver.resolve(root, "Evidence") == null && root.creations == 1, "Do not replace conflicting file");
        root.child.directory = true; root.child.readable = false;
        check(resolver.resolve(root, "Evidence") == null, "Reject subfolder without read-back access");
        root.child.readable = true; root.child.writable = false;
        check(resolver.resolve(root, "Evidence") == null, "Reject read-only subfolder");
        root.child = null; root.allowCreation = false;
        check(resolver.resolve(root, "Evidence") == null, "Provider creation failure does not fall back to root");
        root.writable = false;
        check(resolver.resolve(root, "") == null, "Revoked root access blocks delivery");
        System.out.println("PASS: SAF folder creation, reuse, access checks and non-destructive conflict/failure handling.");
    }
}
