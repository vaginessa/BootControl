package com.github.capntrips.bootcontrol.common.extensions

import java.nio.ByteBuffer
import kotlin.ByteArray

object ByteBuffer {
    fun ByteBuffer.getByteArray(size: Int): ByteArray {
        val bytes = ByteArray(size)
        get(bytes)
        return bytes
    }
}