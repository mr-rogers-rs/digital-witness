import android.content.Context;
import android.os.SystemClock;
import com.fieldnotes.recorder.cloud.CloudUploadManager;
import com.fieldnotes.recorder.upload.UploadDrain;
import com.fieldnotes.recorder.upload.UploadQueue;
import com.fieldnotes.recorder.upload.UploadStatus;
import com.fieldnotes.recorder.upload.DeliveryPolicy;
import com.fieldnotes.recorder.upload.TransferTuning;
import com.fieldnotes.recorder.upload.SafDocumentLookup;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

/** Compiled drain orchestration with synthetic queues/providers; never uses Google credentials. */
public class UploadDrainHarness {
    static final Context ctx = new Context();
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    static UploadDrain.Outcome run() throws Exception {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
            (scope, continuation) -> UploadDrain.INSTANCE.run(ctx, continuation));
    }
    static File add(String name) throws Exception {
        File file = new File(ctx.getFilesDir(), name + ".mp4.enc");
        Files.writeString(file.toPath(), "Synthetic original bytes");
        UploadQueue.pending.add(file); return file;
    }
    static void reset() {
        UploadQueue.pending.clear(); UploadQueue.acknowledged.clear(); CloudUploadManager.confirmed.clear();
        CloudUploadManager.instances=0; CloudUploadManager.calls.set(0);
        CloudUploadManager.failName=null; CloudUploadManager.cancel=false;
        CloudUploadManager.afterFirst=null; CloudUploadManager.tick=false;
        CloudUploadManager.transientFailure=false;
        CloudUploadManager.backoffResponse=false; CloudUploadManager.cancelAfter=0;
        CloudUploadManager.activeNames.clear();
        CloudUploadManager.advance=false; CloudUploadManager.failAfter=0; CloudUploadManager.failed=false;
        CloudUploadManager.launchesAfterFailure=0;
        CloudUploadManager.slowName=null; CloudUploadManager.releaseSlow=new CountDownLatch(1);
        CloudUploadManager.maxActive.set(0); CloudUploadManager.active.set(0);
        SystemClock.now=0; DeliveryPolicy.changed=false; DeliveryPolicy.generation++;
        com.fieldnotes.recorder.util.Prefs.method="cloud";
        android.content.ContentResolver.loading=false; android.content.ContentResolver.error=false;
        com.fieldnotes.recorder.util.PanicWipe.blocked=false;
    }
    public static void main(String[] args) throws Exception {
        reset(); File first=add("first"); File failed=add("failed");
        CloudUploadManager.failName="failed.mp4";
        CloudUploadManager.afterFirst=() -> { try { add("arrived_during_upload"); } catch(Exception e) { throw new RuntimeException(e); } };
        check(run()==UploadDrain.Outcome.RETRY,"Actual failed delivery must retain retry status");
        check(UploadQueue.pending.equals(java.util.List.of(failed)),"Newly arriving part drained in the same pass; failed file retained");
        check(UploadQueue.acknowledged.size()==2 && CloudUploadManager.instances==1,"One provider reused; only confirmed files acknowledged");
        check(CloudUploadManager.calls.get()==3,"Failed file attempted once per pass");
        CloudUploadManager.failName=null;
        check(run()==UploadDrain.Outcome.COMPLETE && UploadQueue.pending.isEmpty(),"Retry drains original retained file");
        reset(); add("budget_first"); add("budget_second"); CloudUploadManager.tick=true;
        check(run()==UploadDrain.Outcome.MORE && UploadQueue.pending.size()==1,"Time budget yields without treating new work as a network failure");
        CloudUploadManager.tick=false;
        check(run()==UploadDrain.Outcome.COMPLETE,"Immediate successor drains remaining part");
        reset(); File cancelled=add("cancelled"); CloudUploadManager.cancel=true;
        boolean propagated=false;
        try { run(); } catch(java.util.concurrent.CancellationException expected) { propagated=true; }
        check(propagated && UploadQueue.pending.contains(cancelled) && UploadQueue.acknowledged.isEmpty(),"Cancellation preserves queued evidence");
        check(!UploadStatus.INSTANCE.getActive(),"Cancellation clears active status");
        reset(); add("quota_a"); add("quota_b"); CloudUploadManager.transientFailure=true;
        check(run()==UploadDrain.Outcome.RETRY && CloudUploadManager.calls.get()==1 && UploadQueue.pending.size()==2,
            "Provider-wide quota/outage stops this pass after one attempt and preserves all files");
        reset(); add("throttle_a"); add("throttle_b"); CloudUploadManager.backoffResponse=true;
        check(run()==UploadDrain.Outcome.RETRY && CloudUploadManager.calls.get()==1 && UploadQueue.pending.size()==2,
            "HTTP throttle signal survives helpers returning false and stops new work");
        reset(); add("serial_a"); add("serial_b");
        var threads=Executors.newFixedThreadPool(2);
        try {
            var a=threads.submit(() -> run()); var b=threads.submit(() -> run());
            check(a.get()==UploadDrain.Outcome.COMPLETE && b.get()==UploadDrain.Outcome.COMPLETE,"Both drain owners complete");
            check(CloudUploadManager.maxActive.get()==1 && CloudUploadManager.calls.get()==2,"Service and worker drain attempts serialize without duplicate sends");
        } finally { threads.shutdownNow(); }
        reset(); File changed=add("changed"); DeliveryPolicy.changed=true;
        check(run()==UploadDrain.Outcome.RETRY && CloudUploadManager.calls.get()==0 && UploadQueue.pending.contains(changed),"Changed destination blocked before any upload");
        reset(); File retained=add("wipe"); com.fieldnotes.recorder.util.PanicWipe.blocked=true;
        check(run()==UploadDrain.Outcome.STOPPED && CloudUploadManager.calls.get()==0 && UploadQueue.pending.contains(retained),"Wipe gate stops new upload work");
        reset(); CloudUploadManager.advance=true;
        for(int i=0;i<9;i++) Files.write(add("parallel_"+i).toPath(),new byte[300_000]);
        var owners=Executors.newFixedThreadPool(2);
        try {
            var a=owners.submit(() -> run()); var b=owners.submit(() -> run());
            check(a.get()==UploadDrain.Outcome.COMPLETE && b.get()==UploadDrain.Outcome.COMPLETE,"Concurrent owners finish parallel drain");
        } finally { owners.shutdownNow(); }
        check(CloudUploadManager.maxActive.get()==2 && CloudUploadManager.calls.get()==9 && UploadQueue.acknowledged.size()==9,
            "Exactly two transfer slots, one queue owner, no duplicate sends");
        reset(); CloudUploadManager.advance=true; CloudUploadManager.slowName="continuous_1.mp4";
        for(int i=0;i<8;i++) Files.write(add("continuous_"+i).toPath(),new byte[300_000]);
        check(run()==UploadDrain.Outcome.COMPLETE && CloudUploadManager.maxActive.get()==2,"Fast slot refills while another transfer remains in flight");
        reset(); CloudUploadManager.advance=true; CloudUploadManager.cancelAfter=2;
        for(int i=0;i<5;i++) Files.write(add("parallel_cancel_"+i).toPath(),new byte[300_000]);
        propagated=false;
        try { run(); } catch(java.util.concurrent.CancellationException expected) { propagated=true; }
        check(propagated && UploadQueue.acknowledged.size()==1 && UploadQueue.pending.size()==4,"Parallel cancellation retains every uncommitted original");
        check(CloudUploadManager.active.get()==0 && !UploadStatus.INSTANCE.getActive(),"All child transfers settle before queue ownership released");
        reset(); add("destination_a"); add("destination_b"); CloudUploadManager.afterFirst=() -> DeliveryPolicy.changed=true;
        check(run()==UploadDrain.Outcome.RETRY && UploadQueue.acknowledged.isEmpty() && UploadQueue.pending.size()==2,
            "Destination changed during transfer prevents receipt commit and new sends");
        reset(); CloudUploadManager.advance=true; Files.write(add("duplicate_warmup").toPath(),new byte[300_000]);
        for(int i=0;i<2;i++) {
            File folder=new File(ctx.getFilesDir(),"duplicate_bundle_"+i); folder.mkdirs();
            File file=new File(folder,"duplicate.mp4.enc"); Files.write(file.toPath(),new byte[300_000]); UploadQueue.pending.add(file);
        }
        check(run()==UploadDrain.Outcome.COMPLETE && UploadQueue.acknowledged.size()==3,"Legacy repeated basename never shares an export temp concurrently");
        reset(); CloudUploadManager.advance=true; CloudUploadManager.failAfter=2;
        for(int i=0;i<9;i++) Files.write(add("fallback_"+i).toPath(),new byte[300_000]);
        check(run()==UploadDrain.Outcome.RETRY && UploadQueue.pending.size()==1,"Failed parallel transfer retains its original");
        check(CloudUploadManager.maxActive.get()==2 && CloudUploadManager.launchesAfterFailure>=4,"Failure switches subsequent transfers to one slot");
        var tune=new TransferTuning(); tune.begin(0); tune.completed(1_000_000,1000);
        check(tune.getLimit()==2,"Measured single-transfer baseline enables second slot");
        tune.completed(300_000,16000); tune.completed(300_000,17000);
        tune.completed(300_000,32000); tune.completed(300_000,33000);
        check(tune.getLimit()==1,"Two degraded throughput windows fall back to one");
        tune.completed(1_000_000,34000); check(tune.getLimit()==1,"Cooldown prevents oscillation");
        tune.begin(640000); tune.completed(1_000_000,641000); check(tune.getLimit()==2,"Second slot reprobed after cooldown");
        reset(); com.fieldnotes.recorder.util.Prefs.method="saf";
        for(int i=0;i<7;i++) Files.write(add("saf_"+i).toPath(),new byte[300_000]);
        File corrupt=add("corrupt"); Files.write(corrupt.toPath(),new byte[300_000]);
        check(run()==UploadDrain.Outcome.RETRY && UploadQueue.pending.equals(java.util.List.of(corrupt)),"SAF read-back mismatch never acknowledges or deletes original");
        check(UploadQueue.acknowledged.size()==7 && androidx.documentfile.provider.DocumentFile.directoriesCreated==1,"Shared SAF directory created once; verified files acknowledged");
        check(androidx.documentfile.provider.DocumentFile.findCalls==0,"Production SAF path avoids DocumentFile.findFile for both files and folders");
        check(!new File(ctx.getFilesDir(),"corrupt.mp4").exists(),"Failed transfer plaintext cleaned");
        File crowded=new File(ctx.getFilesDir(),"crowded"); crowded.mkdirs();
        for(int i=0;i<1500;i++) Files.writeString(new File(crowded,"existing_"+i).toPath(),"Synthetic");
        File wanted=new File(crowded,"wanted.mp4"); Files.writeString(wanted.toPath(),"Synthetic");
        int queries=android.content.ContentResolver.queryCalls.get(),closed=android.content.ContentResolver.closedCursors.get();
        var found=SafDocumentLookup.INSTANCE.find(ctx,android.net.Uri.parse(crowded.toString()),"wanted.mp4");
        check(found!=null && found.isFile() && found.getUri().path.equals(wanted.toString()),"Projected lookup finds exact existing file in a crowded directory");
        check(android.content.ContentResolver.queryCalls.get()==queries+1 && android.content.ContentResolver.closedCursors.get()==closed+1,
            "1501-file lookup uses exactly one projected query and closes its cursor");
        reset(); com.fieldnotes.recorder.util.Prefs.method="saf"; android.content.ContentResolver.loading=true;
        File incomplete=add("incomplete_listing");
        check(run()==UploadDrain.Outcome.RETRY && UploadQueue.pending.contains(incomplete) && UploadQueue.acknowledged.isEmpty(),
            "Incomplete cloud directory listing never creates or acknowledges a duplicate");
        check(!new File(ctx.getFilesDir(),"remote/Evidence/incomplete_listing.mp4").exists(),"No remote file created from a partial listing");
        check(android.content.ContentResolver.queryCalls.get()==android.content.ContentResolver.closedCursors.get(),"All lookup cursors closed on success and failure");
        System.out.println("PASS: compiled production pipeline: two slots, concurrent owners, arrivals, fallback, cooldown/reprobe, retries, cancellation, destination/wipe guards, SAF directory isolation and hash verification.");
    }
}
