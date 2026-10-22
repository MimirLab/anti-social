#!/usr/bin/env bash
set -euo pipefail
ANTISOCIAL_BUILD_ROOT="${ANTISOCIAL_BUILD_ROOT:-/run/media/red/RedtopStorage/DarwinBuild/LiberBuild}"
export RUSTUP_HOME="$ANTISOCIAL_BUILD_ROOT/rustup" CARGO_HOME="$ANTISOCIAL_BUILD_ROOT/cargo" CARGO_TARGET_DIR="$ANTISOCIAL_BUILD_ROOT/rust-target"
export TMPDIR="$ANTISOCIAL_BUILD_ROOT/java-tmp"
export PATH="$CARGO_HOME/bin:$PATH"
mkdir -p "$TMPDIR" "$ANTISOCIAL_BUILD_ROOT/native-host"
cargo build --locked --release --manifest-path native/embedding/Cargo.toml
cp "$CARGO_TARGET_DIR/release/libantisocial_embedding.so" "$ANTISOCIAL_BUILD_ROOT/native-host/"
if [[ "${1:-}" == "android" ]]; then
  python3 scripts/prepare_jni.py
  ANTISOCIAL_NDK="$ANTISOCIAL_BUILD_ROOT/android-sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin"
  export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANTISOCIAL_NDK/aarch64-linux-android26-clang"
  export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$ANTISOCIAL_NDK/armv7a-linux-androideabi26-clang"
  export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ANTISOCIAL_NDK/x86_64-linux-android26-clang"
  export AR_aarch64_linux_android="$ANTISOCIAL_NDK/llvm-ar" AR_armv7_linux_androideabi="$ANTISOCIAL_NDK/llvm-ar" AR_x86_64_linux_android="$ANTISOCIAL_NDK/llvm-ar"
  export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" CC_armv7_linux_androideabi="$CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER" CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
  for antisocial_pair in 'aarch64-linux-android arm64-v8a' 'armv7-linux-androideabi armeabi-v7a' 'x86_64-linux-android x86_64'; do
    read -r antisocial_target antisocial_abi <<< "$antisocial_pair"
    rustup target add "$antisocial_target"
    cargo build --locked --release --lib --manifest-path native/embedding/Cargo.toml --target "$antisocial_target"
    mkdir -p "app/src/main/jniLibs/$antisocial_abi"
    cp "$CARGO_TARGET_DIR/$antisocial_target/release/libantisocial_embedding.so" "app/src/main/jniLibs/$antisocial_abi/"
  done
fi
