package com.fieldnotes.recorder

import android.app.Application
import android.content.Context
import com.fieldnotes.recorder.util.PanicWipe
import kotlinx.coroutines.launch

class WitnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.fieldnotes.recorder.capture.CaptureRecovery.initialize(this)
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            if (!PanicWipe.wipeInProgress) {
                if (!com.fieldnotes.recorder.capture.CaptureStatus.running)
                    com.fieldnotes.recorder.capture.CaptureStatus.problem = com.fieldnotes.recorder.capture.CaptureDiagnostics.lastProblem(this@WitnessApplication)
                com.fieldnotes.recorder.upload.UploadScheduler.ensurePeriodicDrain(this@WitnessApplication)
                if (com.fieldnotes.recorder.upload.UploadQueue.listPending(this@WitnessApplication).isNotEmpty())
                    com.fieldnotes.recorder.upload.UploadScheduler.retryNow(this@WitnessApplication)
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        PanicWipe.resumeBeforeStartup(base)
    }
}
