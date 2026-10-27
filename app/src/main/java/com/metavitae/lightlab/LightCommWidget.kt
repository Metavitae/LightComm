package com.metavitae.lightlab

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget (Chat's NOTE 3, approved as the default). A still widget that changes in steps:
 * the app pushes a new title and caption only when something happens (listening, sending, a new message,
 * stopped). No timed refresh, no new permission, never wakes the camera. Tap opens LightComm.
 * No news (just opened, listening, not listening): our banner fills the widget (founder, Oct 14).
 * Routine news fades back to the banner after 10 minutes; opening the app clears all news (Oct 27).
 * News (sending, delivered, not delivered, new messages): the status shows instead.
 * Not yet: Search countdown, Resting, Antenna (those features don't exist yet).
 */
class LightCommWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val p = ctx.getSharedPreferences("lc_widget", Context.MODE_PRIVATE)
        draw(ctx, mgr, ids, p.getString("title", "LightComm")!!, p.getString("caption", "TAP TO OPEN")!!)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        // A new build was installed: the launcher fell back to the plain layout without the tap action
        // (tapping then opened Light Lab, Oct 14). Draw it again so a tap opens LightComm.
        // Oct 27: the app isn't running after an install, so routine news goes too; news that needs the person stays.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == FADE) {
            val p = ctx.getSharedPreferences("lc_widget", Context.MODE_PRIVATE)
            if (p.getString("title", "") in ROUTINE && (intent.action != FADE || System.currentTimeMillis() - p.getLong("at", 0) >= FADE_MS - 5_000))
                p.edit().putString("title", "LightComm").putString("caption", "Tap to open").apply()
            val mgr = AppWidgetManager.getInstance(ctx)
            onUpdate(ctx, mgr, mgr.getAppWidgetIds(ComponentName(ctx, LightCommWidget::class.java)))
        }
    }

    // The 8T's launcher lost the tap action again by Oct 23 (widget opened Light Lab). Redraw whenever the
    // launcher resizes or restores the widget too.
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: android.os.Bundle) {
        onUpdate(ctx, mgr, intArrayOf(id))
    }

    override fun onRestored(ctx: Context, oldIds: IntArray, newIds: IntArray) {
        onUpdate(ctx, AppWidgetManager.getInstance(ctx), newIds)
    }

    companion object {
        /** How long news stays (founder Oct 27, option 3): routine news ("Sending", "Delivered") goes back to the banner after
         *  10 minutes; news that needs the person ("Not delivered", new messages) stays until the app is opened. */
        private val ROUTINE = setOf("Sending as light", "Delivered")
        private const val FADE_MS = 10 * 60_000L
        private const val FADE = "com.metavitae.lightlab.WIDGET_FADE"

        fun show(ctx: Context, title: String, caption: String) {
            ctx.getSharedPreferences("lc_widget", Context.MODE_PRIVATE).edit().putString("title", title).putString("caption", caption)
                .putLong("at", System.currentTimeMillis()).apply()
            // An inexact alarm: no permission needed, may come a little late, never wakes the camera.
            if (title in ROUTINE) try {
                val fade = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, LightCommWidget::class.java).setAction(FADE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                ctx.getSystemService(android.app.AlarmManager::class.java)
                    .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + FADE_MS, fade)
            } catch (_: Exception) {}
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, LightCommWidget::class.java))
            if (ids.isNotEmpty()) draw(ctx, mgr, ids, title, caption)
        }

        private fun draw(ctx: Context, mgr: AppWidgetManager, ids: IntArray, title: String, caption: String) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_lightcomm)
            val banner = title == "LightComm" || title == "Listening"
            v.setViewVisibility(R.id.widget_banner, if (banner) android.view.View.VISIBLE else android.view.View.GONE)
            v.setViewVisibility(R.id.widget_status, if (banner) android.view.View.GONE else android.view.View.VISIBLE)
            v.setInt(R.id.widget_root, "setBackgroundResource", if (banner) R.drawable.widget_bg_banner else R.drawable.widget_bg)
            v.setTextViewText(R.id.widget_title, title)
            v.setTextViewText(R.id.widget_caption, caption.uppercase())
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, LightCommActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            mgr.updateAppWidget(ids, v)
        }
    }
}
