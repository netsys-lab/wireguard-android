/*
 * Copyright © 2026 SCIONtra / WireGuard Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.os.Build;
import android.system.OsConstants;

import com.wireguard.util.NonNullForAll;

import java.net.InetSocketAddress;

import androidx.annotation.RequiresApi;

/**
 * Looks up the owning app of a socket with
 * {@link ConnectivityManager#getConnectionOwnerUid(int, InetSocketAddress, InetSocketAddress)}.
 * Only the active VPN app may call it, and only for TCP and UDP sockets that still exist.
 */
@NonNullForAll
@RequiresApi(Build.VERSION_CODES.Q)
final class ConnectivityOwnerLookup implements FlowOwnerTracker.OwnerLookup {
    private final ConnectivityManager connectivityManager;

    ConnectivityOwnerLookup(final Context context) {
        connectivityManager = context.getSystemService(ConnectivityManager.class);
    }

    @Override
    public int lookup(final int protocol, final String localIp, final int localPort,
                      final String remoteIp, final int remotePort) {
        final int ipProtocol;
        if (protocol == 6)
            ipProtocol = OsConstants.IPPROTO_TCP;
        else if (protocol == 17)
            ipProtocol = OsConstants.IPPROTO_UDP;
        else
            throw new IllegalArgumentException("unsupported protocol " + protocol);
        // parseNumericAddress never performs a DNS lookup.
        final InetSocketAddress local = new InetSocketAddress(InetAddresses.parseNumericAddress(localIp), localPort);
        final InetSocketAddress remote = new InetSocketAddress(InetAddresses.parseNumericAddress(remoteIp), remotePort);
        // Returns Process.INVALID_UID (-1) if no matching socket exists.
        return connectivityManager.getConnectionOwnerUid(ipProtocol, local, remote);
    }
}
