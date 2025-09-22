// extension-dav1d/src/main/cpp/dav1d_jni.cc
//
// Minimal JNI bridge for dav1d with a scalar I420 -> RGBA converter.
// Writes RGBA (R,G,B,A) into WINDOW_FORMAT_RGBA_8888 buffers and surfaces
// fatal decoder errors to Java to avoid sticky stalls.

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <algorithm>
#include <errno.h>

extern "C" {
#include "dav1d/dav1d.h"
#include "dav1d/data.h"
#include "dav1d/picture.h"
}

#define LOG_TAG "dav1d_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN , LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO , LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/** dav1d_version_int() helpers */
#define DAV1D_API_MAJOR(v) (((v) >> 16) & 0xFF)
#define DAV1D_API_MINOR(v) (((v) >>  8) & 0xFF)
#define DAV1D_API_PATCH(v) (((v) >>  0) & 0xFF)

static constexpr size_t kMaxPendingPackets = 16; // capacity guard (tune as needed)

struct InputNode {
    Dav1dData data;        // dav1d takes ownership when dav1d_send_data == 0
    int64_t pts_us = -1;
    InputNode() = default;
    InputNode(const InputNode&) = delete;
    InputNode& operator=(const InputNode&) = delete;
};

struct NativeCtx {
    Dav1dContext* c = nullptr;
    std::deque<InputNode*> pending;

    // Counters/telemetry
    uint32_t pkts_in_total = 0;
    uint32_t pkts_send_ok = 0;
    uint32_t pkts_send_eagain = 0;
    uint32_t pkts_send_err = 0;
    uint32_t pics_out = 0;
    uint32_t pics_eagain = 0;
    uint32_t dropped_at_flush = 0;

    int64_t last_in_pts  = -1;  // us
    int64_t last_out_pts = -1;  // us

    // Simple stats
    int num_frames_decoded   = 0; // accepted by dav1d_send_data
    int num_frames_displayed = 0; // nativeRenderToSurface posted
    int num_frames_not_decoded = 0; // dropped before send
};

static inline uint8_t clamp8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return static_cast<uint8_t>(v);
}

// I420 → RGBA8888 (writes R,G,B,A)
static void I420ToRGBA8888(
        uint8_t* dst, int dstStridePixels, // stride in pixels
        const uint8_t* y, int yStride,
        const uint8_t* u, const uint8_t* v, int uvStride,
        int w, int h, int bpc) {

    const int dstStrideBytes = dstStridePixels * 4;

    if (bpc == 8) {
        for (int j = 0; j < h; ++j) {
            uint8_t* drow = dst + j * dstStrideBytes;
            const uint8_t* yrow = y + j * yStride;
            const uint8_t* urow = u + (j >> 1) * uvStride;
            const uint8_t* vrow = v + (j >> 1) * uvStride;
            for (int i = 0; i < w; ++i) {
                int Y = int(yrow[i]) - 16; if (Y < 0) Y = 0;
                int U = int(urow[i >> 1]) - 128;
                int V = int(vrow[i >> 1]) - 128;
                int C = 298 * Y;
                int R = (C + 409 * V + 128) >> 8;
                int G = (C - 100 * U - 208 * V + 128) >> 8;
                int B = (C + 516 * U + 128) >> 8;
                uint8_t* px = drow + i * 4;
                px[0] = clamp8(R);
                px[1] = clamp8(G);
                px[2] = clamp8(B);
                px[3] = 255;
            }
        }
        return;
    }

    // >8bpc fallback: downshift to 8-bit then same math
    const int shift = (bpc >= 10) ? (bpc - 8) : 0;
    for (int j = 0; j < h; ++j) {
        uint8_t* drow = dst + j * dstStrideBytes;
        const uint16_t* yrow = reinterpret_cast<const uint16_t*>(y + j * yStride);
        const uint16_t* urow = reinterpret_cast<const uint16_t*>(u + (j >> 1) * uvStride);
        const uint16_t* vrow = reinterpret_cast<const uint16_t*>(v + (j >> 1) * uvStride);
        for (int i = 0; i < w; ++i) {
            int Y = (int(yrow[i]) >> shift) - 16; if (Y < 0) Y = 0;
            int U = (int(urow[i >> 1]) >> shift) - 128;
            int V = (int(vrow[i >> 1]) >> shift) - 128;
            int C = 298 * Y;
            int R = (C + 409 * V + 128) >> 8;
            int G = (C - 100 * U - 208 * V + 128) >> 8;
            int B = (C + 516 * U + 128) >> 8;
            uint8_t* px = drow + i * 4;
            px[0] = clamp8(R);
            px[1] = clamp8(G);
            px[2] = clamp8(B);
            px[3] = 255;
        }
    }
}

struct PictureHolder {
    Dav1dPicture pic; // must be unref'd with dav1d_picture_unref()
};

// ------------------- Pending queue helpers -------------------

static void release_all_pending(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        InputNode* n = ctx->pending.front();
        ctx->pending.pop_front();
        dav1d_data_unref(&n->data);
        delete n;
        ctx->num_frames_not_decoded++;
    }
}

static void flush_pending_to_decoder(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        InputNode* n = ctx->pending.front();
        int rc = dav1d_send_data(ctx->c, &n->data);
        if (rc == 0) {
            ctx->pkts_send_ok++;
            ctx->num_frames_decoded++;
            ctx->last_in_pts = n->data.m.timestamp;
            ctx->pending.pop_front();     // dav1d now owns the data
            delete n;                     // do not dav1d_data_unref here
        } else if (rc == -EAGAIN) {
            ctx->pkts_send_eagain++;
            break; // need to drain pictures first
        } else {
            ctx->pkts_send_err++;
            LOGE("dav1d_send_data fatal: %d (dropping packet)", rc);
            dav1d_data_unref(&n->data);   // drop & free
            ctx->pending.pop_front();
            delete n;
        }
    }
}

// --------------------------- JNI API ---------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeCreate(
        JNIEnv* /*env*/, jclass /*clazz*/, jint frameThreads, jint /*tileThreads*/) {
    auto* ctx = new NativeCtx();

    Dav1dSettings s;
    dav1d_default_settings(&s);
    s.n_threads = (frameThreads > 0) ? frameThreads : 1;

    int rc = dav1d_open(&ctx->c, &s);
    if (rc != 0) {
        LOGE("dav1d_open failed: %d", rc);
        delete ctx;
        return 0;
    }
    LOGI("dav1d created (threads=%d)", s.n_threads);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeFlush(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx) return;
    ctx->dropped_at_flush += static_cast<uint32_t>(ctx->pending.size());
    release_all_pending(ctx);
    dav1d_flush(ctx->c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeClose(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx) return;
    release_all_pending(ctx);
    if (ctx->c) {
        dav1d_close(&ctx->c);
        ctx->c = nullptr;
    }
    LOGD("CLOSE stats: decoded=%d displayed=%d not_decoded=%d send_ok=%u eagain=%u err=%u pics_out=%u dropped_at_flush=%u",
         ctx->num_frames_decoded, ctx->num_frames_displayed, ctx->num_frames_not_decoded,
         ctx->pkts_send_ok, ctx->pkts_send_eagain, ctx->pkts_send_err, ctx->pics_out, ctx->dropped_at_flush);
    delete ctx;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeQueueInput(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
        jobject byteBuffer, jint offset, jint size, jlong ptsUs) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->c || !byteBuffer || size <= 0) return -EINVAL;

    // Capacity guard (soft); Java will usually check nativeHasCapacity first.
    if (ctx->pending.size() >= kMaxPendingPackets) {
        return -EAGAIN;
    }

    uint8_t* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!src) {
        LOGE("Input buffer is not a direct ByteBuffer");
        return -EINVAL;
    }
    src += offset;

    auto* node = new InputNode();
    uint8_t* dst = dav1d_data_create(&node->data, static_cast<size_t>(size));
    if (!dst) {
        LOGE("dav1d_data_create returned null");
        delete node;
        return -ENOMEM;
    }

    std::memcpy(dst, src, static_cast<size_t>(size));
    node->data.m.timestamp = static_cast<int64_t>(ptsUs);
    node->pts_us = static_cast<int64_t>(ptsUs);

    ctx->pkts_in_total++;
    ctx->pending.push_back(node);

    // Try to feed immediately (non-blocking)
    flush_pending_to_decoder(ctx);
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeHasCapacity(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->c) return JNI_FALSE;
    return (ctx->pending.size() < kMaxPendingPackets) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeDequeueFrame(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
        jintArray outWH, jlongArray outPtsUs) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->c) return 0;

    // Always attempt to feed pending before draining pictures.
    flush_pending_to_decoder(ctx);

    Dav1dPicture pic;
    std::memset(&pic, 0, sizeof(pic));
    int rc = dav1d_get_picture(ctx->c, &pic);
    if (rc == -EAGAIN) {
        ctx->pics_eagain++;
        return 0; // no frame available yet
    }
    if (rc < 0) {
        // *** Propagate fatal to Java via sentinel ***
        LOGE("dav1d_get_picture failed: %d", rc);
        if (outWH && env->GetArrayLength(outWH) >= 2) {
            jint wh_err[2] = { -1, rc }; // wh[0] == -1 => fatal; wh[1] carries rc
            env->SetIntArrayRegion(outWH, 0, 2, wh_err);
        }
        return 0; // handle==0 but flagged fatal through outWH
    }

    // Success
    auto* hold = new PictureHolder();
    hold->pic = pic; // we now own this reference

    jint wh[2] = { static_cast<jint>(pic.p.w), static_cast<jint>(pic.p.h) };
    env->SetIntArrayRegion(outWH, 0, 2, wh);

    jlong pts[1] = { static_cast<jlong>(pic.m.timestamp) };
    env->SetLongArrayRegion(outPtsUs, 0, 1, pts);

    ctx->pics_out++;
    ctx->last_out_pts = pic.m.timestamp;

    return reinterpret_cast<jlong>(hold);
}

static inline int alignTo16(int v){ return (v + 15) & ~15; }

static inline void copyPlanePad(const uint8_t* src, int srcStrideBytes,
                                uint8_t* dst, int dstStrideBytes,
                                int rowBytes, int rows) {
    for (int j = 0; j < rows; ++j) {
        memcpy(dst + j*dstStrideBytes, src + j*srcStrideBytes, rowBytes);
        if (dstStrideBytes > rowBytes) {
            memset(dst + j*dstStrideBytes + rowBytes, 0, dstStrideBytes - rowBytes);
        }
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeRenderToSurface(
        JNIEnv* env, jclass, jlong handle, jlong nativePic, jobject surface) {

    auto* ctx  = reinterpret_cast<NativeCtx*>(handle);
    auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
    if (!ctx || !ctx->c || !hold || !surface) return -EINVAL;

    const Dav1dPicture& pic = hold->pic;
    const int w = pic.p.w;
    const int h = pic.p.h;

    // Only support 8-bit 4:2:0 in this YV12 fast path.
    if (pic.p.bpc != 8 || pic.p.layout != DAV1D_PIXEL_LAYOUT_I420) {
        return -ENOSYS;
    }

    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return -1;

    // Cache geometry (per-process). If you can render to multiple surfaces, move this cache.
    static int lastW = 0, lastH = 0;
    if (w != lastW || h != lastH) {
        const int kYV12 = 0x32315659; // 'YV12' (HAL_PIXEL_FORMAT_YV12)
#ifdef HAL_PIXEL_FORMAT_YV12
        ANativeWindow_setBuffersGeometry(win, w, h, HAL_PIXEL_FORMAT_YV12);
#else
        ANativeWindow_setBuffersGeometry(win, w, h, kYV12);
#endif
        lastW = w; lastH = h;
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, nullptr) != 0) {
        ANativeWindow_release(win);
        return -1;
    }

    // Dest strides (bytes). In YV12, Y stride = buf.stride (1B/sample).
    uint8_t* dstY = static_cast<uint8_t*>(buf.bits);
    const int dstYStrideBytes  = buf.stride;
    const int dstUVStrideBytes = alignTo16(dstYStrideBytes >> 1);

    // Dest plane pointers (Y, then V, then U for YV12)
    uint8_t* dstV = dstY + dstYStrideBytes * h;
    const int uvH = (h + 1) / 2;
    const int uvW = (w + 1) / 2;
    uint8_t* dstU = dstV + dstUVStrideBytes * uvH;

    // Sources
    const uint8_t* srcY = static_cast<const uint8_t*>(pic.data[0]);
    const uint8_t* srcU = static_cast<const uint8_t*>(pic.data[1]);
    const uint8_t* srcV = static_cast<const uint8_t*>(pic.data[2]);
    const int srcYStride  = static_cast<int>(pic.stride[0]);
    const int srcUVStride = static_cast<int>(pic.stride[1]);

    // Copy with padding zeroed
    copyPlanePad(srcY, srcYStride,  dstY, dstYStrideBytes,  w,   h);
    copyPlanePad(srcV, srcUVStride, dstV, dstUVStrideBytes, uvW, uvH); // YV12: V first
    copyPlanePad(srcU, srcUVStride, dstU, dstUVStrideBytes, uvW, uvH);

    ANativeWindow_unlockAndPost(win);
    ANativeWindow_release(win);

    ctx->num_frames_displayed++;
    return 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeReleasePicture(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong /*handle*/, jlong nativePic) {
    auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
    if (!hold) return;
    dav1d_picture_unref(&hold->pic);
    delete hold;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_dav1dGetVersion(JNIEnv* env) {
    const unsigned version = dav1d_version_api();
    const int major = DAV1D_API_MAJOR(version);
    const int minor = DAV1D_API_MINOR(version);
    const int patch = DAV1D_API_PATCH(version);
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%d.%d.%d", major, minor, patch);
    return env->NewStringUTF(buffer);
}
