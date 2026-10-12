package com.metavitae.lightlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(16))
}

fun LinearLayout.text(s: String, size: Float = 16f, bold: Boolean = false, color: Int = Color.WHITE): TextView {
    val t = TextView(context)
    t.text = s
    t.textSize = size
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
    t.setPadding(0, context.dp(4), 0, context.dp(4))
    addView(t)
    return t
}

fun LinearLayout.button(s: String, onClick: () -> Unit): Button {
    val b = Button(context)
    b.text = s
    b.isAllCaps = false
    b.textSize = 17f
    b.setOnClickListener { onClick() }
    addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(6)
    })
    return b
}

/** A labeled drop-down. Returns a getter for the chosen index. */
fun LinearLayout.choice(label: String, options: List<String>, default: Int = 0): () -> Int {
    text(label, 14f, color = Color.LTGRAY)
    val sp = Spinner(context)
    sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
    sp.setSelection(default)
    var picked = default
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { picked = pos }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
    addView(sp)
    return { picked }
}

object Results {
    /** Saves to Download/LightLab/ so it shows up in Files by Google. Returns the path shown to the user. */
    fun save(ctx: Context, kind: String, body: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val name = "LightLab_${kind}_${Build.MODEL}_$stamp.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LightLab")
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(body.toByteArray()) }
            "Download/LightLab/$name"
        } catch (e: Exception) {
            "NOT SAVED: ${e.message}"
        }
    }

    fun header(title: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "Light Lab - $title\nPhone: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})\nTime: $stamp\n\n"
    }
}

class Stats(values: List<Double>) {
    val n = values.size
    val mean = if (n == 0) 0.0 else values.average()
    val std = if (n < 2) 0.0 else sqrt(values.sumOf { (it - mean) * (it - mean) } / (n - 1))
    private val sorted = values.sorted()
    val min = sorted.firstOrNull() ?: 0.0
    val max = sorted.lastOrNull() ?: 0.0
    fun pct(p: Double): Double = if (n == 0) 0.0 else sorted[((n - 1) * p).toInt()]
    fun line(unit: String) = if (n == 0) "no data" else
        "n=$n mean=${f(mean)}$unit std=${f(std)}$unit min=${f(min)}$unit p95=${f(pct(0.95))}$unit max=${f(max)}$unit"

    companion object {
        fun f(d: Double) = String.format(Locale.US, "%.2f", d)
    }
}

object Patterns {
    /** 127-bit maximal-length sequence (x^7 + x^6 + 1): a known "test pattern" the receiver can line up against later. */
    val PN: BooleanArray = run {
        var reg = 0x7F
        BooleanArray(127) {
            val bit = ((reg shr 6) xor (reg shr 5)) and 1
            reg = ((reg shl 1) or bit) and 0x7F
            bit == 1
        }
    }

    /** Symbol k of a pattern, as a level 0..(levels-1). */
    fun level(kind: Int, k: Int, levels: Int): Int = when (kind) {
        0 -> if (k % 2 == 0) levels - 1 else 0                // blink
        1 -> if (PN[k % 127]) levels - 1 else 0               // test pattern, on/off
        2 -> k % levels                                       // staircase through levels
        else -> {                                             // random levels from the test pattern
            var v = 0
            for (i in 0 until 2) v = (v shl 1) or (if (PN[(2 * k + i) % 127]) 1 else 0)
            v % levels
        }
    }
}
