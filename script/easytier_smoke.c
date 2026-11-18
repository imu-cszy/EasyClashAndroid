// EasyClash: 原生层冒烟测试（不依赖 App UI）。
// 验证：dlopen 合并后的 .so → parse_config(TOML) → run_network_instance →
//       collect_network_infos → retain_network_instance(NULL) 停止。
//
// 用法（设备上）：
//   ./easytier_smoke <jni.so> [ffi.so]
//   传 ffi.so 时先以 RTLD_GLOBAL 预加载（App 的做法，应成功）；
//   不传则直接 dlopen JNI 库（复现 Android RTLD_LOCAL 下的符号失败）。
//
// 编译（NDK clang）：
//   clang --target=x86_64-linux-android21 -o easytier_smoke easytier_smoke.c -ldl

#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/in.h>

static const char *TOML =
    "instance_name = \"smoke-test\"\n"
    "hostname = \"smoke-test\"\n"
    "ipv4 = \"10.126.126.20/24\"\n"
    // 注意：socks5_proxy 必须位于任何 [table] / [[array-of-tables]] 之前，
    // 否则按 TOML 规范会被解析进上一个表（曾导致 socks5 portal 不启动）。
    "socks5_proxy = \"socks5://127.0.0.1:11080\"\n"
    "\n"
    "[network_identity]\n"
    "network_name = \"smoke-network\"\n"
    "network_secret = \"smoke-secret\"\n"
    "\n"
    "[[peer]]\n"
    "uri = \"tcp://127.0.0.1:11010\"\n"
    "\n"
    "[flags]\n"
    "no_tun = true\n"
    "default_protocol = \"tcp\"\n"
    "latency_first = false\n"
    "enable_encryption = true\n";

int main(int argc, char **argv) {
    const char *lib = argc > 1 ? argv[1] : "./libeasytier_android_jni.so";
    const char *ffi = argc > 2 ? argv[2] : NULL;

    if (ffi != NULL) {
        printf("[0] preload %s (RTLD_NOW|RTLD_GLOBAL) —— App 的做法\n", ffi);
        void *fh = dlopen(ffi, RTLD_NOW | RTLD_GLOBAL);
        if (!fh) {
            printf("    FAILED: %s\n", dlerror());
            return 1;
        }
        printf("    OK handle=%p\n", fh);
        void *probe = dlsym(RTLD_DEFAULT, "parse_config");
        printf("    dlsym(RTLD_DEFAULT, parse_config) = %p %s\n",
               probe, probe ? "(全局可见)" : "(全局不可见!)");
        void *probe2 = dlsym(fh, "parse_config");
        printf("    dlsym(ffi_handle, parse_config) = %p\n", probe2);
    } else {
        printf("[0] no preload —— 直接加载 JNI 库（预期在 Android 上失败）\n");
    }

    printf("[1] dlopen %s (RTLD_NOW)\n", lib);
    void *h = dlopen(lib, RTLD_NOW);
    if (!h) {
        printf("    FAILED: %s\n", dlerror());
        return 1;
    }
    printf("    OK\n");

    int (*parse_config)(const char *) = (int (*)(const char *)) dlsym(h, "parse_config");
    int (*run_network_instance)(const char *) =
        (int (*)(const char *)) dlsym(h, "run_network_instance");
    int (*retain_network_instance)(const char *const *, size_t) =
        (int (*)(const char *const *, size_t)) dlsym(h, "retain_network_instance");
    int (*collect_network_infos)(void *, size_t) =
        (int (*)(void *, size_t)) dlsym(h, "collect_network_infos");
    void (*get_error_msg)(const char **) = (void (*)(const char **)) dlsym(h, "get_error_msg");

    if (!parse_config || !run_network_instance || !retain_network_instance ||
        !collect_network_infos) {
        printf("[2] dlsym FAILED (missing C API symbols)\n");
        return 2;
    }
    printf("[2] dlsym OK (parse/run/retain/collect)\n");

    printf("[3] parse_config\n");
    int r = parse_config(TOML);
    printf("    -> %d\n", r);
    if (r != 0) {
        const char *err = NULL;
        if (get_error_msg) get_error_msg(&err);
        printf("    error: %s\n", err ? err : "(null)");
        return 3;
    }

    printf("[4] run_network_instance (no_tun, socks5 127.0.0.1:11080)\n");
    r = run_network_instance(TOML);
    printf("    -> %d\n", r);
    if (r != 0) {
        const char *err = NULL;
        if (get_error_msg) get_error_msg(&err);
        printf("    error: %s\n", err ? err : "(null)");
    }

    // 给实例一点启动时间，然后收集信息
    for (volatile long i = 0; i < 200000000L; ++i) { }
    struct { const char *key; const char *value; } infos[8];
    memset(infos, 0, sizeof(infos));
    int n = collect_network_infos(infos, 8);
    printf("[5] collect_network_infos -> %d\n", n);
    for (int i = 0; i < n && i < 8; ++i) {
        printf("    KEY=[%s]\n    JSON=%s\n", infos[i].key ? infos[i].key : "?",
               infos[i].value ? infos[i].value : "?");
    }

    printf("[5b] 轮询 TCP 127.0.0.1:11080（验证本地 SOCKS5 监听，最多 15s）\n");
    {
        int ok_at = -1;
        for (int sec = 0; sec <= 15; ++sec) {
            int fd = socket(AF_INET, SOCK_STREAM, 0);
            struct sockaddr_in sa;
            memset(&sa, 0, sizeof(sa));
            sa.sin_family = AF_INET;
            sa.sin_port = htons(11080);
            sa.sin_addr.s_addr = htonl(0x7f000001);
            int rc = connect(fd, (struct sockaddr *) &sa, sizeof(sa));
            close(fd);
            printf("    t=%2ds connect -> %d\n", sec, rc);
            if (rc == 0) {
                ok_at = sec;
                break;
            }
            sleep(1);
        }
        printf("    => %s\n", ok_at >= 0 ? "SOCKS5 已监听" : "15s 内未监听（socks5 portal 未启动）");
    }

    printf("[6] retain_network_instance(NULL) 停止全部\n");
    r = retain_network_instance(NULL, 0);
    printf("    -> %d\n", r);

    return 0;
}
