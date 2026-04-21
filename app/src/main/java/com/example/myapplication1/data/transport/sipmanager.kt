package com.example.myapplication1.data.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

interface SipEventListener {
    fun onCallStateChanged(state: String) 
    fun onRegistrationStatus(success: Boolean)
    fun onMessageReceived(from: String, body: String)
}

object SipManager {
    private const val TAG = "SipManager"
    private var socket: DatagramSocket? = null
    private var localPort = 5060
    private var localIp = "10.0.2.15"
    
    var listener: SipEventListener? = null

    private var currentCallId: String? = null
    private var currentRemoteTag: String? = null
    private var currentLocalTag: String = "tag${System.currentTimeMillis()}"
    private var lastCallee: String? = "103"
    private var lastCaller: String? = "102"
    private var lastServer: String? = "10.0.2.2"

    fun initSipStack(localIp: String) {
        this.localIp = localIp
        try {
            if (socket == null || socket!!.isClosed) {
                socket = DatagramSocket(localPort)
                Log.d(TAG, "✅ SIP 引擎启动成功")
                startListening()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ UDP 端口绑定失败: ${e.message}")
        }
    }

    private fun startListening() {
        Thread {
            val buffer = ByteArray(2048)
            while (socket != null && !socket!!.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket!!.receive(packet)
                    val response = String(packet.data, 0, packet.length)
                    handleSipMessage(response, packet.address.hostAddress)
                } catch (e: Exception) {
                    Log.e(TAG, "接收错误: ${e.message}")
                }
            }
        }.start()
    }

    private fun handleSipMessage(raw: String, serverIp: String) {
        val callId = raw.lineSequence().find { it.startsWith("Call-ID:", true) }?.substringAfter(":")?.trim()
        val toLine = raw.lineSequence().find { it.startsWith("To:", true) }
        val remoteTag = toLine?.substringAfter("tag=", "")?.takeIf { it.isNotEmpty() }

        if (raw.startsWith("SIP/2.0 200 OK")) {
            if (raw.contains("CSeq: 1 REGISTER")) {
                listener?.onRegistrationStatus(true)
            } else if (raw.contains("CSeq: 2 INVITE")) {
                currentCallId = callId
                currentRemoteTag = remoteTag
                
                // 🎯 核心契约：解析 PC 端媒体端口并启动音频流
                val sdpPart = raw.substringAfter("\r\n\r\n", "")
                val remoteAudioPort = parseRemoteAudioPort(sdpPart)
                val remoteIp = parseRemoteIp(sdpPart) ?: serverIp
                
                Log.i(TAG, "🔗 跨端互通：PC 端媒体地址 $remoteIp:$remoteAudioPort")
                
                // 🔥 启动 RTP 音频流
                RtpAudioHandler.start(remoteIp, remoteAudioPort)
                
                sendAck(serverIp)
                listener?.onCallStateChanged("CONNECTED")
            }
        } else if (raw.startsWith("MESSAGE")) {
            val body = raw.substringAfter("\r\n\r\n")
            val from = raw.lineSequence().find { it.startsWith("From:", true) }?.substringAfter("<")?.substringBefore(">") ?: "Unknown"
            listener?.onMessageReceived(from, body)
            sendSimpleResponse("200 OK", serverIp, callId ?: "", raw)
        } else if (raw.startsWith("BYE")) {
            RtpAudioHandler.stop() // 挂断时停止音频流
            listener?.onCallStateChanged("DISCONNECTED")
            sendSimpleResponse("200 OK", serverIp, callId ?: "", raw)
        }
    }

    private fun parseRemoteAudioPort(sdp: String): Int {
        val regex = Regex("m=audio (\\d+) RTP/AVP")
        return regex.find(sdp)?.groupValues?.get(1)?.toIntOrNull() ?: 8000
    }

    private fun parseRemoteIp(sdp: String): String? {
        val regex = Regex("c=IN IP4 ([\\d.]+)")
        return regex.find(sdp)?.groupValues?.get(1)
    }

    private fun sendAck(server: String) {
        val branch = "z9hG4bK-ack-${System.currentTimeMillis()}"
        val ackMessage = """
            ACK sip:$lastCallee@$server:5060 SIP/2.0
            Via: SIP/2.0/UDP $localIp:$localPort;branch=$branch
            Max-Forwards: 70
            To: <sip:$lastCallee@$server>${if(currentRemoteTag != null) ";tag=$currentRemoteTag" else ""}
            From: <sip:$lastCaller@$server>;tag=$currentLocalTag
            Call-ID: $currentCallId
            CSeq: 2 ACK
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        sendPacket(ackMessage, server)
    }

    suspend fun register(user: String, server: String) {
        lastCaller = user
        lastServer = server
        withContext(Dispatchers.IO) {
            val sipMessage = """
                REGISTER sip:$server:5060 SIP/2.0
                Via: SIP/2.0/UDP $localIp:$localPort;branch=z9hG4bK-reg
                Max-Forwards: 70
                To: <sip:$user@$server>
                From: <sip:$user@$server>;tag=$currentLocalTag
                Call-ID: reg-${System.currentTimeMillis()}
                CSeq: 1 REGISTER
                Contact: <sip:$user@$localIp:$localPort>
                Content-Length: 0

            """.trimIndent().replace("\n", "\r\n") + "\r\n"
            sendPacket(sipMessage, server)
        }
    }

    suspend fun makeCall(caller: String, callee: String, server: String) {
        lastCaller = caller
        lastCallee = callee
        lastServer = server
        withContext(Dispatchers.IO) {
            val callId = "call-${System.currentTimeMillis()}@$localIp"
            val sdp = """
                v=0
                o=- ${System.currentTimeMillis()} ${System.currentTimeMillis()} IN IP4 $localIp
                s=LinkTalk
                c=IN IP4 $localIp
                t=0 0
                m=audio 8000 RTP/AVP 0 8
                a=rtpmap:0 PCMU/8000
                a=sendrecv
                m=video 9000 RTP/AVP 96
                a=rtpmap:96 H264/90000
                a=sendrecv
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
            
            val sipMessage = """
                INVITE sip:$callee@$server:5060 SIP/2.0
                Via: SIP/2.0/UDP $localIp:$localPort;branch=z9hG4bK-inv
                Max-Forwards: 70
                To: <sip:$callee@$server>
                From: <sip:$caller@$server>;tag=$currentLocalTag
                Call-ID: $callId
                CSeq: 2 INVITE
                Contact: <sip:$caller@$localIp:$localPort>
                Content-Type: application/sdp
                Content-Length: ${sdp.toByteArray().size}

            """.trimIndent().replace("\n", "\r\n") + "\r\n" + sdp
            sendPacket(sipMessage, server)
            listener?.onCallStateChanged("CALLING")
        }
    }

    suspend fun hangup() {
        withContext(Dispatchers.IO) {
            val sipMessage = """
                BYE sip:$lastCallee@$lastServer:5060 SIP/2.0
                Via: SIP/2.0/UDP $localIp:$localPort;branch=z9hG4bK-bye
                Max-Forwards: 70
                To: <sip:$lastCallee@$lastServer>${if(currentRemoteTag != null) ";tag=$currentRemoteTag" else ""}
                From: <sip:$lastCaller@$lastServer>;tag=$currentLocalTag
                Call-ID: $currentCallId
                CSeq: 3 BYE
                Content-Length: 0

            """.trimIndent().replace("\n", "\r\n") + "\r\n"
            lastServer?.let { sendPacket(sipMessage, it) }
            RtpAudioHandler.stop() // 挂断时停止音频流
            listener?.onCallStateChanged("DISCONNECTED")
        }
    }

    suspend fun sendMessage(to: String, server: String, body: String) {
        withContext(Dispatchers.IO) {
            val sipMessage = """
                MESSAGE sip:$to@$server:5060 SIP/2.0
                Via: SIP/2.0/UDP $localIp:$localPort;branch=z9hG4bK-msg
                Max-Forwards: 70
                To: <sip:$to@$server>
                From: <sip:$lastCaller@$server>;tag=$currentLocalTag
                Call-ID: msg-${System.currentTimeMillis()}
                CSeq: 4 MESSAGE
                Content-Type: text/plain
                Content-Length: ${body.toByteArray().size}

            """.trimIndent().replace("\n", "\r\n") + "\r\n" + body
            sendPacket(sipMessage, server)
        }
    }

    private fun sendSimpleResponse(type: String, server: String, callId: String, rawRequest: String) {
        val from = rawRequest.lineSequence().find { it.startsWith("From:", true) } ?: ""
        val to = rawRequest.lineSequence().find { it.startsWith("To:", true) } ?: ""
        val cseq = rawRequest.lineSequence().find { it.startsWith("CSeq:", true) } ?: ""
        
        val resp = """
            SIP/2.0 $type
            Via: SIP/2.0/UDP $server:5060;branch=z9hG4bK-remote
            To: $to${if(!to.contains("tag=")) ";tag=local${System.currentTimeMillis()}" else ""}
            From: $from
            Call-ID: $callId
            CSeq: $cseq
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        sendPacket(resp, server)
    }

    private fun sendPacket(message: String, server: String) {
        try {
            val serverAddress = InetAddress.getByName(server)
            val data = message.toByteArray()
            val packet = DatagramPacket(data, data.size, serverAddress, 5060)
            socket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送失败: ${e.message}")
        }
    }
}
