/**
 * ClashZeroVPN JNI 桥接层
 *
 * 功能：把 Kotlin 的 native 函数映射到 libzt API。
 *
 * 有两种编译模式：
 *  - CZVPN_HAS_LIBZT=1 : 已提供 libzt.so 和头文件，真实调用 ZeroTier SDK
 *  - CZVPN_HAS_LIBZT=0 : stub 占位，仅打日志并返回成功（便于 APK 先编译通过）
 *
 * Kotlin 对应类：com.clashzerovpn.engine.ZeroTierEngine
 */

#include <jni.h>
#include <string>
#include <cstring>
#include <thread>
#include <chrono>
#include <atomic>
#include <mutex>
#include <vector>

#include <android/log.h>

#define LOG_TAG "CZ.Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if CZVPN_HAS_LIBZT
#include "ZeroTierSockets.h"
#endif

// ----------- 全局状态 -----------
static std::atomic<bool> g_started{false};
static std::atomic<bool> g_networkJoined{false};
static std::atomic<int64_t> g_nodeId{0};
static std::atomic<int64_t> g_networkId{0};
static std::atomic<bool> g_wireRunning{false};

// Kotlin JVM 回调引用：virtual wire 读到包时调用 Kotlin 的 returnToTun
static JavaVM* g_jvm = nullptr;
static jobject g_callbackObject = nullptr;  // ZeroTierEngine 实例
static jmethodID g_returnToTunMethod = nullptr;

static std::mutex g_jniMutex;

#if CZVPN_HAS_LIBZT
// virtual wire 编号 (libzt 支持多个虚拟网卡，我们只用 0 号)
static constexpr int VWIRE_IDX = 0;
#endif

static void callKotlinReturnToTun(JNIEnv* env, const uint8_t* data, int len) {
    if (!g_returnToTunMethod || !g_callbackObject || len <= 0) return;
    std::lock_guard<std::mutex> _(g_jniMutex);
    jbyteArray byteArr = env->NewByteArray(len);
    if (!byteArr) return;
    env->SetByteArrayRegion(byteArr, 0, len, reinterpret_cast<const jbyte*>(data));
    env->CallVoidMethod(g_callbackObject, g_returnToTunMethod, byteArr);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(byteArr);
}

/**
 * virtual wire 读线程：
 *   libzt 通过 zts_virtual_wire_read() 把"虚拟网卡出来的、要回系统 TUN"的 IP 包给我们。
 *   我们调用 Kotlin 的 returnToTun() 函数回写 App 的 VpnService TUN 接口。
 */
#if CZVPN_HAS_LIBZT
static void virtualWireReaderThread() {
    g_wireRunning = true;
    LOGI("virtualWireReaderThread started");
    uint8_t buf[65536];
    while (g_started.load()) {
        int n = zts_virtual_wire_read(VWIRE_IDX, buf, sizeof(buf));
        if (n > 0) {
            JNIEnv* env = nullptr;
            int attached = 0;
            jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (res == JNI_EDETACHED) {
                if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) continue;
                attached = 1;
            }
            if (env) callKotlinReturnToTun(env, buf, n);
            if (attached) g_jvm->DetachCurrentThread();
        } else if (n == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    }
    g_wireRunning = false;
    LOGI("virtualWireReaderThread stopped");
}
#endif

// ---------- JNI 生命周期 ----------
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeInit(
        JNIEnv* env, jobject thiz, jstring storagePath) {

    // 缓存回调方法引用：Kotlin 侧的 fun returnToTun(packet: ByteArray)
    jclass cls = env->GetObjectClass(thiz);
    jmethodID mid = env->GetMethodID(cls, "returnToTun", "([B)V");
    if (!mid) {
        LOGE("returnToTun([B)V not found on ZeroTierEngine");
        return -1;
    }
    g_returnToTunMethod = mid;
    if (g_callbackObject) env->DeleteGlobalRef(g_callbackObject);
    g_callbackObject = env->NewGlobalRef(thiz);

    const char* path = env->GetStringUTFChars(storagePath, nullptr);
    LOGI("nativeInit storage=%s", path);
    int rc = 0;

#if CZVPN_HAS_LIBZT
    rc = zts_init_from_storage(path);
    if (rc != ZTS_ERR_OK) {
        LOGE("zts_init_from_storage failed: %d", rc);
        env->ReleaseStringUTFChars(storagePath, path);
        return rc;
    }
    // 启用 virtual wire 模式（注入/读出原始 IP 帧）
    zts_virtual_wire_enable(VWIRE_IDX);

    // 设置 node id
    g_nodeId.store(zts_node_get_id());
    LOGI("zerotier node id: %llx", (unsigned long long)g_nodeId.load());
#else
    LOGI("nativeInit STUB mode");
#endif

    env->ReleaseStringUTFChars(storagePath, path);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeStart(
        JNIEnv* env, jobject thiz, jstring networkIdStr) {

    if (g_started.load()) {
        LOGI("already started, ignore");
        return 0;
    }
    const char* netStr = env->GetStringUTFChars(networkIdStr, nullptr);
    // 解析 16 位十六进制网络 ID
    uint64_t netId = 0;
    try {
        size_t len = strlen(netStr);
        if (len != 16) {
            LOGE("invalid network id length: %zu", len);
            env->ReleaseStringUTFChars(networkIdStr, netStr);
            return -2;
        }
        netId = std::stoull(netStr, nullptr, 16);
    } catch (...) {
        LOGE("parse network id failed");
        env->ReleaseStringUTFChars(networkIdStr, netStr);
        return -2;
    }
    g_networkId.store((int64_t)netId);
    LOGI("nativeStart network=%016llx", (unsigned long long)netId);
    int rc = 0;

#if CZVPN_HAS_LIBZT
    // 启动后台线程（zts_start 会阻塞，所以我们异步启动）
    struct StartArgs {
        uint64_t netId;
    };
    auto* args = new StartArgs{netId};
    std::thread([args](){
        int r = zts_start(nullptr, 0 /*服务端口 0=随机*/, nullptr /*事件回调*/);
        if (r != ZTS_ERR_OK) {
            LOGE("zts_start failed: %d", r);
        } else {
            // node online 后加入网络
            while (zts_node_is_online() == 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                if (!g_started.load()) break;
            }
            if (g_started.load()) {
                zts_net_join(args->netId);
                g_networkJoined.store(true);
                LOGI("joined network %016llx", (unsigned long long)args->netId);
                // 启动 virtual wire 读线程
                std::thread(virtualWireReaderThread).detach();
            }
        }
        delete args;
    }).detach();

#else
    // STUB 模式：启动一个空占位虚拟 wire 读线程
    g_started.store(true);
    g_networkJoined.store(true);
#endif

    g_started.store(true);
    env->ReleaseStringUTFChars(networkIdStr, netStr);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeInject(
        JNIEnv* env, jobject thiz, jbyteArray packet, jint len) {

    if (!g_started.load()) return -1;
    if (len <= 0 || !packet) return -2;
    jbyte* bytes = env->GetByteArrayElements(packet, nullptr);
    if (!bytes) return -2;
    int rc = 0;
#if CZVPN_HAS_LIBZT
    rc = zts_virtual_wire_write(VWIRE_IDX, (const uint8_t*)bytes, len);
#else
    rc = len; // stub: 直接吞掉,返回 len 假装写入成功
#endif
    env->ReleaseByteArrayElements(packet, bytes, JNI_ABORT);
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeStop(
        JNIEnv* env, jobject thiz) {
    if (!g_started.exchange(false)) return;
    LOGI("nativeStop");
#if CZVPN_HAS_LIBZT
    uint64_t nid = (uint64_t)g_networkId.load();
    if (nid) zts_net_leave(nid);
    // zts_stop 会解阻塞 zts_start 线程
    zts_stop();
#endif
    // 清空 JNI 全局引用
    if (g_callbackObject) {
        env->DeleteGlobalRef(g_callbackObject);
        g_callbackObject = nullptr;
    }
    g_networkJoined.store(false);
    g_nodeId.store(0);
    g_networkId.store(0);
}

// 额外查询 API（给 UI 用，可选）
extern "C" JNIEXPORT jboolean JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeIsOnline(
        JNIEnv* env, jobject thiz) {
#if CZVPN_HAS_LIBZT
    return (jboolean)(g_started.load() && zts_node_is_online());
#else
    return (jboolean)g_networkJoined.load();
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeGetNodeId(
        JNIEnv* env, jobject thiz) {
    return g_nodeId.load();
}
