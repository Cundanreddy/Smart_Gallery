#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <sstream>
#include <iomanip>

using std::vector;
using std::string;

static inline int argb_to_gray(int argb) {
    int r = (argb >> 16) & 0xFF;
    int g = (argb >> 8) & 0xFF;
    int b = argb & 0xFF;
    // standard luminance
    return static_cast<int>(0.299f * r + 0.587f * g + 0.114f * b);
}

// Simple box downsample to target width/height
static vector<int> downsample_to(const int* pixels, int srcW, int srcH, int dstW, int dstH) {
    vector<int> out(dstW * dstH);
    double xRatio = static_cast<double>(srcW) / dstW;
    double yRatio = static_cast<double>(srcH) / dstH;
    for (int j = 0; j < dstH; ++j) {
        int y0 = static_cast<int>(j * yRatio);
        for (int i = 0; i < dstW; ++i) {
            int x0 = static_cast<int>(i * xRatio);
            int sum = 0;
            int count = 0;
            // sample a small neighborhood to reduce aliasing (2x2)
            for (int yy = y0; yy < std::min(srcH, y0 + (int)std::ceil(yRatio)); ++yy) {
                for (int xx = x0; xx < std::min(srcW, x0 + (int)std::ceil(xRatio)); ++xx) {
                    int gray = argb_to_gray(pixels[yy * srcW + xx]);
                    sum += gray;
                    count++;
                }
            }
            out[j * dstW + i] = (count > 0) ? (sum / count) : 0;
        }
    }
    return out;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_smartgallery_NativeBridge_nativeDHash(JNIEnv *env, jobject /* this */,
                                                       jintArray jpixels, jint width, jint height) {
    // TODO: implement nativeDHash()

    if (jpixels == nullptr) return env->NewStringUTF("");

    jint *pixels = env->GetIntArrayElements(jpixels, nullptr);
    if (pixels == nullptr) return env->NewStringUTF("");

    // Downsample to 9 x 8 for dHash (so we can compare adjacent columns)
    const int dhashW = 9;
    const int dhashH = 8;
    vector<int> small = downsample_to(pixels, width, height, dhashW, dhashH);

    // compute bits: compare left vs right pixels across rows
    unsigned long long hash = 0ULL;
    int bitIndex = 0;
    for (int y = 0; y < dhashH; ++y) {
        for (int x = 0; x < dhashW - 1; ++x) {
            int left = small[y * dhashW + x];
            int right = small[y * dhashW + (x + 1)];
            if (left > right) {
                hash |= (1ULL << bitIndex);
            }
            bitIndex++;
        }
    }

    env->ReleaseIntArrayElements(jpixels, pixels, JNI_ABORT);

    // convert 64-bit hash to hex string (16 hex chars)
    std::ostringstream oss;
    oss << std::hex << std::setw(16) << std::setfill('0') << hash;
    std::string s = oss.str();
    return env->NewStringUTF(s.c_str());
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_smartgallery_NativeBridge_nativeIsBlurry(JNIEnv *env, jobject /* this */,
                                                          jintArray jpixels, jint width, jint height, jfloat jthreshold) {
    if (jpixels == nullptr) return JNI_FALSE;
    jint *pixels = env->GetIntArrayElements(jpixels, nullptr);
    if (pixels == nullptr) return JNI_FALSE;

    // Downsample to small fixed size to speed processing (e.g., 200x200 or smaller)
    const int dstW = 200;
    const int dstH = 200;
    int useW = std::min(width, dstW);
    int useH = std::min(height, dstH);

    vector<int> small = downsample_to(pixels, width, height, useW, useH);

    // compute Laplacian variance approximation using simple 3x3 kernel on grayscale values
    double sum = 0.0;
    int count = 0;
    for (int y = 1; y < useH - 1; ++y) {
        for (int x = 1; x < useW - 1; ++x) {
            int center = small[y * useW + x];
            int left = small[y * useW + (x - 1)];
            int right = small[y * useW + (x + 1)];
            int up = small[(y - 1) * useW + x];
            int down = small[(y + 1) * useW + x];
            int lap = (left + right + up + down) - 4 * center; // approximate Laplacian
            double val = lap * lap;
            sum += val;
            count++;
        }
    }
    env->ReleaseIntArrayElements(jpixels, pixels, JNI_ABORT);

    double variance = (count > 0) ? (sum / count) : 0.0;
    double threshold = static_cast<double>(jthreshold);
    // return true if blurry (i.e., variance less than threshold)
    return (variance < threshold) ? JNI_TRUE : JNI_FALSE;
}
