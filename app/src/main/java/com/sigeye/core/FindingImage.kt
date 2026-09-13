package com.sigeye.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.Locale
import java.io.File

/**
 * Draws a finding as a picture somebody can post.
 *
 * Drawn rather than screenshotted, for one reason: a screenshot is whatever happened to be
 * on screen, and what is on screen scrolls. The number people want is usually at the top
 * and the denominator and the limit are usually below the fold, so the honest half is the
 * half that does not make it into the picture. Composing the image from a [Finding] means
 * the caveat cannot be cropped off, because it was never in a different part of the page.
 *
 * Portrait and a fixed palette, not the phone's theme. The image is going to be looked at
 * on somebody else's screen in their own light or dark mode, and a card that came out white
 * on white because of how the sender had their phone set up is a card nobody reads.
 *
 * The layout is deliberately boring: label, enormous number, what it counts, what it is out
 * of, the conditions, then the limit in a box of its own at the bottom. The limit gets a box
 * so that it reads as part of the finding rather than as small print under it.
 */
object FindingImage {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val MARGIN = 84f

    // Fixed, so the card looks the same wherever it ends up.
    private const val INK = 0xFFF2F5F4.toInt()
    private const val GROUND = 0xFF0E1116.toInt()
    private const val ACCENT = 0xFF7FE3A3.toInt()
    private const val MUTED = 0xFF8C9AA3.toInt()
    private const val PANEL = 0xFF1A2028.toInt()

    fun render(finding: Finding): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(GROUND)

        var y = MARGIN + 40f

        y = drawText(canvas, finding.experiment.uppercase(Locale.US), MARGIN, y, 34f, ACCENT, bold = true)
        y += 48f

        // The number, as large as it will go and still fit. A long one has to shrink or it
        // runs off the side, and running off the side is how a card looks broken.
        val headlineSize = fitted(finding.headline, WIDTH - MARGIN * 2, 260f)
        y = drawText(canvas, finding.headline, MARGIN, y, headlineSize, INK, bold = true)
        y += 16f

        y = drawWrapped(canvas, finding.unit, MARGIN, y, 52f, INK)
        y += 18f
        y = drawWrapped(canvas, finding.denominator, MARGIN, y, 40f, ACCENT)

        finding.conditions().takeIf { it.isNotBlank() }?.let {
            y += 14f
            y = drawWrapped(canvas, it, MARGIN, y, 34f, MUTED)
        }

        drawLimit(canvas, finding)
        drawFooter(canvas, finding)
        return bitmap
    }

    /** Writes the picture next to the exports and hands it to the share sheet. */
    fun share(context: Context, finding: Finding) {
        runCatching {
            val directory = File(context.getExternalFilesDir(null), "cards").apply { mkdirs() }
            val file = File(directory, "sigeye-${finding.takenAtMs}.png")
            file.outputStream().use { out ->
                render(finding).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            SweepExport.share(
                context = context,
                file = file,
                mime = "image/png",
                title = "Share this finding",
            )
        }
    }

    /**
     * The limit, in a panel at the bottom, always.
     *
     * Anchored to the bottom rather than flowing after the numbers so that its position
     * does not depend on how much else there was to say. A caveat that moves around is a
     * caveat people learn to skip.
     */
    private fun drawLimit(canvas: Canvas, finding: Finding) {
        val paint = textPaint(30f, INK, bold = false)
        val width = (WIDTH - MARGIN * 2 - 56f).toInt()
        val layout = wrap(finding.limit, paint, width)

        val padding = 28f
        val labelSize = 24f
        val gap = 18f
        val boxHeight = padding + labelSize + gap + layout.height + padding
        val top = HEIGHT - MARGIN - 60f - boxHeight

        canvas.drawRoundRect(
            MARGIN, top, WIDTH - MARGIN, top + boxHeight, 24f, 24f,
            Paint().apply { color = PANEL; isAntiAlias = true },
        )

        drawText(
            canvas,
            "WHAT THIS DOES NOT SHOW",
            MARGIN + padding,
            top + padding,
            labelSize,
            MUTED,
            bold = true,
        )

        canvas.save()
        canvas.translate(MARGIN + padding, top + padding + labelSize + gap)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawFooter(canvas: Canvas, finding: Finding) {
        drawText(
            canvas,
            "SigEye · ${finding.stamp()}",
            MARGIN,
            HEIGHT - MARGIN + 10f,
            26f,
            MUTED,
            bold = false,
        )
    }

    /** Shrinks a headline until it fits, rather than letting it run off the card. */
    private fun fitted(text: String, available: Float, preferred: Float): Float {
        var size = preferred
        val paint = textPaint(size, INK, bold = true)
        while (size > 60f && paint.measureText(text) > available) {
            size -= 8f
            paint.textSize = size
        }
        return size
    }

    /** Returns the baseline to carry on from. */
    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        colour: Int,
        bold: Boolean,
    ): Float {
        val paint = textPaint(size, colour, bold)
        canvas.drawText(text, x, y + size, paint)
        return y + size + size * 0.2f
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        colour: Int,
    ): Float {
        val paint = textPaint(size, colour, bold = false)
        val layout = wrap(text, paint, (WIDTH - MARGIN * 2).toInt())
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return y + layout.height
    }

    private fun wrap(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1f)
            .setIncludePad(false)
            .build()

    private fun textPaint(size: Float, colour: Int, bold: Boolean) = TextPaint().apply {
        this.color = colour
        textSize = size
        isAntiAlias = true
        typeface = Typeface.create(
            Typeface.SANS_SERIF,
            if (bold) Typeface.BOLD else Typeface.NORMAL,
        )
    }
}
