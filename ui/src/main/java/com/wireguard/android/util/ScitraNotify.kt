/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.R

/**
 * Modern notification utility for Scitra WireGuard providing customizable,
 * swipeable Bento-card style snackbars matching the app's modern design system.
 */
object ScitraNotify {

    enum class Type {
        SUCCESS,
        ERROR,
        INFO,
        WARNING
    }

    enum class Duration(val length: Int) {
        SHORT(Snackbar.LENGTH_SHORT),
        LONG(Snackbar.LENGTH_LONG),
        INDEFINITE(Snackbar.LENGTH_INDEFINITE)
    }

    fun show(
        view: View,
        message: CharSequence,
        type: Type = Type.INFO,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG,
        actionText: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ): Snackbar {
        val context = view.context
        val snackbar = Snackbar.make(view, "", duration.length)
        val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout

        // Strip default Snackbar chrome so only the unified card is visible
        snackbarLayout.removeAllViews()
        snackbarLayout.setBackgroundColor(Color.TRANSPARENT)
        snackbarLayout.background = null
        snackbarLayout.backgroundTintList = null
        snackbarLayout.elevation = 0f
        snackbarLayout.outlineProvider = null
        snackbarLayout.clipChildren = false
        snackbarLayout.clipToPadding = false
        snackbarLayout.setPadding(0, 0, 0, 0)

        // Inflate notification card view
        val inflater = LayoutInflater.from(context)
        val bentoView = inflater.inflate(R.layout.scitra_notification_bento, snackbarLayout, false)

        val iconContainer = bentoView.findViewById<View>(R.id.notification_icon_container)
        val iconView = bentoView.findViewById<ImageView>(R.id.notification_icon)
        val titleView = bentoView.findViewById<TextView>(R.id.notification_title)
        val messageView = bentoView.findViewById<TextView>(R.id.notification_message)
        val btnAction = bentoView.findViewById<MaterialButton>(R.id.btn_action)
        val btnClose = bentoView.findViewById<ImageButton>(R.id.btn_close)

        // Set message text
        messageView.text = message

        // Determine title
        val resolvedTitle = when {
            title != null -> title
            type == Type.SUCCESS -> context.getString(R.string.notification_title_success)
            type == Type.ERROR -> context.getString(R.string.notification_title_error)
            type == Type.WARNING -> context.getString(R.string.notification_title_warning)
            else -> context.getString(R.string.notification_title_info)
        }

        if (resolvedTitle.isEmpty()) {
            titleView.visibility = View.GONE
        } else {
            titleView.visibility = View.VISIBLE
            titleView.text = resolvedTitle
        }

        // Configure semantic type colors and icons
        val (accentColor, iconRes) = when (type) {
            Type.SUCCESS -> ContextCompat.getColor(context, R.color.scitra_success) to R.drawable.ic_scitra_check_circle
            Type.ERROR -> ContextCompat.getColor(context, R.color.scitra_error) to R.drawable.ic_scitra_error
            Type.WARNING -> ContextCompat.getColor(context, R.color.scitra_warning) to R.drawable.ic_scitra_warning
            Type.INFO -> ContextCompat.getColor(context, R.color.scitra_primary) to R.drawable.ic_scitra_info
        }

        iconContainer.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 36))
        iconView.setImageResource(iconRes)
        iconView.imageTintList = ColorStateList.valueOf(accentColor)

        // Action button
        if (!actionText.isNullOrBlank() && onAction != null) {
            btnAction.visibility = View.VISIBLE
            btnAction.text = actionText
            btnAction.setTextColor(accentColor)
            btnAction.setOnClickListener {
                onAction()
                snackbar.dismiss()
            }
        } else {
            btnAction.visibility = View.GONE
        }

        // Close button
        btnClose.setOnClickListener {
            snackbar.dismiss()
        }

        snackbarLayout.addView(bentoView, 0)
        snackbar.animationMode = Snackbar.ANIMATION_MODE_SLIDE

        // Anchor the floating pill horizontally centered at the bottom of the screen
        when (val lp = snackbarLayout.layoutParams) {
            is CoordinatorLayout.LayoutParams -> {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                snackbarLayout.layoutParams = lp
            }
            is FrameLayout.LayoutParams -> {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                snackbarLayout.layoutParams = lp
            }
        }

        // Ensure swipe-to-dismiss even when outside CoordinatorLayout
        bentoView.post {
            val lp = snackbarLayout.layoutParams
            if (lp !is CoordinatorLayout.LayoutParams) {
                attachFallbackSwipeDismiss(bentoView) {
                    snackbar.dismiss()
                }
            }
        }

        snackbar.show()
        return snackbar
    }

    private fun attachFallbackSwipeDismiss(cardView: View, onDismiss: () -> Unit) {
        var startX = 0f
        var startY = 0f
        var isDragging = false
        val touchSlop = ViewConfiguration.get(cardView.context).scaledTouchSlop

        cardView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        isDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isDragging) {
                        v.translationX = dx
                        val fraction = (Math.abs(dx) / v.width).coerceIn(0f, 1f)
                        v.alpha = 1f - fraction
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        val dx = event.rawX - startX
                        val threshold = v.width * 0.35f
                        if (Math.abs(dx) > threshold) {
                            val targetX = if (dx > 0) v.width.toFloat() * 1.2f else -v.width.toFloat() * 1.2f
                            v.animate()
                                .translationX(targetX)
                                .alpha(0f)
                                .setDuration(200)
                                .withEndAction { onDismiss() }
                                .start()
                        } else {
                            v.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(200)
                                .start()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    fun show(
        view: View,
        @StringRes messageRes: Int,
        type: Type = Type.INFO,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG,
        actionText: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ): Snackbar = show(view, view.context.getString(messageRes), type, title, duration, actionText, onAction)

    fun success(
        view: View,
        message: CharSequence,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG
    ): Snackbar = show(view, message, Type.SUCCESS, title, duration)

    fun success(
        view: View,
        @StringRes messageRes: Int,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG
    ): Snackbar = show(view, view.context.getString(messageRes), Type.SUCCESS, title, duration)

    fun error(
        view: View,
        message: CharSequence,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG
    ): Snackbar = show(view, message, Type.ERROR, title, duration)

    fun error(
        view: View,
        @StringRes messageRes: Int,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG
    ): Snackbar = show(view, view.context.getString(messageRes), Type.ERROR, title, duration)

    fun info(
        view: View,
        message: CharSequence,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG
    ): Snackbar = show(view, message, Type.INFO, title, duration)

    fun info(
        view: View,
        @StringRes messageRes: Int,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG
    ): Snackbar = show(view, view.context.getString(messageRes), Type.INFO, title, duration)

    fun warning(
        view: View,
        message: CharSequence,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG
    ): Snackbar = show(view, message, Type.WARNING, title, duration)

    fun warning(
        view: View,
        @StringRes messageRes: Int,
        title: CharSequence? = null,
        duration: Duration = Duration.LONG
    ): Snackbar = show(view, view.context.getString(messageRes), Type.WARNING, title, duration)

    // ── Context-accepting overloads ──────────────────────────────────────────
    // Used when no View is in scope; resolves the root decor view via the
    // Activity window so the snackbar still anchors correctly.

    private fun resolveView(ctx: Context): View? {
        var c: Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) {
                return c.window?.decorView?.findViewById(android.R.id.content)
            }
            c = c.baseContext
        }
        return if (c is android.app.Activity) {
            c.window?.decorView?.findViewById(android.R.id.content)
        } else {
            null
        }
    }

    fun show(ctx: Context, message: CharSequence, type: Type = Type.INFO, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        resolveView(ctx)?.let { show(it, message, type, title, duration) }

    fun show(ctx: Context, @StringRes messageRes: Int, type: Type = Type.INFO, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        resolveView(ctx)?.let { show(it, ctx.getString(messageRes), type, title, duration) }

    fun info(ctx: Context, message: CharSequence, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        show(ctx, message, Type.INFO, title, duration)

    fun info(ctx: Context, @StringRes messageRes: Int, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        show(ctx, messageRes, Type.INFO, title, duration)

    fun success(ctx: Context, message: CharSequence, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        show(ctx, message, Type.SUCCESS, title, duration)

    fun success(ctx: Context, @StringRes messageRes: Int, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        show(ctx, messageRes, Type.SUCCESS, title, duration)

    fun error(ctx: Context, message: CharSequence, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        show(ctx, message, Type.ERROR, title, duration)

    fun error(ctx: Context, @StringRes messageRes: Int, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        show(ctx, messageRes, Type.ERROR, title, duration)

    fun warning(ctx: Context, message: CharSequence, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        show(ctx, message, Type.WARNING, title, duration)

    fun warning(ctx: Context, @StringRes messageRes: Int, title: CharSequence? = null, duration: Duration = Duration.LONG): Snackbar? =
        show(ctx, messageRes, Type.WARNING, title, duration)
}
