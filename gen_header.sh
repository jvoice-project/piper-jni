set -xe

LIB_SRC=src/main/java/io/github/jvoiceproject/piperjni

javac -h src/main/native \
  $LIB_SRC/internal/PiperAudioChunk.java \
  $LIB_SRC/PiperSynthesizeOptions.java \
  $LIB_SRC/PiperVoiceConfig.java \
  $LIB_SRC/internal/PiperBinding.java

rm -rf $LIB_SRC/*.class $LIB_SRC/internal/*.class
