package com.gamedrop.alert

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private data class Listing(
    val key: String,
    val label: String,
    val source: String,
    val eventAt: Long? = null
)

class Monitor(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("gamedrop", Context.MODE_PRIVATE)
            val current = linkedMapOf<String, Listing>()
            readLinks("https://gaming.lenovo.com/", "LENOVO_HOME", current)
            readLinks("https://gaming.lenovo.com/giveaways", "LENOVO", current)
            readLinks("https://www.alienwarearena.com/", "ALIENWARE", current)
            readLinks("https://www.alienwarearena.com/marketplace", "ALIENWARE", current)
            readLinks("https://www.alienwarearena.com/rewards", "ALIENWARE", current)
            readLinks("https://na.alienwarearena.com/", "ALIENWARE", current)
            if (current.isEmpty()) return Result.retry()

            val wasInitialized = prefs.getBoolean("initialized", false)
            val previous = prefs.getStringSet("known_items", emptySet()) ?: emptySet()
            val fresh = if (wasInitialized) current.filterKeys { it !in previous } else emptyMap()

            // Schedule Lenovo reminders for newly discovered listings. If a published event date
            // changes later, update the scheduled reminders instead of keeping the old times.
            current.values.filter { it.source.startsWith("LENOVO") && it.eventAt != null }.forEach { item ->
                val stampKey = "event_${stableId(item.key)}"
                val oldStamp = prefs.getLong(stampKey, 0L)
                if (item.key in fresh.keys || oldStamp != item.eventAt) {
                    ReminderScheduler.schedule(applicationContext, item.key, item.label, item.eventAt!!)
                    prefs.edit().putLong(stampKey, item.eventAt).apply()
                }
            }

            prefs.edit().putStringSet("known_items", (previous + current.keys).toSet())
                .putBoolean("initialized", true).apply()

            if (fresh.isNotEmpty()) {
                val lines = fresh.values.distinctBy { it.key }.take(8).map { item ->
                    item.label + (item.eventAt?.let { " — يبدأ ${DateFormatters.display(it)}" } ?: "")
                }
                val message = lines.joinToString("\n")
                val history = (message + "\n\n" + (prefs.getString("history", "") ?: "")).take(5000)
                prefs.edit().putString("history", history).apply()
                val notification = NotificationCompat.Builder(applicationContext, "drops")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("GameDrop Alert — تحديث جديد")
                    .setContentText(lines.firstOrNull() ?: "تم العثور على تحديث")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).build()
                try { NotificationManagerCompat.from(applicationContext).notify(501, notification) } catch (_: SecurityException) { }
            } else if (!wasInitialized) {
                prefs.edit().putString("history", "تم إعداد المراقبة وحفظ أول نسخة من الصفحات. التذكيرات تُفعّل تلقائيًا لأي لعبة Lenovo جديدة يتوفر لها موعد واضح.").apply()
            }
            prefs.edit().putString("last_check", System.currentTimeMillis().toString()).apply()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    private fun readLinks(url: String, source: String, output: MutableMap<String, Listing>) {
        val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (compatible; GameDropAlert/1.1)").timeout(20000).get()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href").substringBefore("#").substringBefore("?")
            val title = a.text().replace(Regex("\\s+"), " ").trim()
            if (title.length < 4 || href.isBlank()) continue
            val card = a.parents().take(4).lastOrNull() ?: a.parent()
            val context = (card?.text().orEmpty() + " " + a.parent()?.parent()?.text().orEmpty())
                .replace(Regex("\\s+"), " ").trim()
            val haystack = (title + " " + context).lowercase(Locale.ROOT)
            val isCandidate = when (source) {
                "LENOVO_HOME" -> href.contains("gaming.lenovo.com") && href != "https://gaming.lenovo.com/" && title.length >= 5 && !href.contains("/login") && !href.contains("/register") && !href.contains("/terms") && !href.contains("/privacy")
                "LENOVO" -> href.contains("gaming.lenovo.com") && (href.contains("drop") || href.contains("giveaway") || href.contains("perk") || title.contains("key", true) || title.contains("drop", true))
                else -> href.contains("alienwarearena.com") && (haystack.contains("giveaway") || haystack.contains("game key") || haystack.contains("key drop") || haystack.contains("limited keys") || haystack.contains("game vault") || Regex("(?i)[0-9][0-9,]*\\s*ARP").containsMatchIn(context))
            }
            if (!isCandidate) continue
            val arp = Regex("(?i)([0-9][0-9,]*)\\s*ARP").find(context)?.groupValues?.get(1)
            val label = if (source == "ALIENWARE") "Alienware: $title" + (arp?.let { " — $it ARP" } ?: "") else "Lenovo: $title"
            val key = "${if (source.startsWith("LENOVO")) "LENOVO" else source}|$href"
            val eventAt = if (source.startsWith("LENOVO")) EventDateParser.findEventTime(card, context) else null
            output[key] = Listing(key, label, source, eventAt)
        }
    }

    companion object {
        const val WORK = "gamedrop-monitor"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<Monitor>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<Monitor>().build())
        }
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("game_title") ?: "لعبة جديدة"
        val eventAt = inputData.getLong("event_at", 0L)
        val hours = inputData.getInt("hours_before", 0)
        val time = if (eventAt > 0) DateFormatters.display(eventAt) else "قريبًا"
        val body = when (hours) {
            24 -> "تذكير: طابور $title هيبدأ بكرة في $time."
            2 -> "فاضل ساعتين على بداية طابور $title في $time! جهّز نفسك."
            else -> "موعد طابور $title: $time"
        }
        val notification = NotificationCompat.Builder(applicationContext, "reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GameDrop Alert — السحب قرب")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(applicationContext).notify((inputData.getString("reminder_id") ?: title).hashCode(), notification) } catch (_: SecurityException) { }
        return Result.success()
    }
}

private object ReminderScheduler {
    fun schedule(context: Context, key: String, title: String, eventAt: Long) {
        val now = System.currentTimeMillis()
        listOf(24, 2).forEach { hours ->
            val triggerAt = eventAt - TimeUnit.HOURS.toMillis(hours.toLong())
            val name = "reminder_${stableId(key)}_${hours}h"
            val wm = WorkManager.getInstance(context)
            if (triggerAt <= now) {
                wm.cancelUniqueWork(name)
            } else {
                val data = workDataOf(
                    "game_title" to title.removePrefix("Lenovo: "),
                    "event_at" to eventAt,
                    "hours_before" to hours,
                    "reminder_id" to name
                )
                val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(triggerAt - now, TimeUnit.MILLISECONDS)
                    .setInputData(data).build()
                wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}

private object EventDateParser {
    private val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "MMM d, yyyy h:mm a", "MMMM d, yyyy h:mm a", "d MMM yyyy h:mm a", "d MMMM yyyy h:mm a",
        "MMM d yyyy h:mm a", "MMMM d yyyy h:mm a", "MMM d, yyyy HH:mm", "MMMM d, yyyy HH:mm",
        "d MMM yyyy HH:mm", "d MMMM yyyy HH:mm", "MMM d, h:mm a", "MMMM d, h:mm a",
        "d MMM h:mm a", "d MMMM h:mm a", "MMM d HH:mm", "MMMM d HH:mm", "d MMM HH:mm", "d MMMM HH:mm",
        "MMM d, yyyy h a", "MMMM d, yyyy h a", "d MMM yyyy h a", "d MMMM yyyy h a",
        "MMM d, yyyy 'at' h a", "MMMM d, yyyy 'at' h a", "MMM d, yyyy 'at' h:mm a", "MMMM d, yyyy 'at' h:mm a",
        "MMM d, h a", "MMMM d, h a", "d MMM h a", "d MMMM h a", "MMM d, yyyy ha", "d MMM yyyy ha"
    )

    fun findEventTime(card: org.jsoup.nodes.Element?, context: String): Long? {
        val candidates = mutableListOf<String>()
        card?.select("time[datetime], [data-date], [data-start], [datetime]")?.forEach { el ->
            listOf("datetime", "data-date", "data-start").forEach { attr ->
                if (el.hasAttr(attr)) candidates.add(el.attr(attr))
            }
        }
        candidates.add(context)
        val now = System.currentTimeMillis()
        for (candidate in candidates) {
            for (pattern in patterns) {
                try {
                    if (!pattern.contains("yyyy")) {
                        val pivot = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                            isLenient = false
                                timeZone = TimeZone.getDefault()
                        }
                        val parsedWithoutYear = pivot.parse(candidate.trim())
                        if (parsedWithoutYear != null) {
                            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                            val cal = java.util.Calendar.getInstance().apply {
                                time = parsedWithoutYear
                                set(java.util.Calendar.YEAR, year)
                            }
                            if (cal.timeInMillis < now - TimeUnit.HOURS.toMillis(2)) cal.add(java.util.Calendar.YEAR, 1)
                            return cal.timeInMillis
                        }
                    } else {
                        val format = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                            isLenient = false
                            timeZone = TimeZone.getDefault()
                        }
                        val parsed = format.parse(candidate.trim())
                        if (parsed != null && parsed.time > now - TimeUnit.HOURS.toMillis(2)) return parsed.time
                    }
                } catch (_: Exception) { }
            }
            // Search date/time substrings inside a longer card description.
            val regexes = listOf(
                Regex("(?i)(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}\\s+(?:at\\s+)?\\d{1,2}:\\d{2}\\s*(?:AM|PM)"),
                Regex("(?i)\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*,?\\s+\\d{4}\\s+\\d{1,2}:\\d{2}\\s*(?:AM|PM)"),
                Regex("(?i)(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}\\s+(?:at\\s+)?\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM)"),
                Regex("(?i)\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*,?\\s+\\d{4}\\s+\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM)")
            )
            for (regex in regexes) {
                val match = regex.find(candidate)?.value ?: continue
                for (pattern in patterns) try {
                    val parsed = SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false }.parse(match)
                    if (parsed != null && parsed.time > now - TimeUnit.HOURS.toMillis(2)) return parsed.time
                } catch (_: Exception) { }
            }
        }
        return null
    }
}

private object DateFormatters {
    fun display(timestamp: Long): String = SimpleDateFormat("d MMMM yyyy 'الساعة' h:mm a", Locale("ar")).format(Date(timestamp))
}

private fun stableId(value: String): String = value.hashCode().toUInt().toString()
