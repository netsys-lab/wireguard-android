/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

struct go_string { const char *str; long n; };
extern int wgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings);
extern void wgTurnOff(int handle);
extern int wgGetSocketV4(int handle);
extern int wgGetSocketV6(int handle);
extern char *wgGetConfig(int handle);
extern char *wgVersion();
extern char *wgScionBootstrap(struct go_string configDir, struct go_string bootstrapURL);
extern char *wgInitScion(int handle, struct go_string configDir, struct go_string interfaceName);
extern char *wgInitScionWithBootstrapRetry(
        int handle,
        struct go_string configDir,
        struct go_string interfaceName,
        struct go_string bootstrapURL,
        struct go_string localIPv4,
        struct go_string localIPv6
);
extern char *wgGetScionStatus(int handle);

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgInitScionWithBootstrapRetry(
        JNIEnv *env,
        jclass c,
        jint handle,
        jstring configDir,
        jstring interfaceName,
        jstring bootstrapURL,
        jstring localIPv4,
        jstring localIPv6)
{
    const char *configDir_str = (*env)->GetStringUTFChars(env, configDir, 0);
    size_t configDir_len = (*env)->GetStringUTFLength(env, configDir);

    const char *interfaceName_str = (*env)->GetStringUTFChars(env, interfaceName, 0);
    size_t interfaceName_len = (*env)->GetStringUTFLength(env, interfaceName);

    const char *bootstrapURL_str = (*env)->GetStringUTFChars(env, bootstrapURL, 0);
    size_t bootstrapURL_len = (*env)->GetStringUTFLength(env, bootstrapURL);

    const char *localIPv4_str = (*env)->GetStringUTFChars(env, localIPv4, 0);
    size_t localIPv4_len = (*env)->GetStringUTFLength(env, localIPv4);

    const char *localIPv6_str = (*env)->GetStringUTFChars(env, localIPv6, 0);
    size_t localIPv6_len = (*env)->GetStringUTFLength(env, localIPv6);

    char *result = wgInitScionWithBootstrapRetry(
            handle,
            (struct go_string){
                    .str = configDir_str,
                    .n = configDir_len
            },
            (struct go_string){
                    .str = interfaceName_str,
                    .n = interfaceName_len
            },
            (struct go_string){
                    .str = bootstrapURL_str,
                    .n = bootstrapURL_len
            },
            (struct go_string){
                    .str = localIPv4_str,
                    .n = localIPv4_len
            },
            (struct go_string){
                    .str = localIPv6_str,
                    .n = localIPv6_len
            });

    (*env)->ReleaseStringUTFChars(env, configDir, configDir_str);
    (*env)->ReleaseStringUTFChars(env, interfaceName, interfaceName_str);
    (*env)->ReleaseStringUTFChars(env, bootstrapURL, bootstrapURL_str);
    (*env)->ReleaseStringUTFChars(env, localIPv4, localIPv4_str);
    (*env)->ReleaseStringUTFChars(env, localIPv6, localIPv6_str);

    if (!result)
        return NULL;

    jstring ret = (*env)->NewStringUTF(env, result);
    free(result);
    return ret;
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgScionBootstrap(JNIEnv *env, jclass c, jstring configDir, jstring bootstrapURL)
{
    const char *configDir_str = (*env)->GetStringUTFChars(env, configDir, 0);
    size_t configDir_len = (*env)->GetStringUTFLength(env, configDir);
    const char *bootstrapURL_str = (*env)->GetStringUTFChars(env, bootstrapURL, 0);
    size_t bootstrapURL_len = (*env)->GetStringUTFLength(env, bootstrapURL);
    char *result = wgScionBootstrap((struct go_string){
            .str = configDir_str,
            .n = configDir_len
    }, (struct go_string){
            .str = bootstrapURL_str,
            .n = bootstrapURL_len
    });
    (*env)->ReleaseStringUTFChars(env, configDir, configDir_str);
    (*env)->ReleaseStringUTFChars(env, bootstrapURL, bootstrapURL_str);
    if (!result)
        return NULL;
    jstring ret = (*env)->NewStringUTF(env, result);
    free(result);
    return ret;
}
JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgInitScion(JNIEnv *env, jclass c, jint handle, jstring configDir, jstring interfaceName)
{
    const char *configDir_str = (*env)->GetStringUTFChars(env, configDir, 0);
    size_t configDir_len = (*env)->GetStringUTFLength(env, configDir);
    const char *interfaceName_str = (*env)->GetStringUTFChars(env, interfaceName, 0);
    size_t interfaceName_len = (*env)->GetStringUTFLength(env, interfaceName);
    char *result = wgInitScion(handle, (struct go_string){
            .str = configDir_str,
            .n = configDir_len
    }, (struct go_string){
            .str = interfaceName_str,
            .n = interfaceName_len
    });
    (*env)->ReleaseStringUTFChars(env, configDir, configDir_str);
    (*env)->ReleaseStringUTFChars(env, interfaceName, interfaceName_str);
    if (!result)
        return NULL;
    jstring ret = (*env)->NewStringUTF(env, result);
    free(result);
    return ret;
}
JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetScionStatus(JNIEnv *env, jclass c, jint handle)
{
    char *result = wgGetScionStatus(handle);
    if (!result)
        return NULL;
    jstring ret = (*env)->NewStringUTF(env, result);
    free(result);
    return ret;
}

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOn(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings)
{
	const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
	size_t ifname_len = (*env)->GetStringUTFLength(env, ifname);
	const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
	size_t settings_len = (*env)->GetStringUTFLength(env, settings);
	int ret = wgTurnOn((struct go_string){
		.str = ifname_str,
		.n = ifname_len
	}, tun_fd, (struct go_string){
		.str = settings_str,
		.n = settings_len
	});
	(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
	(*env)->ReleaseStringUTFChars(env, settings, settings_str);
	return ret;
}

JNIEXPORT void JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOff(JNIEnv *env, jclass c, jint handle)
{
	wgTurnOff(handle);
}

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetSocketV4(JNIEnv *env, jclass c, jint handle)
{
	return wgGetSocketV4(handle);
}

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetSocketV6(JNIEnv *env, jclass c, jint handle)
{
	return wgGetSocketV6(handle);
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetConfig(JNIEnv *env, jclass c, jint handle)
{
	jstring ret;
	char *config = wgGetConfig(handle);
	if (!config)
		return NULL;
	ret = (*env)->NewStringUTF(env, config);
	free(config);
	return ret;
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgVersion(JNIEnv *env, jclass c)
{
	jstring ret;
	char *version = wgVersion();
	if (!version)
		return NULL;
	ret = (*env)->NewStringUTF(env, version);
	free(version);
	return ret;
}

extern char *wgScionTestBridge(struct go_string inputPath);

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgScionTestBridge(JNIEnv *env, jclass c, jstring inputPath)
{
	const char *inputPath_str = (*env)->GetStringUTFChars(env, inputPath, 0);
	size_t inputPath_len = (*env)->GetStringUTFLength(env, inputPath);
	char *out_str = wgScionTestBridge((struct go_string){
		.str = inputPath_str,
		.n = inputPath_len
	});
	(*env)->ReleaseStringUTFChars(env, inputPath, inputPath_str);
	
	if (!out_str)
		return NULL;
	jstring ret = (*env)->NewStringUTF(env, out_str);
	free(out_str);
	return ret;
}
