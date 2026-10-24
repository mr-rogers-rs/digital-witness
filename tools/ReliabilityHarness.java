import android.content.Context;
import android.media.MediaFormat;
import com.fieldnotes.recorder.capture.CaptureJournal;
import com.fieldnotes.recorder.capture.CaptureRecovery;
import com.fieldnotes.recorder.capture.CaptureLimits;
import com.fieldnotes.recorder.capture.CaptureDiagnostics;
import com.fieldnotes.recorder.capture.SegmentMuxer;
import com.fieldnotes.recorder.integrity.ProofManager;
import com.fieldnotes.recorder.upload.UploadQueue;
import com.fieldnotes.recorder.upload.DeliveryPolicy;
import com.fieldnotes.recorder.util.PanicWipe;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

public class ReliabilityHarness {
    static final Context ctx = new Context();
    static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); }
    public static class Store extends JvmSecurityHarness.TestStore {
        static final KeyPair signing;
        static { try { var generator=KeyPairGenerator.getInstance("EC"); generator.initialize(256); signing=generator.generateKeyPair(); }
            catch(Exception e) { throw new RuntimeException(e); } }
        @Override public Key engineGetKey(String a,char[] p) { return a.equals("dw_signing_key")? signing.getPrivate():super.engineGetKey(a,p); }
        @Override public Certificate engineGetCertificate(String a) { return new Certificate("X.509") {
            public byte[] getEncoded() { return signing.getPublic().getEncoded(); }
            public void verify(PublicKey k) {} public void verify(PublicKey k,String p) {}
            public String toString() { return "Test public key"; } public PublicKey getPublicKey() { return signing.getPublic(); }
        }; }
    }
    static boolean recover() throws Exception { return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
        (scope,k) -> CaptureRecovery.INSTANCE.run(ctx,k)); }
    static CaptureJournal journal() {
        var j=CaptureJournal.Companion.create(new File(ctx.getFilesDir(),"capture_journal"),"interrupted-synthetic-session","test-target",90);
        var format=new MediaFormat(); format.setString("mime","video/avc"); format.setInteger("width",640); format.setInteger("height",480);
        format.setByteBuffer("csd-0",java.nio.ByteBuffer.wrap(new byte[]{1,2,3})); j.format(true,format);
        return j;
    }
    static void add(CaptureJournal j,long pts,boolean key) { j.append(true,pts,key,("original-video-"+pts).getBytes()); }
    static void reset() throws Exception {
        Path files=ctx.getFilesDir().toPath();
        try(var paths=Files.walk(files)) { for(var p:paths.sorted(Comparator.reverseOrder()).toList()) if(!p.equals(files)) Files.delete(p); }
        PanicWipe.blocked=false; DeliveryPolicy.destination="test-target"; SegmentMuxer.calls=0;
    }
    static void journalDoesNotBlockCapture(boolean failCommit) throws Exception {
        reset();
        var journal=journal(); add(journal,1000,true);
        android.util.AtomicFile.slowName="000000000000.spf";
        android.util.AtomicFile.entered=new CountDownLatch(1);
        android.util.AtomicFile.release=new CountDownLatch(1);
        if(failCommit) android.util.AtomicFile.failName="000000000000.spf";
        var pool=Executors.newFixedThreadPool(2);
        boolean appendFinished=false;
        Future<?> writer=null, capture=null;
        try {
            writer=pool.submit(journal::flush);
            check(android.util.AtomicFile.entered.await(5,TimeUnit.SECONDS),"Journal reached simulated slow disk");
            capture=pool.submit(() -> add(journal,2000,false));
            try { capture.get(500,TimeUnit.MILLISECONDS); appendFinished=true; }
            catch(TimeoutException expected) { }
        } finally {
            android.util.AtomicFile.release.countDown();
            if(writer!=null) try { writer.get(5,TimeUnit.SECONDS); check(!failCommit,"Write should fail"); }
                catch(ExecutionException e) { check(failCommit,"Unexpected journal failure"); }
            if(capture!=null) capture.get(5,TimeUnit.SECONDS);
            android.util.AtomicFile.slowName=null;
            pool.shutdownNow();
        }
        check(appendFinished,"Capture callback must not wait for encrypted journal disk writes");
        journal.flush();
        var packets=CaptureJournal.Companion.open(journal.getDirectory()).remaining();
        check(packets.size()==2 && packets.get(0).getPts()==1000 && packets.get(1).getPts()==2000,
            "Concurrent append and failed-write retry preserve exact packet order without duplication");
    }
    public static void main(String[] args) throws Exception {
        Security.addProvider(new Provider("AndroidKeyStore","1","TEST ONLY") {{ put("KeyStore.AndroidKeyStore",Store.class.getName()); }});
        journalDoesNotBlockCapture(false);
        journalDoesNotBlockCapture(true);
        reset();
        CaptureDiagnostics.INSTANCE.record(ctx,"capture_buffer_full",24000000,3);
        check(CaptureDiagnostics.INSTANCE.lastProblem(ctx)!=null,"Stop reason survives a later read without volatile status");
        android.util.AtomicFile.failName="capture_stop.spf";
        boolean diagnosticFailed=false;
        try { CaptureDiagnostics.INSTANCE.record(ctx,"user_stop",0,3); } catch(Exception expected) { diagnosticFailed=true; }
        check(diagnosticFailed && new org.json.JSONObject(CaptureDiagnostics.INSTANCE.report(ctx)).getString("reason").equals("capture_buffer_full"),"Interrupted diagnostic commit retains the last reason");
        CaptureDiagnostics.INSTANCE.record(ctx,"user_stop",0,3);
        check(CaptureDiagnostics.INSTANCE.lastProblem(ctx)==null,"A deliberate stop clears an older failure reason");
        reset();
        var j=journal(); add(j,1000,true); add(j,2000,true); j.flush(); add(j,3000,true);
        var reopened=CaptureJournal.Companion.open(j.getDirectory());
        check(reopened.remaining().size()==2,"Only durable batches survive process boundary; unflushed tail is not invented");
        var format=CaptureJournal.Companion.restoreFormat(reopened.getState().getJSONObject("videoFormat"));
        check(format.getInteger("width")==640 && format.getByteBuffer("csd-0").remaining()==3,"Codec format and config survive restart");
        var block=Arrays.stream(j.getDirectory().listFiles()).filter(f->f.getName().matches("[0-9]{12}\\.spf")).findFirst().orElseThrow();
        check(!new String(Files.readAllBytes(block.toPath()),java.nio.charset.StandardCharsets.ISO_8859_1).contains("original-video"),"Journal encrypted by production SecureFile");
        String stem=UUID.randomUUID().toString(); reopened.beginSeal(stem,-1,1000); reopened.completeSeal();
        check(CaptureJournal.Companion.open(j.getDirectory()).remaining().size()==1,"Per-track cursor preserves boundary carry-over");
        check(recover(),"Partial recording recovers locally");
        check(UploadQueue.INSTANCE.listPending(ctx).size()==2 && SegmentMuxer.calls==1,"Recovered media and signed proof published once");
        check(recover() && UploadQueue.INSTANCE.listPending(ctx).size()==2,"Repeated recovery does not duplicate publication");
        check(com.fieldnotes.recorder.util.Prefs.writes==0,"Recovery proof does not overwrite normal chain state");
        Path verified=Path.of(args[0]); Files.createDirectories(verified);
        for(File enc:UploadQueue.INSTANCE.listPending(ctx)) {
            File plain=UploadQueue.INSTANCE.decryptToTemp(ctx,enc);
            Files.copy(plain.toPath(),verified.resolve(plain.getName()),StandardCopyOption.REPLACE_EXISTING);
        }
        reset(); j=journal(); add(j,1000,true); j.flush(); stem=UUID.randomUUID().toString(); j.beginSeal(stem,-1,1000);
        var work=new File(ctx.getFilesDir(),"simple_capture"); work.mkdirs(); var mp4=new File(work,stem+".mp4"); Files.writeString(mp4.toPath(),"synthetic-muxed-video");
        var proof=new ProofManager(ctx,"recovery-"+stem,"old-session",false).generateProof(mp4,"synthetic",30,2000,0,0,null);
        check(proof!=null,"Proof durable before queue publication");
        var queued=UploadQueue.INSTANCE.enqueueBundle(ctx,List.of(mp4,proof),stem); check(queued!=null,"Bundle commit");
        for(File f:queued) UploadQueue.INSTANCE.markUploaded(ctx,f);
        check(UploadQueue.INSTANCE.listPending(ctx).isEmpty() && UploadQueue.INSTANCE.publicationExists(ctx,stem),"Commit witness survives all member acknowledgments");
        check(recover() && SegmentMuxer.calls==0 && UploadQueue.INSTANCE.listPending(ctx).isEmpty(),"Kill after queue publication before cursor does not recreate already delivered footage");
        reset(); j=journal(); add(j,1000,true); j.flush(); stem=UUID.randomUUID().toString(); j.beginSeal(stem,-1,1000);
        android.util.AtomicFile.failName="state.spf"; boolean failed=false;
        try { j.completeSeal(); } catch(Exception expected) { failed=true; }
        check(failed && CaptureJournal.Companion.open(j.getDirectory()).remaining().size()==1,"Failed cursor commit retains samples");
        check(recover(),"Failed cursor transaction can be replayed");
        reset(); j=journal(); add(j,1000,true); j.flush(); DeliveryPolicy.failBind=true;
        check(!recover() && UploadQueue.INSTANCE.listPending(ctx).isEmpty() && j.getDirectory().isDirectory(),"Failed bundle binding retains journal and publishes no partial bundle");
        DeliveryPolicy.failBind=false;
        check(recover() && UploadQueue.INSTANCE.listPending(ctx).size()==2,"Bundle retry publishes media and proof together");
        reset(); j=journal(); add(j,1000,true); j.flush(); DeliveryPolicy.destination="changed-target";
        check(!recover() && SegmentMuxer.calls==0 && j.getDirectory().isDirectory(),"Changed destination retains journal without mux/upload");
        DeliveryPolicy.destination="test-target"; PanicWipe.blocked=true;
        check(!recover() && UploadQueue.INSTANCE.listPending(ctx).isEmpty(),"Wipe gate blocks recovery publication");
        PanicWipe.blocked=false;
        block=Arrays.stream(j.getDirectory().listFiles()).filter(f->f.getName().matches("[0-9]{12}\\.spf")).findFirst().orElseThrow();
        var bytes=Files.readAllBytes(block.toPath()); bytes[bytes.length-1]^=1; Files.write(block.toPath(),bytes);
        check(!recover() && j.getDirectory().isDirectory(),"Corrupt journal fails closed and is retained");
        reset();
        var damaged=journal(); add(damaged,1000,true); damaged.flush();
        var stateFile=new File(damaged.getDirectory(),"state.spf");
        var damagedBytes=Files.readAllBytes(stateFile.toPath()); damagedBytes[damagedBytes.length-1]^=1;
        Files.write(stateFile.toPath(),damagedBytes);
        var healthy=journal(); add(healthy,1000,true); healthy.flush();
        check(!recover(),"An unrecoverable journal remains reported");
        check(damaged.getDirectory().isDirectory() && !healthy.getDirectory().exists(),
            "A damaged journal is retained without preventing healthy journal recovery");
        check(UploadQueue.INSTANCE.listPending(ctx).size()==2 && CaptureRecovery.INSTANCE.getFailureCode()!=null,
            "Healthy media/proof is published and a content-free support code is retained");
        var lease=CaptureRecovery.INSTANCE.tryClaimCapture();
        check(lease!=null,"Failed pending recovery does not indefinitely block a new user capture");
        try {
            check(CaptureRecovery.INSTANCE.tryClaimCapture()==null,"Only one capture/recovery owner can hold the writer lease");
            check(!recover(),"Recovery does not wait on or modify journals while capture owns the writers");
            com.fieldnotes.recorder.capture.CaptureStatus.INSTANCE.start(1000);
            CaptureRecovery.INSTANCE.request(ctx);
            check(CaptureRecovery.INSTANCE.message()==null,"An active journal is not displayed as interrupted capture");
            var fresh=journal(); add(fresh,2000,true); fresh.flush();
            check(!fresh.getDirectory().equals(damaged.getDirectory()),"New capture uses an independent journal");
            check(Arrays.equals(Files.readAllBytes(stateFile.toPath()),damagedBytes),"A new capture never rewrites older damaged data");
        } finally {
            com.fieldnotes.recorder.capture.CaptureStatus.INSTANCE.setRunning(false);
            CaptureRecovery.INSTANCE.releaseCapture(lease);
        }
        check(!CaptureRecovery.INSTANCE.blocksNewRecording(),"Writer ownership is released after capture cleanup");
        reset(); j=journal(); byte[] large=new byte[8*1024*1024];
        for(int i=0;i<4;i++) j.append(true,1000+i,i==0,large);
        failed=false; try { j.append(true,5000,false,new byte[1]); } catch(Exception expected) { failed=true; }
        check(failed,"Journal memory bound rejects additional data");
        long reserve=CaptureLimits.INSTANCE.reserveBytes(2000,32,10);
        check(!CaptureLimits.INSTANCE.canStart(reserve-1,reserve) && CaptureLimits.INSTANCE.canStart(reserve,reserve),"Low-storage boundary and reserve arithmetic");
        System.out.println("PASS: encrypted journal restart, codec restoration, carry-over, signed partial recovery, atomic replay, failure isolation, new capture despite a retained damaged journal, exclusive writer ownership, destination/wipe gates and memory/storage limits.");
    }
}
