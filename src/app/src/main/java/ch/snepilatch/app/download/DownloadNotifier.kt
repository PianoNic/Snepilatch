package ch.snepilatch.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ch.snepilatch.app.R

/**
 * Progress notification for downloads, on its own low-importance channel so it never makes a sound
 * or pushes the playback notification aside.
 */
object DownloadNotifier {

    private const val CHANNEL_ID = "snepilatch_downloads"
    private const val NOTIFICATION_ID = 3

    /** Read by MainActivity to land on the downloads manager rather than the last screen. */
    const val EXTRA_OPEN_DOWNLOADS = "ch.snepilatch.app.OPEN_DOWNLOADS"

    private var channelReady = false

    private fun manager(context: Context): NotificationManager {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!channelReady) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
            channelReady = true
        }
        return manager
    }

    private fun openManager(context: Context): PendingIntent? {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_OPEN_DOWNLOADS, true)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun base(context: Context, title: String) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentIntent(openManager(context))
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.downloading))
        .setContentText(title)
        .setOnlyAlertOnce(true)
        .setSilent(true)

    /** [percent] below zero shows an indeterminate bar, which is what a missing Content-Length gives. */
    fun progress(context: Context, title: String, percent: Int, bytesPerSecond: Long = 0L) {
        val builder = base(context, title)
            .setContentText(withRate(title, bytesPerSecond))
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
        manager(context).notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * Aggregate progress for an album or playlist, replacing the per-track notification. Scaled by a
     * hundred so the current track's own progress moves the bar between tracks rather than it
     * sitting still and then jumping a whole step.
     */
    fun batch(
        context: Context,
        title: String,
        done: Int,
        total: Int,
        trackPercent: Int = 0,
        bytesPerSecond: Long = 0L,
    ) {
        val builder = base(context, title)
            .setContentTitle(context.getString(R.string.downloading_count, done, total))
            .setContentText(withRate(title, bytesPerSecond))
            .setOngoing(true)
            .setProgress(total * 100, (done - 1) * 100 + trackPercent.coerceIn(0, 100), false)
        manager(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun batchFinished(context: Context, total: Int, failed: Int) {
        val builder = base(context, "")
            .setContentTitle(context.getString(R.string.download_complete))
            .setContentText(context.getString(R.string.downloaded_count, total - failed, total))
            .setOngoing(false)
            .setAutoCancel(true)
        manager(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun finished(context: Context, title: String) {
        val builder = base(context, title)
            .setContentTitle(context.getString(R.string.download_complete))
            .setOngoing(false)
            .setAutoCancel(true)
        manager(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun failed(context: Context, title: String, reason: String) {
        val builder = base(context, title)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText("$title: $reason")
            .setOngoing(false)
            .setAutoCancel(true)
        manager(context).notify(NOTIFICATION_ID, builder.build())
    }

    /** "Artist - Title · 4.2 MB/s", or just the title before any bytes have moved. */
    private fun withRate(title: String, bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return title
        val megabytes = bytesPerSecond / 1024.0 / 1024.0
        val rate = if (megabytes >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB/s", megabytes)
        } else {
            String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        }
        return "$title · $rate"
    }

    fun clear(context: Context) = manager(context).cancel(NOTIFICATION_ID)
}
