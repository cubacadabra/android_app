#include <jni.h>
#include <stdint.h>
#include <limits.h>
#include "../../../../../rust/include/cubacadabra_app.h"

#define APP_JNI(name) Java_dev_andrewarrow_cubacadabra_nativebridge_NativeEngine_##name

static CubacadabraApp *app(jlong handle) { return (CubacadabraApp *)(intptr_t)handle; }

static jbyteArray output(JNIEnv *env, CubacadabraApp *runtime) {
    uintptr_t length = cubacadabra_app_output_len(runtime);
    if (length > INT_MAX) return NULL;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (bytes && length) {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length,
            (const jbyte *)cubacadabra_app_output_ptr(runtime));
    }
    return bytes;
}

JNIEXPORT jlong JNICALL APP_JNI(nativeAppCreate)(JNIEnv *env, jobject object) {
    (void)env; (void)object;
    return (jlong)(intptr_t)cubacadabra_app_create();
}

JNIEXPORT void JNICALL APP_JNI(nativeAppDestroy)(JNIEnv *env, jobject object, jlong handle) {
    (void)env; (void)object;
    cubacadabra_app_destroy(app(handle));
}

JNIEXPORT jboolean JNICALL APP_JNI(nativeAppDispatch)(JNIEnv *env, jobject object, jlong handle, jbyteArray action) {
    (void)object;
    if (!action) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, action);
    jbyte *bytes = (*env)->GetByteArrayElements(env, action, NULL);
    if (!bytes) return JNI_FALSE;
    uint8_t accepted = cubacadabra_app_dispatch_json(app(handle), (const uint8_t *)bytes, (uintptr_t)length);
    (*env)->ReleaseByteArrayElements(env, action, bytes, JNI_ABORT);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL APP_JNI(nativeAppSnapshot)(JNIEnv *env, jobject object, jlong handle) {
    (void)object;
    return cubacadabra_app_snapshot_json(app(handle)) ? output(env, app(handle)) : NULL;
}

JNIEXPORT jbyteArray JNICALL APP_JNI(nativeAppPollEffect)(JNIEnv *env, jobject object, jlong handle) {
    (void)object;
    return cubacadabra_app_poll_effect_json(app(handle)) ? output(env, app(handle)) : NULL;
}
