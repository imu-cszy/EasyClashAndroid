#!/usr/bin/env bash
# EasyClash: 构建 EasyTier v2.6.4 的 Android 原生库（Windows Git Bash / Linux 均可）。
#
# 产出：单个 libeasytier_android_jni.so（已静态合并 easytier-ffi 实现）。
#
# ⚠️ 为什么必须合并（2026-11-18 真机 + 模拟器实测）：
#   上游把 JNI 层与 FFI 层编成两个 .so，JNI 库对 FFI 的 C API 是"未定义引用"，
#   两者之间没有 DT_NEEDED。Android 的 System.loadLibrary 使用 RTLD_LOCAL，先加载的
#   FFI 符号对随后加载的 JNI 库不可见，运行时必然报：
#     dlopen failed: cannot locate symbol "collect_network_infos"
#   更关键的是：即便用 dlopen(ffi, RTLD_GLOBAL) 预加载，dlsym(RTLD_DEFAULT) 能看到符号，
#   但新库的重定位不查全局组，仍然失败（API 35 实测）。因此只能让 .so 自包含。
#
# 合并方式（本脚本自动打补丁，幂等）：
#   1. easytier-ffi 的 crate-type 增加 "rlib"；
#   2. easytier-android-jni 依赖 easytier-ffi；
#   3. lib.rs 增加一个 #[used] 静态引用强制链接 rlib（仅声明依赖不够，未被引用的
#      rlib 不会进入 cdylib 的链接输入）。
#
# 前置（Windows 本机，均已安装）：
#   - Rust 1.95 工具链 + aarch64-linux-android target（rustup toolchain install 1.95）
#   - cargo-ndk 4.1.2（D:/devetools/cargo-ndk 预编译版已拷入 ~/.cargo/bin）
#   - NDK 29.0.14206865（SDK 内）
#   - protoc 36.2（D:/devetools/protoc）
#   - kcp-sys 需要 clang 头文件：已把 NDK clang resource include 复制到
#     ~/.cargo/git/checkouts/kcp-sys-*/<rev>/extra_includes，并用相对路径传入
#     （KCP_SYS_EXTRA_HEADER_PATH 按冒号切分，Windows 盘符冒号会踩坑，必须相对路径）。
#
# 用法：
#   EASYTIER_DIR=/d/zycodes/Android_Dev_projects/EasyClashAndroid_proj/third_party/EasyTier \
#   ./script/build-easytier-jni.sh [额外 ABI，如 x86_64]
#
# 产物复制到 core/src/main/jniLibs/<abi>/ 后 git commit。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EASYTIER_DIR="${EASYTIER_DIR:-$REPO_ROOT/../third_party/EasyTier}"
NDK_ROOT="${ANDROID_NDK_ROOT:-D:/devetools/AndroidSDK/ndk/29.0.14206865}"
PROTOC_BIN="${PROTOC:-D:/devetools/protoc/bin/protoc.exe}"

TARGETS=("arm64-v8a")
if [ "${1:-}" != "" ]; then
  TARGETS+=("$1")
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    LIBCLANG="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin"
    ;;
  *)
    LIBCLANG="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
    ;;
esac

cd "$EASYTIER_DIR"

# ------------------------------------------------------- 合并补丁（幂等）
echo "==> Applying merge patch (ffi -> rlib, forced link into jni cdylib)"
python - "$EASYTIER_DIR" <<'PY'
import io, os, sys
root = sys.argv[1]
ffi_toml = os.path.join(root, "easytier-contrib", "easytier-ffi", "Cargo.toml")
jni_toml = os.path.join(root, "easytier-contrib", "easytier-android-jni", "Cargo.toml")
jni_lib = os.path.join(root, "easytier-contrib", "easytier-android-jni", "src", "lib.rs")

s = io.open(ffi_toml, encoding="utf-8").read()
if '"rlib"' not in s:
    s = s.replace('crate-type = ["cdylib"]', 'crate-type = ["cdylib", "rlib"]')
    io.open(ffi_toml, "w", encoding="utf-8", newline="").write(s)
    print("  patched", ffi_toml)

s = io.open(jni_toml, encoding="utf-8").read()
if "easytier-ffi" not in s:
    s = s.rstrip() + '\neasytier-ffi = { path = "../easytier-ffi" }\n'
    io.open(jni_toml, "w", encoding="utf-8", newline="").write(s)
    print("  patched", jni_toml)

s = io.open(jni_lib, encoding="utf-8").read()
if "_EASYTIER_FFI_FORCE_LINK" not in s:
    force = (
        "// EasyClash: 强制把 easytier-ffi 静态链接进本 cdylib（详见 script/build-easytier-jni.sh）。\n"
        "#[used]\n"
        'static _EASYTIER_FFI_FORCE_LINK: unsafe extern "C" fn(*const std::ffi::c_char) -> std::ffi::c_int =\n'
        "    easytier_ffi::parse_config;\n\n"
    )
    s = force + s
    io.open(jni_lib, "w", encoding="utf-8", newline="").write(s)
    print("  patched", jni_lib)
PY

# kcp-sys 头文件（相对路径，见文件头说明）
KCP_CHECKOUT="$(ls -d "$HOME"/.cargo/git/checkouts/kcp-sys-*/[0-9a-f]* 2>/dev/null | head -1 || true)"
if [ -n "$KCP_CHECKOUT" ] && [ ! -d "$KCP_CHECKOUT/extra_includes" ]; then
  CLANG_INC="$(ls -d "$NDK_ROOT"/toolchains/llvm/prebuilt/*/lib/clang/*/include | head -1)"
  cp -r "$CLANG_INC" "$KCP_CHECKOUT/extra_includes"
  echo "==> Seeded kcp-sys extra_includes from $CLANG_INC"
fi

export ANDROID_NDK_ROOT="$NDK_ROOT"
export LIBCLANG_PATH="$LIBCLANG"
export PROTOC="$PROTOC_BIN"
export KCP_SYS_EXTRA_HEADER_PATH="extra_includes"
export https_proxy="${https_proxy:-http://127.0.0.1:7890}"
export http_proxy="${http_proxy:-http://127.0.0.1:7890}"

for abi in "${TARGETS[@]}"; do
  echo "==> Building merged easytier-android-jni for $abi"
  cargo ndk -t "$abi" build --release -p easytier-android-jni

  case "$abi" in
    arm64-v8a)   rust_triple=aarch64-linux-android ;;
    armeabi-v7a) rust_triple=armv7-linux-androideabi ;;
    x86)         rust_triple=i686-linux-android ;;
    x86_64)      rust_triple=x86_64-linux-android ;;
    *) echo "unknown abi: $abi"; exit 1 ;;
  esac

  OUT="$REPO_ROOT/core/src/main/jniLibs/$abi"
  mkdir -p "$OUT"
  # 旧的两库布局不再使用
  rm -f "$OUT/libeasytier_ffi.so"
  cp "target/$rust_triple/release/libeasytier_android_jni.so" "$OUT/"

  echo "==> Artifacts for $abi:"
  ls -la "$OUT"
  sha256sum "$OUT"/*.so

  echo "==> Verifying no undefined EasyTier C API symbols (must be empty):"
  NM="$(ls "$NDK_ROOT"/toolchains/llvm/prebuilt/*/bin/llvm-nm* | head -1)"
  "$NM" -D --undefined-only "$OUT/libeasytier_android_jni.so" \
    | grep -E "parse_config|run_network_instance|collect_network_infos|retain_network_instance|get_error_msg|free_string|set_tun_fd" \
    && { echo "ERROR: unresolved EasyTier symbols remain"; exit 1; } || echo "  OK"
done
