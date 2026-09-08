/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 SCIONtra / WireGuard Project
 */

package com.wireguard.android.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import com.wireguard.android.model.PathGeoDomain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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

    // Zoom & scale
    private var zoom: Float = 1.0f

    // Touch interaction
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(0.8f, 3.5f)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = if (zoom > 1.3f) 1.0f else 2.2f
            centerLat = targetLat
            centerLon = targetLon
            invalidate()
            return true
        }
    })

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
    }

    private val atmospherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#0284C7")
    }

    private val atmosphereGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#38BDF8")
        alpha = 60
    }

    private val graticulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#1B3554")
    }

    private val primeMeridianPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#2563EB")
        alpha = 160
    }

    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E2D44")
    }

    private val landBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = Color.parseColor("#385273")
    }

    private val pathArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = Color.parseColor("#00E5FF")
    }

    private val pathGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        color = Color.parseColor("#0284C7")
        alpha = 100
    }

    private val pathBackArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#334155")
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val pulseDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
    }

    private val pinFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pinHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pinDarkBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#0F172A")
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F20F172A")
    }

    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.parseColor("#38BDF8")
    }

    private val labelStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#64748B")
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textSize = 23f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Comprehensive World Landmass Polygonal Outlines (lat, lon pairs)
    private val worldLandmasses = listOf(
        // Europe & Scandinavia
        listOf(
            36.0 to -5.5, 37.0 to -9.0, 43.5 to -9.0, 43.5 to -2.0, 47.0 to -2.5,
            49.5 to -1.5, 51.0 to 2.0, 53.5 to 7.0, 55.5 to 8.5, 58.0 to 11.5,
            63.0 to 12.0, 70.0 to 25.0, 71.0 to 28.0, 66.0 to 24.0, 60.0 to 18.0,
            56.0 to 12.0, 54.5 to 19.0, 60.0 to 30.0, 67.0 to 42.0, 68.0 to 50.0,
            55.0 to 50.0, 45.0 to 37.0, 45.0 to 29.0, 41.0 to 29.0, 40.0 to 23.0,
            37.5 to 22.0, 40.0 to 18.0, 41.0 to 15.0, 38.0 to 15.0, 44.0 to 8.0,
            43.0 to 3.0, 36.0 to -5.5
        ),
        // Asia
        listOf(
            68.0 to 50.0, 73.0 to 80.0, 76.0 to 110.0, 72.0 to 140.0, 66.0 to 170.0,
            60.0 to -170.0, 55.0 to 160.0, 50.0 to 140.0, 40.0 to 130.0, 32.0 to 122.0,
            22.0 to 114.0, 10.0 to 107.0, 1.5 to 104.0, 15.0 to 100.0, 22.0 to 91.0,
            13.0 to 80.0, 8.0 to 77.5, 15.0 to 73.5, 24.0 to 68.0, 25.0 to 62.0,
            25.0 to 56.0, 13.0 to 45.0, 28.0 to 35.0, 32.0 to 35.0, 37.0 to 36.0,
            41.0 to 29.0, 55.0 to 50.0, 68.0 to 50.0
        ),
        // Africa
        listOf(
            36.0 to -5.5, 37.0 to 10.0, 32.0 to 15.0, 31.5 to 32.0, 27.0 to 34.5,
            12.0 to 44.0, 10.5 to 51.0, 2.0 to 45.0, -5.0 to 39.0, -15.0 to 40.5,
            -26.0 to 33.0, -34.5 to 20.0, -33.0 to 18.0, -22.0 to 14.0, -5.0 to 12.0,
            4.5 to 9.0, 5.5 to 0.0, 4.5 to -8.0, 12.0 to -16.5, 15.0 to -17.5,
            21.0 to -17.0, 33.0 to -9.0, 36.0 to -5.5
        ),
        // North America
        listOf(
            15.0 to -90.0, 18.0 to -96.0, 26.0 to -97.0, 30.0 to -85.0, 25.0 to -80.5,
            35.0 to -75.5, 44.0 to -66.0, 47.0 to -53.0, 52.0 to -56.0, 60.0 to -65.0,
            63.0 to -80.0, 70.0 to -90.0, 72.0 to -120.0, 71.0 to -156.0, 65.0 to -168.0,
            60.0 to -165.0, 54.0 to -163.0, 58.0 to -137.0, 48.5 to -125.0, 37.0 to -122.5,
            32.5 to -117.0, 23.0 to -110.0, 20.0 to -105.0, 15.0 to -93.0, 15.0 to -90.0
        ),
        // South America
        listOf(
            11.5 to -73.0, 10.5 to -62.0, 5.0 to -52.0, -2.5 to -44.0, -5.5 to -35.0,
            -13.0 to -38.5, -23.0 to -43.0, -35.0 to -57.0, -45.0 to -66.0, -55.0 to -67.0,
            -50.0 to -75.0, -40.0 to -74.0, -18.0 to -71.0, -5.0 to -81.0, 2.0 to -79.0,
            8.5 to -77.5, 11.5 to -73.0
        ),
        // Australia
        listOf(
            -12.0 to 131.0, -12.0 to 136.0, -15.0 to 136.0, -11.0 to 142.0, -18.0 to 146.0,
            -28.0 to 153.5, -37.5 to 150.0, -38.5 to 145.0, -35.0 to 136.0, -32.0 to 126.0,
            -34.0 to 115.0, -22.0 to 114.0, -18.0 to 122.0, -14.5 to 129.0, -12.0 to 131.0
        ),
        // British Isles
        listOf(
            50.0 to -5.0, 51.0 to 1.5, 55.0 to -1.5, 58.5 to -3.0, 58.5 to -5.0,
            55.0 to -5.5, 52.5 to -4.5, 50.5 to -4.5, 50.0 to -5.0
        ),
        // Japan
        listOf(
            31.0 to 130.5, 34.0 to 132.0, 35.5 to 136.0, 35.5 to 140.0, 41.5 to 141.5,
            45.0 to 142.0, 43.0 to 145.5, 39.0 to 140.0, 36.0 to 136.0, 34.0 to 131.0,
            31.0 to 130.5
        ),
        // Greenland
        listOf(
            60.0 to -45.0, 65.0 to -40.0, 75.0 to -20.0, 82.0 to -30.0, 83.0 to -40.0,
            76.0 to -68.0, 70.0 to -55.0, 60.0 to -45.0
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
        zoom = 1.0f

        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress) {
            isDragging = false
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    // Drag sensitivity scaled inversely with zoom for stable control
                    val sensitivity = 0.005 / zoom
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
        val baseRadius = min(w, h) * 0.43f
        val radius = baseRadius * zoom

        // 1. Draw Sphere Background & Oceanic Depth
        val oceanShader = RadialGradient(
            cx - baseRadius * 0.35f, cy - baseRadius * 0.35f, baseRadius * 1.35f,
            intArrayOf(Color.parseColor("#131D2D"), Color.parseColor("#090E17")),
            floatArrayOf(0.3f, 1.0f),
            Shader.TileMode.CLAMP
        )
        sphereBgPaint.shader = oceanShader
        canvas.drawCircle(cx, cy, baseRadius, sphereBgPaint)

        // Outer soft cyan glow
        canvas.drawCircle(cx, cy, baseRadius + 2f, atmosphereGlowPaint)
        canvas.drawCircle(cx, cy, baseRadius, atmospherePaint)

        // Clip everything inside sphere
        val clipPath = Path().apply {
            addCircle(cx, cy, baseRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        // 2. Draw Graticule Lines (Parallels & Meridians)
        drawGraticule(canvas, cx, cy, radius)

        // 3. Draw Landmasses (Filled + Crisp Borders)
        drawLandmasses(canvas, cx, cy, radius)

        // 4. Draw Path Arcs between hops
        drawPathArcs(canvas, cx, cy, radius)

        // 5. Draw Hop Node Pins and Intelligent Collision-Free Labels
        drawHopPins(canvas, cx, cy, radius)

        canvas.restore()

        // Draw crisp rim highlight
        canvas.drawCircle(cx, cy, baseRadius, atmospherePaint)
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

            val isEquator = latDeg == 0.0
            val paint = if (isEquator) primeMeridianPaint else graticulePaint

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
            canvas.drawPath(path, paint)
        }

        // Meridians (Longitudes every 30 deg)
        for (lonDeg in -180 until 180 step 30) {
            val lonRad = Math.toRadians(lonDeg.toDouble())
            val path = Path()
            var first = true

            val isPrime = lonDeg == 0
            val paint = if (isPrime) primeMeridianPaint else graticulePaint

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
            canvas.drawPath(path, paint)
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
            canvas.drawPath(path, landBorderPaint)
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
            pulseDotPaint.color = Color.WHITE
            canvas.drawCircle(globalPulseX, globalPulseY, 6f, pulseDotPaint)
            pulseDotPaint.color = Color.parseColor("#00E5FF")
            pulseDotPaint.alpha = 160
            canvas.drawCircle(globalPulseX, globalPulseY, 13f, pulseDotPaint)
            pulseDotPaint.alpha = 255
        }
    }

    private data class VisiblePin(
        val index: Int,
        val pt: GlobePoint,
        val proj: ProjectedPoint,
        var labelOffsetY: Float = -36f,
        var labelOffsetX: Float = 0f,
        var showStem: Boolean = false
    )

    private fun drawHopPins(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val visibleList = mutableListOf<VisiblePin>()

        for ((index, pt) in points.withIndex()) {
            val latRad = Math.toRadians(pt.lat)
            val lonRad = Math.toRadians(pt.lon)
            val proj = project(latRad, lonRad, cx, cy, radius) ?: continue
            if (!proj.visible) continue
            visibleList.add(VisiblePin(index, pt, proj))
        }

        if (visibleList.isEmpty()) return

        // Intelligent Collision Resolution:
        // Check pairwise distance; if two pins project within 55px of each other,
        // offset their labels in opposite directions so they NEVER overlap!
        for (i in 0 until visibleList.size) {
            for (j in i + 1 until visibleList.size) {
                val pinA = visibleList[i]
                val pinB = visibleList[j]
                val dist = hypot((pinA.proj.x - pinB.proj.x).toDouble(), (pinA.proj.y - pinB.proj.y).toDouble()).toFloat()

                if (dist < 55f) {
                    // Stagger: pinA above, pinB below
                    pinA.labelOffsetY = -44f
                    pinA.showStem = true

                    pinB.labelOffsetY = 24f
                    pinB.showStem = true
                }
            }
        }

        // Draw Pins & Labels
        for (item in visibleList) {
            val proj = item.proj
            val pt = item.pt
            val index = item.index

            val pinColor = when {
                pt.isSource -> Color.parseColor("#10B981") // Emerald Green
                pt.isDestination -> Color.parseColor("#00E5FF") // Cyan
                else -> Color.parseColor("#6366F1") // Indigo
            }

            pinFillPaint.color = pinColor
            pinHaloPaint.color = pinColor
            pinHaloPaint.alpha = 50

            // Halo pulse
            canvas.drawCircle(proj.x, proj.y, 14f, pinHaloPaint)
            // Pin Dark Outline
            canvas.drawCircle(proj.x, proj.y, 7f, pinDarkBorderPaint)
            // Main Pin Core
            canvas.drawCircle(proj.x, proj.y, 6f, pinFillPaint)
            // Center Dot
            pinFillPaint.color = Color.WHITE
            canvas.drawCircle(proj.x, proj.y, 2.5f, pinFillPaint)

            // Label pill calculation
            val label = pt.label ?: "Hop ${index + 1}"
            val textWidth = labelTextPaint.measureText(label)
            val pillPadding = 10f
            val pillHeight = 32f
            val pillCenterX = proj.x + item.labelOffsetX
            val pillCenterY = proj.y + item.labelOffsetY

            val pillLeft = pillCenterX - (textWidth / 2f) - pillPadding
            val pillRight = pillCenterX + (textWidth / 2f) + pillPadding
            val pillTop = pillCenterY - (pillHeight / 2f)
            val pillBottom = pillCenterY + (pillHeight / 2f)

            // Draw stem line from pin to label if offset
            if (item.showStem) {
                val stemTargetY = if (item.labelOffsetY < 0) pillBottom else pillTop
                canvas.drawLine(proj.x, proj.y, pillCenterX, stemTargetY, labelStemPaint)
            }

            val rect = RectF(pillLeft, pillTop, pillRight, pillBottom)
            // Crisp solid label pill
            canvas.drawRoundRect(rect, 8f, 8f, labelBgPaint)
            canvas.drawRoundRect(rect, 8f, 8f, labelBorderPaint)
            canvas.drawText(label, pillCenterX, pillCenterY + 8f, labelTextPaint)
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
