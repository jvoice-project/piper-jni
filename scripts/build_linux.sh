#!/bin/bash
set -xe

# Use TARGETARCH if provided (e.g. from Docker)
# Otherwise, try to detect it using uname -m
AARCH=${1:-${TARGETARCH:-$(uname -m)}}

case $AARCH in
    x86_64|amd64|x86-64)
        TARGET_ARCH="x86-64" ;;
    aarch64|arm64)
        TARGET_ARCH="aarch64" ;;
    *)
        echo "Unsupported architecture: ${AARCH}"
        exit 1 ;;
esac

SOURCE_DIR="src/main/native"
BUILD_DIR="target/native-build"
TARGET_DIR="src/main/resources/native/linux-$TARGET_ARCH"

if [ ! -f "src/main/native/piper/CMakeLists.txt" ]; then
    echo "Initializing submodules..."
    git submodule update --init --recursive
fi

cmake -S "$SOURCE_DIR" \
       -B "$BUILD_DIR" \
       -DCMAKE_INSTALL_PREFIX="$TARGET_DIR" \
       "${@:2}"
cmake --build "$BUILD_DIR" --config Release -j$(nproc)
cmake --install "$BUILD_DIR"

find "$TARGET_DIR" -type l \( -name "*.so" -o -name "*.so.*" \) | while read -r link; do
    target=$(readlink -f "$link")
    [ -f "$target" ] || continue
    rm -f "$link"
    mv "$target" "$link"
    rmdir "$(dirname "$target")" 2>/dev/null || true
done
