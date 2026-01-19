package eu.kanade.tachiyomi.data.ai

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.ai.interactor.IndexLibrary
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryIndexingJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val indexLibrary: IndexLibrary = Injekt.get()
    private val notifier = LibraryIndexingNotifier(context)

    override suspend fun doWork(): Result {
        setForegroundSafely()

        return try {
            logcat(LogPriority.INFO) { "Starting library indexing job" }

            val result = indexLibrary.await(force = true) { current, total, title ->
                notifier.showProgressNotification(title, current, total)
            }

            if (result.notConfigured) {
                logcat(LogPriority.WARN) { "AI not configured, skipping indexing" }
                notifier.cancelProgressNotification()
                return Result.success()
            }

            logcat(LogPriority.INFO) {
                "Library indexing completed: ${result.indexed} indexed, ${result.skipped} skipped, ${result.failed} failed"
            }

            notifier.showCompleteNotification(result.indexed, result.skipped, result.failed)
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) {
                notifier.cancelProgressNotification()
                Result.success()
            } else {
                logcat(LogPriority.ERROR, e)
                notifier.cancelProgressNotification()
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_AI_INDEXING_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val TAG = "LibraryIndexing"

        fun startNow(context: Context): Boolean {
            val wm = context.workManager
            if (wm.isRunning(TAG)) {
                return false
            }

            val request = OneTimeWorkRequestBuilder<LibraryIndexingJob>()
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
            return true
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }
    }
}
