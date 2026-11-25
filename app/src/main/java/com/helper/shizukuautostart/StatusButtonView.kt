package com.helper.shizukuautostart

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * A round status button: a solid, softly lit lamp that reads green while the
 * Shizuku service is running and red while it is not.
 *
 * The lamp is drawn as a lit body inside a fully opaque rim, so it looks like a
 * physical button rather than a flat dot: the rim gives it its solid outline and
 * the top-lit body gives it volume. There is deliberately no halo or glow
 * spilling outside the button, and no specular dot inside it.
 */
class StatusButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val ON_COLOR = 0xFF22C55E.toInt()
        const val OFF_COLOR = 0xFFEF4444.toInt()
        const val PULSE_DURATION = 2200L
        const val COLOR_DURATION = 420L
        /** Share of the radius taken by the solid rim. */
        const val RIM_FRACTION = 0.16f
    }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var color = OFF_COLOR
    private var centreX = 0f
    private var centreY = 0f
    private var radius = 0f
    private var rimWidth = 0f
    private var pulse = 0f

    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    /** Applies the running/stopped state, animating the colour when asked to. */
    fun setRunning(running: Boolean, animate: Boolean = true) {
        val target = if (running) ON_COLOR else OFF_COLOR
        contentDescription = context.getString(
            if (running) R.string.status_dot_on else R.string.status_dot_off
        )
        if (target == color) return

        colorAnimator?.cancel()
        if (!animate) {
            color = target
            rebuildShading()
            return
        }
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), color, target).apply {
            duration = COLOR_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                color = it.animatedValue as Int
                rebuildShading()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centreX = w / 2f
        centreY = h / 2f
        // Fill the view the way the old plain dot did, so the lamp measures the
        // same on screen as before.
        radius = minOf(w, h) / 2f * 0.94f
        rimWidth = radius * RIM_FRACTION
        rebuildShading()
    }

    private fun rebuildShading() {
        if (radius <= 0f) return
        val inner = (radius - rimWidth).coerceAtLeast(1f)
        // Top-lit body: a lighter cap fading to a deeper base gives the button
        // its convex, "switched on" look without any outer glow or white dot.
        bodyPaint.shader = LinearGradient(
            centreX,
            centreY - inner,
            centreX,
            centreY + inner,
            lighten(color, 0.24f + pulse * 0.10f),
            darken(color, 0.18f),
            Shader.TileMode.CLAMP
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        // Solid rim, drawn as a fully opaque disc that the body sits inside.
        // It is what makes the lamp read as a solid button rather than a glow.
        rimPaint.style = Paint.Style.FILL
        rimPaint.color = darken(color, 0.42f)
        canvas.drawCircle(centreX, centreY, radius, rimPaint)

        // Lit body.
        bodyPaint.style = Paint.Style.FILL
        canvas.drawCircle(centreX, centreY, radius - rimWidth, bodyPaint)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate()
            .scaleX(if (pressed) 0.88f else 1f)
            .scaleY(if (pressed) 0.88f else 1f)
            .setDuration(if (pressed) 90L else 260L)
            .start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pulseAnimator == null) {
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = PULSE_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    rebuildShading()
                }
            }
        }
        pulseAnimator?.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun lighten(color: Int, fraction: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * fraction).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * fraction).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * fraction).toInt()
    )

    private fun darken(color: Int, fraction: Float): Int = Color.rgb(
        (Color.red(color) * (1f - fraction)).toInt(),
        (Color.green(color) * (1f - fraction)).toInt(),
        (Color.blue(color) * (1f - fraction)).toInt()
    )
}
