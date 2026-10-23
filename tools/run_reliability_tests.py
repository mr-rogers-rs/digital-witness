"""Run compiled journal, queue, recovery and proof code with host Android/media adapters.

Uses real SecureFile encryption and ECDSA with an in-memory test Keystore. No phone,
real account, camera, microphone or network is accessed. Fake muxing tests orchestration;
the Android instrumentation suite separately exercises real muxing/decoding.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--json-jar', type=Path, required=True)
parser.add_argument('--android-jar', type=Path, required=True)
parser.add_argument('--variant', choices=('debug', 'release'), default='debug')
args = parser.parse_args()
java = Path(os.environ['JAVA_HOME']) / 'bin'
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
libs = [next(p for p in (cache / group / name / version).rglob('*.jar')
    if not p.name.endswith(('-sources.jar', '-javadoc.jar'))) for group, name, version in
    [('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.24'),
     ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')]]
with tempfile.TemporaryDirectory(prefix='dw-reliability-') as temp:
    folder = Path(temp)
    stubs = {
        'FileDurability': 'package com.fieldnotes.recorder.secure; public class FileDurability { public static final FileDurability INSTANCE=new FileDurability(); public void syncDirectory(java.io.File f) {} }',
        'Context': '''package android.content; public class Context {
            public String getString(int id) { return "Synthetic recording problem"; }
            public java.io.File getFilesDir() { var f=new java.io.File(System.getProperty("dw.test.files")); f.mkdirs(); return f; }}''',
        'Log': '''package android.util; public class Log {
            public static int i(String t,String s) { return 0; } public static int w(String t,String s) { return 0; }
            public static int e(String t,String s,Throwable e) { return 0; }}''',
        'Base64': '''package android.util; public class Base64 {
            public static String encodeToString(byte[] b,int flags) { return java.util.Base64.getEncoder().encodeToString(b); }
            public static byte[] decode(String s,int flags) { return java.util.Base64.getDecoder().decode(s); }}''',
        'AtomicFile': '''package android.util; import java.io.*; import java.nio.file.*;
            public class AtomicFile { private final File base,pending; public static String failName=null;
            public static volatile String slowName=null;
            public static volatile java.util.concurrent.CountDownLatch entered,release;
            public AtomicFile(File f) { base=f; pending=new File(f+".new"); }
            public byte[] readFully() throws IOException { return Files.readAllBytes(base.toPath()); }
            public FileOutputStream startWrite() throws IOException {
                if(base.getName().equals(slowName)) { entered.countDown();
                    try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Blocked test write timed out"); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } }
                base.getParentFile().mkdirs(); return new FileOutputStream(pending); }
            public void finishWrite(FileOutputStream out) throws IOException { out.close();
                if(base.getName().equals(failName)) { failName=null; throw new IOException("Synthetic commit failure"); }
                Files.move(pending.toPath(),base.toPath(),StandardCopyOption.REPLACE_EXISTING); }
            public void failWrite(FileOutputStream out) throws IOException { out.close(); pending.delete(); }
            public void delete() { base.delete(); pending.delete(); }}''',
        'MediaFormat': '''package android.media; public class MediaFormat {
            private final java.util.Map<String,Object> values=new java.util.HashMap<>();
            public void setInteger(String k,int v) { values.put(k,v); } public int getInteger(String k) { return (Integer)values.get(k); }
            public void setString(String k,String v) { values.put(k,v); } public String getString(String k) { return (String)values.get(k); }
            public boolean containsKey(String k) { return values.containsKey(k); }
            public void setByteBuffer(String k,java.nio.ByteBuffer b) { values.put(k,b); }
            public java.nio.ByteBuffer getByteBuffer(String k) { return (java.nio.ByteBuffer)values.get(k); }}''',
        'Prefs': '''package com.fieldnotes.recorder.util; import android.content.Context; public class Prefs {
            public static final Prefs INSTANCE=new Prefs(); public static boolean active=false; public static int writes=0;
            public boolean isAnonymousMode(Context c) { return false; } public String exportMode(Context c) { return "plaintext"; }
            public boolean keepLocalCopy(Context c) { return false; } public int localArchiveMaxMb(Context c) { return 100; }
            public boolean isRecordingActive(Context c) { return active; } public void endRecordingSession(Context c) { active=false; }
            public int chainSegmentIndex(Context c) { return 0; } public String chainPrevHash(Context c) { return null; }
            public String chainSaltB64(Context c) { return "AAAAAAAAAAAAAAAAAAAAAA=="; }
            public String chainBeacon(Context c) { return null; } public String chainAttestation(Context c) { return null; }
            public void saveChainProgress(Context c,int i,String h) { writes++; }}''',
        'PanicWipe': '''package com.fieldnotes.recorder.util; public class PanicWipe {
            public static boolean blocked=false; public static boolean getWipeInProgress() { return blocked; }}''',
        'DeliveryPolicy': '''package com.fieldnotes.recorder.upload; import android.content.Context; import java.io.File;
            public class DeliveryPolicy { public static final DeliveryPolicy INSTANCE=new DeliveryPolicy(); public static String destination="test-target";
            private final ThreadLocal<String> active=new ThreadLocal<>(); public ThreadLocal<String> getActiveDestination() { return active; }
            public static boolean failBind=false;
            public String target(Context c) { return destination; }
            public org.json.JSONObject bind(Context c,File f) { if(failBind || (active.get()!=null && !active.get().equals(destination))) throw new IllegalStateException("Synthetic binding failure"); return new org.json.JSONObject().put("target",destination); }
            public void removeState(File f) {} }''',
        'UploadScheduler': '''package com.fieldnotes.recorder.upload; public class UploadScheduler {
            public static final UploadScheduler INSTANCE=new UploadScheduler(); public void triggerDrain(android.content.Context c) {} }''',
        'OtsUpgradeManager': '''package com.fieldnotes.recorder.integrity; public class OtsUpgradeManager {
            public static final OtsUpgradeManager INSTANCE=new OtsUpgradeManager(); public void trackNeedsStamp(android.content.Context c,byte[] b,String s) {} }''',
        'EnvironmentCapture': '''package com.fieldnotes.recorder.integrity; public class EnvironmentCapture {
            public static final EnvironmentCapture INSTANCE=new EnvironmentCapture();
            public org.json.JSONObject capture(android.content.Context c,boolean a) { return new org.json.JSONObject(); }}''',
        'SegmentMuxer': '''package com.fieldnotes.recorder.capture; import java.io.*; import java.util.List; import android.media.MediaFormat;
            public class SegmentMuxer { public static final SegmentMuxer INSTANCE=new SegmentMuxer(); public static int calls=0;
            public void write(File out,MediaFormat af,MediaFormat vf,List<MuxSample> a,List<MuxSample> v,long max,int orientation) throws IOException {
                calls++; SegmentTimeline.INSTANCE.baseline(a,v,max);
                try(var stream=new DataOutputStream(new FileOutputStream(out))) {
                    for(var sample:a) stream.write(sample.getData()); for(var sample:v) stream.write(sample.getData());
                }
            }}''',
    }
    sources = []
    for name, source in stubs.items():
        path = folder / (name + '.java'); path.write_text(source); sources.append(path)
    resource_task = 'process' + args.variant[0].upper() + args.variant[1:] + 'Resources'
    cp = os.pathsep.join(map(str, [folder, root / f'android-app/app/build/tmp/kotlin-classes/{args.variant}',
        root / f'android-app/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/{args.variant}/{resource_task}/R.jar',
        args.json_jar, args.android_jar, *libs]))
    subprocess.run([str(java / 'javac'), '-cp', cp, '-d', str(folder), *map(str, sources),
        str(root / 'tools/JvmSecurityHarness.java'), str(root / 'tools/ReliabilityHarness.java')], check=True)
    subprocess.run([str(java / 'java'), '-Ddw.test.files=' + str(folder / 'files'), '-cp', cp,
        'ReliabilityHarness', str(folder / 'verification')], check=True)
    # Independently verify the production recovery proof and signature generated by Kotlin.
    subprocess.run([os.sys.executable, str(root / 'tools/verify_chain.py'), str(folder / 'verification'),
        '--no-network', '--require-signed', '--require-bound-metadata'], check=True)
