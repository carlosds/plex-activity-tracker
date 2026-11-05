package io.github.plexactivitytracker

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Scheduler.schedule(this, ExistingPeriodicWorkPolicy.KEEP)
    }
}

object Scheduler {
    private const val PERIODIC = "plexactivitytracker-periodic"
    private const val NOW = "plexactivitytracker-now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context, policy: ExistingPeriodicWorkPolicy) {
        val minutes = Prefs(context).intervalMinutes.coerceAtLeast(15)
        val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, policy, req)
    }

    /** Sincronización inmediata (con un pequeño retardo para agrupar cambios de ajustes seguidos). */
    fun syncSoon(context: Context, delaySeconds: Long = 3) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Prefs(applicationContext).isConfigured()) return Result.success()
        return try {
            SyncEngine.run(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

/** Arranque de la tele y petición del sistema para inicializar los canales. */
class SyncTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.schedule(context, ExistingPeriodicWorkPolicy.KEEP)
        Scheduler.syncSoon(context, delaySeconds = 20)
    }
}
