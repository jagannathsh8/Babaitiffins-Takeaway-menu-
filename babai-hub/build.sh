#!/usr/bin/env bash
# Builds babai-hub.exe (Windows 10/11, 64-bit; no install needed) after running the tests.
set -euo pipefail
cd "$(dirname "$0")"
go vet ./...
go test -count=1 ./...
mkdir -p dist
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-H windowsgui -s -w" -o dist/babai-hub.exe .
echo "built dist/babai-hub.exe"
