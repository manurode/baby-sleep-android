package com.babysleepmonitor.network

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Manual ONVIF implementation for basic authentication and profile retrieval.
 * Used as a fallback when the standard library fails (Error 400/401).
 * 
 * Note: This implementation is "permissive" - it generates a standard WS-Security header
 * that works with most Chinese/Generic cameras that might reject stricter libraries.
 */
object ManualOnvifClient {

    private val MEDIA_TYPE_SOAP = "application/soap+xml; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // GetProfiles SOAP Body
    private val GET_PROFILES_BODY = """
        <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
            <GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>
        </s:Body>
    """.trimIndent()
    
    // GetStreamUri Template
    private val GET_STREAM_URI_TEMPLATE = """
        <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
            <GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl">
                <StreamSetup>
                    <Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream>
                    <Transport xmlns="http://www.onvif.org/ver10/schema">
                        <Protocol>UDP</Protocol>
                    </Transport>
                </StreamSetup>
                <ProfileToken>%s</ProfileToken>
            </GetStreamUri>
        </s:Body>
    """.trimIndent()

    suspend fun getStreamUri(url: String, username: String, password: String): String? {
        try {
            // 1. Get Profiles to find a token
            val profilesResponse = sendSoapRequest(url, username, password, GET_PROFILES_BODY, "http://www.onvif.org/ver10/media/wsdl")
            val profileToken = extractProfileToken(profilesResponse) ?: return null
            
            // 2. Get Stream URI using the token
            val streamBody = GET_STREAM_URI_TEMPLATE.format(profileToken)
            val streamResponse = sendSoapRequest(url, username, password, streamBody, "http://www.onvif.org/ver10/media/wsdl")
            
            return extractUri(streamResponse)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun sendSoapRequest(url: String, username: String, pass: String, soapBody: String, xmlnsBytes: String? = null): String {
        val nonceRaw = UUID.randomUUID().toString().replace("-", "").toByteArray()
        val created = getCurrentTimestamp()
        val nonceBase64 = Base64.encodeToString(nonceRaw, Base64.NO_WRAP)
        val passwordDigest = generatePasswordDigest(nonceRaw, created, pass)

        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                <s:Header>
                    <wsse:Security s:mustUnderstand="1">
                        <wsse:UsernameToken wsu:Id="UsernameToken-1">
                            <wsse:Username>$username</wsse:Username>
                            <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$passwordDigest</wsse:Password>
                            <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</wsse:Nonce>
                            <wsu:Created>$created</wsu:Created>
                        </wsse:UsernameToken>
                    </wsse:Security>
                </s:Header>
                $soapBody
            </s:Envelope>
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(envelope.toRequestBody(MEDIA_TYPE_SOAP))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code} ${response.message}")
            return response.body?.string() ?: ""
        }
    }

    private fun generatePasswordDigest(nonce: ByteArray, created: String, password: String): String {
        val createdBytes = created.toByteArray(StandardCharsets.UTF_8)
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(createdBytes)
        sha1.update(passwordBytes)
        val hash = sha1.digest()
        
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(Date())
    }

    private fun extractProfileToken(xml: String): String? {
        // Simple regex extraction to avoid full XML parsing overhead for fallback
        // Looking for <tt:Profile token="PROFILE_TOKEN">
        val regex = "token=\"([^\"]+)\"".toRegex()
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun extractUri(xml: String): String? {
        // Look for <tt:Uri>rtsp://...</tt:Uri>
        // Note: Namespace prefixes might vary (tt, ns0, etc), so matches just the tag name
        val regex = "<[^:]*:?Uri>([^<]+)</[^:]*:?Uri>".toRegex()
        return regex.find(xml)?.groupValues?.get(1)
    }
}
