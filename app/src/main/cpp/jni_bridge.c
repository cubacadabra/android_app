#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct CubacadabraEngine CubacadabraEngine;
typedef struct CubacadabraRenderer CubacadabraRenderer;
typedef struct CubacadabraClient CubacadabraClient;

extern CubacadabraClient *client_create(const uint8_t *, uintptr_t, const uint8_t *, uintptr_t);
extern void client_destroy(CubacadabraClient *);
extern CubacadabraEngine *client_engine(CubacadabraClient *);
extern void client_transport_connected(CubacadabraClient *);
extern void client_transport_disconnected(CubacadabraClient *);
extern void client_request_transport(CubacadabraClient *);
extern uint8_t client_receive_text(CubacadabraClient *, const uint8_t *, uintptr_t);
extern uint8_t client_set_ignored_player_ids_json(CubacadabraClient *, const uint8_t *, uintptr_t);
extern uint8_t client_poll_action(CubacadabraClient *);
extern const uint8_t *client_action_ptr(const CubacadabraClient *);
extern uintptr_t client_action_len(const CubacadabraClient *);

extern uint8_t *engine_username_buffer_ptr(CubacadabraEngine *engine, uintptr_t length);
extern uint8_t engine_load_username_buffer(CubacadabraEngine *engine);
extern uint8_t engine_start_world(CubacadabraEngine *, uintptr_t);
extern void engine_set_build_block_count(CubacadabraEngine *, uintptr_t);
extern void engine_set_build_block(CubacadabraEngine *, uintptr_t, float, float, float, float, float, float, uint32_t, uint8_t);
extern void engine_set_input(CubacadabraEngine *, float, float, uint8_t, uint8_t, uint8_t, float, float, float);
extern void engine_set_ui_viewport(CubacadabraEngine *, float, float, float, float, float, float, float);
extern void engine_set_authenticated(CubacadabraEngine *, uint8_t);
extern uint8_t engine_ui_pointer(CubacadabraEngine *, uint64_t, uint8_t, float, float);
extern uint8_t engine_ui_poll_event(CubacadabraEngine *);
extern const uint8_t *engine_ui_event_ptr(const CubacadabraEngine *);
extern uintptr_t engine_ui_event_len(const CubacadabraEngine *);
extern void engine_step(CubacadabraEngine *, float);
extern const float *engine_snapshot_ptr(const CubacadabraEngine *engine);
extern uintptr_t engine_snapshot_len(void);
extern uintptr_t engine_snapshot_stride(void);
extern uintptr_t engine_agent_count(const CubacadabraEngine *engine);
extern uintptr_t engine_local_agent_count(const CubacadabraEngine *engine);
extern uintptr_t engine_remote_player_count(const CubacadabraEngine *engine);
extern uint8_t engine_audio_poll_message(CubacadabraEngine *);
extern const uint8_t *engine_audio_message_ptr(const CubacadabraEngine *);
extern uintptr_t engine_audio_message_len(const CubacadabraEngine *);
extern uintptr_t engine_launch_pad_count(const CubacadabraEngine *engine);
extern uintptr_t engine_launch_pad_occupants(const CubacadabraEngine *engine, uintptr_t);
extern float engine_launch_pad_seconds(const CubacadabraEngine *engine, uintptr_t);
extern uint8_t engine_launch_pad_phase(const CubacadabraEngine *engine, uintptr_t);
extern uintptr_t engine_active_world(const CubacadabraEngine *engine);
extern uint8_t engine_settings_room_state(const CubacadabraEngine *engine);
extern float engine_camera_yaw(const CubacadabraEngine *engine);
extern float engine_camera_pitch(const CubacadabraEngine *engine);
extern float engine_camera_distance(const CubacadabraEngine *engine);
extern uint32_t engine_player_respawn_event_id(const CubacadabraEngine *engine);
extern uint8_t engine_set_local_appearance_json(CubacadabraEngine *, const uint8_t *, uintptr_t);
extern uint8_t engine_set_local_morph_loadout_json(CubacadabraEngine *, const uint8_t *, uintptr_t);
extern uint32_t engine_appearance_revision(const CubacadabraEngine *);
extern CubacadabraRenderer *engine_renderer_create(void *, float, float);
extern void engine_renderer_resize(CubacadabraRenderer *, float, float);
extern uint8_t engine_renderer_set_package_image_atlas(
    CubacadabraRenderer *, uint32_t, uint32_t, const uint8_t *, uintptr_t,
    const uint8_t *, uintptr_t
);
extern uint8_t engine_renderer_register_morph_pack(
    CubacadabraRenderer *, const uint8_t *, uintptr_t
);
extern uint8_t engine_renderer_set_avatar_preview_mode(CubacadabraRenderer *, uint8_t);
extern void engine_renderer_sync(CubacadabraRenderer *, const CubacadabraEngine *);
extern void engine_renderer_draw(CubacadabraRenderer *);
extern void engine_renderer_destroy(CubacadabraRenderer *);

typedef struct {
    CubacadabraRenderer *renderer;
    ANativeWindow *window;
} AndroidRenderer;

typedef struct {
    CubacadabraClient *client;
    CubacadabraEngine *engine;
} AndroidClient;

static AndroidClient *android_client(jlong value) { return (AndroidClient *)(intptr_t)value; }
static CubacadabraEngine *engine(jlong value) {
    AndroidClient *holder = android_client(value);
    return holder ? holder->engine : NULL;
}

static jlong JNICALL nativeCreate(JNIEnv *env, jclass klass, jbyteArray manifest, jbyteArray script) {
    (void)klass;
    jsize manifestLength = (*env)->GetArrayLength(env, manifest);
    jsize scriptLength = (*env)->GetArrayLength(env, script);
    jbyte *manifestSource = (*env)->GetByteArrayElements(env, manifest, NULL);
    jbyte *scriptSource = (*env)->GetByteArrayElements(env, script, NULL);
    if ((!manifestSource && manifestLength > 0) || (!scriptSource && scriptLength > 0)) {
        if (manifestSource) (*env)->ReleaseByteArrayElements(env, manifest, manifestSource, JNI_ABORT);
        if (scriptSource) (*env)->ReleaseByteArrayElements(env, script, scriptSource, JNI_ABORT);
        return 0;
    }
    CubacadabraClient *client = client_create(
        (const uint8_t *)manifestSource, (uintptr_t)manifestLength,
        (const uint8_t *)scriptSource, (uintptr_t)scriptLength
    );
    if (manifestSource) (*env)->ReleaseByteArrayElements(env, manifest, manifestSource, JNI_ABORT);
    if (scriptSource) (*env)->ReleaseByteArrayElements(env, script, scriptSource, JNI_ABORT);
    if (!client) return 0;
    AndroidClient *holder = (AndroidClient *)calloc(1, sizeof(AndroidClient));
    if (!holder) {
        client_destroy(client);
        return 0;
    }
    holder->client = client;
    holder->engine = client_engine(client);
    if (!holder->engine) {
        client_destroy(client);
        free(holder);
        return 0;
    }
    return (jlong)(intptr_t)holder;
}

static void JNICALL nativeDestroy(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    AndroidClient *holder = android_client(value);
    if (!holder) return;
    client_destroy(holder->client);
    free(holder);
}

static void JNICALL nativeTransportConnected(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    AndroidClient *holder = android_client(value);
    if (holder) client_transport_connected(holder->client);
}

static void JNICALL nativeTransportDisconnected(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    AndroidClient *holder = android_client(value);
    if (holder) client_transport_disconnected(holder->client);
}

static void JNICALL nativeRequestTransport(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    AndroidClient *holder = android_client(value);
    if (holder) client_request_transport(holder->client);
}

static jboolean JNICALL nativeReceiveTransportMessage(JNIEnv *env, jclass klass, jlong value, jbyteArray bytes) {
    (void)klass;
    AndroidClient *holder = android_client(value);
    if (!holder) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *source = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!source && length > 0) return JNI_FALSE;
    uint8_t accepted = client_receive_text(holder->client, (const uint8_t *)source, (uintptr_t)length);
    if (source) (*env)->ReleaseByteArrayElements(env, bytes, source, JNI_ABORT);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL nativeSetIgnoredPlayerIds(JNIEnv *env, jclass klass, jlong value, jbyteArray bytes) {
    (void)klass;
    AndroidClient *holder = android_client(value);
    if (!holder) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *source = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!source && length > 0) return JNI_FALSE;
    uint8_t accepted = client_set_ignored_player_ids_json(
        holder->client, (const uint8_t *)source, (uintptr_t)length
    );
    if (source) (*env)->ReleaseByteArrayElements(env, bytes, source, JNI_ABORT);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray JNICALL nativePollClientAction(JNIEnv *env, jclass klass, jlong value) {
    (void)klass;
    AndroidClient *holder = android_client(value);
    if (!holder) return NULL;
    uint8_t kind = client_poll_action(holder->client);
    if (kind == 0) return NULL;
    uintptr_t payloadLength = client_action_len(holder->client);
    jbyteArray result = (*env)->NewByteArray(env, (jsize)(payloadLength + 1));
    if (!result) return NULL;
    jbyte kindByte = (jbyte)kind;
    (*env)->SetByteArrayRegion(env, result, 0, 1, &kindByte);
    const uint8_t *payload = client_action_ptr(holder->client);
    if (payload && payloadLength > 0) {
        (*env)->SetByteArrayRegion(env, result, 1, (jsize)payloadLength, (const jbyte *)payload);
    }
    return result;
}

static void JNICALL nativeSetInput(JNIEnv *env, jclass klass, jlong value, jfloat forward, jfloat strafe,
                                    jboolean sprint, jboolean jump, jboolean climb, jfloat lookX, jfloat lookY, jfloat zoom) {
    (void)env; (void)klass;
    engine_set_input(engine(value), forward, strafe, sprint ? 1 : 0, jump ? 1 : 0, climb ? 1 : 0, lookX, lookY, zoom);
}

static void JNICALL nativeSetUiViewport(JNIEnv *env, jclass klass, jlong value, jfloat width, jfloat height,
                                         jfloat scale, jfloat safeTop, jfloat safeRight, jfloat safeBottom,
                                         jfloat safeLeft) {
    (void)env; (void)klass;
    engine_set_ui_viewport(engine(value), width, height, scale, safeTop, safeRight, safeBottom, safeLeft);
}

static void JNICALL nativeSetAuthenticated(JNIEnv *env, jclass klass, jlong value, jboolean authenticated) {
    (void)env; (void)klass;
    engine_set_authenticated(engine(value), authenticated ? 1 : 0);
}

static jboolean JNICALL nativeUiPointer(JNIEnv *env, jclass klass, jlong value, jlong pointerID, jint phase,
                                         jfloat x, jfloat y) {
    (void)env; (void)klass;
    return engine_ui_pointer(engine(value), (uint64_t)pointerID, (uint8_t)phase, x, y) ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL nativePollUiEvent(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    return engine_ui_poll_event(engine(value)) ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray JNICALL nativeUiEvent(JNIEnv *env, jclass klass, jlong value) {
    (void)klass;
    const uintptr_t length = engine_ui_event_len(engine(value));
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (!result || length == 0) return result;
    const uint8_t *source = engine_ui_event_ptr(engine(value));
    if (source) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)source);
    return result;
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

static jbyteArray JNICALL nativePollAudioMessage(JNIEnv *env, jclass klass, jlong value) {
    (void)klass;
    if (!engine_audio_poll_message(engine(value))) return NULL;
    const uintptr_t length = engine_audio_message_len(engine(value));
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (!result || length == 0) return result;
    const uint8_t *source = engine_audio_message_ptr(engine(value));
    if (source) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)source);
    return result;
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

static jboolean JNICALL nativeSetPackageImageAtlas(JNIEnv *env, jclass klass, jlong value,
                                                    jint width, jint height, jbyteArray pixels,
                                                    jbyteArray regions) {
    (void)klass;
    AndroidRenderer *holder = (AndroidRenderer *)(intptr_t)value;
    if (!holder || width <= 0 || height <= 0) return JNI_FALSE;

    jsize pixelLength = (*env)->GetArrayLength(env, pixels);
    jsize regionLength = (*env)->GetArrayLength(env, regions);
    jbyte *pixelSource = (*env)->GetByteArrayElements(env, pixels, NULL);
    jbyte *regionSource = (*env)->GetByteArrayElements(env, regions, NULL);
    if ((!pixelSource && pixelLength > 0) || (!regionSource && regionLength > 0)) {
        if (pixelSource) (*env)->ReleaseByteArrayElements(env, pixels, pixelSource, JNI_ABORT);
        if (regionSource) (*env)->ReleaseByteArrayElements(env, regions, regionSource, JNI_ABORT);
        return JNI_FALSE;
    }
    uint8_t applied = engine_renderer_set_package_image_atlas(
        holder->renderer,
        (uint32_t)width,
        (uint32_t)height,
        (const uint8_t *)pixelSource,
        (uintptr_t)pixelLength,
        (const uint8_t *)regionSource,
        (uintptr_t)regionLength
    );
    if (pixelSource) (*env)->ReleaseByteArrayElements(env, pixels, pixelSource, JNI_ABORT);
    if (regionSource) (*env)->ReleaseByteArrayElements(env, regions, regionSource, JNI_ABORT);
    return applied ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL nativeRegisterMorphPack(JNIEnv *env, jclass klass, jlong value, jbyteArray pack) {
    (void)klass;
    AndroidRenderer *holder = (AndroidRenderer *)(intptr_t)value;
    if (!holder || !pack) return JNI_FALSE;
    jsize length = (*env)->GetArrayLength(env, pack);
    jbyte *source = (*env)->GetByteArrayElements(env, pack, NULL);
    if (!source && length > 0) return JNI_FALSE;
    uint8_t accepted = engine_renderer_register_morph_pack(
        holder->renderer,
        (const uint8_t *)source,
        (uintptr_t)length
    );
    if (source) (*env)->ReleaseByteArrayElements(env, pack, source, JNI_ABORT);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL nativeSetAvatarPreviewMode(JNIEnv *env, jclass klass, jlong value, jboolean enabled) {
    (void)env; (void)klass;
    AndroidRenderer *holder = (AndroidRenderer *)(intptr_t)value;
    if (holder) engine_renderer_set_avatar_preview_mode(holder->renderer, enabled ? 1 : 0);
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

static jboolean JNICALL nativeSetLocalAppearance(JNIEnv *env, jclass klass, jlong value, jbyteArray bytes) {
    (void)klass;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *source = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!source && length > 0) return JNI_FALSE;
    uint8_t applied = engine_set_local_appearance_json(
        engine(value),
        (const uint8_t *)source,
        (uintptr_t)length
    );
    if (source) (*env)->ReleaseByteArrayElements(env, bytes, source, JNI_ABORT);
    return applied ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL nativeSetLocalMorphLoadout(JNIEnv *env, jclass klass, jlong value, jbyteArray bytes) {
    (void)klass;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *source = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!source && length > 0) return JNI_FALSE;
    uint8_t applied = engine_set_local_morph_loadout_json(
        engine(value),
        (const uint8_t *)source,
        (uintptr_t)length
    );
    if (source) (*env)->ReleaseByteArrayElements(env, bytes, source, JNI_ABORT);
    return applied ? JNI_TRUE : JNI_FALSE;
}

static jint JNICALL nativeAppearanceRevision(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    return (jint)engine_appearance_revision(engine(value));
}

static jboolean JNICALL nativeStartWorld(JNIEnv *env, jclass klass, jlong value, jint world) {
    (void)env; (void)klass;
    return engine_start_world(engine(value), (uintptr_t)(world < 0 ? 0 : world));
}

static jint JNICALL nativePlayerRespawnEventId(JNIEnv *env, jclass klass, jlong value) {
    (void)env; (void)klass;
    return (jint)engine_player_respawn_event_id(engine(value));
}

static void JNICALL nativeSetBuildBlockCount(JNIEnv *env, jclass klass, jlong value, jint count) {
    (void)env; (void)klass;
    engine_set_build_block_count(engine(value), (uintptr_t)(count < 0 ? 0 : count));
}

static void JNICALL nativeSetBuildBlock(JNIEnv *env, jclass klass, jlong value, jint index, jfloat x, jfloat y, jfloat z,
                                        jfloat width, jfloat height, jfloat depth, jint color, jint rotation) {
    (void)env; (void)klass;
    engine_set_build_block(engine(value), (uintptr_t)(index < 0 ? 0 : index), x, y, z, width, height, depth,
                           (uint32_t)color, (uint8_t)rotation);
}

static JNINativeMethod methods[] = {
    {"nativeCreate", "([B[B)J", (void *)nativeCreate},
    {"nativeDestroy", "(J)V", (void *)nativeDestroy},
    {"nativeTransportConnected", "(J)V", (void *)nativeTransportConnected},
    {"nativeTransportDisconnected", "(J)V", (void *)nativeTransportDisconnected},
    {"nativeRequestTransport", "(J)V", (void *)nativeRequestTransport},
    {"nativeReceiveTransportMessage", "(J[B)Z", (void *)nativeReceiveTransportMessage},
    {"nativeSetIgnoredPlayerIds", "(J[B)Z", (void *)nativeSetIgnoredPlayerIds},
    {"nativePollClientAction", "(J)[B", (void *)nativePollClientAction},
    {"nativeSetInput", "(JFFZZZFFF)V", (void *)nativeSetInput},
    {"nativeSetUiViewport", "(JFFFFFFF)V", (void *)nativeSetUiViewport},
    {"nativeSetAuthenticated", "(JZ)V", (void *)nativeSetAuthenticated},
    {"nativeUiPointer", "(JJIFF)Z", (void *)nativeUiPointer},
    {"nativePollUiEvent", "(J)Z", (void *)nativePollUiEvent},
    {"nativeUiEvent", "(J)[B", (void *)nativeUiEvent},
    {"nativeStep", "(JF)V", (void *)nativeStep},
    {"nativeReadFrame", "(J)[F", (void *)nativeReadFrame},
    {"nativePollAudioMessage", "(J)[B", (void *)nativePollAudioMessage},
    {"nativeCreateRenderer", "(JLandroid/view/Surface;FF)J", (void *)nativeCreateRenderer},
    {"nativeResizeRenderer", "(JFF)V", (void *)nativeResizeRenderer},
    {"nativeSetPackageImageAtlas", "(JII[B[B)Z", (void *)nativeSetPackageImageAtlas},
    {"nativeRegisterMorphPack", "(J[B)Z", (void *)nativeRegisterMorphPack},
    {"nativeSetAvatarPreviewMode", "(JZ)V", (void *)nativeSetAvatarPreviewMode},
    {"nativeDrawRenderer", "(JJ)V", (void *)nativeDrawRenderer},
    {"nativeDestroyRenderer", "(J)V", (void *)nativeDestroyRenderer},
    {"nativeSnapshotLength", "()I", (void *)nativeSnapshotLength},
    {"nativeSettingsRoomState", "(J)I", (void *)nativeSettingsRoomState},
    {"nativeSetUsername", "(J[B)Z", (void *)nativeSetUsername},
    {"nativeSetLocalAppearance", "(J[B)Z", (void *)nativeSetLocalAppearance},
    {"nativeSetLocalMorphLoadout", "(J[B)Z", (void *)nativeSetLocalMorphLoadout},
    {"nativeAppearanceRevision", "(J)I", (void *)nativeAppearanceRevision},
    {"nativeStartWorld", "(JI)Z", (void *)nativeStartWorld},
    {"nativePlayerRespawnEventId", "(J)I", (void *)nativePlayerRespawnEventId},
    {"nativeSetBuildBlockCount", "(JI)V", (void *)nativeSetBuildBlockCount},
    {"nativeSetBuildBlock", "(JIFFFFFFII)V", (void *)nativeSetBuildBlock},
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
