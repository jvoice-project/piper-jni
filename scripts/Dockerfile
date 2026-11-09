# syntax=docker/dockerfile:1

# Stage 1: Native library compilation
# This stage runs on the target platform (using QEMU if needed)
FROM maven:3.9.9-eclipse-temurin-17-focal AS native-builder
# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends git build-essential curl
# Add Kitware repo for newer CMake version
RUN curl -s https://apt.kitware.com/kitware-archive.sh | bash -s
# Install CMake
RUN apt-get update && apt-get install -y --no-install-recommends cmake

WORKDIR /app

COPY .git .git
COPY .gitmodules .gitmodules
COPY src/main/native/CMakeLists.txt .
COPY pom.xml .
COPY scripts/build_linux.sh .
COPY src src

ARG TARGETARCH
ARG RUN_TEST

RUN git submodule update --init
RUN TARGETARCH=${TARGETARCH} ./build_linux.sh
RUN mkdir -p /app/install/lib
RUN cp src/main/resources/native/linux-*/*.so /app/install/lib/
RUN tar -cvf /app/install/piper-jni-libs.tar -C /app/install/lib .
RUN cp src/main/resources/*.zip /app/install/

RUN if [ "$RUN_TEST" = "true" ]; then mvn test -Dtest="PiperJNITest#getPiperVersion"; fi

# Stage 2: Export binaries
# This stage is used to extract the built libraries from the image
FROM scratch AS export
COPY --from=native-builder /app/install/*.tar /
COPY --from=native-builder /app/install/*.zip /
