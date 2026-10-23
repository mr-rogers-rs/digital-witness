import com.fieldnotes.recorder.crypto.RecipientEncryption;
import com.fieldnotes.recorder.integrity.OtsProofParser;
import com.fieldnotes.recorder.secure.SecureFile;
import kotlin.Unit;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.security.*;
import java.security.cert.Certificate;
import javax.crypto.spec.SecretKeySpec;

public class JvmSecurityHarness {
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    static byte[] cat(byte[]... arrays) throws Exception {
        var out = new ByteArrayOutputStream();
        for (var a : arrays) out.write(a);
        return out.toByteArray();
    }
    static byte[] pending() throws Exception {
        byte[] uri = "https://a.pool.opentimestamps.org".getBytes("US-ASCII");
        byte[] payload = cat(new byte[]{(byte)uri.length}, uri);
        return cat(new byte[]{0}, OtsProofParser.Companion.getPENDING_TAG(),
            new byte[]{(byte)payload.length}, payload);
    }
    public static class TestStore extends KeyStoreSpi {
        static final java.security.Key wrap = new SecretKeySpec(new byte[32], "AES");
        public Key engineGetKey(String alias, char[] password) { return wrap; }
        public KeyStore.Entry engineGetEntry(String alias, KeyStore.ProtectionParameter protection) {
            return new KeyStore.SecretKeyEntry((javax.crypto.SecretKey)wrap);
        }
        public Certificate[] engineGetCertificateChain(String alias) { return null; }
        public Certificate engineGetCertificate(String alias) { return null; }
        public Date engineGetCreationDate(String alias) { return new Date(); }
        public void engineSetKeyEntry(String a, Key k, char[] p, Certificate[] c) {}
        public void engineSetKeyEntry(String a, byte[] k, Certificate[] c) {}
        public void engineSetCertificateEntry(String a, Certificate c) {}
        public void engineDeleteEntry(String a) {}
        public Enumeration<String> engineAliases() { return Collections.enumeration(List.of("dw_wrap_key")); }
        public boolean engineContainsAlias(String a) { return true; }
        public int engineSize() { return 1; }
        public boolean engineIsKeyEntry(String a) { return true; }
        public boolean engineIsCertificateEntry(String a) { return false; }
        public String engineGetCertificateAlias(Certificate c) { return null; }
        public void engineStore(OutputStream out, char[] p) {}
        public void engineLoad(InputStream in, char[] p) {}
    }
    static byte[] legacy(byte[] plain) throws Exception {
        byte[] dataKey = new byte[32], salt = new byte[16];
        Arrays.fill(dataKey,(byte)19);
        var wrapping = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        wrapping.init(javax.crypto.Cipher.ENCRYPT_MODE,TestStore.wrap);
        byte[] wrapped = wrapping.doFinal(dataKey);
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.write(new byte[]{68,87,83,70,1});
        output.write(wrapping.getIV()); output.writeShort(wrapped.length); output.write(wrapped);
        output.write(salt); output.writeInt(65536);
        long index = 0;
        for (int offset=0; offset<plain.length; offset+=65536) {
            byte[] part = Arrays.copyOfRange(plain,offset,Math.min(offset+65536,plain.length));
            byte[] nonce = com.fieldnotes.recorder.crypto.CryptoEngine.INSTANCE.deriveNonce(salt,index);
            byte[] aad = java.nio.ByteBuffer.allocate(8).putLong(index++).array();
            byte[] cipher = com.fieldnotes.recorder.crypto.CryptoEngine.INSTANCE.encryptChunk(
                new SecretKeySpec(dataKey,"AES"),nonce,aad,part);
            output.writeInt(cipher.length); output.write(cipher);
        }
        return bytes.toByteArray();
    }
    static void formatTests() throws Exception {
        Security.addProvider(new Provider("AndroidKeyStore", "1", "TEST ONLY, in-memory wrapping key") {{
            put("KeyStore.AndroidKeyStore", TestStore.class.getName());
        }});
        byte[] plain = new byte[140000]; new Random(42).nextBytes(plain);
        check(Arrays.equals(plain,SecureFile.INSTANCE.decryptBytes(legacy(plain))),"legacy v1 read");
        byte[] encrypted = SecureFile.INSTANCE.encryptBytes(plain);
        check(Arrays.equals(plain, SecureFile.INSTANCE.decryptBytes(encrypted)), "secure file roundtrip");
        for (int size : new int[]{0,4,5,19,67,87,88,65643,131199,encrypted.length-1,encrypted.length-36}) {
            try { SecureFile.INSTANCE.decryptBytes(Arrays.copyOf(encrypted,size)); throw new AssertionError("truncation accepted: "+size); }
            catch (Exception expected) {}
        }
        byte[] downgrade = encrypted.clone(); downgrade[4]=1;
        try { SecureFile.INSTANCE.decryptBytes(downgrade); throw new AssertionError("downgrade accepted"); }
        catch (Exception expected) {}
        byte[] header = encrypted.clone(); header[84]^=1;
        try { SecureFile.INSTANCE.decryptBytes(header); throw new AssertionError("header mutation accepted"); }
        catch (Exception expected) {}
        byte[] trailing = Arrays.copyOf(encrypted,encrypted.length+1);
        try { SecureFile.INSTANCE.decryptBytes(trailing); throw new AssertionError("trailing bytes accepted"); }
        catch (Exception expected) {}
        byte[] empty = SecureFile.INSTANCE.encryptBytes(new byte[0]);
        check(SecureFile.INSTANCE.decryptBytes(empty).length == 0, "empty roundtrip");
        System.out.println("SecureFile v2: roundtrip, truncation, downgrade, header, trailing and empty checks passed (test Keystore).");
    }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String pem = Files.readString(dir.resolve("public.pem"));
        for (int size : new int[]{0,1,2,3,15,16,17,65535,65536,65537,2097152,4194305,8388608}) {
            Path input = dir.resolve(size+".bin");
            byte[] plain = new byte[size]; new Random(size).nextBytes(plain); Files.write(input,plain);
            Files.writeString(dir.resolve(size+".hash"), com.fieldnotes.recorder.cloud.DropboxContentHash.INSTANCE.of(input.toFile()));
            try (OutputStream out = Files.newOutputStream(dir.resolve(size+".bin.jwe"))) {
                RecipientEncryption.INSTANCE.encrypt(pem, out, stream -> {
                    try { Files.copy(input,stream); } catch(IOException e) { throw new java.io.UncheckedIOException(e); }
                    return Unit.INSTANCE;
                });
            }
        }
        byte[] digest = new byte[32];
        byte[] proof = OtsProofParser.Companion.detached(digest,pending());
        var parser = new OtsProofParser(proof);
        check(parser.isValid() && !parser.isUpgraded(), "pending validity");
        check(parser.findPendingAttestations().get(0).getCalendarUrl().equals("https://a.pool.opentimestamps.org"),"nested URI");
        byte[] bitcoin = cat(new byte[]{0},OtsProofParser.Companion.getBITCOIN_TAG(),new byte[]{1,42});
        check(new OtsProofParser(OtsProofParser.Companion.detached(digest,bitcoin)).isUpgraded(),"structural Bitcoin claim");
        byte[] spoof = cat(new byte[]{(byte)240,8},OtsProofParser.Companion.getBITCOIN_TAG(),pending());
        check(!new OtsProofParser(OtsProofParser.Companion.detached(digest,spoof)).isUpgraded(),"embedded tag not an anchor");
        byte[] forks = new byte[5000]; Arrays.fill(forks,(byte)255);
        check(!new OtsProofParser(OtsProofParser.Companion.detached(digest,forks)).isValid(),"fork limit");
        check(!new OtsProofParser(Arrays.copyOf(proof,proof.length-1)).isValid(),"truncated OTS");
        check(!new OtsProofParser(cat(proof,new byte[]{0})).isValid(),"trailing OTS");
        var upgraded = parser.spliceUpgrade(proof,parser.findPendingAttestations().get(0),bitcoin);
        check(new OtsProofParser(upgraded).isUpgraded(),"spliced upgrade");
        formatTests();
        System.out.println("OTS structural parser: 8 checks passed. Generated 13 Kotlin JWE fixtures.");
    }
}
