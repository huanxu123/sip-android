package com.example.myapplication1.data.transport

import kotlin.experimental.and
import kotlin.experimental.or

/**
 * G.711 PCMU (u-Law) 编解码器，用于与 PC 端 G711.java 对齐
 */
object G711Codec {

    private val SIGN_BIT = 0x80
    private val QUANT_MASK = 0xf
    private val SEG_SHIFT = 4
    private val SEG_MASK = 0x70
    private val BIAS = 0x84
    private val CLIP = 32635

    private val segEnd = intArrayOf(0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF)

    fun encodePCMU(pcm: Short): Byte {
        var pcmVal = pcm.toInt()
        val mask: Int
        if (pcmVal < 0) {
            pcmVal = BIAS - pcmVal
            mask = 0x7F
        } else {
            pcmVal += BIAS
            mask = 0xFF
        }
        if (pcmVal > CLIP) pcmVal = CLIP

        var seg = 8
        for (i in 0..7) {
            if (pcmVal <= segEnd[i]) {
                seg = i
                break
            }
        }

        if (seg >= 8) return (0x80 xor mask).toByte()
        
        var uval = (seg shl SEG_SHIFT) or ((pcmVal shr (seg + 3)) and QUANT_MASK)
        return (uval xor mask).toByte()
    }

    fun decodePCMU(uLaw: Byte): Short {
        var uVal = uLaw.toInt() xor 0xFF
        val t = ((uVal and QUANT_MASK) shl 3) + BIAS
        val sample = t shl ((uVal and SEG_MASK) shr SEG_SHIFT)
        val result = if ((uVal and SIGN_BIT) != 0) (BIAS - sample) else (sample - BIAS)
        return result.toShort()
    }
}
