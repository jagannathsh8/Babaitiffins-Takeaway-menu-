//go:build windows

package main

// Keeps the Console PC awake while the Console runs: Windows must never put it to sleep or
// hibernate it in the middle of service (no KOTs printed, every screen "reconnecting"). The
// display may still turn off. Same as a video player does: SetThreadExecutionState on a thread
// that stays alive for the life of the program.

import (
	"log"
	"runtime"
	"time"

	"golang.org/x/sys/windows"
)

const (
	esContinuous     = 0x80000000
	esSystemRequired = 0x00000001
)

var procSetThreadExecutionState = windows.NewLazySystemDLL("kernel32.dll").NewProc("SetThreadExecutionState")

func keepAwake() {
	runtime.LockOSThread() // the state belongs to this thread: it must never change or end
	if r, _, err := procSetThreadExecutionState.Call(esContinuous | esSystemRequired); r == 0 {
		log.Printf("keep awake: %v", err)
	} else {
		log.Printf("keep awake: this PC won't sleep while the Console runs")
	}
	for {
		time.Sleep(time.Hour)
		procSetThreadExecutionState.Call(esContinuous | esSystemRequired) // renewed, in case
	}
}
