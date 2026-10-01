# Piper JNI

A JNI wrapper for [Piper](https://github.com/OHF-Voice/piper1-gpl), a fast, local neural text-to-speech system.

## Platform Support

Java >= 17 is supported.
This library aims to support the following platforms:

* Windows x86_64
* Linux x86_64/arm64 (built with Ubuntu Focal Fossa, GLIBC version 2.31)
* macOS x86_64/arm64 (built for macOS 14 Sonoma and newer)

The JAR includes the piper-jni native library, which contains the [Piper](https://github.com/OHF-Voice/piper1-gpl) source,
and the shared libraries it depends on:
[espeak](https://espeak.sourceforge.net) and [onxxruntime](https://onnxruntime.ai).

## Usage

The package is distributed through [Maven Central](https://central.sonatype.com/artifact/io.github.jvoice-project/piper-jni):

### Maven

```xml
<dependency>
    <groupId>io.github.jvoice-project</groupId>
    <artifactId>piper-jni</artifactId>
    <!-- replace $version with a specific version -->
    <version>$version</version>
</dependency>
```

### Gradle

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.jvoice-project:piper-jni:+' // gets the latest version
}
```

All releases are signed with the PGP key `047A5F7D27B9F2408F31EB6D577886B2F4A44CB7`,
you can find the public key on [keys.openpgp.org](https://keys.openpgp.org/search?q=047A5F7D27B9F2408F31EB6D577886B2F4A44CB7).
To import the public key, use the following command:

```shell
gpg --keyserver keys.openpgp.org --recv-keys 047A5F7D27B9F2408F31EB6D577886B2F4A44CB7
```

You can also find the package's jar attached to each [release](https://github.com/jvoice-project/piper-jni/releases).

### Examples

```java
String textToSpeak = "Hello, world!";

PiperJNI piper = new PiperJNI();
// Initialize Piper before using it.
piper.initialize();
try (var voice = piper.loadVoice(Paths.get("/path/to/en_US-lessac-medium.onnx"), Path.of("/path/to/en_US-lessac-medium.onnx.json"))) {
    int sampleRate = voice.getSampleRate();
    short[] samples = piper.textToAudio(voice, textToSpeak);
    // Do something with the samples...
} finally {
    piper.close();
}
```

### Audio Output Specification

When synthesizing text via `piper.textToAudio()`, the Java API layer produces audio with the following specification:

| Attribute              | Value / Format                                                                             |
|:-----------------------|:-------------------------------------------------------------------------------------------|
| **Container / Header** | Raw PCM stream (no header). Wrap with `AudioSystem.write` for RIFF WAV (`.wav`).           |
| **Data Type**          | `short[]` (Java 16-bit primitive array)                                                    |
| **Encoding Format**    | 16-bit Signed Linear PCM (`PCM_SIGNED`)                                                    |
| **Sample Value Range** | `-32768` to `32767`                                                                        |
| **Channels**           | **1** (Mono)                                                                               |
| **Sample Rate**        | Model-dependent, retrieved via `voice.getSampleRate()` (e.g. `22050`, `16000`, `24000` Hz) |
| **Endianness**         | Host native in memory; **Little-Endian** when serialized to `.wav` file                    |

## Development

You need to have Java >= 17 and C++ setup.

After cloning the project, you need to init the piper submodule by running:

```shell
git submodule update --init
```

### Build with Docker (Recommended)

You can build for all supported Linux platforms (`amd64`, `arm64`) using Docker:

```shell
./build_linux-all.sh
```

This uses `docker buildx` to build the native libraries and places them in `src/main/resources`.

### Native Build (Local)

If you prefer to build locally for your current platform:

- **Linux:** Run `./build_linux.sh`
- **macOS:** Run `./build_macos.sh`
- **Windows:** Run `.\build_win.cmd`

These scripts compile the JNI shared library and copy it directly to the corresponding `src/main/resources` folder.

### Java Build

Finally, you can build the Java library, run the tests, and package the JAR with Maven:

```shell
./mvnw package
```

Maven will download the Piper voice and configure the unit tests to use it.
Optionally, you can override the voice model name, download URL as well as the text to speak:

```shell
./mvnw \
  -Dtest.model.name="de_DE-thorsten-medium.onnx" \
  -Dtest.model.url="https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE/thorsten/medium" \
  -Dtest.text="Guten Tag!" \
  package
```

### Extending the Native API

If you want to add any missing piper functionality, you need to:

* Add the native method description in [`PiperJNI.java`](src/main/java/io/github/jvoiceproject/piperjni/PiperJNI.java).
* Run the `gen_header.sh` script to regenerate the [`io_github_jvoiceproject_piperjni_PiperJNI.h`](src/main/native/io_github_jvoiceproject_piperjni_PiperJNI.h) header file. 
* Add the native method implementation in [`io_github_jvoiceproject_piperjni_PiperJNI.cpp`](src/main/native/io_github_jvoiceproject_piperjni_PiperJNI.cpp).
* Add a new test for it at [`PiperJNITest.java`](src/test/java/io/github/jvoice/piperjni/PiperJNITest.java).
