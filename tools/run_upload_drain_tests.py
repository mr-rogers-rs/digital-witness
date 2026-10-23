"""Exercise the compiled drain with host Android/queue/provider adapters; no network or private data."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--json-jar', type=Path, required=True)
parser.add_argument('--android-jar', type=Path, required=True)
args = parser.parse_args()
java = Path(os.environ['JAVA_HOME']) / 'bin'
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
libs = []
for group, artifact, version in [('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.24'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')]:
    libs.append(next(p for p in (cache / group / artifact / version).rglob('*.jar')
        if not p.name.endswith(('-sources.jar', '-javadoc.jar'))))
with tempfile.TemporaryDirectory(prefix='dw-drain-') as temp:
    folder = Path(temp)
    stubs = {
    'Context': '''package android.content; public class Context {
        public String getString(int id) { return "Synthetic"; } public Object getSystemService(String name) { return null; }
        public java.io.File getFilesDir() { var f=new java.io.File(System.getProperty("dw.test.files")); f.mkdirs(); return f; }}''',
    'SystemClock': 'package android.os; public class SystemClock { public static long now=0; public static long elapsedRealtime() { return now; }}',
    'Log': 'package android.util; public class Log { public static int w(String t,String s,Throwable e) { return 0; } public static int w(String t,String s) { return 0; } public static int e(String t,String s,Throwable e) { return 0; }}',
    'Prefs': '''package com.fieldnotes.recorder.util; import android.content.Context; public class Prefs {
        public static final Prefs INSTANCE=new Prefs(); public String uploadMethod(Context c) { return "cloud"; }
        public String cloudProvider(Context c) { return "dropbox"; } public boolean isCalculatorDisguiseEnabled(Context c) { return false; }}''',
    'PanicWipe': 'package com.fieldnotes.recorder.util; public class PanicWipe { public static boolean blocked=false; public static boolean getWipeInProgress() { return blocked; }}',
    'UploadReadiness': 'package com.fieldnotes.recorder.upload; public class UploadReadiness { public static final UploadReadiness INSTANCE=new UploadReadiness(); public Integer blocker(android.content.Context c) { return null; }}',
    'UploadQueue': '''package com.fieldnotes.recorder.upload; import java.io.*; import java.util.*; import android.content.Context;
        public class UploadQueue { public static final UploadQueue INSTANCE=new UploadQueue();
        public static final List<File> pending=new java.util.concurrent.CopyOnWriteArrayList<>();
        public static final List<File> acknowledged=new java.util.concurrent.CopyOnWriteArrayList<>();
        public List<File> listPending(Context c) { return new ArrayList<>(pending); }
        public void cleanTempDir(Context c) {} public int recoverOrphans(Context c) { return 0; }
        public void markUploaded(Context c,File f) { if(!com.fieldnotes.recorder.cloud.CloudUploadManager.confirmed.contains(f.getName().replace(".enc",""))) throw new AssertionError("Unconfirmed acknowledgment"); acknowledged.add(f); pending.remove(f); f.delete(); }}''',
    'DeliveryPolicy': '''package com.fieldnotes.recorder.upload; import android.content.Context; import java.io.*; import org.json.JSONObject;
        public class DeliveryPolicy { public static final DeliveryPolicy INSTANCE=new DeliveryPolicy(); public static boolean changed=false;
        private final ThreadLocal<String> active=new ThreadLocal<>(); public ThreadLocal<String> getActiveDestination() { return active; }
        public JSONObject bind(Context c,File f) { if(changed) throw new DestinationChangedException(); return new JSONObject().put("mode","plaintext").put("target","synthetic").put("subfolder",""); }
        public File export(Context c,File f,JSONObject p) throws IOException { File out=new File(c.getFilesDir(),f.getName().replace(".enc","")); java.nio.file.Files.copy(f.toPath(),out.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); return out; }
        public void checkActiveDestination(Context c) { if(changed || !"synthetic".equals(active.get())) throw new DestinationChangedException(); }}''',
    'CloudUploadManager': '''package com.fieldnotes.recorder.cloud; import android.content.Context; import java.io.File; import java.util.*;
        public class CloudUploadManager { public static int instances=0,calls=0; public static String failName=null;
        public static boolean cancel=false,tick=false,transientFailure=false; public static Runnable afterFirst=null;
        public static final java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger(),maxActive=new java.util.concurrent.atomic.AtomicInteger();
        public static final Set<String> confirmed=java.util.concurrent.ConcurrentHashMap.newKeySet();
        public CloudUploadManager(Context c) { instances++; } public Object initialize(kotlin.coroutines.Continuation<? super Boolean> k) { return true; }
        public Object uploadFile(File f,String path,kotlin.coroutines.Continuation<? super Boolean> k) {
            int concurrent=active.incrementAndGet(); maxActive.accumulateAndGet(concurrent,Math::max);
            try { calls++; if(cancel) throw new java.util.concurrent.CancellationException("Synthetic cancellation");
                if(transientFailure) return sneaky(new com.fieldnotes.recorder.upload.UploadRetryLaterException());
                try { Thread.sleep(15); } catch(InterruptedException e) { throw new RuntimeException(e); }
                if(tick) android.os.SystemClock.now+=120001;
                Runnable next=afterFirst; afterFirst=null; if(next!=null) next.run();
                boolean ok=!f.getName().equals(failName); if(ok) confirmed.add(f.getName()); return ok;
            } finally { active.decrementAndGet(); }
        }
        private static <T,E extends Throwable> T sneaky(Throwable t) throws E { throw (E)t; }
        }''',
    }
    sources = []
    for name, source in stubs.items():
        path = folder / (name + '.java'); path.write_text(source); sources.append(path)
    cp = os.pathsep.join(map(str, [folder, root / 'android-app/app/build/tmp/kotlin-classes/debug',
        root / 'android-app/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar',
        args.json_jar, args.android_jar, *libs]))
    subprocess.run([str(java / 'javac'), '-cp', cp, '-d', str(folder), *map(str, sources),
        str(root / 'tools/UploadDrainHarness.java')], check=True)
    subprocess.run([str(java / 'java'), '-Ddw.test.files=' + str(folder / 'files'), '-cp', cp, 'UploadDrainHarness'], check=True)
