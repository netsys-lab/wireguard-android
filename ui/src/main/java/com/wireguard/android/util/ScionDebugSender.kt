/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import android.net.TrafficStats
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException

object ScionDebugSender {
    private const val TAG = "WireGuard/ScionDebugSender"
    private const val TARGET_HOST = "fc04:800:900::ffff:8184:af68"
    private const val TARGET_PORT = 30041
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val WRITE_TIMEOUT_MS = 10_000
    private const val DEBUG_PACKET_TRAFFIC_TAG = 0x00FF

    sealed class SendResult {
        data class Success(val message: String) : SendResult()
        data class Failure(val message: String, val exception: Throwable?) : SendResult()
    }

    suspend fun sendUdp(payload: String): SendResult = withContext(Dispatchers.IO) {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "UDP send started: dst=$TARGET_HOST:$TARGET_PORT chars=${payload.length} bytes=${payloadBytes.size}")
        TrafficStats.setThreadStatsTag(DEBUG_PACKET_TRAFFIC_TAG)
        try {
            DatagramSocket().use { socket ->
                Log.d(TAG, "UDP socket created")
                val address = InetSocketAddress(TARGET_HOST, TARGET_PORT)
                val packet = DatagramPacket(payloadBytes, payloadBytes.size, address)
                socket.send(packet)
                Log.d(TAG, "UDP datagram sent successfully")
                SendResult.Success("Sent")
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "UDP send failed: UnknownHost", e)
            SendResult.Failure("Could not send the packet. Make sure the SCION tunnel is ready.", e)
        } catch (e: SocketException) {
            Log.e(TAG, "UDP send failed: SocketException", e)
            SendResult.Failure("Could not send the packet. Make sure the SCION tunnel is ready.", e)
        } catch (e: IOException) {
            Log.e(TAG, "UDP send failed: IOException", e)
            SendResult.Failure("Could not send the packet. Make sure the SCION tunnel is ready.", e)
        } catch (e: Exception) {
            Log.e(TAG, "UDP send failed: unexpected error", e)
            SendResult.Failure("Could not send the packet. Make sure the SCION tunnel is ready.", e)
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
    }

    suspend fun sendTcp(payload: String): SendResult = withContext(Dispatchers.IO) {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "TCP send started: dst=$TARGET_HOST:$TARGET_PORT chars=${payload.length} bytes=${payloadBytes.size}")
        TrafficStats.setThreadStatsTag(DEBUG_PACKET_TRAFFIC_TAG)
        try {
            Socket().use { socket ->
                Log.d(TAG, "TCP socket created, connecting...")
                socket.connect(InetSocketAddress(TARGET_HOST, TARGET_PORT), CONNECT_TIMEOUT_MS)
                Log.d(TAG, "TCP connected, sending payload")
                socket.soTimeout = WRITE_TIMEOUT_MS
                socket.getOutputStream().use { out ->
                    out.write(payloadBytes)
                    out.flush()
                }
                Log.d(TAG, "TCP payload sent successfully")
                SendResult.Success("Sent")
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "TCP send failed: UnknownHost", e)
            SendResult.Failure("Could not send the packet. Make sure the SCION tunnel is ready.", e)
        } catch (e: SocketException) {
            Log.e(TAG, "TCP send failed: SocketException", e)
            SendResult.Failure("Could not send the packet. Make sure the SCION tunnel is ready.", e)
        } catch (e: IOException) {
            Log.e(TAG, "TCP send failed: IOException", e)
            SendResult.Failure("Could not send the packet. Make sure the SCION tunnel is ready.", e)
        } catch (e: Exception) {
            Log.e(TAG, "TCP send failed: unexpected error", e)
            SendResult.Failure("Could not send the packet. Make sure the SCION tunnel is ready.", e)
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
    }
}
