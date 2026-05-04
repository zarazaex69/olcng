package xyz.zarazaex.olc.util

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface

/**
 * Simple markdown to Spanned converter for basic ** bold ** syntax without external libraries.
 */
object MarkdownUtil {
    /**
     * Convert simple markdown to Spanned. Supports:
     * - **bold**
     * - # headers (rendered as bold)
     * - - list items (bullet)
     */
    fun parseBasic(text: String): CharSequence {
        val lines = text.split("\n")
        val builder = SpannableStringBuilder()
        
        for (line in lines) {
            val processedLine = processLine(line.trimEnd())
            builder.append(processedLine)
            builder.append("\n")
        }
        
        // Remove trailing newlines
        while (builder.isNotEmpty() && builder.last() == '\n') {
            builder.delete(builder.length - 1, builder.length)
        }
        
        return builder
    }
    
    private fun processLine(line: String): CharSequence {
        // Handle headers
        val headerLine = when {
            line.startsWith("### ") -> line.removePrefix("### ")
            line.startsWith("## ") -> line.removePrefix("## ")
            line.startsWith("# ") -> line.removePrefix("# ")
            else -> null
        }
        if (headerLine != null) {
            val sb = SpannableStringBuilder(processBold(headerLine))
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        
        // Handle list items
        if (line.startsWith("- ") || line.startsWith("* ")) {
            return SpannableStringBuilder("• " + processBold(line.substring(2)))
        }
        
        return processBold(line)
    }
    
    private fun processBold(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    val boldText = text.substring(i + 2, end)
                    val start = sb.length
                    sb.append(boldText)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                } else {
                    sb.append(text[i])
                    i++
                }
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb
    }
}
