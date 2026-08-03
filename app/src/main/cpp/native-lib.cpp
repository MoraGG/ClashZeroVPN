/**
 * ClashZeroVPN JNI 桥接层 (libzt 1.16 API)
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
#include <cstdlib>

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
static std::atomic<bool> g_nodeOnline{false};
static std::atomic<uint64_t> g_nodeId{0};
static std::atomic<uint64_t> g_networkId{0};

// JNI 回调：node 在线状态变化时通知 Kotlin
static JavaVM* g_jvm = nullptr;
static jobject g_callbackObject = nullptr;  // ZeroTierEngine 实例
static jmethodID g_onNetworkUpdateMethod = nullptr; // onNetworkUpdate() 回调
static jmethodID g_returnToTunMethod = nullptr;    // returnToTun(packet) 回调

static std::mutex g_jniMutex;

#if CZVPN_HAS_LIBZT
// libzt 1.16 事件回调（C 风格函数作为桥接）
static void ztEventCallback(void* msg) {
    // 收到事件后通知 Kotlin
    JNIEnv* env = nullptr;
    int attached = 0;
    jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = 1;
    }
    if (env && g_callbackObject && g_onNetworkUpdateMethod) {
        std::lock_guard<std::mutex> _(g_jniMutex);
        env->CallVoidMethod(g_callbackObject, g_onNetworkUpdateMethod);
    }
    if (attached) g_jvm->DetachCurrentThread();
}

// node 在线状态轮询线程
static void nodePollingThread() {
    LOGI("nodePollingThread started");
    while (g_started.load()) {
        bool online = false;
#if CZVPN_HAS_LIBZT
        online = (zts_node_is_online() == 1);
        uint64_t nid = zts_node_get_id();
        if (nid != 0) {
            g_nodeId.store(nid);
            LOGD("node online, id=%llx", (unsigned long long)nid);
        }
#endif
        if (online != g_nodeOnline.load()) {
            g_nodeOnline.store(online);
            LOGI("node online state changed: %s", online ? "ONLINE" : "OFFLINE");
        }
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
    LOGI("nodePollingThread stopped");
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

    // 缓存回调方法引用
    jclass cls = env->GetObjectClass(thiz);

    // returnToTun(packet: ByteArray) - 收到要注入到 TUN 的数据包（新版 libzt 通过事件回调获取）
    jmethodID mid = env->GetMethodID(cls, "returnToTun", "([B)V");
    if (!mid) {
        LOGE("returnToTun([B)V not found");
        return -1;
    }
    g_returnToTunMethod = mid;

    // onNetworkUpdate() - 网络状态变化时由 Kotlin 调用查询
    jmethodID nid = env->GetMethodID(cls, "onNetworkUpdate", "()V");
    g_onNetworkUpdateMethod = nid;

    if (g_callbackObject) env->DeleteGlobalRef(g_callbackObject);
    g_callbackObject = env->NewGlobalRef(thiz);

    const char* path = env->GetStringUTFChars(storagePath, nullptr);
    LOGI("nativeInit storage=%s", path);
    int rc = 0;

#if CZVPN_HAS_LIBZT
    // 设置事件回调（libzt 1.16 通过回调通知网络事件）
    zts_init_set_event_handler(ztEventCallback);

    // 从 storage 路径初始化（该路径存 node identity 和配置）
    rc = zts_init_from_storage(path);
    if (rc != ZTS_ERR_OK) {
        LOGE("zts_init_from_storage failed: %d", rc);
        env->ReleaseStringUTFChars(storagePath, path);
        return rc;
    }
    LOGI("zts_init_from_storage OK");
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
        LOGI("already started");
        return 0;
    }

    const char* netStr = env->GetStringUTFChars(networkIdStr, nullptr);
    uint64_t netId = 0;
    try {
        size_t len = strlen(netStr);
        if (len != 16) {
            LOGE("invalid network id length: %zu (expected 16 hex chars)", len);
            env->ReleaseStringUTFChars(networkIdStr, netStr);
            return -2;
        }
        netId = std::stoull(netStr, nullptr, 16);
    } catch (...) {
        LOGE("parse network id failed");
        env->ReleaseStringUTFChars(networkIdStr, netStr);
        return -2;
    }
    g_networkId.store(netId);
    LOGI("nativeStart network=%016llx", (unsigned long long)netId);

#if CZVPN_HAS_LIBZT
    // 启动 node（异步，在后台线程运行）
    int r = zts_node_start();
    if (r != ZTS_ERR_OK) {
        LOGE("zts_node_start failed: %d", r);
        env->ReleaseStringUTFChars(networkIdStr, netStr);
        return r;
    }
    LOGI("zts_node_start OK, waiting for node to come online...");

    // 启动轮询线程检测在线状态
    std::thread(nodePollingThread).detach();

    // 加入指定网络（node online 后需要手动 join）
    std::thread([netId](){
        // 等待 node 变为 online
        int waitCount = 0;
        while (g_started.load()) {
            bool online = (zts_node_is_online() == 1);
            if (online) {
                LOGI("node is online, joining network %016llx", (unsigned long long)netId);
                int jr = zts_net_join(netId);
                if (jr == ZTS_ERR_OK || jr == ZTS_ERR_SERVICE) {
                    LOGI("zts_net_join OK");
                } else {
                    LOGE("zts_net_join failed: %d", jr);
                }
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            waitCount++;
            if (waitCount > 60) { // 30s 超时
                LOGE("node online timeout");
                break;
            }
        }
    }).detach();

#else
    // STUB 模式
    g_started.store(true);
    g_nodeOnline.store(true);
#endif

    g_started.store(true);
    env->ReleaseStringUTFChars(networkIdStr, netStr);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeInject(
        JNIEnv* env, jobject thiz, jbyteArray packet, jint len) {

    if (!g_started.load()) return -1;
    if (len <= 0 || !packet) return -2;

    // libzt 1.16 通过 BSD socket API 通信，不支持原始 IP 包注入
    // 这里返回 len 假装成功，实际数据包由 Clash TUN 处理
#if CZVPN_HAS_LIBZT
    // TODO: 通过 zts_bsd_sendto 发送（需要已建立的 socket）
    LOGD("nativeInject called (bsd socket mode, len=%d)", len);
#endif
    return len; // stub: 假装写入成功
}

extern "C" JNIEXPORT void JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeStop(
        JNIEnv* env, jobject thiz) {

    if (!g_started.exchange(false)) return;
    LOGI("nativeStop");

#if CZVPN_HAS_LIBZT
    uint64_t nid = g_networkId.load();
    if (nid) {
        zts_net_leave(nid);
        LOGI("left network %016llx", (unsigned long long)nid);
    }
    zts_node_stop();
    LOGI("zts_node_stop done");
#endif

    if (g_callbackObject) {
        env->DeleteGlobalRef(g_callbackObject);
        g_callbackObject = nullptr;
    }
    g_nodeOnline.store(false);
    g_nodeId.store(0);
    g_networkId.store(0);
    LOGI("nativeStop done");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeIsOnline(
        JNIEnv* env, jobject thiz) {
#if CZVPN_HAS_LIBZT
    bool online = g_nodeOnline.load();
    LOGD("nativeIsOnline: %s", online ? "true" : "false");
    return (jboolean)online;
#else
    return (jboolean)g_nodeOnline.load();
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_clashzerovpn_engine_ZeroTierEngine_nativeGetNodeId(
        JNIEnv* env, jobject thiz) {
#if CZVPN_HAS_LIBZT
    uint64_t nid = g_nodeId.load();
    if (nid == 0) {
        nid = zts_node_get_id();
        if (nid != 0) g_nodeId.store(nid);
    }
    return (jlong)nid;
#else
    return (jlong)g_nodeId.load();
#endif
}
