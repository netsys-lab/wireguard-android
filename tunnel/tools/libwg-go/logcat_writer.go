// SPDX-License-Identifier: Apache-2.0
//
// This file contains the pure-Go logcat writer logic, separated from cgo
// so that it can be unit-tested on any platform.  api-android.go wires
// the cgo bridge (defaultLogFunc) and exposes the same types.

package main

import (
	"bytes"
	"fmt"
	"sync"
)

// Buffer and chunk limits for the logcat writer.
const (
	// maxPendingLineBytes is the maximum number of bytes buffered for
	// incomplete lines before force-flushing oldest data.
	maxPendingLineBytes = 16 * 1024

	// maxLogcatChunkBytes is the maximum payload per __android_log_write
	// call.  Android truncates records above ~4 KB; 3 KB leaves headroom
	// for the continuation-marker prefix "[1/99] ".
	maxLogcatChunkBytes = 3 * 1024
)

// logFunc is the function used to emit a single null-terminated record to
// logcat.  It is a package-level variable so that tests can substitute a
// mock without touching cgo.
var logFunc func(msg string)

// androidLogWriter implements io.Writer.  It buffers partial lines until a
// newline is received, then emits complete lines to __android_log_write.
// The writer is process-global and must not be closed when an individual
// tunnel stops.
type androidLogWriter struct {
	mu      sync.Mutex
	partial []byte
}

func (w *androidLogWriter) Write(p []byte) (n int, err error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	w.partial = append(w.partial, p...)

	// Bound the partial buffer.  If data accumulates beyond
	// maxPendingLineBytes without a newline, flush oldest data in
	// bounded chunks so memory cannot grow without limit.
	for len(w.partial) > maxPendingLineBytes {
		end := maxLogcatChunkBytes
		if end > len(w.partial) {
			end = len(w.partial)
		}
		idx := bytes.IndexByte(w.partial[:end], '\n')
		if idx >= 0 {
			line := w.partial[:idx]
			w.partial = w.partial[idx+1:]
			if len(line) > 0 {
				emitLine(line)
			}
		} else {
			emitChunk(w.partial[:end])
			w.partial = w.partial[end:]
		}
	}

	// Process complete lines.
	for {
		idx := bytes.IndexByte(w.partial, '\n')
		if idx < 0 {
			break
		}
		line := w.partial[:idx]
		w.partial = w.partial[idx+1:]
		if len(line) == 0 {
			continue
		}
		emitLine(line)
	}

	return len(p), nil
}

// Flush emits any partial line remaining in the buffer.  It is safe to
// call from any goroutine.  The writer is process-global; call Flush
// only at process shutdown, never per-tunnel.
func (w *androidLogWriter) Flush() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(w.partial) > 0 {
		emitChunk(w.partial)
		w.partial = w.partial[:0]
	}
}

// emitLine sends a complete line to logcat.  Lines that fit in a single
// record are sent as-is.  Oversized lines are split into bounded chunks
// with continuation markers so they do not silently interleave.
func emitLine(line []byte) {
	if len(line) <= maxLogcatChunkBytes {
		logFunc(string(line))
		return
	}
	total := (len(line) + maxLogcatChunkBytes - 1) / maxLogcatChunkBytes
	for i := 0; len(line) > 0; i++ {
		sz := maxLogcatChunkBytes
		if sz > len(line) {
			sz = len(line)
		}
		logFunc(fmt.Sprintf("[%d/%d] %s", i+1, total, string(line[:sz])))
		line = line[sz:]
	}
}

// emitChunk sends raw bytes to logcat in bounded chunks without
// continuation markers.  Used for buffer-overflow flushing and Flush.
func emitChunk(data []byte) {
	for len(data) > 0 {
		sz := maxLogcatChunkBytes
		if sz > len(data) {
			sz = len(data)
		}
		logFunc(string(data[:sz]))
		data = data[sz:]
	}
}
