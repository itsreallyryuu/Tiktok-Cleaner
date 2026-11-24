package com.app.tiktokcleaner

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Ikon garis (line icon) yang digambar lewat kode, grid 24x24. */
class IconDrawable(private val kind: Kind, private var color: Int) : Drawable() {

    enum class Kind {
        HEART, BOOKMARK, REPOST, PLAY, STOP, SLIDERS, ACCESS, BOLT, GLOBE,
        SUN, MOON, CHECK, CHEVRON_DOWN, CHEVRON_UP, CLOCK, FORWARD, SHIELD
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setColor(c: Int) { color = c; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        paint.color = color
        paint.strokeWidth = 1.9f
        paint.style = Paint.Style.STROKE

        when (kind) {
            Kind.HEART -> canvas.drawPath(Path().apply {
                moveTo(12f, 20.5f)
                cubicTo(5f, 15f, 2.5f, 11.5f, 2.5f, 8.2f)
                cubicTo(2.5f, 5.4f, 4.6f, 3.5f, 7.1f, 3.5f)
                cubicTo(9.1f, 3.5f, 11f, 4.6f, 12f, 6.4f)
                cubicTo(13f, 4.6f, 14.9f, 3.5f, 16.9f, 3.5f)
                cubicTo(19.4f, 3.5f, 21.5f, 5.4f, 21.5f, 8.2f)
                cubicTo(21.5f, 11.5f, 19f, 15f, 12f, 20.5f)
                close()
            }, paint)

            Kind.BOOKMARK -> canvas.drawPath(Path().apply {
                moveTo(6f, 4f); lineTo(6f, 21f); lineTo(12f, 16.8f); lineTo(18f, 21f); lineTo(18f, 4f)
                quadTo(18f, 3f, 17f, 3f); lineTo(7f, 3f); quadTo(6f, 3f, 6f, 4f); close()
            }, paint)

            Kind.REPOST -> canvas.drawPath(Path().apply {
                moveTo(17f, 1.5f); lineTo(21f, 5f); lineTo(17f, 8.5f)
                moveTo(3f, 11f); lineTo(3f, 9f); quadTo(3f, 5f, 7f, 5f); lineTo(21f, 5f)
                moveTo(7f, 22.5f); lineTo(3f, 19f); lineTo(7f, 15.5f)
                moveTo(21f, 13f); lineTo(21f, 15f); quadTo(21f, 19f, 17f, 19f); lineTo(3f, 19f)
            }, paint)

            Kind.PLAY -> {
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = 1.6f
                canvas.drawPath(Path().apply {
                    moveTo(8f, 5.5f); lineTo(19f, 12f); lineTo(8f, 18.5f); close()
                }, paint)
            }

            Kind.STOP -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(6.5f, 6.5f, 17.5f, 17.5f), 2.5f, 2.5f, paint)
            }

            Kind.SLIDERS -> {
                canvas.drawLine(4f, 6f, 20f, 6f, paint)
                canvas.drawLine(4f, 12f, 20f, 12f, paint)
                canvas.drawLine(4f, 18f, 20f, 18f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(8f, 6f, 2.6f, paint)
                canvas.drawCircle(16f, 12f, 2.6f, paint)
                canvas.drawCircle(11f, 18f, 2.6f, paint)
            }

            Kind.ACCESS -> {
                canvas.drawCircle(12f, 4.6f, 2f, paint)
                canvas.drawPath(Path().apply {
                    moveTo(5f, 9f); lineTo(19f, 9f)
                    moveTo(12f, 9f); lineTo(12f, 14f)
                    moveTo(12f, 14f); lineTo(8f, 21f)
                    moveTo(12f, 14f); lineTo(16f, 21f)
                }, paint)
            }

            Kind.BOLT -> canvas.drawPath(Path().apply {
                moveTo(13f, 2.5f); lineTo(5f, 13.5f); lineTo(12f, 13.5f)
                lineTo(11f, 21.5f); lineTo(19f, 10.5f); lineTo(12f, 10.5f); close()
            }, paint)

            Kind.GLOBE -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawLine(3f, 12f, 21f, 12f, paint)
                canvas.drawOval(RectF(8f, 3f, 16f, 21f), paint)
            }

            Kind.SUN -> {
                canvas.drawCircle(12f, 12f, 4f, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    canvas.drawLine(12f + c * 7f, 12f + sn * 7f, 12f + c * 9.5f, 12f + sn * 9.5f, paint)
                }
            }

            Kind.MOON -> {
                val a = Path().apply { addCircle(12f, 12f, 8.5f, Path.Direction.CW) }
                val c = Path().apply { addCircle(17f, 8f, 7f, Path.Direction.CW) }
                a.op(c, Path.Op.DIFFERENCE)
                canvas.drawPath(a, paint)
            }

            Kind.CHECK -> canvas.drawPath(Path().apply {
                moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
            }, paint)

            Kind.CHEVRON_DOWN -> canvas.drawPath(Path().apply {
                moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
            }, paint)

            Kind.CHEVRON_UP -> canvas.drawPath(Path().apply {
                moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f)
            }, paint)

            Kind.CLOCK -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawPath(Path().apply {
                    moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f)
                }, paint)
            }

            Kind.FORWARD -> canvas.drawPath(Path().apply {
                moveTo(6f, 6f); lineTo(12f, 12f); lineTo(6f, 18f)
                moveTo(12f, 6f); lineTo(18f, 12f); lineTo(12f, 18f)
            }, paint)

            Kind.SHIELD -> {
                canvas.drawPath(Path().apply {
                    moveTo(12f, 2.8f); lineTo(19.5f, 5.8f); lineTo(19.5f, 11.5f)
                    cubicTo(19.5f, 16.3f, 16.4f, 19.8f, 12f, 21.5f)
                    cubicTo(7.6f, 19.8f, 4.5f, 16.3f, 4.5f, 11.5f)
                    lineTo(4.5f, 5.8f); close()
                }, paint)
                canvas.drawPath(Path().apply {
                    moveTo(8.5f, 12f); lineTo(11f, 14.5f); lineTo(15.5f, 9.5f)
                }, paint)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}