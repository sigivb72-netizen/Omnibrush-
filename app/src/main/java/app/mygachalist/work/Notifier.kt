package app.mygachalist.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mygachalist.MainActivity
import app.mygachalist.R
import app.mygachalist.data.Game
import app.mygachalist.data.GameStatus
import app.mygachalist.data.ListEntry
import app.mygachalist.data.ListStatus
import app.mygachalist.data.Precision
import app.mygachalist.data.Region
import app.mygachalist.data.Repository
import app.mygachalist.data.Settings
import app.mygachalist.data.allReleases
import app.mygachalist.data.gameStatus
import java.time.LocalDate

/** A planned alert. Kept separate from Android so the selection logic is unit-testable. */
data class Alert(val key: String, val gameId: String, val title: String, val text: String, val channel: String)

object Notifier {
    const val CHANNEL_RELEASE = "releases"
    const val CHANNEL_PREREG = "prereg"
    const val EXTRA_GAME_ID = "game_id"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RELEASE, "Release day", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A game on your Plan to Play list launches today" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PREREG, "Pre-registration", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A game on your Plan to Play list opened pre-registration" }
        )
    }

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED)

    /**
     * Alerts for Plan to Play games: a release happening today in the region the user cares about
     * (entry region, then default region, then any region), and games that entered pre-registration.
     */
    fun planAlerts(games: List<Game>, entries: List<ListEntry>, settings: Settings, today: LocalDate): List<Alert> {
        val byId = games.associateBy { it.id }
        val out = ArrayList<Alert>()
        for (e in entries) {
            if (e.listStatus != ListStatus.PLAN_TO_PLAY) continue
            val g = byId[e.gameId] ?: continue
            if (g.adult && !settings.showAdult) continue
            if (settings.notifyRelease) {
                val wanted = Region.fromKey(e.region) ?: settings.region
                val todays = g.allReleases().filter { it.date.precision == Precision.EXACT && it.date.start == today }
                val hit = todays.firstOrNull { it.region == wanted } ?: todays.firstOrNull()
                if (hit != null) {
                    out += Alert(
                        key = "release:${g.id}:${hit.region.key}:$today",
                        gameId = g.id,
                        title = "${g.title} is out today",
                        text = "${hit.region.label} release" + if (hit.confirmed) "" else " (rumoured date)",
                        channel = CHANNEL_RELEASE,
                    )
                }
            }
            if (settings.notifyPrereg && g.gameStatus == GameStatus.PRE_REGISTRATION) {
                out += Alert(
                    key = "prereg:${g.id}",
                    gameId = g.id,
                    title = "${g.title}: pre-registration is open",
                    text = "Tap to see store links and release info.",
                    channel = CHANNEL_PREREG,
                )
            }
        }
        return out
    }

    /** Shows every not-yet-shown alert. Safe to call often. */
    suspend fun checkAndNotify(context: Context, repo: Repository, settings: Settings) {
        if (!settings.notifyRelease && !settings.notifyPrereg) return
        if (!canNotify(context)) return
        val alerts = planAlerts(repo.allGames(), repo.allEntries(), settings, LocalDate.now())
        for (a in alerts) {
            if (repo.wasNotified(a.key)) continue
            show(context, a)
            repo.markNotified(a.key)
        }
    }

    private fun show(context: Context, a: Alert) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_GAME_ID, a.gameId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, a.key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, a.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(a.key.hashCode(), n)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the call.
        }
    }
}
