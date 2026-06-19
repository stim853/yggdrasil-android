#!/bin/bash
set -e

cd "$(dirname "$0")/yggdrasil-go"
git config --global --add safe.directory "$(pwd)" 2>/dev/null || true

PKGSRC="github.com/yggdrasil-network/yggdrasil-go/src/version"
PKGNAME=$(sh contrib/semver/name.sh)
PKGVER=$(sh contrib/semver/version.sh --bare)
LDFLAGS="-X $PKGSRC.buildName=$PKGNAME -X $PKGSRC.buildVersion=$PKGVER -s -w -checklinkname=0"

go get -tool golang.org/x/mobile/cmd/gobind

echo "Building yggdrasil.aar for Android (arm, arm64)..."
gomobile bind \
  -target=android/arm,android/arm64 \
  -androidapi=21 \
  -tags mobile \
  -o yggdrasil.aar \
  -ldflags="$LDFLAGS" \
  ./contrib/mobile ./contrib/exitnode ./src/config

mkdir -p ../../app/libs/
cp yggdrasil.aar ../../app/libs/
echo "yggdrasil.aar built and copied to app/libs/"
