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
import android.view.ViewConfiguration
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

    // Touch interaction & disambiguation
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var touchInterceptionDisallowed = false

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

    // Precomputed flat radian coordinates for zero-allocation landmass rendering
    private val worldLandmassesRad: List<DoubleArray> = worldLandmasses.map { polygon ->
        val arr = DoubleArray(polygon.size * 2)
        for (i in polygon.indices) {
            val (latDeg, lonDeg) = polygon[i]
            arr[i * 2] = Math.toRadians(latDeg)
            arr[i * 2 + 1] = Math.toRadians(lonDeg)
        }
        arr
    }

    private val parallelLats = doubleArrayOf(-60.0, -30.0, 0.0, 30.0, 60.0)

    // Reusable graphics primitives (zero allocations in onDraw)
    private val clipPath = Path()
    private val graticulePath = Path()
    private val landmassPath = Path()
    private val frontArcPath = Path()
    private val backArcPath = Path()
    private val reusableRect = RectF()
    private val tempProj = ReusableProjectedPoint()
    private val slerpOut = DoubleArray(2)

    private class ReusableProjectedPoint {
        var x: Float = 0f
        var y: Float = 0f
        var z: Float = 0f
        var visible: Boolean = false

        fun set(latRad: Double, lonRad: Double, cx: Float, cy: Float, radius: Float, cLat: Double, cLon: Double): Boolean {
            val cosC = sin(cLat) * sin(latRad) + cos(cLat) * cos(latRad) * cos(lonRad - cLon)
            visible = cosC >= 0.0
            x = cx + (radius * cos(latRad) * sin(lonRad - cLon)).toFloat()
            y = cy - (radius * (cos(cLat) * sin(latRad) - sin(cLat) * cos(latRad) * cos(lonRad - cLon))).toFloat()
            z = (radius * cosC).toFloat()
            return visible
        }
    }

    private class VisiblePin {
        var index: Int = 0
        var pt: GlobePoint? = null
        var projX: Float = 0f
        var projY: Float = 0f
        var labelOffsetY: Float = -36f
        var labelOffsetX: Float = 0f
        var showStem: Boolean = false
    }

    private val visiblePinsPool = mutableListOf<VisiblePin>()

    // Cached shader dimensions
    private var cachedWidth = 0f
    private var cachedHeight = 0f
    private var cachedBaseRadius = 0f

    init {
        updateAnimationState()
    }

    private fun updateAnimationState() {
        val shouldAnimate = isAttachedToWindow && isShown && windowVisibility == View.VISIBLE
        if (shouldAnimate) {
            if (!pulseAnimator.isRunning) {
                pulseAnimator.start()
            }
        } else {
            if (pulseAnimator.isRunning) {
                pulseAnimator.cancel()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimationState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimationState()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateOceanShader(w.toFloat(), h.toFloat())
    }

    private fun updateOceanShader(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        cachedWidth = w
        cachedHeight = h
        val cx = w / 2f
        val cy = h / 2f
        val baseRadius = min(w, h) * 0.43f
        cachedBaseRadius = baseRadius

        sphereBgPaint.shader = RadialGradient(
            cx - baseRadius * 0.35f, cy - baseRadius * 0.35f, baseRadius * 1.35f,
            intArrayOf(Color.parseColor("#131D2D"), Color.parseColor("#090E17")),
            floatArrayOf(0.3f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    fun setPath(geoCoordinates: List<PathGeoDomain>) {
        points.clear()
        val valid = geoCoordinates.filter {
            it.latitude != null && it.longitude != null
        }
        if (valid.isEmpty()) {
            contentDescription = "Interactive SCION path globe"
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

        val hopsCount = points.size
        val src = points.first().label ?: "Origin"
        val dst = points.last().label ?: "Destination"
        contentDescription = "SCION Path globe showing $hopsCount hops from $src to $dst. Double tap to reset orientation."

        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress) {
            isDragging = false
            if (!touchInterceptionDisallowed) {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchInterceptionDisallowed = true
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                initialTouchX = event.x
                initialTouchY = event.y
                isDragging = false
                touchInterceptionDisallowed = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val totalDx = abs(event.x - initialTouchX)
                    val totalDy = abs(event.y - initialTouchY)

                    if (!isDragging) {
                        if (totalDx > touchSlop || totalDy > touchSlop) {
                            if (totalDx > totalDy) {
                                // Horizontal rotation intent
                                isDragging = true
                                if (!touchInterceptionDisallowed) {
                                    parent?.requestDisallowInterceptTouchEvent(true)
                                    touchInterceptionDisallowed = true
                                }
                            } else {
                                // Vertical scrolling intent: let parent scroll view handle it
                                return false
                            }
                        }
                    }

                    if (isDragging) {
                        val sensitivity = 0.005 / zoom
                        centerLon -= dx * sensitivity
                        centerLat = (centerLat + dy * sensitivity).coerceIn(-PI / 2.2, PI / 2.2)

                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (touchInterceptionDisallowed) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    touchInterceptionDisallowed = false
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (w != cachedWidth || h != cachedHeight || sphereBgPaint.shader == null) {
            updateOceanShader(w, h)
        }

        val cx = w / 2f
        val cy = h / 2f
        val baseRadius = cachedBaseRadius
        val radius = baseRadius * zoom

        // 1. Draw Sphere Background & Oceanic Depth
        canvas.drawCircle(cx, cy, baseRadius, sphereBgPaint)

        // Outer soft cyan glow
        canvas.drawCircle(cx, cy, baseRadius + 2f, atmosphereGlowPaint)
        canvas.drawCircle(cx, cy, baseRadius, atmospherePaint)

        // Clip everything inside sphere
        clipPath.rewind()
        clipPath.addCircle(cx, cy, baseRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // 2. Draw Graticule Lines (Parallels & Meridians)
        drawGraticule(canvas, cx, cy, radius)

        // 3. Draw Landmasses (Filled + Crisp Borders)
        drawLandmasses(canvas, cx, cy, radius)

        // 4. Draw Path Arcs between hops
        drawPathArcs(canvas, cx, cy, radius)

        // 5. Draw Hop Node Pins and Tiered Collision-Free Labels
        drawHopPins(canvas, cx, cy, radius)

        canvas.restore()

        // Draw crisp rim highlight
        canvas.drawCircle(cx, cy, baseRadius, atmospherePaint)
    }

    private fun projectInto(
        latRad: Double,
        lonRad: Double,
        cx: Float,
        cy: Float,
        radius: Float,
        out: ReusableProjectedPoint
    ): Boolean {
        return out.set(latRad, lonRad, cx, cy, radius, centerLat, centerLon)
    }

    private fun drawGraticule(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Parallels (Latitudes: -60, -30, 0, 30, 60)
        for (latDeg in parallelLats) {
            val latRad = Math.toRadians(latDeg)
            graticulePath.rewind()
            var first = true

            val isEquator = latDeg == 0.0
            val paint = if (isEquator) primeMeridianPaint else graticulePaint

            for (lonDeg in -180..180 step 10) {
                val lonRad = Math.toRadians(lonDeg.toDouble())
                projectInto(latRad, lonRad, cx, cy, radius, tempProj)
                if (tempProj.visible) {
                    if (first) {
                        graticulePath.moveTo(tempProj.x, tempProj.y)
                        first = false
                    } else {
                        graticulePath.lineTo(tempProj.x, tempProj.y)
                    }
                } else {
                    first = true
                }
            }
            canvas.drawPath(graticulePath, paint)
        }

        // Meridians (Longitudes every 30 deg)
        for (lonDeg in -180 until 180 step 30) {
            val lonRad = Math.toRadians(lonDeg.toDouble())
            graticulePath.rewind()
            var first = true

            val isPrime = lonDeg == 0
            val paint = if (isPrime) primeMeridianPaint else graticulePaint

            for (latDeg in -80..80 step 5) {
                val latRad = Math.toRadians(latDeg.toDouble())
                projectInto(latRad, lonRad, cx, cy, radius, tempProj)
                if (tempProj.visible) {
                    if (first) {
                        graticulePath.moveTo(tempProj.x, tempProj.y)
                        first = false
                    } else {
                        graticulePath.lineTo(tempProj.x, tempProj.y)
                    }
                } else {
                    first = true
                }
            }
            canvas.drawPath(graticulePath, paint)
        }
    }

    private fun drawLandmasses(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        for (polygonRad in worldLandmassesRad) {
            landmassPath.rewind()
            var anyVisible = false
            var first = true

            for (i in polygonRad.indices step 2) {
                val latRad = polygonRad[i]
                val lonRad = polygonRad[i + 1]
                projectInto(latRad, lonRad, cx, cy, radius, tempProj)

                if (tempProj.visible) {
                    anyVisible = true
                }

                if (first) {
                    landmassPath.moveTo(tempProj.x, tempProj.y)
                    first = false
                } else {
                    landmassPath.lineTo(tempProj.x, tempProj.y)
                }
            }

            if (anyVisible) {
                landmassPath.close()
                canvas.drawPath(landmassPath, landPaint)
                canvas.drawPath(landmassPath, landBorderPaint)
            }
        }
    }

    private fun drawPathArcs(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (points.size < 2) return

        val totalSegments = 24
        frontArcPath.rewind()
        backArcPath.rewind()

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

            var lastWasVisible: Boolean? = null

            for (step in 0..totalSegments) {
                val t = step / totalSegments.toDouble()
                slerpInto(lat1, lon1, lat2, lon2, t, slerpOut)
                projectInto(slerpOut[0], slerpOut[1], cx, cy, radius, tempProj)

                if (tempProj.visible) {
                    if (lastWasVisible != true) {
                        frontArcPath.moveTo(tempProj.x, tempProj.y)
                    } else {
                        frontArcPath.lineTo(tempProj.x, tempProj.y)
                    }
                    lastWasVisible = true
                } else {
                    if (lastWasVisible != false) {
                        backArcPath.moveTo(tempProj.x, tempProj.y)
                    } else {
                        backArcPath.lineTo(tempProj.x, tempProj.y)
                    }
                    lastWasVisible = false
                }
            }

            // Compute current pulse dot along path
            val progressScaled = pulseProgress * (points.size - 1)
            if (progressScaled >= i && progressScaled <= (i + 1)) {
                val pulseT = (progressScaled - i).coerceIn(0f, 1f).toDouble()
                slerpInto(lat1, lon1, lat2, lon2, pulseT, slerpOut)
                projectInto(slerpOut[0], slerpOut[1], cx, cy, radius, tempProj)
                if (tempProj.visible) {
                    globalPulseX = tempProj.x
                    globalPulseY = tempProj.y
                    globalPulseVisible = true
                }
            }
        }

        // Draw back & front paths
        canvas.drawPath(backArcPath, pathBackArcPaint)
        canvas.drawPath(frontArcPath, pathGlowPaint)
        canvas.drawPath(frontArcPath, pathArcPaint)

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

    private fun drawHopPins(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        var visibleCount = 0
        for ((index, pt) in points.withIndex()) {
            val latRad = Math.toRadians(pt.lat)
            val lonRad = Math.toRadians(pt.lon)
            projectInto(latRad, lonRad, cx, cy, radius, tempProj)
            if (!tempProj.visible) continue

            while (visiblePinsPool.size <= visibleCount) {
                visiblePinsPool.add(VisiblePin())
            }
            val pin = visiblePinsPool[visibleCount]
            pin.index = index
            pin.pt = pt
            pin.projX = tempProj.x
            pin.projY = tempProj.y
            pin.labelOffsetY = -36f
            pin.labelOffsetX = 0f
            pin.showStem = false
            visibleCount++
        }

        if (visibleCount == 0) return

        // Multi-Node Cluster Collision Resolution using Disjoint-Set (Union-Find)
        if (visibleCount > 1) {
            val parent = IntArray(visibleCount) { it }
            fun find(i: Int): Int {
                var root = i
                while (root != parent[root]) root = parent[root]
                var curr = i
                while (curr != root) {
                    val next = parent[curr]
                    parent[curr] = root
                    curr = next
                }
                return root
            }

            for (i in 0 until visibleCount) {
                val pinA = visiblePinsPool[i]
                for (j in i + 1 until visibleCount) {
                    val pinB = visiblePinsPool[j]
                    val dist = hypot((pinA.projX - pinB.projX).toDouble(), (pinA.projY - pinB.projY).toDouble()).toFloat()
                    if (dist < 55f) {
                        val rootA = find(i)
                        val rootB = find(j)
                        if (rootA != rootB) {
                            parent[rootB] = rootA
                        }
                    }
                }
            }

            // Group pins into clusters by their root representative
            val clusters = mutableMapOf<Int, MutableList<VisiblePin>>()
            for (i in 0 until visibleCount) {
                val root = find(i)
                clusters.getOrPut(root) { mutableListOf() }.add(visiblePinsPool[i])
            }

            // Assign distinct vertical tiers and horizontal offsets for each pin in a cluster
            val tierOffsets = floatArrayOf(-46f, 26f, -76f, 54f, -104f, 82f)
            for ((_, cluster) in clusters) {
                if (cluster.size == 1) {
                    cluster[0].labelOffsetY = -36f
                    cluster[0].labelOffsetX = 0f
                    cluster[0].showStem = false
                } else {
                    for ((idx, pin) in cluster.withIndex()) {
                        pin.labelOffsetY = tierOffsets[idx % tierOffsets.size]
                        pin.labelOffsetX = if (cluster.size > 2) {
                            when (idx % 3) {
                                1 -> -20f
                                2 -> 20f
                                else -> 0f
                            }
                        } else 0f
                        pin.showStem = true
                    }
                }
            }
        }

        // Draw Pins & Labels
        for (i in 0 until visibleCount) {
            val item = visiblePinsPool[i]
            val pt = item.pt ?: continue
            val index = item.index
            val px = item.projX
            val py = item.projY

            val pinColor = when {
                pt.isSource -> Color.parseColor("#10B981") // Emerald Green
                pt.isDestination -> Color.parseColor("#00E5FF") // Cyan
                else -> Color.parseColor("#6366F1") // Indigo
            }

            pinFillPaint.color = pinColor
            pinHaloPaint.color = pinColor
            pinHaloPaint.alpha = 50

            // Halo pulse
            canvas.drawCircle(px, py, 14f, pinHaloPaint)
            // Pin Dark Outline
            canvas.drawCircle(px, py, 7f, pinDarkBorderPaint)
            // Main Pin Core
            canvas.drawCircle(px, py, 6f, pinFillPaint)
            // Center Dot
            pinFillPaint.color = Color.WHITE
            canvas.drawCircle(px, py, 2.5f, pinFillPaint)

            // Label pill calculation
            val label = pt.label ?: "Hop ${index + 1}"
            val textWidth = labelTextPaint.measureText(label)
            val pillPadding = 10f
            val pillHeight = 32f
            val pillCenterX = px + item.labelOffsetX
            val pillCenterY = py + item.labelOffsetY

            val pillLeft = pillCenterX - (textWidth / 2f) - pillPadding
            val pillRight = pillCenterX + (textWidth / 2f) + pillPadding
            val pillTop = pillCenterY - (pillHeight / 2f)
            val pillBottom = pillCenterY + (pillHeight / 2f)

            // Draw stem line from pin to label if offset
            if (item.showStem) {
                val stemTargetY = if (item.labelOffsetY < 0) pillBottom else pillTop
                canvas.drawLine(px, py, pillCenterX, stemTargetY, labelStemPaint)
            }

            reusableRect.set(pillLeft, pillTop, pillRight, pillBottom)
            // Crisp solid label pill
            canvas.drawRoundRect(reusableRect, 8f, 8f, labelBgPaint)
            canvas.drawRoundRect(reusableRect, 8f, 8f, labelBorderPaint)
            canvas.drawText(label, pillCenterX, pillCenterY + 8f, labelTextPaint)
        }
    }

    // Great circle interpolation (Spherical Linear Interpolation) without heap allocation
    private fun slerpInto(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        t: Double,
        out: DoubleArray
    ) {
        val x1 = cos(lat1) * cos(lon1)
        val y1 = cos(lat1) * sin(lon1)
        val z1 = sin(lat1)

        val x2 = cos(lat2) * cos(lon2)
        val y2 = cos(lat2) * sin(lon2)
        val z2 = sin(lat2)

        val dot = (x1 * x2 + y1 * y2 + z1 * z2).coerceIn(-1.0, 1.0)
        val omega = Math.acos(dot)

        if (abs(omega) < 1e-6) {
            out[0] = lat1
            out[1] = lon1
            return
        }

        val sinOmega = sin(omega)
        val a = sin((1 - t) * omega) / sinOmega
        val b = sin(t * omega) / sinOmega

        val x = a * x1 + b * x2
        val y = a * y1 + b * y2
        val z = a * z1 + b * z2

        out[0] = atan2(z, sqrt(x * x + y * y))
        out[1] = atan2(y, x)
    }
}
