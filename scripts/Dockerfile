# syntax=docker/dockerfile:1

# Stage 1: Native library compilation (Ubuntu 20.04 / glibc 2.31 for broad Linux compatibility)
# This stage runs on the target platform (using QEMU if needed)
FROM maven:3.9.9-eclipse-temurin-17-focal AS native-builder

# Install build dependencies (GCC 10 for C++17 support on glibc 2.31)
RUN apt-get update && apt-get install -y --no-install-recommends \
    git build-essential curl ca-certificates gcc-10 g++-10 \
    && rm -rf /var/lib/apt/lists/*

# Add Kitware repo for newer CMake version
RUN curl -s https://apt.kitware.com/kitware-archive.sh | bash -s \
    && apt-get update && apt-get install -y --no-install-recommends cmake \
    && rm -rf /var/lib/apt/lists/*

ENV CC=gcc-10
ENV CXX=g++-10

WORKDIR /app

COPY .git .git
COPY .gitmodules .gitmodules
COPY pom.xml .
COPY scripts/build_linux.sh .
COPY src src

ARG TARGETARCH
ARG RUN_TEST

RUN git submodule update --init
RUN TARGETARCH=${TARGETARCH} ./build_linux.sh

# Verify hermetic build: no system libstdc++/libgcc_s, no onnxruntime link dependency, no leaked C++ symbols, glibc <= 2.31
RUN LIB_SO=$(ls src/main/resources/native/linux-*/libpiper-jni.so) && \
    echo "=== ldd $LIB_SO ===" && \
    ldd "$LIB_SO" && \
    if ldd "$LIB_SO" | grep -E "libstdc\+\+|libgcc_s"; then \
        echo "ERROR: $LIB_SO must not depend on system libstdc++ or libgcc_s!" >&2; \
        exit 1; \
    fi && \
    if ldd "$LIB_SO" | grep -i "onnxruntime"; then \
        echo "ERROR: $LIB_SO must not link against libonnxruntime!" >&2; \
        exit 1; \
    fi && \
    echo "=== Checking for leaked C++ symbols ===" && \
    if nm -D --defined-only "$LIB_SO" | grep " _Z"; then \
        echo "ERROR: Leaked C++ symbols found in $LIB_SO!" >&2; \
        exit 1; \
    fi && \
    echo "=== Max GLIBC version ===" && \
    objdump -T "$LIB_SO" | grep -oE 'GLIBC_[0-9.]+' | sort -Vu | tail -n 5 && \
    MAX_GLIBC=$(objdump -T "$LIB_SO" | grep -oE 'GLIBC_[0-9.]+' | sed 's/GLIBC_//' | sort -Vu | tail -n 1) && \
    if [ "$(printf '%s\n' "$MAX_GLIBC" 2.31 | sort -V | tail -n 1)" != "2.31" ]; then \
        echo "ERROR: $LIB_SO requires GLIBC_$MAX_GLIBC, but must not require more than GLIBC_2.31!" >&2; \
        exit 1; \
    fi

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
