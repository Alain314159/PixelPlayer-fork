package com.theveloper.pixelplay.data.remote.qqmusic

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import timber.log.Timber

/**
 * OkHttp Interceptor for QQ Music `musics.fcg` encrypted API.
 * Automatically handles:
 * 1. Signature generation (sign)
 * 2. Body encryption (AES-128-GCM -> Base64)
 * 3. Response decryption (XOR)
 *
 * BUG FIX: previously this interceptor only used QQSignGenerator (WebView-based),
 * which blocked on the main thread for 2-8 seconds on first call (WebView init +
 * loadUrl("https://y.qq.com/") + onPageFinished wait). Now it tries the Duktape
 * implementation first (which runs on any background thread, ~50ms first call),
 * and falls back to the WebView implementation only if Duktape returns null
 * (typically for vm_new paths that use crypto.subtle).
 */
class QQMusicEncryptInterceptor(
    private val signGenerator: QQSignGenerator,
    private val duktapeSignGenerator: QQSignGeneratorDuktape? = null
) : Interceptor {

    private val mediaType = "text/plain".toMediaType()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Only intercept requests to musics.fcg or those requiring encryption
        if (!originalRequest.url.toString().contains("musics.fcg")) {
            return chain.proceed(originalRequest)
        }

        Timber.d("QQMusicEncryptInterceptor: Intercepting request to ${originalRequest.url}")

        // 1. Get original JSON body
        val requestBuffer = Buffer()
        originalRequest.body?.writeTo(requestBuffer)
        val plaintextJson = requestBuffer.readUtf8()
        Timber.d("QQMusicEncryptInterceptor: Plain request json: $plaintextJson")

        // 2. Generate sign — try Duktape first, fall back to WebView if it fails.
        //    qq_sign.js does not use crypto.subtle, so Duktape should handle it.
        val sign = (duktapeSignGenerator?.generateSign(plaintextJson) ?: signGenerator.generateSign(plaintextJson)) ?: ""
        Timber.d("QQMusicEncryptInterceptor: Generated sign: $sign (via ${if (duktapeSignGenerator != null) "Duktape" else "WebView"})")

        // 3. Encrypt body to Base64 using AES-GCM.
        //    vm_new.js uses crypto.subtle, so Duktape will likely return null here —
        //    fall back to WebView, then to QQMusicSecurity (hand-rolled XOR/AES).
        val encryptedBase64Body = duktapeSignGenerator?.encryptRequestWithVm(plaintextJson)
            ?: signGenerator.encryptRequestWithVm(plaintextJson)
            ?: QQMusicSecurity.encryptRequest(plaintextJson)

        // 4. Rebuild request with 'ag-1' encoding and 'sign' parameter
        val newUrl = originalRequest.url.newBuilder()
            .addQueryParameter("encoding", "ag-1")
            .addQueryParameter("sign", sign)
            .build()

        val encryptedRequest = originalRequest.newBuilder()
            .url(newUrl)
            .post(encryptedBase64Body.toRequestBody(mediaType))
            .header("accept", "application/octet-stream")
            .header("content-type", "text/plain")
            .build()

        // 5. Execute request and intercept response
        val response = chain.proceed(encryptedRequest)
        Timber.d("QQMusicEncryptInterceptor: Response code: ${response.code}")

        if (response.isSuccessful) {
            val responseBytes = response.body.bytes()
            Timber.d("QQMusicEncryptInterceptor: Response body size: ${responseBytes.size}")

            // 6. Decrypt response — try Duktape first, then WebView, then XOR fallback.
            val vmDecrypted = duktapeSignGenerator?.decryptResponseWithVm(responseBytes)
                ?: signGenerator.decryptResponseWithVm(responseBytes)
            if (vmDecrypted == null) {
                Timber.w("QQMusicEncryptInterceptor: vm_new decrypt returned null, fallback to XOR")
            }
            val decryptedJson = vmDecrypted ?: QQMusicSecurity.decryptResponse(responseBytes)
            Timber.d("QQMusicEncryptInterceptor: Decrypted JSON length: ${decryptedJson.length}")
            runCatching {
                val root = org.json.JSONObject(decryptedJson)
                val req0Code = root.optJSONObject("req_0")?.optInt("code")
                val req0SubCode = root.optJSONObject("req_0")?.optInt("subcode")
                Timber.d("QQMusicEncryptInterceptor: Parsed req_0 code=$req0Code, subcode=$req0SubCode")
            }

            // 7. Return decrypted response as JSON
            return response.newBuilder()
                .body(decryptedJson.toResponseBody("application/json".toMediaType()))
                .build()
        }

        return response
    }
}
