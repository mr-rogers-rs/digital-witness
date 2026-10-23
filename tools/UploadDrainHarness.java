import android.content.Context;
import android.os.SystemClock;
import com.fieldnotes.recorder.cloud.CloudUploadManager;
import com.fieldnotes.recorder.upload.UploadDrain;
import com.fieldnotes.recorder.upload.UploadQueue;
import com.fieldnotes.recorder.upload.UploadStatus;
import com.fieldnotes.recorder.upload.DeliveryPolicy;
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
        CloudUploadManager.instances=0; CloudUploadManager.calls=0;
        CloudUploadManager.failName=null; CloudUploadManager.cancel=false;
        CloudUploadManager.afterFirst=null; CloudUploadManager.tick=false;
        CloudUploadManager.transientFailure=false;
        CloudUploadManager.maxActive.set(0); CloudUploadManager.active.set(0);
        SystemClock.now=0; DeliveryPolicy.changed=false;
        com.fieldnotes.recorder.util.PanicWipe.blocked=false;
    }
    public static void main(String[] args) throws Exception {
        reset(); File first=add("first"); File failed=add("failed");
        CloudUploadManager.failName="failed.mp4";
        CloudUploadManager.afterFirst=() -> { try { add("arrived_during_upload"); } catch(Exception e) { throw new RuntimeException(e); } };
        check(run()==UploadDrain.Outcome.RETRY,"Actual failed delivery must retain retry status");
        check(UploadQueue.pending.equals(java.util.List.of(failed)),"Newly arriving part drained in the same pass; failed file retained");
        check(UploadQueue.acknowledged.size()==2 && CloudUploadManager.instances==1,"One provider reused; only confirmed files acknowledged");
        check(CloudUploadManager.calls==3,"Failed file attempted once per pass");
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
        check(run()==UploadDrain.Outcome.RETRY && CloudUploadManager.calls==1 && UploadQueue.pending.size()==2,
            "Provider-wide quota/outage stops this pass after one attempt and preserves all files");
        reset(); add("serial_a"); add("serial_b");
        var threads=Executors.newFixedThreadPool(2);
        try {
            var a=threads.submit(() -> run()); var b=threads.submit(() -> run());
            check(a.get()==UploadDrain.Outcome.COMPLETE && b.get()==UploadDrain.Outcome.COMPLETE,"Both drain owners complete");
            check(CloudUploadManager.maxActive.get()==1 && CloudUploadManager.calls==2,"Service and worker drain attempts serialize without duplicate sends");
        } finally { threads.shutdownNow(); }
        reset(); File changed=add("changed"); DeliveryPolicy.changed=true;
        check(run()==UploadDrain.Outcome.RETRY && CloudUploadManager.calls==0 && UploadQueue.pending.contains(changed),"Changed destination blocked before any upload");
        reset(); File retained=add("wipe"); com.fieldnotes.recorder.util.PanicWipe.blocked=true;
        check(run()==UploadDrain.Outcome.STOPPED && CloudUploadManager.calls==0 && UploadQueue.pending.contains(retained),"Wipe gate stops new upload work");
        System.out.println("PASS: production drain: arrivals during upload, one provider per pass, retries retain evidence, immediate continuation, cancellation, shared-lock serialization and destination guard.");
    }
}
