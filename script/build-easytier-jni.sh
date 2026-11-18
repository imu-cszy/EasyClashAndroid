#!/usr/bin/env bash
# EasyClash: 本地构建 EasyTier v2.6.4 JNI/FFI 原生库（Windows Git Bash / Linux 均可）。
#
# 前置（Windows 本机，均已安装）：
#   - Rust 1.95 工具链 + aarch64-linux-android target（rustup toolchain install 1.95）
#   - cargo-ndk（D:/devetools/cargo-ndk 预编译版已拷入 ~/.cargo/bin）
#   - NDK 29.0.14206865（SDK 内）
#   - protoc 36.2（D:/devetools/protoc）
#   - kcp-sys 需要 clang 头文件：已把 NDK clang resource include 复制到
#     ~/.cargo/git/checkouts/kcp-sys-*/<rev>/extra_includes，并用相对路径传入
#     （KCP_SYS_EXTRA_HEADER_PATH 按冒号切分，Windows 盘符冒号会踩坑，必须用相对路径）。
#
# 用法：
#   EASYTIER_DIR=/d/zycodes/Android_Dev_projects/EasyClashAndroid_proj/third_party/EasyTier \
#   ./script/build-easytier-jni.sh
#
# 产物复制到 core/src/main/jniLibs/arm64-v8a/ 后 git commit。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EASYTIER_DIR="${EASYTIER_DIR:-$REPO_ROOT/../third_party/EasyTier}"
NDK_ROOT="${ANDROID_NDK_ROOT:-D:/devetools/AndroidSDK/ndk/29.0.14206865}"
PROTOC_BIN="${PROTOC:-D:/devetools/protoc/bin/protoc.exe}"
TARGET=arm64-v8a

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    LIBCLANG="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin"
    KCP_EXTRA="extra_includes"  # 相对路径：见文件头说明
    ;;
  *)
    LIBCLANG="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
    KCP_EXTRA="$(ls -d "$HOME"/.cargo/registry/cache/*/  >/dev/null 2>&1; echo '')"
    ;;
esac

cd "$EASYTIER_DIR"

export ANDROID_NDK_ROOT="$NDK_ROOT"
export LIBCLANG_PATH="$LIBCLANG"
export PROTOC="$PROTOC_BIN"
export KCP_SYS_EXTRA_HEADER_PATH="$KCP_EXTRA"
export https_proxy="${https_proxy:-http://127.0.0.1:7890}"
export http_proxy="${http_proxy:-http://127.0.0.1:7890}"

echo "==> Building easytier-ffi + easytier-android-jni for $TARGET"
cargo ndk -t "$TARGET" build --release -p easytier-ffi -p easytier-android-jni

OUT="$REPO_ROOT/core/src/main/jniLibs/$TARGET"
mkdir -p "$OUT"
cp "target/aarch64-linux-android/release/libeasytier_ffi.so" "$OUT/"
cp "target/aarch64-linux-android/release/libeasytier_android_jni.so" "$OUT/"

echo "==> Done. Artifacts:"
ls -la "$OUT"
sha256sum "$OUT"/*.so
