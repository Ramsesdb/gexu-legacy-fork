package eu.kanade.tachiyomi.data.ai

import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import java.math.RoundingMode
import java.text.NumberFormat

/**
 * Notifier for AI library indexing job progress.
 * Shows progress, completion, and error notifications similar to LibraryUpdateNotifier.
 */
class LibraryIndexingNotifier(private val context: Context) {

    private val percentFormatter = NumberFormat.getPercentInstance().apply {
        roundingMode = RoundingMode.DOWN
        maximumFractionDigits = 0
    }

    /**
     * Pending intent to cancel the indexing job
     */
    private val cancelIntent by lazy {
        NotificationReceiver.cancelAiIndexingPendingBroadcast(context)
    }

    /**
     * Bitmap of the app for notifications.
     */
    private val notificationBitmap by lazy {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
        drawable?.toBitmap(NOTIF_ICON_SIZE, NOTIF_ICON_SIZE)
            ?: BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    /**
     * Cached progress notification builder.
     */
    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_AI_PROGRESS) {
            setContentTitle("Indexando biblioteca para IA")
            setSmallIcon(R.drawable.ic_notification)
            setLargeIcon(notificationBitmap)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(R.drawable.ic_close_24dp, "Cancelar", cancelIntent)
        }
    }

    /**
     * Shows the progress notification.
     *
     * @param mangaTitle Current manga being indexed
     * @param current Current progress
     * @param total Total items to index
     */
    fun showProgressNotification(mangaTitle: String, current: Int, total: Int) {
        progressNotificationBuilder
            .setContentTitle(
                "Indexando: ${percentFormatter.format(current.toFloat() / total)}"
            )
            .setContentText(mangaTitle.take(40))
            .setStyle(NotificationCompat.BigTextStyle().bigText(mangaTitle))

        context.notify(
            Notifications.ID_AI_INDEXING_PROGRESS,
            progressNotificationBuilder
                .setProgress(total, current, false)
                .build(),
        )
    }

    /**
     * Shows completion notification.
     *
     * @param indexed Number of items successfully indexed
     * @param skipped Number of items skipped (already indexed)
     * @param failed Number of items that failed
     */
    fun showCompleteNotification(indexed: Int, skipped: Int, failed: Int) {
        context.cancelNotification(Notifications.ID_AI_INDEXING_PROGRESS)

        val message = buildString {
            append("✓ $indexed indexados")
            if (skipped > 0) append(" • $skipped omitidos")
            if (failed > 0) append(" • $failed errores")
        }

        context.notify(
            Notifications.ID_AI_INDEXING_COMPLETE,
            Notifications.CHANNEL_AI_PROGRESS,
        ) {
            setContentTitle("Indexación completada")
            setContentText(message)
            setSmallIcon(R.drawable.ic_done_24dp)
            setLargeIcon(notificationBitmap)
            setAutoCancel(true)
        }
    }

    /**
     * Cancels the progress notification.
     */
    fun cancelProgressNotification() {
        context.cancelNotification(Notifications.ID_AI_INDEXING_PROGRESS)
    }

    companion object {
        private const val NOTIF_ICON_SIZE = 192
    }
}
