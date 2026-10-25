/*
 * 方案 B 的 native 桥：把 libfrida-core **静态**链进来，让 app 自己当
 * **frida-server 的客户端**（add_remote_device → attach → create script → load → eternalize）。
 *
 * ★为什么是"当客户端"而不是"自己注入"（2026-10-14 实测，别改回去）：
 *   这台游戏的反作弊会打死"自己注入"那条路 —— frida-inject 也好、frida-core 的 local device 也好，
 *   脚本能执行成功（console.log 打出来了），紧接着游戏进程就 EXIT_SELF；
 *   而"让 frida-server 去注入"（PC 版走的就是这条）毫发无损。
 *   所以本桥只做客户端：注入动作全部交给设备上那个 root 的 frida-server 进程。
 *   附带好处：app 不需要 root。
 *
 * 编译：见 tools/build_frida_bridge.ps1（NDK clang 直接编，不引 CMake）
 *   clang --target=x86_64-linux-android26 -shared -fPIC -O2 -ffunction-sections -fdata-sections \
 *         -I<devkit> -o libfrida_bridge.so bridge.c -L<devkit> -lfrida-core \
 *         -llog -ldl -lm -pthread -Wl,--gc-sections
 */
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "frida-core.h"

#define TAG "FridaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static gboolean g_inited = FALSE;

static char * read_text_file(const char * path, gsize * out_len)
{
    FILE * fp;
    long n;
    size_t got;
    char * buf;

    fp = fopen(path, "rb");
    if (fp == NULL)
        return NULL;
    fseek(fp, 0, SEEK_END);
    n = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    if (n <= 0) {
        fclose(fp);
        return NULL;
    }
    buf = (char *) g_malloc((gsize) n + 1);
    got = fread(buf, 1, (size_t) n, fp);
    fclose(fp);
    buf[got] = '\0';
    if (out_len != NULL)
        *out_len = (gsize) got;
    return buf;
}

/*
 * 进程名匹配：Android 上 frida 枚举出来的名字有两种形态 ——
 *   ① 完整包名（来自 cmdline），② 被截断成 15 字节的 comm（如 com.bilibili.pr）；
 *   子进程是 "包名:后缀"。这里两种形态都认，并排除子进程。
 */
static gboolean name_matches(const char * name, const char * target)
{
    size_t ln, lt;

    if (name == NULL || name[0] == '\0')
        return FALSE;
    ln = strlen(name);
    lt = strlen(target);
    if (strcmp(name, target) == 0)
        return TRUE;
    if (ln > lt && strncmp(name, target, lt) == 0 && name[lt] == ':')
        return FALSE;   /* 子进程 */
    if (ln < lt && strncmp(target, name, ln) == 0)
        return TRUE;    /* 名字被截断 */
    return FALSE;
}

/*
 * 查进程 pid（免 root）：借 frida-server 的进程枚举。
 * 返回 pid，找不到返回 -1。
 *
 * ★名字必须和 Kotlin 侧那个 **external** 函数一致：Kotlin 里 suspend 的包装叫 findPid、
 *   真正 external 的是 findPidNative —— 一开始写成 findPid 就对不上，调用直接
 *   UnsatisfiedLinkError（被 runCatching 吞掉后表现为"查不到 pid"，白掉一次 root）。
 */
JNIEXPORT jint JNICALL
Java_com_lmj_labyrinthauto_frida_FridaCore_findPidNative(
        JNIEnv * env, jclass cls, jstring j_addr, jstring j_name)
{
    const char * addr;
    const char * name;
    FridaDeviceManager * mgr = NULL;
    FridaDevice * device = NULL;
    FridaProcessList * procs = NULL;
    GError * error = NULL;
    gint n, i;
    jint result = -1;

    addr = (*env)->GetStringUTFChars(env, j_addr, NULL);
    name = (*env)->GetStringUTFChars(env, j_name, NULL);

    if (!g_inited) {
        frida_init();
        g_inited = TRUE;
    }

    mgr = frida_device_manager_new();
    device = frida_device_manager_add_remote_device_sync(mgr, addr, NULL, NULL, &error);
    if (error == NULL) {
        procs = frida_device_enumerate_processes_sync(device, NULL, NULL, &error);
    }
    if (error == NULL && procs != NULL) {
        n = frida_process_list_size(procs);
        for (i = 0; i < n; i++) {
            FridaProcess * p = frida_process_list_get(procs, i);
            if (name_matches(frida_process_get_name(p), name)) {
                result = (jint) frida_process_get_pid(p);
                frida_unref(p);
                break;
            }
            frida_unref(p);
        }
    }
    if (error != NULL) {
        LOGE("findPid 失败：%s", error->message);
        g_error_free(error);
        error = NULL;
    }
    if (procs != NULL)
        frida_unref(procs);
    if (device != NULL)
        frida_unref(device);
    if (mgr != NULL) {
        frida_device_manager_close_sync(mgr, NULL, NULL);
        frida_unref(mgr);
    }

    (*env)->ReleaseStringUTFChars(env, j_addr, addr);
    (*env)->ReleaseStringUTFChars(env, j_name, name);
    return result;
}

/*
 * 成功返回空串；失败返回人话错误（会一路带回 Kotlin 日志里）。
 * 注意：这是**阻塞**调用（frida 的 sync API），必须在后台线程调，别放主线程。
 */
JNIEXPORT jstring JNICALL
Java_com_lmj_labyrinthauto_frida_FridaCore_attachAndLoadAgent(
        JNIEnv * env, jclass cls, jstring j_addr, jint j_pid, jstring j_script, jboolean j_eternalize)
{
    const char * addr;
    const char * script_path;
    char err[1024];
    FridaDeviceManager * mgr = NULL;
    FridaDevice * device = NULL;
    FridaSession * session = NULL;
    FridaScript * script = NULL;
    FridaScriptOptions * options = NULL;
    GError * error = NULL;
    char * source = NULL;
    gsize source_len = 0;

    addr = (*env)->GetStringUTFChars(env, j_addr, NULL);
    script_path = (*env)->GetStringUTFChars(env, j_script, NULL);
    err[0] = '\0';

    if (!g_inited) {
        frida_init();
        g_inited = TRUE;
    }

    mgr = frida_device_manager_new();

    LOGI("add_remote_device %s", addr);
    device = frida_device_manager_add_remote_device_sync(mgr, addr, NULL, NULL, &error);
    if (error != NULL) {
        snprintf(err, sizeof err, "连不上 frida-server(%s)：%s", addr, error->message);
        goto beach;
    }

    LOGI("attach pid=%d", (int) j_pid);
    session = frida_device_attach_sync(device, (guint) j_pid, NULL, NULL, &error);
    if (error != NULL) {
        snprintf(err, sizeof err, "attach 进程 %d 失败：%s", (int) j_pid, error->message);
        goto beach;
    }

    source = read_text_file(script_path, &source_len);
    if (source == NULL) {
        snprintf(err, sizeof err, "读不到 agent 脚本：%s", script_path);
        goto beach;
    }

    options = frida_script_options_new();
    frida_script_options_set_name(options, "lmj-agent");
    frida_script_options_set_runtime(options, FRIDA_SCRIPT_RUNTIME_QJS);

    script = frida_session_create_script_sync(session, source, options, NULL, &error);
    if (error != NULL) {
        snprintf(err, sizeof err, "创建脚本失败：%s", error->message);
        goto beach;
    }

    LOGI("load script (%u bytes)", (unsigned) source_len);
    frida_script_load_sync(script, NULL, &error);
    if (error != NULL) {
        snprintf(err, sizeof err, "加载脚本失败：%s", error->message);
        goto beach;
    }

    if (j_eternalize) {
        frida_script_eternalize_sync(script, NULL, &error);
        if (error != NULL) {
            snprintf(err, sizeof err, "eternalize 失败：%s", error->message);
            goto beach;
        }
    }

beach:
    if (error != NULL) {
        LOGE("%s", err);
        g_error_free(error);
        error = NULL;
    }
    if (source != NULL)
        g_free(source);
    if (options != NULL)
        frida_unref(options);
    if (script != NULL)
        frida_unref(script);
    if (session != NULL) {
        frida_session_detach_sync(session, NULL, NULL);
        frida_unref(session);
    }
    if (device != NULL)
        frida_unref(device);
    if (mgr != NULL) {
        frida_device_manager_close_sync(mgr, NULL, NULL);
        frida_unref(mgr);
    }

    (*env)->ReleaseStringUTFChars(env, j_addr, addr);
    (*env)->ReleaseStringUTFChars(env, j_script, script_path);

    if (err[0] != '\0')
        return (*env)->NewStringUTF(env, err);
    return (*env)->NewStringUTF(env, "");
}
