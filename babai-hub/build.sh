#!/usr/bin/env bash
# Builds babai-console.exe (Babai Console: Hub + Bridge Print; Windows 10/11, 64-bit) after the tests.
set -euo pipefail
cd "$(dirname "$0")"
cp ../dosa/index.html customer/index.html   # the customer page served by "Download customer page"
go vet ./...
go test -count=1 ./...
mkdir -p dist
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go vet ./...
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-H windowsgui -s -w" -o dist/babai-console.exe .
echo "built dist/babai-console.exe"
