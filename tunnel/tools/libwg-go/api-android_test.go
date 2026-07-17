// SPDX-License-Identifier: Apache-2.0

package main

import (
	"strings"
	"sync"
	"testing"
)

// mockLogFunc returns a mock logFunc and a function to retrieve recorded
// messages.  It installs the mock as the package-level logFunc and returns
// a restore function to put the original back.
func mockLogFunc() (messages *[]string, restore func()) {
	orig := logFunc
	msgs := &[]string{}
	logFunc = func(msg string) {
		*msgs = append(*msgs, msg)
	}
	return msgs, func() { logFunc = orig }
}

func TestWriter_CompleteLine(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	w.Write([]byte("hello\n"))

	if len(*msgs) != 1 {
		t.Fatalf("expected 1 message, got %d", len(*msgs))
	}
	if (*msgs)[0] != "hello" {
		t.Errorf("expected %q, got %q", "hello", (*msgs)[0])
	}
}

func TestWriter_SplitAcrossTwoWrites(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	w.Write([]byte("hel"))
	w.Write([]byte("lo\n"))

	if len(*msgs) != 1 {
		t.Fatalf("expected 1 message, got %d", len(*msgs))
	}
	if (*msgs)[0] != "hello" {
		t.Errorf("expected %q, got %q", "hello", (*msgs)[0])
	}
}

func TestWriter_MultipleLinesInOneWrite(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	w.Write([]byte("a\nb\nc\n"))

	if len(*msgs) != 3 {
		t.Fatalf("expected 3 messages, got %d: %v", len(*msgs), *msgs)
	}
	expected := []string{"a", "b", "c"}
	for i, exp := range expected {
		if (*msgs)[i] != exp {
			t.Errorf("message %d: expected %q, got %q", i, exp, (*msgs)[i])
		}
	}
}

func TestWriter_FinalPartialLineBuffered(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	w.Write([]byte("hello"))

	if len(*msgs) != 0 {
		t.Fatalf("expected 0 messages (partial buffered), got %d", len(*msgs))
	}
	if string(w.partial) != "hello" {
		t.Errorf("expected partial %q, got %q", "hello", string(w.partial))
	}
}

func TestWriter_FlushEmitsPartial(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	w.Write([]byte("hello"))
	w.Flush()

	if len(*msgs) != 1 {
		t.Fatalf("expected 1 message after Flush, got %d", len(*msgs))
	}
	if (*msgs)[0] != "hello" {
		t.Errorf("expected %q, got %q", "hello", (*msgs)[0])
	}
	if len(w.partial) != 0 {
		t.Errorf("expected empty partial after Flush, got %q", string(w.partial))
	}
}

func TestWriter_ConcurrentWrites(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	const goroutines = 50
	const linesPerGoroutine = 10

	var wg sync.WaitGroup
	wg.Add(goroutines)
	for g := 0; g < goroutines; g++ {
		go func(id int) {
			defer wg.Done()
			for i := 0; i < linesPerGoroutine; i++ {
				line := strings.Repeat("x", 10)
				w.Write([]byte(line + "\n"))
			}
		}(g)
	}
	wg.Wait()

	total := goroutines * linesPerGoroutine
	if len(*msgs) != total {
		t.Fatalf("expected %d messages, got %d", total, len(*msgs))
	}
	for i, msg := range *msgs {
		if msg != strings.Repeat("x", 10) {
			t.Errorf("message %d: expected 10 x's, got %q (possible interleaving)", i, msg)
			break
		}
	}
}

func TestWriter_OversizedLineSplit(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	bigLine := strings.Repeat("A", maxLogcatChunkBytes+500) + "\n"
	w.Write([]byte(bigLine))

	// Should be split into 2 chunks: maxLogcatChunkBytes + 500
	// Chunk 1: maxLogcatChunkBytes, Chunk 2: 500
	if len(*msgs) != 2 {
		t.Fatalf("expected 2 messages for oversized line, got %d", len(*msgs))
	}

	// Both should have continuation markers.
	for i, msg := range *msgs {
		if !strings.HasPrefix(msg, "[") {
			t.Errorf("message %d missing continuation marker: %q", i, msg)
		}
	}

	// Verify marker values.
	if (*msgs)[0] != "[1/2] "+strings.Repeat("A", maxLogcatChunkBytes) {
		t.Errorf("chunk 1 mismatch")
	}
	if (*msgs)[1] != "[2/2] "+strings.Repeat("A", 500) {
		t.Errorf("chunk 2 mismatch")
	}
}

func TestWriter_BufferOverflowFlush(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	// Write more than maxPendingLineBytes without a newline.
	overflow := strings.Repeat("B", maxPendingLineBytes+1000)
	w.Write([]byte(overflow))

	// The writer should have flushed some data (cannot grow without bound).
	if len(*msgs) == 0 {
		t.Fatal("expected some messages from buffer overflow flush")
	}

	// Verify total bytes emitted cover the overflow.
	totalEmitted := 0
	for _, msg := range *msgs {
		totalEmitted += len(msg)
	}
	if totalEmitted < maxPendingLineBytes {
		t.Errorf("expected at least %d bytes emitted, got %d", maxPendingLineBytes, totalEmitted)
	}
}

func TestWriter_EmptyWrite(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	w.Write([]byte{})

	if len(*msgs) != 0 {
		t.Fatalf("expected 0 messages for empty write, got %d", len(*msgs))
	}
	if len(w.partial) != 0 {
		t.Errorf("expected empty partial after empty write, got %q", string(w.partial))
	}
}

func TestWriter_NullTerminationViaCString(t *testing.T) {
	// Verify that logFunc receives a string that would survive
	// C.CString (no embedded NUL bytes).  The actual NUL termination
	// happens inside logFunc/defaultLogFunc via C.CString.
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	w.Write([]byte("no\x00nulls\n"))

	if len(*msgs) != 1 {
		t.Fatalf("expected 1 message, got %d", len(*msgs))
	}
	if (*msgs)[0] != "no\x00nulls" {
		t.Errorf("expected %q, got %q", "no\x00nulls", (*msgs)[0])
	}
}

func TestWriter_MultipleSplitsInOneFlush(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	// Write 3 full chunk-sized lines plus a partial, all in one Write.
	chunk := strings.Repeat("C", maxLogcatChunkBytes)
	w.Write([]byte(chunk + "\n" + chunk + "\n" + chunk + "\npart"))

	if len(*msgs) != 3 {
		t.Fatalf("expected 3 complete messages, got %d", len(*msgs))
	}
	for i, msg := range *msgs {
		if len(msg) != maxLogcatChunkBytes {
			t.Errorf("message %d: expected %d bytes, got %d", i, maxLogcatChunkBytes, len(msg))
		}
	}
	// "part" should remain in the buffer.
	if string(w.partial) != "part" {
		t.Errorf("expected partial %q, got %q", "part", string(w.partial))
	}
}

func TestFlush_PackageLevel(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	logWriter.Write([]byte("pkg-level"))
	Flush()

	if len(*msgs) != 1 {
		t.Fatalf("expected 1 message from package Flush, got %d", len(*msgs))
	}
	if (*msgs)[0] != "pkg-level" {
		t.Errorf("expected %q, got %q", "pkg-level", (*msgs)[0])
	}
}

func TestWriter_ChunkLimitRespected(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	// Exact maxLogcatChunkBytes line should NOT be split.
	line := strings.Repeat("D", maxLogcatChunkBytes) + "\n"
	w.Write([]byte(line))

	if len(*msgs) != 1 {
		t.Fatalf("expected 1 message for exact-chunk-size line, got %d", len(*msgs))
	}
	if (*msgs)[0] != strings.Repeat("D", maxLogcatChunkBytes) {
		t.Error("message content mismatch for exact-chunk-size line")
	}
}

func TestWriter_ContinuationMarkerThreeChunks(t *testing.T) {
	msgs, restore := mockLogFunc()
	defer restore()

	w := &androidLogWriter{}
	line := strings.Repeat("E", maxLogcatChunkBytes*2+100) + "\n"
	w.Write([]byte(line))

	if len(*msgs) != 3 {
		t.Fatalf("expected 3 messages, got %d", len(*msgs))
	}
	if !strings.HasPrefix((*msgs)[0], "[1/3]") {
		t.Errorf("chunk 1 marker: %q", (*msgs)[0][:6])
	}
	if !strings.HasPrefix((*msgs)[1], "[2/3]") {
		t.Errorf("chunk 2 marker: %q", (*msgs)[1][:6])
	}
	if !strings.HasPrefix((*msgs)[2], "[3/3]") {
		t.Errorf("chunk 3 marker: %q", (*msgs)[2][:6])
	}
}
