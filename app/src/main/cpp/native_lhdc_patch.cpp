#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <sys/mman.h>

extern "C" JNIEXPORT jint JNICALL
Java_xyz_melodylsp_codec_system_NativeLhdcMemoryPatch_nativeMprotect(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jlong address,
        jlong length,
        jint prot) {
    void* page = reinterpret_cast<void*>(static_cast<uintptr_t>(address));
    int rc = mprotect(page, static_cast<size_t>(length), prot);
    if (rc == 0) {
        return 0;
    }
    return errno != 0 ? -errno : -1;
}
