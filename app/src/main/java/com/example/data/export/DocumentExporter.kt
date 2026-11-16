package com.example.data.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.data.audio.AudioFileException
import com.example.data.audio.AudioFileHelper
import com.example.data.local.TranscriptEntity
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.ExportFormat
import com.example.domain.TranscriptSegment
import com.example.domain.TranscriptionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocumentExporter {

    suspend fun exportToUri(
        context: Context,
        uri: Uri,
        entity: TranscriptEntity,
        mode: TranscriptionMode,
        segments: List<TranscriptSegment>,
        format: ExportFormat
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { out ->
            writeExport(context, out, entity, mode, segments, format)
        } ?: throw AudioFileException(AppError(AppErrorType.EXPORT_FAILED, "Could not open output stream"))
    }

    suspend fun exportToShareCacheFile(
        context: Context,
        entity: TranscriptEntity,
        mode: TranscriptionMode,
        segments: List<TranscriptSegment>,
        format: ExportFormat
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = sanitizeFilename(entity.title)
        val outFile = File(dir, "${safeName}.${format.extension}")
        FileOutputStream(outFile).use { out ->
            writeExport(context, out, entity, mode, segments, format)
        }
        outFile
    }

    internal fun writeExport(
        context: Context,
        outputStream: OutputStream,
        entity: TranscriptEntity,
        mode: TranscriptionMode,
        segments: List<TranscriptSegment>,
        format: ExportFormat
    ) {
        val bodyText = entity.textForMode(mode)
        when (format) {
            ExportFormat.TXT -> writeTxt(outputStream, bodyText)
            ExportFormat.PDF -> writePdf(context, outputStream, entity, mode, bodyText)
            ExportFormat.DOCX -> writeDocx(outputStream, entity, mode, bodyText)
            ExportFormat.SRT -> writeSrt(outputStream, segments)
        }
    }

    internal fun writeTxt(outputStream: OutputStream, text: String) {
        outputStream.write(text.toByteArray(StandardCharsets.UTF_8))
        outputStream.flush()
    }

    internal fun buildSrtContent(segments: List<TranscriptSegment>): String {
        if (segments.isEmpty()) {
            throw AudioFileException(AppError(AppErrorType.NO_TIMESTAMPS_FOR_SRT))
        }
        val sb = StringBuilder()
        segments.forEachIndexed { idx, seg ->
            val start = formatSrtTime(seg.startMs)
            val end = formatSrtTime(seg.endMs.coerceAtLeast(seg.startMs + 500L))
            sb.append(idx + 1).append('\n')
            sb.append(start).append(" --> ").append(end).append('\n')
            sb.append(seg.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    internal fun writeSrt(outputStream: OutputStream, segments: List<TranscriptSegment>) {
        val srt = buildSrtContent(segments)
        outputStream.write(srt.toByteArray(StandardCharsets.UTF_8))
        outputStream.flush()
    }

    private fun writePdf(
        context: Context,
        outputStream: OutputStream,
        entity: TranscriptEntity,
        mode: TranscriptionMode,
        bodyText: String
    ) {
        val latinFont = runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) }.getOrNull()
            ?: Typeface.SANS_SERIF
        val bengaliFont = runCatching { ResourcesCompat.getFont(context, R.font.noto_sans_bengali) }.getOrNull()
            ?: latinFont
        val devanagariFont = runCatching { ResourcesCompat.getFont(context, R.font.noto_sans_devanagari) }.getOrNull()
            ?: latinFont

        // A4 at 72 DPI: 595 x 842
        val pageWidth = 595
        val pageHeight = 842
        val marginH = 48
        val marginTop = 52
        val marginBottom = 52
        val contentWidth = pageWidth - marginH * 2

        val document = PdfDocument()
        try {
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 16f
                typeface = Typeface.create(latinFont, Typeface.BOLD)
            }
            val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#475569")
                textSize = 9.5f
                typeface = latinFont
            }
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1E293B")
                textSize = 11.5f
                typeface = latinFont
            }
            val dividerPaint = Paint().apply {
                color = Color.parseColor("#CBD5E1")
                strokeWidth = 1f
            }

            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entity.createdAt))
            val durationStr = AudioFileHelper.formatDuration(entity.audioDurationMs)
            val metaLine = "${entity.originalFileName}  |  $durationStr  |  ${entity.detectedLanguage.uppercase()}  |  ${mode.id.uppercase()}  |  $dateStr"

            val titleSpanned = buildMultiscriptSpannable(entity.title, latinFont, bengaliFont, devanagariFont)
            val titleLayout = StaticLayout.Builder.obtain(titleSpanned, 0, titleSpanned.length, titlePaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.15f)
                .build()

            val metaSpanned = buildMultiscriptSpannable(metaLine, latinFont, bengaliFont, devanagariFont)
            val metaLayout = StaticLayout.Builder.obtain(metaSpanned, 0, metaSpanned.length, metaPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()

            val bodySpanned = buildMultiscriptSpannable(bodyText, latinFont, bengaliFont, devanagariFont)
            val bodyLayout = StaticLayout.Builder.obtain(bodySpanned, 0, bodySpanned.length, bodyPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.35f)
                .build()

            var pageNumber = 1
            var lineIdx = 0
            val totalLines = bodyLayout.lineCount

            while (lineIdx < totalLines || pageNumber == 1) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val page = document.startPage(pageInfo)
                val canvas: Canvas = page.canvas
                var y = marginTop.toFloat()

                if (pageNumber == 1) {
                    canvas.save()
                    canvas.translate(marginH.toFloat(), y)
                    titleLayout.draw(canvas)
                    canvas.restore()
                    y += titleLayout.height + 6f

                    canvas.save()
                    canvas.translate(marginH.toFloat(), y)
                    metaLayout.draw(canvas)
                    canvas.restore()
                    y += metaLayout.height + 10f

                    canvas.drawLine(marginH.toFloat(), y, (pageWidth - marginH).toFloat(), y, dividerPaint)
                    y += 16f
                }

                val availableHeight = (pageHeight - marginBottom) - y
                val startLine = lineIdx
                val firstLineTop = if (startLine < totalLines) bodyLayout.getLineTop(startLine) else 0

                while (lineIdx < totalLines) {
                    val lineBottom = bodyLayout.getLineBottom(lineIdx) - firstLineTop
                    if (lineBottom > availableHeight && lineIdx > startLine) {
                        break
                    }
                    lineIdx++
                }

                if (startLine < lineIdx) {
                    val clipHeight = (bodyLayout.getLineBottom(lineIdx - 1) - firstLineTop).toFloat()
                    canvas.save()
                    canvas.translate(marginH.toFloat(), y - firstLineTop.toFloat())
                    canvas.clipRect(0f, firstLineTop.toFloat(), contentWidth.toFloat(), firstLineTop + clipHeight)
                    bodyLayout.draw(canvas)
                    canvas.restore()
                }

                // Footer page number
                val footerText = "AudioText Pro  •  $pageNumber"
                canvas.drawText(footerText, marginH.toFloat(), (pageHeight - 24).toFloat(), metaPaint)
                document.finishPage(page)

                if (lineIdx >= totalLines) break
                pageNumber++
            }

            document.writeTo(outputStream)
            outputStream.flush()
        } finally {
            document.close()
        }
    }

    internal fun writeDocx(
        outputStream: OutputStream,
        entity: TranscriptEntity,
        mode: TranscriptionMode,
        bodyText: String
    ) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entity.createdAt))
        val durationStr = AudioFileHelper.formatDuration(entity.audioDurationMs)
        val metaLine = "${entity.originalFileName} | $durationStr | ${entity.detectedLanguage.uppercase()} | ${mode.id.uppercase()} | $dateStr"

        val paragraphsXml = StringBuilder()
        paragraphsXml.append(docxParagraphXml(entity.title, bold = true, fontSizeHalfPts = 32))
        paragraphsXml.append(docxParagraphXml(metaLine, bold = false, fontSizeHalfPts = 20, colorHex = "475569"))
        paragraphsXml.append(docxParagraphXml("", bold = false, fontSizeHalfPts = 22))

        val lines = bodyText.replace("\r\n", "\n").split('\n')
        for (line in lines) {
            paragraphsXml.append(docxParagraphXml(line, bold = false, fontSizeHalfPts = 24))
        }

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    $paragraphsXml
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
    </w:sectPr>
  </w:body>
</w:document>"""

        val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        ZipOutputStream(outputStream).use { zip ->
            addZipEntry(zip, "[Content_Types].xml", contentTypesXml)
            addZipEntry(zip, "_rels/.rels", rootRelsXml)
            addZipEntry(zip, "word/document.xml", documentXml)
        }
    }

    private fun docxParagraphXml(
        text: String,
        bold: Boolean,
        fontSizeHalfPts: Int,
        colorHex: String = "0F172A"
    ): String {
        val escaped = xmlEscape(text)
        val boldTag = if (bold) "<w:b/>" else ""
        return """
            <w:p>
              <w:pPr><w:spacing w:after="140" w:line="320" w:lineRule="auto"/></w:pPr>
              <w:r>
                <w:rPr>
                  <w:rFonts w:ascii="Noto Sans Bengali" w:hAnsi="Noto Sans Bengali" w:cs="Noto Sans Bengali"/>
                  $boldTag
                  <w:color w:val="$colorHex"/>
                  <w:sz w:val="$fontSizeHalfPts"/>
                  <w:szCs w:val="$fontSizeHalfPts"/>
                </w:rPr>
                <w:t xml:space="preserve">$escaped</w:t>
              </w:r>
            </w:p>
        """.trimIndent()
    }

    private fun addZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun xmlEscape(input: String): String = buildString(input.length) {
        for (ch in input) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> {
                    // Strip invalid XML 1.0 control characters
                    if (ch.code == 0x9 || ch.code == 0xA || ch.code == 0xD || ch.code >= 0x20) {
                        append(ch)
                    }
                }
            }
        }
    }

    private fun buildMultiscriptSpannable(
        text: String,
        latinFont: Typeface,
        bengaliFont: Typeface,
        devanagariFont: Typeface
    ): SpannableStringBuilder {
        val builder = SpannableStringBuilder(text)
        if (text.isEmpty()) return builder

        var runStart = 0
        var currentFont = fontForChar(text[0], latinFont, bengaliFont, devanagariFont)
        for (i in 1 until text.length) {
            val ch = text[i]
            if (ch.isWhitespace() || ch in ".,!?;:\"'()-[]0123456789।") continue
            val nextFont = fontForChar(ch, latinFont, bengaliFont, devanagariFont)
            if (nextFont !== currentFont) {
                applyTypefaceSpan(builder, runStart, i, currentFont)
                runStart = i
                currentFont = nextFont
            }
        }
        applyTypefaceSpan(builder, runStart, text.length, currentFont)
        return builder
    }

    private fun fontForChar(
        ch: Char,
        latinFont: Typeface,
        bengaliFont: Typeface,
        devanagariFont: Typeface
    ): Typeface = when (ch.code) {
        in 0x0980..0x09FF -> bengaliFont
        in 0x0900..0x097F -> devanagariFont
        else -> latinFont
    }

    private fun applyTypefaceSpan(builder: SpannableStringBuilder, start: Int, end: Int, typeface: Typeface) {
        if (start >= end) return
        val span: Any = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            TypefaceSpan(typeface)
        } else {
            CompatTypefaceSpan(typeface)
        }
        builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private class CompatTypefaceSpan(private val customTypeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            tp.typeface = customTypeface
        }

        override fun updateMeasureState(tp: TextPaint) {
            tp.typeface = customTypeface
        }
    }

    internal fun formatSrtTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val minutes = (clamped % 3_600_000L) / 60_000L
        val seconds = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun sanitizeFilename(name: String): String {
        val cleaned = name.replace(Regex("[^a-zA-Z0-9\\u0980-\\u09FF\\u0900-\\u097F _-]"), "_").trim()
        return cleaned.ifBlank { "AudioText_Transcript" }.take(48)
    }
}
