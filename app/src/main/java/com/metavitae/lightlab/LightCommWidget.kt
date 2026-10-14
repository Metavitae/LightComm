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
 * Not yet: Search countdown, Resting, Antenna (those features don't exist yet).
 */
class LightCommWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val p = ctx.getSharedPreferences("lc_widget", Context.MODE_PRIVATE)
        draw(ctx, mgr, ids, p.getString("title", "LightComm")!!, p.getString("caption", "TAP TO OPEN")!!)
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
            v.setTextViewText(R.id.widget_title, title)
            v.setTextViewText(R.id.widget_caption, caption.uppercase())
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, LightCommActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            mgr.updateAppWidget(ids, v)
        }
    }
}
