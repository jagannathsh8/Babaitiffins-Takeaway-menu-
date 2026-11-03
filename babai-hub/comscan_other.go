//go:build !windows

package main

import "errors"

type comPort struct {
	Name string `json:"name"`
	Dev  string `json:"dev"`
}

func comPorts() []comPort { return []comPort{} }

func readComPort(port string, stop func() bool) error {
	return errors.New("COM scanners work on the Windows PC only")
}
