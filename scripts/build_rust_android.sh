#!/bin/sh
set -eu

rust_root=${1:?Rust crate path is required}
output_root=${2:?Output directory is required}
api_level=${ANDROID_API_LEVEL:-26}
script_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(dirname "$script_root")

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    ndk_root=$ANDROID_NDK_HOME
elif [ -n "${ANDROID_NDK_ROOT:-}" ]; then
    ndk_root=$ANDROID_NDK_ROOT
elif [ -d "${ANDROID_SDK_ROOT:-}/ndk" ]; then
    ndk_root=$(find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)
elif [ -d "${ANDROID_HOME:-}/ndk" ]; then
    ndk_root=$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)
elif [ -f "$project_root/local.properties" ]; then
    sdk_root=$(sed -n 's/^sdk\.dir=//p' "$project_root/local.properties" | sed 's/\\\\:/:/g')
    ndk_root=$(find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)
elif [ -f "$(dirname "$rust_root")/android/local.properties" ]; then
    sdk_root=$(sed -n 's/^sdk\.dir=//p' "$(dirname "$rust_root")/android/local.properties" | sed 's/\\\\:/:/g')
    ndk_root=$(find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)
else
    echo "Android NDK not found. Set ANDROID_NDK_HOME before building." >&2
    exit 1
fi

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)
        if [ -d "$ndk_root/toolchains/llvm/prebuilt/darwin-arm64" ]; then
            toolchain_host=darwin-arm64
        else
            toolchain_host=darwin-x86_64
        fi
        ;;
    Darwin-*) toolchain_host=darwin-x86_64 ;;
    Linux-*) toolchain_host=linux-x86_64 ;;
    *) echo "Unsupported build host: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

clang_root="$ndk_root/toolchains/llvm/prebuilt/$toolchain_host/bin"
if [ ! -x "$clang_root/aarch64-linux-android${api_level}-clang" ] || \
    [ ! -x "$clang_root/aarch64-linux-android${api_level}-clang++" ] || \
    [ ! -x "$clang_root/llvm-ar" ]; then
    echo "Android NDK clang toolchain not found under $ndk_root." >&2
    exit 1
fi

rust_toolchain=
if command -v rustup >/dev/null 2>&1; then
    rust_toolchain=$(cd "$rust_root" && rustup show active-toolchain | awk 'NR == 1 { print $1 }')
    rustup target add --toolchain "$rust_toolchain" aarch64-linux-android x86_64-linux-android
fi

mkdir -p "$output_root/arm64-v8a" "$output_root/x86_64"

build_one() {
    target=$1
    abi=$2
    linker="$clang_root/${target}${api_level}-clang"
    cxx="$clang_root/${target}${api_level}-clang++"
    target_env=$(printf '%s' "$target" | tr '-' '_')
    linker_env="CARGO_TARGET_$(printf '%s' "$target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
    cc_env="CC_$target_env"
    cxx_env="CXX_$target_env"
    ar_env="AR_$target_env"
    if [ -n "$rust_toolchain" ]; then
        env \
            "CARGO_TARGET_DIR=$rust_root/target" \
            "$linker_env=$linker" \
            "$cc_env=$linker" \
            "$cxx_env=$cxx" \
            "$ar_env=$clang_root/llvm-ar" \
            rustup run "$rust_toolchain" cargo build --manifest-path "$rust_root/Cargo.toml" --target "$target" --release --features android-backends
    else
        env \
            "CARGO_TARGET_DIR=$rust_root/target" \
            "$linker_env=$linker" \
            "$cc_env=$linker" \
            "$cxx_env=$cxx" \
            "$ar_env=$clang_root/llvm-ar" \
            cargo build --manifest-path "$rust_root/Cargo.toml" --target "$target" --release --features android-backends
    fi
    cp "$rust_root/target/$target/release/libcubacadabra_engine.so" \
        "$output_root/$abi/libcubacadabra_engine.so"
    cp "$ndk_root/toolchains/llvm/prebuilt/$toolchain_host/sysroot/usr/lib/$target/libc++_shared.so" \
        "$output_root/$abi/libc++_shared.so"
}

build_one aarch64-linux-android arm64-v8a
build_one x86_64-linux-android x86_64
