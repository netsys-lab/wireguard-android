package com.wireguard.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowModelsTest {

    @Test
    fun `empty response parses to empty list`() {
        val json = """{"flows":[]}"""
        val response = parseFlowListResponse(json)
        assertTrue(response.flows.isEmpty())
        assertNull(response.error)
    }

    @Test
    fun `TCP IPv4 flow parses all fields`() {
        val json = """{
            "flows": [{
                "id": 1,
                "ipVersion": 4,
                "protocol": 6,
                "endpointA": {"address": "10.0.2.16", "port": 50568},
                "endpointB": {"address": "141.44.25.151", "port": 8041},
                "status": "active",
                "egressKind": "ip",
                "txPackets": 9,
                "txBytes": 1331,
                "rxPackets": 12,
                "rxBytes": 2048,
                "createdAt": "2026-07-21T10:00:00.123456789Z",
                "lastSeen": "2026-07-21T10:05:00.987654321Z"
            }]
        }"""
        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        val flow = response.flows[0]
        assertEquals(1L, flow.id)
        assertEquals(4, flow.ipVersion)
        assertEquals(6, flow.protocol)
        assertEquals("10.0.2.16", flow.endpointA.address)
        assertEquals(50568, flow.endpointA.port)
        assertEquals("141.44.25.151", flow.endpointB.address)
        assertEquals(8041, flow.endpointB.port)
        assertEquals("active", flow.status)
        assertEquals(9L, flow.txPackets)
        assertEquals(1331L, flow.txBytes)
        assertEquals(12L, flow.rxPackets)
        assertEquals(2048L, flow.rxBytes)
        assertEquals("ip", flow.egressKind)
        assertEquals(FlowEgressKind.IP, flow.egressKindEnum)
        assertEquals("2026-07-21T10:00:00.123456789Z", flow.createdAt)
        assertEquals("2026-07-21T10:05:00.987654321Z", flow.lastSeen)
        assertNull(response.error)
    }

    @Test
    fun `UDP IPv6 flow parses with brackets in endpoints`() {
        val json = """{
            "flows": [{
                "id": 2,
                "ipVersion": 6,
                "protocol": 17,
                "endpointA": {"address": "fd42:42:42::70", "port": 49152},
                "endpointB": {"address": "fc04:7800:4a00::ffff:a2c:1947", "port": 443},
                "status": "active",
                "egressKind": "scion",
                "txPackets": 0,
                "txBytes": 0,
                "rxPackets": 0,
                "rxBytes": 0
            }]
        }"""
        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        val flow = response.flows[0]
        assertEquals(2L, flow.id)
        assertEquals(6, flow.ipVersion)
        assertEquals(17, flow.protocol)
        assertEquals("fd42:42:42::70", flow.endpointA.address)
        assertEquals(49152, flow.endpointA.port)
        assertEquals("fc04:7800:4a00::ffff:a2c:1947", flow.endpointB.address)
        assertEquals(443, flow.endpointB.port)
        assertEquals("scion", flow.egressKind)
        assertEquals(FlowEgressKind.SCION, flow.egressKindEnum)
        assertNull(response.error)
    }

    @Test
    fun `error response parses error field`() {
        val json = """{"flows":[],"error":"device_not_found"}"""
        val response = parseFlowListResponse(json)
        assertTrue(response.flows.isEmpty())
        assertEquals("device_not_found", response.error)
    }

    @Test
    fun `egressKind defaults to unknown when missing`() {
        val json = """{"flows":[{"id":1,"ipVersion":4,"protocol":6,"endpointA":{"address":"1.1.1.1","port":80},"endpointB":{"address":"2.2.2.2","port":443},"status":"active","txPackets":0,"txBytes":0,"rxPackets":0,"rxBytes":0}]}"""
        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        assertEquals("unknown", response.flows[0].egressKind)
        assertEquals(FlowEgressKind.UNKNOWN, response.flows[0].egressKindEnum)
    }

    @Test
    fun `egressKind parses scion when present`() {
        val json = """{"flows":[{"id":1,"ipVersion":4,"protocol":6,"endpointA":{"address":"1.1.1.1","port":80},"endpointB":{"address":"2.2.2.2","port":443},"status":"active","txPackets":0,"txBytes":0,"rxPackets":0,"rxBytes":0,"egressKind":"scion"}]}"""
        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        assertEquals("scion", response.flows[0].egressKind)
        assertEquals(FlowEgressKind.SCION, response.flows[0].egressKindEnum)
    }

    @Test
    fun `egressKind parses ip when present`() {
        val json = """{"flows":[{"id":1,"ipVersion":4,"protocol":6,"endpointA":{"address":"1.1.1.1","port":80},"endpointB":{"address":"2.2.2.2","port":443},"status":"active","txPackets":0,"txBytes":0,"rxPackets":0,"rxBytes":0,"egressKind":"ip"}]}"""
        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        assertEquals("ip", response.flows[0].egressKind)
        assertEquals(FlowEgressKind.IP, response.flows[0].egressKindEnum)
    }

    @Test
    fun `invalid egressKind value defaults to unknown`() {
        val json = """{"flows":[{"id":1,"ipVersion":4,"protocol":6,"endpointA":{"address":"1.1.1.1","port":80},"endpointB":{"address":"2.2.2.2","port":443},"status":"active","txPackets":0,"txBytes":0,"rxPackets":0,"rxBytes":0,"egressKind":"bogus"}]}"""
        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        assertEquals("bogus", response.flows[0].egressKind)
        assertEquals(FlowEgressKind.UNKNOWN, response.flows[0].egressKindEnum)
    }

    @Test
    fun `legacy isScion true migrates to scion`() {
        val json = """{"flows":[{"id":1,"ipVersion":4,"protocol":6,"endpointA":{"address":"1.1.1.1","port":80},"endpointB":{"address":"2.2.2.2","port":443},"status":"active","txPackets":0,"txBytes":0,"rxPackets":0,"rxBytes":0,"isScion":true}]}"""
        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        assertEquals("scion", response.flows[0].egressKind)
        assertEquals(FlowEgressKind.SCION, response.flows[0].egressKindEnum)
    }

    @Test
    fun `egressKind takes precedence over legacy isScion`() {
        val json = """{"flows":[{"id":1,"ipVersion":4,"protocol":6,"endpointA":{"address":"1.1.1.1","port":80},"endpointB":{"address":"2.2.2.2","port":443},"status":"active","txPackets":0,"txBytes":0,"rxPackets":0,"rxBytes":0,"egressKind":"ip","isScion":true}]}"""
        val response = parseFlowListResponse(json)
        assertEquals(1, response.flows.size)
        // egressKind takes precedence
        assertEquals("ip", response.flows[0].egressKind)
        assertEquals(FlowEgressKind.IP, response.flows[0].egressKindEnum)
    }

    @Test
    fun `null flows becomes empty list`() {
        val json = """{}"""
        val response = parseFlowListResponse(json)
        assertNotNull(response.flows)
        assertTrue(response.flows.isEmpty())
    }

    @Test
    fun `IPv4 endpoint formatting`() {
        val ep = FlowEndpointDto(address = "10.0.2.16", port = 50568)
        val formatted = if (ep.address.contains(":")) "[${ep.address}]:${ep.port}" else "${ep.address}:${ep.port}"
        assertEquals("10.0.2.16:50568", formatted)
    }

    @Test
    fun `IPv6 endpoint formatting uses brackets`() {
        val ep = FlowEndpointDto(address = "fd42:42:42::70", port = 49152)
        val formatted = if (ep.address.contains(":")) "[${ep.address}]:${ep.port}" else "${ep.address}:${ep.port}"
        assertEquals("[fd42:42:42::70]:49152", formatted)
    }

    @Test
    fun `String toFlowEgressKind maps correctly`() {
        assertEquals(FlowEgressKind.IP, "ip".toFlowEgressKind())
        assertEquals(FlowEgressKind.IP, "IP".toFlowEgressKind())
        assertEquals(FlowEgressKind.SCION, "scion".toFlowEgressKind())
        assertEquals(FlowEgressKind.SCION, "SCION".toFlowEgressKind())
        assertEquals(FlowEgressKind.UNKNOWN, "".toFlowEgressKind())
        assertEquals(FlowEgressKind.UNKNOWN, "unknown".toFlowEgressKind())
        assertEquals(FlowEgressKind.UNKNOWN, "bogus".toFlowEgressKind())
    }
}
