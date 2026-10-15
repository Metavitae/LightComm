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
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val mgr = AppWidgetManager.getInstance(ctx)
            onUpdate(ctx, mgr, mgr.getAppWidgetIds(ComponentName(ctx, LightCommWidget::class.java)))
        }
    }

    companion object {
        fun show(ctx: Context, title: String, caption: String) {
            ctx.getSharedPreferences("lc_widget", Context.MODE_PRIVATE).edit().putString("title", title).putString("caption", caption).apply()
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
