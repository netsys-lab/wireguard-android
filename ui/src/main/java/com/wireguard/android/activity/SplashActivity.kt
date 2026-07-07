/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import com.wireguard.android.R

/**
 * Branded splash screen with a staggered fade-in animation.
 * Shows the Scitra logo, app name, tagline, and loading indicator,
 * then transitions to MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_activity)

        val logo = findViewById<View>(R.id.splash_logo)
        val glow = findViewById<View>(R.id.glow_ring)
        val title = findViewById<View>(R.id.splash_title)
        val subtitle = findViewById<View>(R.id.splash_subtitle)
        val progress = findViewById<View>(R.id.splash_progress)
        val footer = findViewById<View>(R.id.splash_footer)

        // Stagger timings
        val logoDelay = 200L
        val titleDelay = 500L
        val subtitleDelay = 700L
        val progressDelay = 900L
        val footerDelay = 800L
        val transitionDelay = 1800L

        // Logo: scale up from 0.6 + fade in
        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        val logoFade = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f).apply { duration = 500 }
        val logoScaleX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.6f, 1f).apply {
            duration = 600
            interpolator = OvershootInterpolator(1.5f)
        }
        val logoScaleY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.6f, 1f).apply {
            duration = 600
            interpolator = OvershootInterpolator(1.5f)
        }

        // Glow: subtle fade in
        val glowFade = ObjectAnimator.ofFloat(glow, View.ALPHA, 0f, 0.6f).apply { duration = 800 }

        // Title: fade in + slide up
        title.translationY = 20f
        val titleFade = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f).apply { duration = 400 }
        val titleSlide = ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, 20f, 0f).apply {
            duration = 400
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Subtitle: fade in + slide up
        subtitle.translationY = 15f
        val subtitleFade = ObjectAnimator.ofFloat(subtitle, View.ALPHA, 0f, 1f).apply { duration = 400 }
        val subtitleSlide = ObjectAnimator.ofFloat(subtitle, View.TRANSLATION_Y, 15f, 0f).apply {
            duration = 400
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Progress: fade in
        val progressFade = ObjectAnimator.ofFloat(progress, View.ALPHA, 0f, 0.7f).apply { duration = 300 }

        // Footer: fade in
        val footerFade = ObjectAnimator.ofFloat(footer, View.ALPHA, 0f, 0.5f).apply { duration = 500 }

        // Build the animation set
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(
            // Logo group (starts at logoDelay)
            logoFade.apply { startDelay = logoDelay },
            logoScaleX.apply { startDelay = logoDelay },
            logoScaleY.apply { startDelay = logoDelay },
            glowFade.apply { startDelay = logoDelay + 200 },
            // Title (starts at titleDelay)
            titleFade.apply { startDelay = titleDelay },
            titleSlide.apply { startDelay = titleDelay },
            // Subtitle (starts at subtitleDelay)
            subtitleFade.apply { startDelay = subtitleDelay },
            subtitleSlide.apply { startDelay = subtitleDelay },
            // Progress (starts at progressDelay)
            progressFade.apply { startDelay = progressDelay },
            // Footer (starts at footerDelay)
            footerFade.apply { startDelay = footerDelay }
        )
        animatorSet.start()

        // Transition to MainActivity after the animation completes
        logo.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, transitionDelay)
    }
}
