#!/bin/bash
set -e

# Ensure we are in the project root
cd "$(dirname "$0")"

echo "Building native libraries for all supported platforms using Docker..."

# Initialize submodules if not already done
if [ ! -f "src/main/native/piper/VERSION" ]; then
    echo "Initializing submodules..."
    git submodule update --init --recursive
fi

# Build for Linux amd64 and arm64
# The --output flag extracts the built libraries from the 'export' stage
# and places them into src/main/resources/
docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --target export \
    --output "type=local,dest=../src/main/resources/tmp" \
    --file Dockerfile \
    ..

# Ensure resource directories exist
mkdir -p ../src/main/resources/native/linux-x86-64 ../src/main/resources/native/linux-aarch64
# espeak-ng-data.zip
mv ../src/main/resources/tmp/linux_amd64/espeak-ng-data.zip ../src/main/resources/
# Binaries
tar -xvf ../src/main/resources/tmp/linux_amd64/piper-jni-libs.tar -C ../src/main/resources/native/linux-x86-64/
tar -xvf ../src/main/resources/tmp/linux_arm64/piper-jni-libs.tar -C ../src/main/resources/native/linux-aarch64/

rm -rf ../src/main/resources/tmp

echo "Build complete. Binaries are located in src/main/resources/native/"
ls -R ../src/main/resources/native
