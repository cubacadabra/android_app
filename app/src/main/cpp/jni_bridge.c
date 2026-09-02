#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct CubacadabraEngine CubacadabraEngine;
typedef struct CubacadabraRenderer CubacadabraRenderer;

extern CubacadabraEngine *engine_create(void);
extern void engine_destroy(CubacadabraEngine *engine);
extern uint8_t *engine_script_buffer_ptr(CubacadabraEngine *engine, uintptr_t length);
extern uint8_t engine_load_script_buffer(CubacadabraEngine *engine);
extern uint8_t *engine_package_buffer_ptr(CubacadabraEngine *engine, uintptr_t length);
extern uint8_t engine_load_package_buffer(CubacadabraEngine *engine);
extern uint8_t *engine_username_buffer_ptr(CubacadabraEngine *engine, uintptr_t length);
extern uint8_t engine_load_username_buffer(CubacadabraEngine *engine);
extern void engine_set_input(CubacadabraEngine *, float, float, uint8_t, uint8_t, float, float, float);
extern void engine_step(CubacadabraEngine *, float);
extern const float *engine_snapshot_ptr(const CubacadabraEngine *engine);
extern uintptr_t engine_snapshot_len(void);
extern uintptr_t engine_snapshot_stride(void);
extern uintptr_t engine_agent_count(const CubacadabraEngine *engine);
extern uintptr_t engine_local_agent_count(const CubacadabraEngine *engine);
extern uintptr_t engine_remote_player_count(const CubacadabraEngine *engine);
extern void engine_set_remote_player_count(CubacadabraEngine *, uintptr_t);
extern void engine_set_remote_player(CubacadabraEngine *, uintptr_t, float, float, float, float, uint8_t, uint8_t);
extern uintptr_t engine_launch_pad_count(const CubacadabraEngine *engine);
extern uintptr_t engine_launch_pad_occupants(const CubacadabraEngine *engine, uintptr_t);
extern float engine_launch_pad_seconds(const CubacadabraEngine *engine, uintptr_t);
extern uint8_t engine_launch_pad_phase(const CubacadabraEngine *engine, uintptr_t);
extern uintptr_t engine_active_world(const CubacadabraEngine *engine);
extern uint8_t engine_settings_room_state(const CubacadabraEngine *engine);
extern float engine_camera_yaw(const CubacadabraEngine *engine);
extern float engine_camera_pitch(const CubacadabraEngine *engine);
extern float engine_camera_distance(const CubacadabraEngine *engine);
extern CubacadabraRenderer *engine_renderer_create(void *, float, float);
extern void engine_renderer_resize(CubacadabraRenderer *, float, float);
extern void engine_renderer_sync(CubacadabraRenderer *, const CubacadabraEngine *);
extern void engine_renderer_draw(CubacadabraRenderer *);
extern void engine_renderer_destroy(CubacadabraRenderer *);

typedef struct {
    CubacadabraRenderer *renderer;
    ANativeWindow *window;
} AndroidRenderer;

static CubacadabraEngine *engine(jlong value) { return (CubacadabraEngine *)(intptr_t)value; }

static jlong JNICALL nativeCreate(JNIEnv *env, jclass klass) {
    (void)env; (void)klass;
    return (jlong)(intptr_t)engine_create();
}

static void JNICALL nativeDestroy(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    engine_destroy(engine(value));
}

static jboolean JNICALL nativeLoad(JNIEnv *env, jclass klass, jlong value, jbyteArray bytes, jboolean package) {
    (void)klass;
    jsize length = (*env)->GetArrayLength(env, bytes);
    uint8_t *destination = package
        ? engine_package_buffer_ptr(engine(value), (uintptr_t)length)
        : engine_script_buffer_ptr(engine(value), (uintptr_t)length);
    if (!destination) return 0;
    jbyte *source = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!source) return 0;
    memcpy(destination, source, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, bytes, source, JNI_ABORT);
    return package ? engine_load_package_buffer(engine(value)) : engine_load_script_buffer(engine(value));
}

static void JNICALL nativeSetInput(JNIEnv *env, jclass klass, jlong value, jfloat forward, jfloat strafe,
                                    jboolean sprint, jboolean jump, jfloat lookX, jfloat lookY, jfloat zoom) {
    (void)env; (void)klass;
    engine_set_input(engine(value), forward, strafe, sprint ? 1 : 0, jump ? 1 : 0, lookX, lookY, zoom);
}

static void JNICALL nativeStep(JNIEnv *env, jclass klass, jlong value, jfloat delta) {
    (void)env; (void)klass;
    engine_step(engine(value), delta);
}

static jfloatArray JNICALL nativeReadFrame(JNIEnv *env, jclass klass, jlong value) {
    (void)klass;
    CubacadabraEngine *game = engine(value);
    uintptr_t snapshotLength = engine_snapshot_len();
    uintptr_t pads = engine_launch_pad_count(game);
    jsize length = (jsize)(snapshotLength + 8 + pads * 3);
    jfloatArray result = (*env)->NewFloatArray(env, length);
    if (!result) return NULL;
    jfloat *values = (jfloat *)calloc((size_t)length, sizeof(jfloat));
    if (!values) return result;
    memcpy(values, engine_snapshot_ptr(game), snapshotLength * sizeof(float));
    values[snapshotLength + 0] = (jfloat)engine_agent_count(game);
    values[snapshotLength + 1] = (jfloat)engine_local_agent_count(game);
    values[snapshotLength + 2] = (jfloat)engine_remote_player_count(game);
    values[snapshotLength + 3] = (jfloat)pads;
    values[snapshotLength + 4] = (jfloat)engine_active_world(game);
    values[snapshotLength + 5] = engine_camera_yaw(game);
    values[snapshotLength + 6] = engine_camera_pitch(game);
    values[snapshotLength + 7] = engine_camera_distance(game);
    for (uintptr_t index = 0; index < pads; index++) {
        uintptr_t offset = snapshotLength + 8 + index * 3;
        values[offset] = (jfloat)engine_launch_pad_occupants(game, index);
        values[offset + 1] = engine_launch_pad_seconds(game, index);
        values[offset + 2] = (jfloat)engine_launch_pad_phase(game, index);
    }
    (*env)->SetFloatArrayRegion(env, result, 0, length, values);
    free(values);
    return result;
}

static void JNICALL nativeSetRemotePlayers(JNIEnv *env, jclass klass, jlong value, jfloatArray players) {
    (void)klass;
    jsize length = (*env)->GetArrayLength(env, players);
    jfloat *values = (*env)->GetFloatArrayElements(env, players, NULL);
    if (!values) return;
    uintptr_t count = (uintptr_t)length / 6;
    engine_set_remote_player_count(engine(value), count);
    for (uintptr_t index = 0; index < count; index++) {
        const jfloat *player = values + index * 6;
        engine_set_remote_player(engine(value), index, player[0], player[1], player[2], player[3],
                                 player[4] > 0.5f, player[5] > 0.5f);
    }
    (*env)->ReleaseFloatArrayElements(env, players, values, JNI_ABORT);
}

static jlong JNICALL nativeCreateRenderer(JNIEnv *env, jclass klass, jlong engineValue, jobject surface, jfloat width, jfloat height) {
    (void)klass; (void)engineValue;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;
    CubacadabraRenderer *renderer = engine_renderer_create(window, width, height);
    if (!renderer) {
        ANativeWindow_release(window);
        return 0;
    }
    AndroidRenderer *holder = (AndroidRenderer *)calloc(1, sizeof(AndroidRenderer));
    if (!holder) {
        engine_renderer_destroy(renderer);
        ANativeWindow_release(window);
        return 0;
    }
    holder->renderer = renderer;
    holder->window = window;
    return (jlong)(intptr_t)holder;
}

static void JNICALL nativeResizeRenderer(JNIEnv *env, jclass klass, jlong value, jfloat width, jfloat height) {
    (void)env; (void)klass;
    AndroidRenderer *holder = (AndroidRenderer *)(intptr_t)value;
    if (holder) engine_renderer_resize(holder->renderer, width, height);
}

static void JNICALL nativeDrawRenderer(JNIEnv *env, jclass klass, jlong value, jlong engineValue) {
    (void)env; (void)klass;
    AndroidRenderer *holder = (AndroidRenderer *)(intptr_t)value;
    if (!holder) return;
    engine_renderer_sync(holder->renderer, engine(engineValue));
    engine_renderer_draw(holder->renderer);
}

static void JNICALL nativeDestroyRenderer(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    AndroidRenderer *holder = (AndroidRenderer *)(intptr_t)value;
    if (!holder) return;
    engine_renderer_destroy(holder->renderer);
    ANativeWindow_release(holder->window);
    free(holder);
}

static jint JNICALL nativeSnapshotLength(JNIEnv *env, jclass klass) {
    (void)env; (void)klass;
    return (jint)engine_snapshot_len();
}

static jint JNICALL nativeSettingsRoomState(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    return (jint)engine_settings_room_state(engine(value));
}

static jboolean JNICALL nativeSetUsername(JNIEnv *env, jclass klass, jlong value, jbyteArray bytes) {
    (void)klass;
    jsize length = (*env)->GetArrayLength(env, bytes);
    uint8_t *destination = engine_username_buffer_ptr(engine(value), (uintptr_t)length);
    if (!destination && length > 0) return 0;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)destination);
    if ((*env)->ExceptionCheck(env)) return 0;
    return engine_load_username_buffer(engine(value));
}

static JNINativeMethod methods[] = {
    {"nativeCreate", "()J", (void *)nativeCreate},
    {"nativeDestroy", "(J)V", (void *)nativeDestroy},
    {"nativeLoad", "(J[BZ)Z", (void *)nativeLoad},
    {"nativeSetInput", "(JFFZZFFF)V", (void *)nativeSetInput},
    {"nativeStep", "(JF)V", (void *)nativeStep},
    {"nativeReadFrame", "(J)[F", (void *)nativeReadFrame},
    {"nativeSetRemotePlayers", "(J[F)V", (void *)nativeSetRemotePlayers},
    {"nativeCreateRenderer", "(JLandroid/view/Surface;FF)J", (void *)nativeCreateRenderer},
    {"nativeResizeRenderer", "(JFF)V", (void *)nativeResizeRenderer},
    {"nativeDrawRenderer", "(JJ)V", (void *)nativeDrawRenderer},
    {"nativeDestroyRenderer", "(J)V", (void *)nativeDestroyRenderer},
    {"nativeSnapshotLength", "()I", (void *)nativeSnapshotLength},
    {"nativeSettingsRoomState", "(J)I", (void *)nativeSettingsRoomState},
    {"nativeSetUsername", "(J[B)Z", (void *)nativeSetUsername},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass klass = (*env)->FindClass(env, "dev/andrewarrow/cubacadabra/nativebridge/NativeEngine");
    if (!klass) return JNI_ERR;
    if ((*env)->RegisterNatives(env, klass, methods, (jint)(sizeof(methods) / sizeof(methods[0]))) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}
