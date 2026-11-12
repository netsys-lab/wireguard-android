package com.wireguard.android.util

import android.content.Context

fun createFlowPathRepositoryProvider(context: Context): FlowPathRepositoryProvider {
    return DebugFlowPathRepositoryProvider(context)
}
