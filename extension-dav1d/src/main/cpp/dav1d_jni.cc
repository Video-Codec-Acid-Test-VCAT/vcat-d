// extension-dav1d/src/main/cpp/dav1d_jni.cc
//
// Minimal JNI bridge for dav1d with a scalar I420 -> RGBA converter.
// Fixes the blue shift by writing RGBA (R,G,B,A) into WINDOW_FORMAT_RGBA_8888 buffers.

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>

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

/**
 * Extract version components from the value returned by
 * dav1d_version_int()
 */
#define DAV1D_API_MAJOR(v) (((v) >> 16) & 0xFF)
#define DAV1D_API_MINOR(v) (((v) >>  8) & 0xFF)
#define DAV1D_API_PATCH(v) (((v) >>  0) & 0xFF)

// Android YUV format. See:
// https://developer.android.com/reference/android/graphics/ImageFormat.html#YV12.
const int kImageFormatYV12 = 0x32315659;

constexpr int AlignTo16(int value) { return (value + 15) & (~15); }

void CopyPlane(const uint8_t* source, int source_stride, uint8_t* destination,
               int destination_stride, int width, int height) {
    while (height--) {
        std::memcpy(destination, source, width);
        //std::memset(destination, 0, width);
        source += source_stride;
        destination += destination_stride;
    }
}

// --------------------------- Helpers ---------------------------

static inline uint8_t clamp8(int v) {
    if (v < 0) return 0;
    if (v > 255) return 255;
    return static_cast<uint8_t>(v);
}

// Simple (not optimized) I420 → RGBA8888 (writes R,G,B,A)
static void I420ToRGBA8888(
        uint8_t* dst, int dstStridePixels, // stride in pixels
        const uint8_t* y, int yStride,
        const uint8_t* u, const uint8_t* v, int uvStride,
        int w, int h, int bpc /*8 or 10/12/16*/) {

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

                // BT.601-ish coefficients (good enough for now)
                int C = 298 * Y;
                int R = (C + 409 * V + 128) >> 8;
                int G = (C - 100 * U - 208 * V + 128) >> 8;
                int B = (C + 516 * U + 128) >> 8;

                uint8_t* px = drow + i * 4;
                // WRITE RGBA (fixes previous BGR swap)
                px[0] = clamp8(R);
                px[1] = clamp8(G);
                px[2] = clamp8(B);
                px[3] = 255;
            }
        }
        return;
    }

    // Fallback for >8bpc: downshift to 8-bit then same math.
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

// --------------------------- Native state ---------------------------

struct InputNode {
    Dav1dData data; // owns its buffer via dav1d_data_create()
};

struct PictureHolder {
    Dav1dPicture pic; // must be unref'd with dav1d_picture_unref()
};

struct NativeCtx {
    Dav1dContext* c = nullptr;
    std::deque<InputNode*> pending; // queue of inputs not yet accepted by dav1d
};

static void release_all_pending(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        InputNode* n = ctx->pending.front();
        ctx->pending.pop_front();
        dav1d_data_unref(&n->data);
        delete n;
    }
}

static void flush_pending_to_decoder(NativeCtx* ctx) {
    while (!ctx->pending.empty()) {
        InputNode* n = ctx->pending.front();
        int rc = dav1d_send_data(ctx->c, &n->data);
        if (rc == 0) {
            // dav1d now owns the buffer; do NOT unref here.
            ctx->pending.pop_front();
            delete n;
        } else if (rc == -EAGAIN) {
            // Decoder wants us to drain pictures first.
            break;
        } else {
            LOGE("dav1d_send_data fatal: %d (dropping packet)", rc);
            // Drop: free our buffer.
            dav1d_data_unref(&n->data);
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
    s.n_threads = frameThreads > 0 ? frameThreads : 1;

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
    delete ctx;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeQueueInput(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
        jobject byteBuffer, jint offset, jint size, jlong ptsUs) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->c || !byteBuffer || size <= 0) return -22; // EINVAL

    uint8_t* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!src) {
        LOGE("Input buffer is not a direct ByteBuffer");
        return -22;
    }
    src += offset;

    auto* node = new InputNode();

    // dav1d allocates and returns a writable pointer.
    uint8_t* dst = dav1d_data_create(&node->data, static_cast<size_t>(size));
    if (!dst) {
        LOGE("dav1d_data_create returned null");
        delete node;
        return -12; // -ENOMEM
    }

    std::memcpy(dst, src, static_cast<size_t>(size));
    node->data.m.timestamp = static_cast<int64_t>(ptsUs);

    ctx->pending.push_back(node);
    flush_pending_to_decoder(ctx);
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeDequeueFrame(
        JNIEnv* env, jclass /*clazz*/, jlong handle,
        jintArray outWH, jlongArray outPtsUs) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    if (!ctx || !ctx->c) return 0;

    // Try to feed any pending packets first (handles prior EAGAIN).
    flush_pending_to_decoder(ctx);

    Dav1dPicture pic;
    std::memset(&pic, 0, sizeof(pic));
    int rc = dav1d_get_picture(ctx->c, &pic);
    if (rc == -EAGAIN) {
        return 0; // no frame available yet
    }
    if (rc < 0) {
        LOGE("dav1d_get_picture failed: %d", rc);
        return 0;
    }

    // Prepare holder to hand ownership to Java side.
    auto* hold = new PictureHolder();
    hold->pic = pic; // shallow copy; we now own this reference

    // Fill outs
    jint wh[2];
    wh[0] = static_cast<jint>(pic.p.w);
    wh[1] = static_cast<jint>(pic.p.h);
    env->SetIntArrayRegion(outWH, 0, 2, wh);

    jlong pts[1];
    pts[0] = static_cast<jlong>(pic.m.timestamp);
    env->SetLongArrayRegion(outPtsUs, 0, 1, pts);

    return reinterpret_cast<jlong>(hold);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_roncatech_extension_1dav1d_NativeDav1d_nativeRenderToSurface(
        JNIEnv* env, jclass /*clazz*/, jlong handle, jlong nativePic, jobject surface) {
    auto* ctx = reinterpret_cast<NativeCtx*>(handle);
    auto* hold = reinterpret_cast<PictureHolder*>(nativePic);
    if (!ctx || !ctx->c || !hold || !surface) return -22;

    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) {
        LOGE("ANativeWindow_fromSurface failed");
        return -1;
    }

    const Dav1dPicture& pic = hold->pic;
    const int w = pic.p.w;
    const int h = pic.p.h;

    // Use YV12 window buffers.
    ANativeWindow_setBuffersGeometry(win, w, h, kImageFormatYV12);

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, nullptr) != 0) {
        LOGE("ANativeWindow_lock failed");
        ANativeWindow_release(win);
        return -1;
    }

    uint8_t* dst = static_cast<uint8_t*>(buf.bits);
    const int dstStridePixels = buf.stride; // pixels

    // Source planes/strides
    const int bpc = pic.p.bpc; // 8 or 10/12
    const int layout = pic.p.layout; // Dav1dPixelLayout
    const uint8_t* y = static_cast<const uint8_t*>(pic.data[0]);
    const uint8_t* u = static_cast<const uint8_t*>(pic.data[1]);
    const uint8_t* v = static_cast<const uint8_t*>(pic.data[2]);

    const int yStride  = static_cast<int>(pic.stride[0]);
    const int uvStride = static_cast<int>(pic.stride[1]);

    // Y plane
    CopyPlane(y, yStride,
              dst,
              dstStridePixels,
              w,
              h);

    const int y_plane_size = dstStridePixels * h;

    const int32_t native_window_buffer_uv_height = (h + 1) / 2;

    const int native_window_buffer_uv_stride = AlignTo16(dstStridePixels / 2);

    // V plane
    // Since the format for ANativeWindow is YV12, V plane is being processed
    // before U plane.
    const int v_plane_height = std::min(native_window_buffer_uv_height,
                                        (h / 2));

    CopyPlane(
            v, uvStride,
            dst + y_plane_size,
            native_window_buffer_uv_stride,
            w / 2,
            v_plane_height);

    const int v_plane_size = v_plane_height * native_window_buffer_uv_stride;

    // U plane
    CopyPlane(u, uvStride,
              dst +
              y_plane_size + v_plane_size,
              native_window_buffer_uv_stride,
              w / 2,
              std::min(native_window_buffer_uv_height,
                       (h / 2)));

#if 0
    // Implement correct 4:2:0; other layouts fallback to treating as 4:2:0.
    I420ToRGBA8888(
            dst, dstStridePixels,
            y, yStride,
            u, v, uvStride,
            w, h, bpc);
#endif

    ANativeWindow_unlockAndPost(win);
    ANativeWindow_release(win);
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
Java_com_roncatech_extension_1dav1d_NativeDav1d_dav1dGetVersion(JNIEnv* env)
{
    const unsigned version = dav1d_version_api();
    const int major = DAV1D_API_MAJOR(version);
    const int minor = DAV1D_API_MINOR(version);
    const int patch = DAV1D_API_PATCH(version);

    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%d.%d.%d", major, minor, patch);
    return env->NewStringUTF(buffer);
}
