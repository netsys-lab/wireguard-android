package com.wireguard.android.model

import androidx.annotation.StringRes
import com.wireguard.android.R

enum class PathBadge(@StringRes val labelResId: Int) {
    CURRENT(R.string.badge_current),
    LOWEST_LATENCY(R.string.badge_lowest_latency),
    HIGHEST_BANDWIDTH(R.string.badge_highest_bandwidth),
    SHORTEST_PATH(R.string.badge_shortest_path),
    OVERRIDE(R.string.badge_override),
    INTRA_AS(R.string.badge_intra_as),
}