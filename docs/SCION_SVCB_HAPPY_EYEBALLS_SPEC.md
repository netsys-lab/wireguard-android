# SCION SVCB DNS Resolution & Happy Eyeballs Specification

## Overview
This document specifies the technical design, requirements, and task breakdown for integrating **SVCB (Type 64)** / **HTTPS (Type 65)** DNS record resolution, **Happy Eyeballs (RFC 8305)** multi-path racing, and **DNS A-Record Interception** into the Android SCION/WireGuard ecosystem (`wireguard-android` + `wireguard-go`).

The objective is to enable standard web browsers (e.g., Chrome, Firefox) and native Android applications to transparently connect to SCION endpoints without modifying client application binaries or the core SCION translator.

---

## 1. Advisor Meeting Notes & Goals

### Meeting Notes Summary
* **Service Binding Records (SVCB/HTTPS)** → **Happy Eyeballs Protocol** → **Alternative HTTP/3 over QUIC, IPv4 alternative** with different record priorities.
* **Racer Component:** Sorts endpoint candidate information based on record priority (SCION preferred) and attempts parallel connections across all candidates.
* **SCION Preference:** High priority given to mapped SCION addresses.
* **DNS Interception:** Intercept A-record queries ("cord A record abfangen"), inject mapped SCION addresses into the local resolver/hosts structure.
* **Zero-Modification Constraint:** Do NOT modify the client app (browser) or SCION translator binaries (`app und translator nicht ändern`).
* **Testing:** Add SVCB records on the authoritative DNS server (provided by advisor) and test via standard mobile browser.

---

## 2. System Architecture

```
[ Web Browser / App ]
        |
    1. Standard DNS Query (A / AAAA)
        v
[ Local DNS Interceptor / VPN Tunnel ]  <--->  Queries SVCB (Type 64/65) from DNS Server
        |
    2. Resolves SVCB record (SCION Prio 1, QUIC Prio 2, IPv4 Prio 3)
    3. Injects Mapped SCION IP into A-Record Response
        v
[ Web Browser / App ]  --->  Connects to Mapped SCION IP
        |
        v
[ Go Backend (libwg-go) / SCION Translator ]  --->  Routes packet over SCION Network
```

---

## 3. Implementation Task Roadmap

### Task 1: Authoritative DNS & SVCB Record Setup
* DNS server (managed by supervisor) configured with SVCB (Type 64) and HTTPS (Type 65) records.
* Record priorities:
  * **Priority 1:** SCION endpoint / SCION target hint.
  * **Priority 2:** HTTP/3 over QUIC (`alpn="h3"`).
  * **Priority 3:** Standard IPv4 (`alpn="h2,http/1.1"`).

### Task 2: Custom DNS & SVCB Resolver in Backend (`wireguard-go`)
* Implement explicit DNS resolver in `wireguard-go` to query SVCB (Type 64) / HTTPS (Type 65) records alongside A/AAAA queries.
* Parse SVCB fields:
  * Priority (`priority`)
  * Target domain/IP (`target`)
  * Protocol ALPN hints (`alpn`)
  * SCION-specific parameters

### Task 3: SCION Mapped IP Interception & Hosts Resolution
* Intercept A-record queries from client apps entering the VPN TUN interface.
* Map SCION endpoints to virtual IPv4 range (e.g. `100.64.0.0/16` or `10.0.0.0/8`).
* Return mapped SCION IPv4 in DNS A-record response so standard socket calls route to the SCION translator.

### Task 4: Happy Eyeballs "Racer" Engine (RFC 8305)
* Implement connection racing across candidate endpoints.
* Order candidates: `SCION Mapped IP (Prio 1)` > `HTTP/3 QUIC (Prio 2)` > `IPv4 Standard (Prio 3)`.
* Race connection attempts with staggered delays (Connection Attempt Delay = 250ms).

### Task 5: Verification & Browser Testing
* Test end-to-end domain resolution and connection setup using standard mobile browser over active VPN tunnel.
* Verify SCION routing precedence and graceful fallback to HTTP/3 / IPv4.

---

## 4. Local Development Setup

To work seamlessly across `wireguard-android` and `wireguard-go`:
1. Clone `wireguard-go` locally at `C:\Users\fidel\StudioProjects\wireguard-go`.
2. Update `tunnel/tools/libwg-go/go.mod`:
   ```go
   replace golang.zx2c4.com/wireguard => ../../../wireguard-go
   ```
3. Local edits in `wireguard-go` will compile directly into `libwg-go.so` during `./gradlew :tunnel:assembleDebug`.
