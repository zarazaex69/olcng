#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset

# Set magic variables for current file & dir
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
__file="${__dir}/$(basename "${BASH_SOURCE[0]}")"
__base="$(basename "${__file}" .sh)"

trap 'echo -e "Aborted, error $? in command: $BASH_COMMAND"; trap ERR; exit 1' ERR INT

export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export NDK_HOME=${NDK_HOME:-$ANDROID_HOME/ndk/25.2.9519653}

if [[ ! -d $NDK_HOME ]]; then
  echo "Android NDK: NDK_HOME not found. please set env \$NDK_HOME"
  exit 1
fi

echo "Updating submodules..."
cd "$__dir"
git submodule update --init --recursive

echo "Setting up gomobile..."
export PATH=$PATH:$(go env GOPATH)/bin
if ! command -v gomobile &> /dev/null; then
  go install golang.org/x/mobile/cmd/gomobile@latest
  go install golang.org/x/mobile/cmd/gobind@latest
  gomobile init
fi

echo "Building AndroidLibXrayLite (libv2ray.aar)..."
pushd "$__dir/AndroidLibXrayLite" >/dev/null
go mod tidy
gomobile bind -v -androidapi 21 -ldflags='-s -w' -o libv2ray.aar ./
popd >/dev/null

echo "Building hev-socks5-tunnel..."
bash "$__dir/compile-hevtun.sh"

echo "Copying libraries to V2rayNG/app/libs..."
mkdir -p "$__dir/V2rayNG/app/libs"
cp -r "$__dir/libs/"* "$__dir/V2rayNG/app/libs/" 2>/dev/null || true
cp "$__dir/AndroidLibXrayLite/libv2ray.aar" "$__dir/V2rayNG/app/libs/"

echo "Building V2rayNG apk..."
pushd "$__dir/V2rayNG" >/dev/null
BUILD_TYPE=${1:-assembleDebug}
if [[ "$BUILD_TYPE" == "release" ]]; then
  BUILD_TYPE="assembleRelease"
elif [[ "$BUILD_TYPE" == "debug" ]]; then
  BUILD_TYPE="assembleDebug"
fi

./gradlew $BUILD_TYPE
popd >/dev/null

echo "Build complete."
