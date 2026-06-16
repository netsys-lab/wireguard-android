# SPDX-License-Identifier: Apache-2.0
#
# Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.

BUILDDIR ?= $(CURDIR)/build
DESTDIR ?= $(CURDIR)/out

NDK_GO_ARCH_MAP_x86 := 386
NDK_GO_ARCH_MAP_x86_64 := amd64
NDK_GO_ARCH_MAP_arm := arm
NDK_GO_ARCH_MAP_arm64 := arm64
NDK_GO_ARCH_MAP_mips := mipsx
NDK_GO_ARCH_MAP_mips64 := mips64x

comma := ,
CLANG_FLAGS := --target=$(TARGET) --sysroot=$(SYSROOT)
export CGO_CFLAGS := $(CLANG_FLAGS) $(subst -mthumb,-marm,$(CFLAGS))
export CGO_LDFLAGS := $(CLANG_FLAGS) $(patsubst -Wl$(comma)--build-id=%,-Wl$(comma)--build-id=none,$(LDFLAGS)) -Wl,-soname=libwg-go.so
export GOARCH := $(NDK_GO_ARCH_MAP_$(ANDROID_ARCH_NAME))
export GOOS := android
export CGO_ENABLED := 1

GO_VERSION := 1.24.3
UNAME_S := $(shell uname -s | tr '[:upper:]' '[:lower:]')
UNAME_M := $(shell uname -m)

# Map MSYS/MINGW to windows
ifeq ($(findstring msys_nt,$(UNAME_S)),msys_nt)
  GO_HOSTOS := windows
else ifeq ($(findstring mingw,$(UNAME_S)),mingw)
  GO_HOSTOS := windows
else
  GO_HOSTOS := $(UNAME_S)
endif

GO_PLATFORM := $(GO_HOSTOS)-$(NDK_GO_ARCH_MAP_$(UNAME_M))

# Use zip on windows, tar.gz elsewhere
ifeq ($(GO_HOSTOS),windows)
  GO_ARCHIVE := go$(GO_VERSION).$(GO_PLATFORM).zip
else
  GO_ARCHIVE := go$(GO_VERSION).$(GO_PLATFORM).tar.gz
endif
GO_HASH_darwin-amd64 := 13e6fe3fcf65689d77d40e633de1e31c6febbdbcb846eb05fc2434ed2213e92b
GO_HASH_darwin-arm64 := 64a3fa22142f627e78fac3018ce3d4aeace68b743eff0afda8aae0411df5e4fb
GO_HASH_linux-amd64 := 3333f6ea53afa971e9078895eaa4ac7204a8c6b5c68c10e6bc9a33e8e391bdd8
GO_HASH_windows-amd64 := BE9787CB08998B1860FE3513E48A5FE5B96302D358A321B58E651184FA9638B3


default: $(DESTDIR)/libwg-go.so

$(GRADLE_USER_HOME)/caches/golang/$(GO_ARCHIVE):
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	curl -o "$@.tmp" "https://dl.google.com/go/$(GO_ARCHIVE)" && \
	echo "$(GO_HASH_$(GO_PLATFORM))  $@.tmp" | sha256sum -c && \
	mv "$@.tmp" "$@"'

$(BUILDDIR)/go-$(GO_VERSION)/.prepared: $(GRADLE_USER_HOME)/caches/golang/$(GO_ARCHIVE)
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c " \
		if [ -f \"$@\" ]; then exit 0; fi; \
		mkdir -p \"$(dir $@)\"; \
		if echo \"$(GO_PLATFORM)\" | grep -q '^windows-'; then \
			unzip -q \"$(GRADLE_USER_HOME)/caches/golang/$(GO_ARCHIVE)\" -d \"$(dir $@)\"; \
			if [ -d \"$(dir $@)/go\" ]; then \
				cp -a \"$(dir $@)/go/.\" \"$(dir $@)\"; \
				rm -rf \"$(dir $@)/go\"; \
			fi; \
		else \
			tar -C \"$(dir $@)\" --strip-components=1 -xzf \"$(GRADLE_USER_HOME)/caches/golang/$(GO_ARCHIVE)\"; \
		fi; \
		patch -p1 -f -N -r- -d \"$(dir $@)\" < goruntime-boottime-over-monotonic.diff || true; \
		touch \"$@\" \
	"

$(DESTDIR)/libwg-go.so: export PATH := $(BUILDDIR)/go-$(GO_VERSION)/bin/:$(PATH)
$(DESTDIR)/libwg-go.so: $(BUILDDIR)/go-$(GO_VERSION)/.prepared go.mod api-android.go jni.c
	"$(BUILDDIR)/go-$(GO_VERSION)/bin/go" build -tags linux -ldflags="-X golang.zx2c4.com/wireguard/ipc.socketDirectory=/data/data/$(ANDROID_PACKAGE_NAME)/cache/wireguard -buildid=" -v -trimpath -buildvcs=false -o "$@" -buildmode c-shared

.DELETE_ON_ERROR:
