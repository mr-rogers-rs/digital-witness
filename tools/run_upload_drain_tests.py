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
        public android.content.ContentResolver getContentResolver() { return new android.content.ContentResolver(); }
        public java.io.File getFilesDir() { var f=new java.io.File(System.getProperty("dw.test.files")); f.mkdirs(); return f; }}''',
    'SystemClock': 'package android.os; public class SystemClock { public static volatile long now=0; public static synchronized void advance(long ms) { now+=ms; } public static long elapsedRealtime() { return now; }}',
    'Uri': '''package android.net; public class Uri { public final String path; private Uri(String p) { path=p; }
        public static Uri parse(String p) { return new Uri(p); } public boolean equals(Object o) { return o instanceof Uri && path.equals(((Uri)o).path); }
        public int hashCode() { return path.hashCode(); }}''',
    'UriPermission': '''package android.content; public class UriPermission {
        public android.net.Uri getUri() { return android.net.Uri.parse(System.getProperty("dw.test.files")+"/remote"); }
        public boolean isReadPermission() { return true; } public boolean isWritePermission() { return true; }}''',
    'Bundle': '''package android.os; public class Bundle {
        public boolean getBoolean(String key) { return android.content.ContentResolver.loading; }
        public String getString(String key) { return android.content.ContentResolver.error?"Synthetic provider error":null; }}''',
    'DocumentsContract': '''package android.provider; import android.net.Uri; import android.content.ContentResolver; import java.io.*;
        public class DocumentsContract {
        public static final String EXTRA_LOADING="loading",EXTRA_ERROR="error";
        public static class Document { public static final String COLUMN_DOCUMENT_ID="document_id", COLUMN_DISPLAY_NAME="_display_name", COLUMN_MIME_TYPE="mime_type", MIME_TYPE_DIR="vnd.android.document/directory"; }
        public static String getDocumentId(Uri uri) { return uri.path; }
        public static Uri buildChildDocumentsUriUsingTree(Uri tree,String id) { return Uri.parse(id); }
        public static Uri buildDocumentUriUsingTree(Uri tree,String id) { return Uri.parse(id); }
        public static Uri createDocument(ContentResolver r,Uri parent,String mime,String name) throws IOException { File f=new File(parent.path,name); return f.createNewFile()?Uri.parse(f.toString()):null; }
        public static boolean deleteDocument(ContentResolver r,Uri uri) { return new File(uri.path).delete(); }}''',
    'UploadMeasurements': '''package com.fieldnotes.recorder.upload; public class UploadMeasurements {
        public static final UploadMeasurements INSTANCE=new UploadMeasurements(); public void begin(String method) {} public void finish(android.content.Context c) {}
        public void add(String s,long n) {} public void confirmed(long n) {} public void failed() {} public void concurrency(int n) {} }''',
    'ContentResolver': '''package android.content; import java.io.*; public class ContentResolver {
        public static volatile boolean loading=false,error=false;
        public static final java.util.concurrent.atomic.AtomicInteger queryCalls=new java.util.concurrent.atomic.AtomicInteger(),closedCursors=new java.util.concurrent.atomic.AtomicInteger();
        public android.database.Cursor query(android.net.Uri uri,String[] projection,String selection,String[] args,String order) {
            queryCalls.incrementAndGet(); File[] children=new File(uri.path).listFiles(); final File[] files=children==null?new File[0]:children;
            int[] row={-1}; return (android.database.Cursor) java.lang.reflect.Proxy.newProxyInstance(android.database.Cursor.class.getClassLoader(),new Class[]{android.database.Cursor.class},(proxy,method,a)-> {
                switch(method.getName()) {
                    case "getExtras": return new android.os.Bundle();
                    case "getColumnIndexOrThrow": int idx=java.util.Arrays.asList(projection).indexOf((String)a[0]); if(idx<0) throw new IllegalArgumentException(); return idx;
                    case "moveToNext": return ++row[0]<files.length;
                    case "getString": String col=projection[(int)a[0]]; File file=files[row[0]];
                        if(col.equals("document_id")) return file.toString(); if(col.equals("_display_name")) return file.getName();
                        return file.isDirectory()?android.provider.DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream";
                    case "close": closedCursors.incrementAndGet(); return null;
                    default: throw new AssertionError("Unexpected cursor method "+method.getName());
                }
            });
        }
        public java.util.List<UriPermission> getPersistedUriPermissions() { return java.util.List.of(new UriPermission()); }
        public InputStream openInputStream(android.net.Uri u) throws IOException { return new FileInputStream(u.path); }
        public OutputStream openOutputStream(android.net.Uri u,String mode) throws IOException {
            return new FileOutputStream(u.path) { public void close() throws IOException { super.close();
                if(u.path.contains("corrupt")) java.nio.file.Files.writeString(java.nio.file.Path.of(u.path),"Corrupted synthetic bytes");
                android.os.SystemClock.advance(1000); }}; }}''',
    'DocumentFile': '''package androidx.documentfile.provider; import java.io.*; public class DocumentFile {
        public static int directoriesCreated=0,findCalls=0; private final File file; public DocumentFile(File f) { file=f; }
        public static DocumentFile fromTreeUri(android.content.Context c,android.net.Uri u) { var f=new File(u.path); f.mkdirs(); return new DocumentFile(f); }
        public static DocumentFile fromSingleUri(android.content.Context c,android.net.Uri u) { return new DocumentFile(new File(u.path)); }
        public android.net.Uri getUri() { return android.net.Uri.parse(file.toString()); }
        public boolean isDirectory() { return file.isDirectory(); } public boolean isFile() { return file.isFile(); }
        public boolean canRead() { return true; } public boolean canWrite() { return true; }
        public DocumentFile findFile(String name) { findCalls++; var f=new File(file,name); return f.exists()?new DocumentFile(f):null; }
        public DocumentFile createDirectory(String name) { directoriesCreated++; var f=new File(file,name); return f.mkdir()?new DocumentFile(f):null; }
        public DocumentFile createFile(String mime,String name) throws IOException { var f=new File(file,name); return f.createNewFile()?new DocumentFile(f):null; }
        public boolean delete() { return file.delete(); }}''',
    'IntegrityManager': '''package com.fieldnotes.recorder.integrity; public class IntegrityManager {
        public static final IntegrityManager INSTANCE=new IntegrityManager(); public byte[] sha256File(java.io.File f) throws Exception {
            return java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(f.toPath())); }}''',
    'Log': 'package android.util; public class Log { public static int w(String t,String s,Throwable e) { return 0; } public static int w(String t,String s) { return 0; } public static int e(String t,String s,Throwable e) { return 0; }}',
    'Prefs': '''package com.fieldnotes.recorder.util; import android.content.Context; public class Prefs {
        public static final Prefs INSTANCE=new Prefs(); public static String method="cloud"; public String uploadMethod(Context c) { return method; }
        public String cloudFolderUri(Context c) { return System.getProperty("dw.test.files")+"/remote"; }
        public String cloudProvider(Context c) { return "dropbox"; } public boolean isCalculatorDisguiseEnabled(Context c) { return false; }}''',
    'PanicWipe': 'package com.fieldnotes.recorder.util; public class PanicWipe { public static boolean blocked=false; public static boolean getWipeInProgress() { return blocked; }}',
    'UploadReadiness': 'package com.fieldnotes.recorder.upload; public class UploadReadiness { public static final UploadReadiness INSTANCE=new UploadReadiness(); public Integer blocker(android.content.Context c) { return null; }}',
    'UploadQueue': '''package com.fieldnotes.recorder.upload; import java.io.*; import java.util.*; import android.content.Context;
        public class UploadQueue { public static final UploadQueue INSTANCE=new UploadQueue();
        public static final List<File> pending=new java.util.concurrent.CopyOnWriteArrayList<>();
        public static final List<File> acknowledged=new java.util.concurrent.CopyOnWriteArrayList<>();
        public List<File> listPending(Context c) { return new ArrayList<>(pending); }
        public void cleanTempDir(Context c) {} public int recoverOrphans(Context c) { return 0; }
        public void markUploaded(Context c,File f) { if(com.fieldnotes.recorder.util.Prefs.method.equals("cloud") && !com.fieldnotes.recorder.cloud.CloudUploadManager.confirmed.contains(f.getName().replace(".enc",""))) throw new AssertionError("Unconfirmed acknowledgment"); acknowledged.add(f); pending.remove(f); f.delete(); }}''',
    'DeliveryPolicy': '''package com.fieldnotes.recorder.upload; import android.content.Context; import java.io.*; import org.json.JSONObject;
        public class DeliveryPolicy { public static final DeliveryPolicy INSTANCE=new DeliveryPolicy(); public static volatile boolean changed=false; public static int generation=0;
        public String target(Context c) { return "synthetic"+generation; }
        private final ThreadLocal<String> active=new ThreadLocal<>(); public ThreadLocal<String> getActiveDestination() { return active; }
        public JSONObject bind(Context c,File f) { if(changed) throw new DestinationChangedException(); return new JSONObject().put("mode","plaintext").put("target",target(c)).put("subfolder","Evidence"); }
        public File export(Context c,File f,JSONObject p) throws IOException { File out=new File(c.getFilesDir(),f.getName().replace(".enc","")); java.nio.file.Files.copy(f.toPath(),out.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); return out; }
        public void checkActiveDestination(Context c) { if(changed || !target(c).equals(active.get())) throw new DestinationChangedException(); }}''',
    'CloudUploadManager': '''package com.fieldnotes.recorder.cloud; import android.content.Context; import java.io.File; import java.util.*;
        public class CloudUploadManager { public static int instances=0; public static final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger(); public static volatile String failName=null;
        public static volatile boolean cancel=false,tick=false,transientFailure=false,advance=false,backoffResponse=false;
        public static volatile int cancelAfter=0;
        public static volatile int failAfter=0; public static volatile boolean failed=false; public static int launchesAfterFailure=0;
        public static volatile String slowName=null; public static volatile java.util.concurrent.CountDownLatch releaseSlow=new java.util.concurrent.CountDownLatch(1);
        public static volatile Runnable afterFirst=null;
        public static final java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger(),maxActive=new java.util.concurrent.atomic.AtomicInteger();
        public static final Set<String> confirmed=java.util.concurrent.ConcurrentHashMap.newKeySet();
        public static final Set<String> activeNames=java.util.concurrent.ConcurrentHashMap.newKeySet();
        public CloudUploadManager(Context c) { instances++; } public Object initialize(kotlin.coroutines.Continuation<? super Boolean> k) { return true; }
        public Object uploadFile(File f,String path,kotlin.coroutines.Continuation<? super Boolean> k) {
            int concurrent=active.incrementAndGet(); maxActive.accumulateAndGet(concurrent,Math::max);
            try { int number=calls.incrementAndGet();
                if(!activeNames.add(f.getName())) throw new AssertionError("Same export temp used concurrently");
                if(failed && number>3) { launchesAfterFailure++; if(concurrent>1) throw new AssertionError("Parallel launch after fallback"); }
                if(number>=5) releaseSlow.countDown();
                if(f.getName().equals(slowName)) { try { if(!releaseSlow.await(4,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Idle pipeline slot while other transfer blocked"); }
                    catch(InterruptedException e) { throw new RuntimeException(e); } }
                if(cancel || cancelAfter==number) throw new java.util.concurrent.CancellationException("Synthetic cancellation");
                if(transientFailure) return sneaky(new com.fieldnotes.recorder.upload.UploadRetryLaterException());
                if(backoffResponse) { com.fieldnotes.recorder.upload.TransferTuning.Companion.getServiceBackoff().get().set(true); return false; }
                try { Thread.sleep(number==1?30:80); } catch(InterruptedException e) { throw new RuntimeException(e); }
                if(tick) android.os.SystemClock.advance(120001); else if(advance) android.os.SystemClock.advance(1000);
                Runnable next; synchronized(CloudUploadManager.class) { next=afterFirst; afterFirst=null; } if(next!=null) next.run();
                if(failAfter==number) { failed=true; return false; }
                boolean ok=!f.getName().equals(failName); if(ok) confirmed.add(f.getName()); return ok;
            } finally { activeNames.remove(f.getName()); active.decrementAndGet(); }
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
    subprocess.run([str(java / 'java'), '-Ddw.test.files=' + str(folder / 'files'), '-cp', cp, 'UploadDrainHarness'], check=True, timeout=90)
