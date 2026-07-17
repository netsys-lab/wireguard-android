# SCION Backend Logging Configuration

SCINtra uses a structured logging system for the SCION backend. Log levels and component filters are compile-time values generated from Gradle properties.

## Configuration Files

**Committed defaults** (tracked in git):

```
gradle/scion-logging.properties
```

**Optional local overrides** (gitignored):

```
gradle/scion-logging.local.properties
```

Priority: local overrides → committed defaults → hardcoded safe fallback (level=info, no components).

## Available Levels

| Level | Meaning |
|-------|---------|
| `trace` | Deep packet/path diagnostics |
| `debug` | Concise packet lifecycle |
| `info` | Initialization and state changes |
| `warn` | Recoverable abnormal conditions |
| `error` | Failures only |

## Available Components

| Component | Subsystem |
|-----------|-----------|
| `path` | PathPool cache lookups and refresh |
| `path-engine` | SCION daemon retriever queries |
| `pending` | Pending SCION packet queue |
| `translate-egress` | SCION header translation |
| `wireguard-egress` | WireGuard encryption and socket write |
| `egress-lifecycle` | End-to-end packet lifecycle events |
| `init` | Device and SCION initialization |

An empty component list means **all components** are enabled.

## Recommended Packet-Debug Configuration

```properties
scionLogLevel=trace
scionLogComponents=path,path-engine,pending,translate-egress,wireguard-egress,egress-lifecycle

scionLogFullTopology=false
scionLogPacketBytes=false
scionLogPathBytes=false
scionLogInternalStructs=false
```

## How to Change the Level

1. Edit `gradle/scion-logging.local.properties`:

```properties
scionLogLevel=debug
scionLogComponents=path,pending,wireguard-egress
```

2. In Android Studio: **File → Sync Project with Gradle Files**
3. Rebuild the debug APK
4. Reinstall or rerun the app
5. Restart the tunnel

BuildConfig values are **compile-time**. Changing the properties file does not affect a running APK.

## Android Studio Steps

1. Edit `gradle/scion-logging.local.properties`
2. **File → Sync Project with Gradle Files**
3. **Build → Select Build Variant** → select `debug`
4. Run or Debug the app
5. Open Logcat
6. Filter for `SCION-LOG-CONFIG`

## Verification

At startup the Go backend emits:

```
[SCION-LOG-CONFIG] source=android-build-config level=trace components=... fullTopology=false ...
```

The Android side also logs before the JNI call:

```
SCION native init configuration:
  level=trace
  components=path,path-engine,pending,translate-egress,wireguard-egress,egress-lifecycle
  fullTopology=false
  packetBytes=false
  pathBytes=false
  internalStructs=false
```

Both logs must agree.

## Build Types

| Build type | Level | Components | Dumps |
|------------|-------|------------|-------|
| debug | From properties file | From properties file | false |
| release | `info` | (empty = all) | false |

Release builds **never** inherit trace diagnostics or raw dumps.
