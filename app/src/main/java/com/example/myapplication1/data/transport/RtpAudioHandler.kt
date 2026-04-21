package com.example.myapplication1.data.transport

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

@SuppressLint("MissingPermission")
object RtpAudioHandler {
    private const val TAG = "RtpAudioHandler"
    private const val SAMPLE_RATE = 8000
    private const val BUFFER_SIZE = 160 // 20ms of audio at 8kHz

    private var isRunning = false
    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null
    
    // RTP 状态
    private var seqNum: Short = 0
    private var timestamp: Int = 0
    private val ssrc = (Math.random() * 1000000).toInt()

    fun start(remoteIp: String, remotePort: Int, localListenPort: Int = 8000) {
        if (isRunning) return
        isRunning = true

        thread(name = "RtpSendThread") {
            try {
                sendSocket = DatagramSocket()
                val remoteAddr = InetAddress.getByName(remoteIp)
                
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                
                recorder.startRecording()
                val pcmBuffer = ShortArray(BUFFER_SIZE)
                
                while (isRunning) {
                    val read = recorder.read(pcmBuffer, 0, BUFFER_SIZE)
                    if (read > 0) {
                        val rtpPacket = buildRtpPacket(pcmBuffer)
                        val packet = DatagramPacket(rtpPacket, rtpPacket.size, remoteAddr, remotePort)
                        sendSocket?.send(packet)
                    }
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                Log.e(TAG, "发送线程异常: ${e.message}")
            }
        }

        thread(name = "RtpReceiveThread") {
            try {
                receiveSocket = DatagramSocket(localListenPort)
                val track = AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, 
                    AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), AudioTrack.MODE_STREAM)
                
                track.play()
                val rtpBuffer = ByteArray(200)
                
                while (isRunning) {
                    val packet = DatagramPacket(rtpBuffer, rtpBuffer.size)
                    receiveSocket?.receive(packet)
                    
                    // 剥离 RTP 头（12字节），解码 PCMU
                    val payload = rtpBuffer.sliceArray(12 until packet.length)
                    val pcmOut = ShortArray(payload.size)
                    for (i in payload.indices) {
                        pcmOut[i] = G711Codec.decodePCMU(payload[i])
                    }
                    track.write(pcmOut, 0, pcmOut.size)
                }
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e(TAG, "接收线程异常: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        sendSocket?.close()
        receiveSocket?.close()
    }

    private fun buildRtpPacket(pcm: ShortArray): ByteArray {
        val rtpHeaderSize = 12
        val payloadSize = pcm.size
        val packet = ByteBuffer.allocate(rtpHeaderSize + payloadSize)

        // RTP Header (V=2, P=0, X=0, CC=0, M=0, PT=0 [PCMU])
        packet.put(0x80.toByte())
        packet.put(0x00.toByte()) // Payload Type 0 (PCMU)
        packet.putShort(seqNum++)
        packet.putInt(timestamp)
        packet.putInt(ssrc)
        
        timestamp += payloadSize

        // Payload
        for (sample in pcm) {
            packet.put(G711Codec.encodePCMU(sample))
        }

        return packet.array()
    }
}
