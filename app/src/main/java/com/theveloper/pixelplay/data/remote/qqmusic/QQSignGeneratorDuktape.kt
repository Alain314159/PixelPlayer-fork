/*
 * Copyright (C) 2026 PixelPlayer
 *
 * Duktape-based alternative to QQSignGenerator (which uses an off-screen WebView).
 *
 * Why: the WebView approach has several problems:
 *  1. WebView must be created on the main thread, so all sign calls block on
 *     mainHandler.post + CountDownLatch.await(3, SECONDS). This adds 2-8 seconds
 *     of cold-start latency on the first QQ Music request (WebView initialization
 *     + loadUrl("https://y.qq.com/") + onPageFinished wait).
 *  2. WebView is a heavy object (~5-10 MB of native memory). It lives forever
 *     once created because it's cached in QQSignGenerator.webView.
 *  3. The WebView requires `https://y.qq.com/` to be reachable on first launch
 *     (the vm_new.js uses web crypto APIs that are only available on HTTPS origins).
 *
 * Duktape solves 1 and 2: it runs on any background thread (no main-thread
 * dance) and is ~200 KB of native memory. It does NOT solve 3 — the JS code
 * still uses `crypto.subtle` and `atob`, which Duktape does not provide natively.
 *
 * To work around the missing browser APIs, we install polyfills for atob, btoa,
 * and a stub for crypto.subtle that throws if called (the qq_sign.js path does
 * not use crypto.subtle; only vm_new.js does). If the polyfills are insufficient,
 * the caller should fall back to QQSignGenerator (WebView) for vm_new paths.
 *
 * Usage:
 *   val generator = QQSignGeneratorDuktape(context)
 *   val sign = generator.generateSign(jsonData)  // try this first
 *   if (sign == null) {
 *       // Fall back to WebView implementation for vm_new paths
 *       sign = webViewGenerator.generateSign(jsonData)
 *   }
 */
package com.theveloper.pixelplay.data.remote.qqmusic

import android.content.Context
import com.squareup.duktape.Duktape
import org.json.JSONObject
import timber.log.Timber
import java.io.InputStreamReader
import java.util.concurrent.locks.ReentrantLock

class QQSignGeneratorDuktape(context: Context) {

    private val appContext = context.applicationContext
    private val lock = ReentrantLock()

    @Volatile
    private var duktape: Duktape? = null

    @Volatile
    private var initialized: Boolean = false

    private val jsContent: String by lazy {
        appContext.assets.open("qq_sign.js").use { inputStream ->
            InputStreamReader(inputStream).readText()
        }
    }

    private val vmContent: String? by lazy {
        runCatching {
            appContext.assets.open("vm_new.js").use { inputStream ->
                InputStreamReader(inputStream).readText()
            }
        }.getOrNull()
    }

    private fun ensureInitialized() {
        if (initialized) return
        lock.lock()
        try {
            if (initialized) return
            val duktape = Duktape.create()
            // Install polyfills for browser APIs that qq_sign.js and vm_new.js expect.
            duktape.evaluate(
                """
                // atob / btoa polyfills (Base64).
                // Duktape has no native Base64; use a JS implementation.
                var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
                var B64_REV = {};
                for (var i = 0; i < B64.length; i++) B64_REV[B64.charAt(i)] = i;

                function atob(s) {
                    s = s.replace(/=+$/, '');
                    var bytes = [];
                    var buffer = 0, bits = 0;
                    for (var i = 0; i < s.length; i++) {
                        var c = s.charAt(i);
                        if (B64_REV[c] === undefined) continue;
                        buffer = (buffer << 6) | B64_REV[c];
                        bits += 6;
                        if (bits >= 8) {
                            bytes.push((buffer >> (bits - 8)) & 0xFF);
                            bits -= 8;
                        }
                    }
                    var result = '';
                    for (var j = 0; j < bytes.length; j++) result += String.fromCharCode(bytes[j]);
                    return result;
                }

                function btoa(s) {
                    var result = '';
                    var buffer = 0, bits = 0;
                    for (var i = 0; i < s.length; i++) {
                        buffer = (buffer << 8) | (s.charCodeAt(i) & 0xFF);
                        bits += 8;
                        while (bits >= 6) {
                            result += B64.charAt((buffer >> (bits - 6)) & 0x3F);
                            bits -= 6;
                        }
                    }
                    if (bits > 0) {
                        result += B64.charAt((buffer << (6 - bits)) & 0x3F);
                    }
                    while (result.length % 4 !== 0) result += '=';
                    return result;
                }

                // globalThis polyfill (Duktape is ES5.1; some scripts use ES6 globalThis).
                if (typeof globalThis === 'undefined') {
                    var globalThis = (typeof this !== 'undefined') ? this :
                                    (typeof window !== 'undefined') ? window :
                                    (typeof self !== 'undefined') ? self : this;
                }

                // crypto.subtle stub — throws if called. qq_sign.js does not use it;
                // vm_new.js does. Callers should fall back to the WebView implementation
                // for vm_new paths if this stub throws.
                var crypto = {
                    subtle: {
                        digest: function() { throw new Error('crypto.subtle.digest not supported in Duktape — use WebView fallback'); },
                        encrypt: function() { throw new Error('crypto.subtle.encrypt not supported in Duktape — use WebView fallback'); },
                        decrypt: function() { throw new Error('crypto.subtle.decrypt not supported in Duktape — use WebView fallback'); }
                    }
                };

                // TextEncoder / TextDecoder stubs (UTF-8 only, which is what these scripts use).
                function TextEncoder() {}
                TextEncoder.prototype.encode = function(s) {
                    var bytes = [];
                    for (var i = 0; i < s.length; i++) {
                        var c = s.charCodeAt(i);
                        if (c < 0x80) bytes.push(c);
                        else if (c < 0x800) {
                            bytes.push(0xC0 | (c >> 6));
                            bytes.push(0x80 | (c & 0x3F));
                        } else {
                            bytes.push(0xE0 | (c >> 12));
                            bytes.push(0x80 | ((c >> 6) & 0x3F));
                            bytes.push(0x80 | (c & 0x3F));
                        }
                    }
                    return new Uint8Array(bytes);
                };

                function TextDecoder() {}
                TextDecoder.prototype.decode = function(bytes) {
                    var result = '';
                    var i = 0;
                    while (i < bytes.length) {
                        var b = bytes[i];
                        if (b < 0x80) { result += String.fromCharCode(b); i++; }
                        else if (b < 0xE0) {
                            var c = ((b & 0x1F) << 6) | (bytes[i+1] & 0x3F);
                            result += String.fromCharCode(c);
                            i += 2;
                        } else {
                            var c = ((b & 0x0F) << 12) | ((bytes[i+1] & 0x3F) << 6) | (bytes[i+2] & 0x3F);
                            result += String.fromCharCode(c);
                            i += 3;
                        }
                    }
                    return result;
                };
                """.trimIndent()
            )
            // Load the qq_sign.js library (defines getSign function).
            duktape.evaluate(jsContent)
            this.duktape = duktape
            this.initialized = true
            Timber.tag("QQSignDuktape").i("Duktape signer initialized")
        } catch (e: Exception) {
            Timber.tag("QQSignDuktape").e(e, "Failed to initialize Duktape signer — falling back to WebView")
            // Set initialized=true so we don't retry; callers should fall back to WebView
            this.initialized = true
        } finally {
            lock.unlock()
        }
    }

    /**
     * Generates a `zzb...` signature for the given request JSON string.
     *
     * Returns null if Duktape is unavailable or the JS throws. Caller should
     * fall back to QQSignGenerator (WebView) in that case.
     */
    fun generateSign(jsonData: String): String? {
        return try {
            ensureInitialized()
            val duktape = duktape ?: return null
            lock.lock()
            try {
                val quotedJson = JSONObject.quote(jsonData)
                duktape.evaluate("(function(){ return getSign($quotedJson); })()") as? String
            } finally {
                lock.unlock()
            }
        } catch (e: Exception) {
            Timber.tag("QQSignDuktape").w(e, "Duktape generateSign failed — caller should fall back to WebView")
            null
        }
    }

    /**
     * vm_new.js __cgiDecrypt — uses crypto.subtle so this will likely throw on Duktape.
     * Caller should fall back to QQSignGenerator (WebView) for vm_new paths.
     */
    fun decryptResponseWithVm(encryptedData: ByteArray): String? {
        return try {
            ensureInitialized()
            val duktape = duktape ?: return null
            val vmCode = vmContent ?: return null
            lock.lock()
            try {
                val b64 = android.util.Base64.encodeToString(encryptedData, android.util.Base64.NO_WRAP)
                duktape.evaluate(
                    """
                    (function() {
                        var e = (typeof globalThis !== 'undefined') ? globalThis : this;
                        $vmCode
                        var raw = "$b64";
                        var bin = atob(raw);
                        var bytes = new Uint8Array(bin.length);
                        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                        return e.__cgiDecrypt(bytes.buffer);
                    })();
                    """.trimIndent()
                ) as? String
            } finally {
                lock.unlock()
            }
        } catch (e: Exception) {
            Timber.tag("QQSignDuktape").w(e, "Duktape vm_new decrypt failed (expected if vm_new uses crypto.subtle) — caller should fall back to WebView")
            null
        }
    }

    /**
     * vm_new.js __cgiEncrypt — uses crypto.subtle so this will likely throw on Duktape.
     * Caller should fall back to QQSignGenerator (WebView) for vm_new paths.
     */
    fun encryptRequestWithVm(plaintext: String): String? {
        return try {
            ensureInitialized()
            val duktape = duktape ?: return null
            val vmCode = vmContent ?: return null
            lock.lock()
            try {
                val quotedPlaintext = JSONObject.quote(plaintext)
                duktape.evaluate(
                    """
                    (function() {
                        var e = (typeof globalThis !== 'undefined') ? globalThis : this;
                        $vmCode
                        return e.__cgiEncrypt($quotedPlaintext);
                    })();
                    """.trimIndent()
                ) as? String
            } finally {
                lock.unlock()
            }
        } catch (e: Exception) {
            Timber.tag("QQSignDuktape").w(e, "Duktape vm_new encrypt failed (expected if vm_new uses crypto.subtle) — caller should fall back to WebView")
            null
        }
    }

    fun close() {
        lock.lock()
        try {
            duktape?.close()
            duktape = null
            initialized = false
        } finally {
            lock.unlock()
        }
    }
}
