/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.wireguard.android.model.PathGeoDomain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PathGlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class GlobePoint(
        val lat: Double,
        val lon: Double,
        val label: String? = null,
        val isSource: Boolean = false,
        val isDestination: Boolean = false
    )

    private val points = mutableListOf<GlobePoint>()

    // Camera angles in radians
    private var centerLat: Double = 0.82 // ~47° N (centered around Europe by default)
    private var centerLon: Double = 0.14 // ~8° E
    private var targetLat: Double = centerLat
    private var targetLon: Double = centerLon

    // Touch interaction
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    // Animation
    private var pulseProgress = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseProgress = it.animatedValue as Float
            invalidate()
        }
    }

    // Paints
    private val sphereBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#13161C")
    }

    private val atmospherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#400067B8")
    }

    private val graticulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#225A6577")
    }

    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1D2330")
    }

    private val pathArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = Color.parseColor("#4A9AE0")
    }

    private val pathGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#330067B8")
    }

    private val pathBackArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#255A6577")
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val pulseDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00F0FF")
    }

    private val pinFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pinHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC111318")
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Simplified Continent Polygon Outlines (lat, lon pairs)
    private val worldLandmasses = listOf(
        // Europe & Asia outline
        listOf(
            36.0 to -5.0, 43.0 to -9.0, 48.0 to -4.0, 53.0 to 5.0, 58.0 to 10.0,
            65.0 to 14.0, 71.0 to 28.0, 68.0 to 45.0, 60.0 to 60.0, 55.0 to 80.0,
            40.0 to 120.0, 25.0 to 120.0, 15.0 to 105.0, 22.0 to 90.0, 10.0 to 76.0,
            25.0 to 65.0, 30.0 to 50.0, 32.0 to 35.0, 36.0 to 28.0, 40.0 to 20.0,
            38.0 to 15.0, 36.0 to -5.0
        ),
        // Africa outline
        listOf(
            35.0 to -5.0, 37.0 to 10.0, 32.0 to 32.0, 12.0 to 44.0, -10.0 to 40.0,
            -34.0 to 20.0, -22.0 to 14.0, 5.0 to 9.0, 5.0 to -10.0, 15.0 to -17.0,
            28.0 to -13.0, 35.0 to -5.0
        ),
        // North America outline
        listOf(
            15.0 to -90.0, 20.0 to -105.0, 30.0 to -115.0, 45.0 to -125.0, 60.0 to -140.0,
            70.0 to -160.0, 72.0 to -120.0, 60.0 to -80.0, 50.0 to -60.0, 40.0 to -70.0,
            25.0 to -80.0, 15.0 to -90.0
        )
    )

    init {
        pulseAnimator.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!pulseAnimator.isRunning) {
            pulseAnimator.start()
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setPath(geoCoordinates: List<PathGeoDomain>) {
        points.clear()
        val valid = geoCoordinates.filter {
            it.latitude != null && it.latitude != 0.0 && it.longitude != null && it.longitude != 0.0
        }
        if (valid.isEmpty()) {
            invalidate()
            return
        }

        var avgLat = 0.0
        var avgLon = 0.0

        valid.forEachIndexed { index, g ->
            val p = GlobePoint(
                lat = g.latitude!!,
                lon = g.longitude!!,
                label = g.label?.replace("\n", ", ")?.substringBefore(","),
                isSource = index == 0,
                isDestination = index == valid.size - 1
            )
            points.add(p)
            avgLat += p.lat
            avgLon += p.lon
        }

        avgLat /= valid.size
        avgLon /= valid.size

        // Set target camera to center of path
        targetLat = Math.toRadians(avgLat)
        targetLon = Math.toRadians(avgLon)

        centerLat = targetLat
        centerLon = targetLon

        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    // Drag factor in radians
                    val sensitivity = 0.006
                    centerLon -= dx * sensitivity
                    centerLat = (centerLat + dy * sensitivity).coerceIn(-PI / 2.2, PI / 2.2)

                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) * 0.42f

        // 1. Draw Sphere Background & Atmospheric Glow
        val atmosphereShader = RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.3f,
            intArrayOf(Color.parseColor("#1B2230"), Color.parseColor("#0C0E14")),
            floatArrayOf(0.4f, 1.0f),
            Shader.TileMode.CLAMP
        )
        sphereBgPaint.shader = atmosphereShader
        canvas.drawCircle(cx, cy, radius, sphereBgPaint)
        canvas.drawCircle(cx, cy, radius, atmospherePaint)

        // Clip drawing to sphere circle
        val clipPath = Path().apply {
            addCircle(cx, cy, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        // 2. Draw Graticule Lines (Parallels & Meridians)
        drawGraticule(canvas, cx, cy, radius)

        // 3. Draw Landmasses
        drawLandmasses(canvas, cx, cy, radius)

        // 4. Draw Path Arcs between hops
        drawPathArcs(canvas, cx, cy, radius)

        // 5. Draw Hop Node Pins and Labels
        drawHopPins(canvas, cx, cy, radius)

        canvas.restore()

        // Draw subtle glass highlight rim
        canvas.drawCircle(cx, cy, radius, atmospherePaint)
    }

    private fun project(latRad: Double, lonRad: Double, cx: Float, cy: Float, radius: Float): ProjectedPoint? {
        val cosC = sin(centerLat) * sin(latRad) + cos(centerLat) * cos(latRad) * cos(lonRad - centerLon)
        val visible = cosC >= 0.0

        val x = cx + (radius * cos(latRad) * sin(lonRad - centerLon)).toFloat()
        val y = cy - (radius * (cos(centerLat) * sin(latRad) - sin(centerLat) * cos(latRad) * cos(lonRad - centerLon))).toFloat()
        val z = (radius * cosC).toFloat()

        return ProjectedPoint(x, y, z, visible)
    }

    private data class ProjectedPoint(val x: Float, val y: Float, val z: Float, val visible: Boolean)

    private fun drawGraticule(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Parallels (Latitudes: -60, -30, 0, 30, 60)
        for (latDeg in listOf(-60.0, -30.0, 0.0, 30.0, 60.0)) {
            val latRad = Math.toRadians(latDeg)
            val path = Path()
            var first = true

            for (lonDeg in -180..180 step 10) {
                val lonRad = Math.toRadians(lonDeg.toDouble())
                val p = project(latRad, lonRad, cx, cy, radius)
                if (p != null && p.visible) {
                    if (first) {
                        path.moveTo(p.x, p.y)
                        first = false
                    } else {
                        path.lineTo(p.x, p.y)
                    }
                } else {
                    first = true
                }
            }
            canvas.drawPath(path, graticulePaint)
        }

        // Meridians (Longitudes every 30 deg)
        for (lonDeg in -180 until 180 step 30) {
            val lonRad = Math.toRadians(lonDeg.toDouble())
            val path = Path()
            var first = true

            for (latDeg in -80..80 step 5) {
                val latRad = Math.toRadians(latDeg.toDouble())
                val p = project(latRad, lonRad, cx, cy, radius)
                if (p != null && p.visible) {
                    if (first) {
                        path.moveTo(p.x, p.y)
                        first = false
                    } else {
                        path.lineTo(p.x, p.y)
                    }
                } else {
                    first = true
                }
            }
            canvas.drawPath(path, graticulePaint)
        }
    }

    private fun drawLandmasses(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        for (polygon in worldLandmasses) {
            val path = Path()
            var first = true

            for ((latDeg, lonDeg) in polygon) {
                val latRad = Math.toRadians(latDeg)
                val lonRad = Math.toRadians(lonDeg)
                val p = project(latRad, lonRad, cx, cy, radius)
                if (p != null && p.visible) {
                    if (first) {
                        path.moveTo(p.x, p.y)
                        first = false
                    } else {
                        path.lineTo(p.x, p.y)
                    }
                }
            }
            path.close()
            canvas.drawPath(path, landPaint)
        }
    }

    private fun drawPathArcs(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (points.size < 2) return

        val totalSegments = 24
        val frontPath = Path()
        val backPath = Path()

        var globalPulseX = -1f
        var globalPulseY = -1f
        var globalPulseVisible = false

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            val lat1 = Math.toRadians(p1.lat)
            val lon1 = Math.toRadians(p1.lon)
            val lat2 = Math.toRadians(p2.lat)
            val lon2 = Math.toRadians(p2.lon)

            var lastPt: ProjectedPoint? = null

            for (step in 0..totalSegments) {
                val t = step / totalSegments.toDouble()
                val (interLat, interLon) = slerp(lat1, lon1, lat2, lon2, t)
                val proj = project(interLat, interLon, cx, cy, radius) ?: continue

                if (proj.visible) {
                    if (lastPt == null || !lastPt.visible) {
                        frontPath.moveTo(proj.x, proj.y)
                    } else {
                        frontPath.lineTo(proj.x, proj.y)
                    }
                } else {
                    if (lastPt == null || lastPt.visible) {
                        backPath.moveTo(proj.x, proj.y)
                    } else {
                        backPath.lineTo(proj.x, proj.y)
                    }
                }
                lastPt = proj
            }

            // Compute current pulse dot along path
            val pulseT = (pulseProgress * (points.size - 1) - i).coerceIn(0f, 1f).toDouble()
            if (pulseT in 0.0..1.0 && (pulseProgress * (points.size - 1)) >= i && (pulseProgress * (points.size - 1)) <= (i + 1)) {
                val (pLat, pLon) = slerp(lat1, lon1, lat2, lon2, pulseT)
                val pProj = project(pLat, pLon, cx, cy, radius)
                if (pProj != null && pProj.visible) {
                    globalPulseX = pProj.x
                    globalPulseY = pProj.y
                    globalPulseVisible = true
                }
            }
        }

        // Draw back & front paths
        canvas.drawPath(backPath, pathBackArcPaint)
        canvas.drawPath(frontPath, pathGlowPaint)
        canvas.drawPath(frontPath, pathArcPaint)

        // Draw animated pulse
        if (globalPulseVisible) {
            pulseDotPaint.alpha = 255
            canvas.drawCircle(globalPulseX, globalPulseY, 7f, pulseDotPaint)
            pulseDotPaint.alpha = 100
            canvas.drawCircle(globalPulseX, globalPulseY, 14f, pulseDotPaint)
        }
    }

    private fun drawHopPins(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        for ((index, pt) in points.withIndex()) {
            val latRad = Math.toRadians(pt.lat)
            val lonRad = Math.toRadians(pt.lon)
            val proj = project(latRad, lonRad, cx, cy, radius) ?: continue
            if (!proj.visible) continue

            val pinColor = when {
                pt.isSource -> Color.parseColor("#10B981") // Green
                pt.isDestination -> Color.parseColor("#0067B8") // Deep Blue
                else -> Color.parseColor("#4A9AE0") // Cyan
            }

            pinFillPaint.color = pinColor
            pinHaloPaint.color = pinColor
            pinHaloPaint.alpha = 60

            // Halo pulse
            canvas.drawCircle(proj.x, proj.y, 14f, pinHaloPaint)
            // Main Pin
            canvas.drawCircle(proj.x, proj.y, 7f, pinFillPaint)
            // Inner Core
            pinFillPaint.color = Color.WHITE
            canvas.drawCircle(proj.x, proj.y, 3f, pinFillPaint)

            // Label pill
            val label = pt.label ?: "Hop ${index + 1}"
            val textWidth = labelTextPaint.measureText(label)
            val pillPadding = 12f
            val pillHeight = 36f
            val pillTop = proj.y - 48f

            val rect = RectF(
                proj.x - (textWidth / 2f) - pillPadding,
                pillTop,
                proj.x + (textWidth / 2f) + pillPadding,
                pillTop + pillHeight
            )
            canvas.drawRoundRect(rect, 10f, 10f, labelBgPaint)
            canvas.drawText(label, proj.x, pillTop + 25f, labelTextPaint)
        }
    }

    // Great circle interpolation (Spherical Linear Interpolation)
    private fun slerp(lat1: Double, lon1: Double, lat2: Double, lon2: Double, t: Double): Pair<Double, Double> {
        val x1 = cos(lat1) * cos(lon1)
        val y1 = cos(lat1) * sin(lon1)
        val z1 = sin(lat1)

        val x2 = cos(lat2) * cos(lon2)
        val y2 = cos(lat2) * sin(lon2)
        val z2 = sin(lat2)

        val dot = (x1 * x2 + y1 * y2 + z1 * z2).coerceIn(-1.0, 1.0)
        val omega = Math.acos(dot)

        if (abs(omega) < 1e-6) {
            return lat1 to lon1
        }

        val sinOmega = sin(omega)
        val a = sin((1 - t) * omega) / sinOmega
        val b = sin(t * omega) / sinOmega

        val x = a * x1 + b * x2
        val y = a * y1 + b * y2
        val z = a * z1 + b * z2

        val outLat = atan2(z, sqrt(x * x + y * y))
        val outLon = atan2(y, x)

        return outLat to outLon
    }
}
