package com.livechat.bubble

import android.util.Base64
import java.net.URLEncoder

/**
 * Plan B para armar los `params` de send_message (protobuf a mano) cuando la página no los trae.
 * Estructura según youtubei.js: { params { ids(5) { channelId(1), videoId(2) } }, 2=1, 3=4 }
 * y luego base64 -> urlencode -> base64. SIN PROBAR contra YouTube real.
 */
object SendParams {
    fun build(channelId: String, videoId: String): String {
        val ids = field(1, channelId.toByteArray()) + field(2, videoId.toByteArray())
        val inner = field(5, ids)
        val msg = field(1, inner) + varintField(2, 1) + varintField(3, 4)
        val b64 = Base64.encodeToString(msg, Base64.NO_WRAP)
        val enc = URLEncoder.encode(b64, "UTF-8")
        return Base64.encodeToString(enc.toByteArray(), Base64.NO_WRAP)
    }

    private fun field(number: Int, data: ByteArray): ByteArray =
        byteArrayOf(((number shl 3) or 2).toByte()) + lengthVarint(data.size) + data

    private fun varintField(number: Int, value: Int): ByteArray =
        byteArrayOf((number shl 3).toByte(), value.toByte())

    private fun lengthVarint(length: Int): ByteArray {
        var l = length
        val out = ArrayList<Byte>()
        while (l > 0x7F) {
            out.add(((l and 0x7F) or 0x80).toByte())
            l = l ushr 7
        }
        out.add(l.toByte())
        return out.toByteArray()
    }
}
