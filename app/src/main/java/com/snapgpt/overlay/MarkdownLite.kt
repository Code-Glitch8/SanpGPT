package com.snapgpt.overlay

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Renders the small subset of formatting the explanations use: "# headings", "**bold**", `code`, "- bullets" and
 * CAPITAL section labels such as "FINAL ANSWER:". Everything else is shown as plain text.
 */
object MarkdownLite {
    private val LABEL = Regex("^([A-Z][A-Z /]{2,30}):(.*)$")

    fun render(text: String, accent: Int): CharSequence {
        val out = SpannableStringBuilder()
        val lines = text.replace("\r", "").split('\n')
        for ((i, raw) in lines.withIndex()) {
            var line = raw.trimEnd()
            val start = out.length
            val heading = line.trimStart().startsWith("#")
            if (heading) line = line.trimStart().trimStart('#').trim()
            if (line.startsWith("- ") || line.startsWith("* ")) line = "• " + line.substring(2)
            val label = LABEL.find(line)
            if (label != null) {
                val name = label.groupValues[1]
                out.append(name).append(':')
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(accent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                appendInline(out, label.groupValues[2])
                if (name.trim() == "FINAL ANSWER") out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                appendInline(out, line)
            }
            if (heading) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.1f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < lines.lastIndex) out.append('\n')
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, s: String) {
        var i = 0
        while (i < s.length) {
            if (s.startsWith("**", i)) {
                val end = s.indexOf("**", i + 2)
                if (end > i + 2) {
                    val st = out.length
                    out.append(s, i + 2, end)
                    out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            if (s[i] == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    val st = out.length
                    out.append(s, i + 1, end)
                    out.setSpan(TypefaceSpan("monospace"), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            out.append(s[i])
            i++
        }
    }
}
