package com.github.capntrips.bootcontrol.common

import android.util.Log
import com.github.capntrips.bootcontrol.common.extensions.ByteArray.readString
import com.github.capntrips.bootcontrol.common.extensions.ByteBuffer.getByteArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

// https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/GptUtils.h;l=40
class GptHeader (
    val signature: ULong,
    val revision: UInt,
    val headerSize: UInt,
    var crc32: UInt,
    val reserved: UInt,
    val currentLba: ULong,
    val backupLba: ULong,
    val firstUsableLba: ULong,
    val lastUsableLba: ULong,
    diskGuid: ByteArray,
    val startLba: ULong,
    val entryCount: UInt,
    val entrySize: UInt,
    var entriesCrc32: UInt,
) {
    companion object Factory {
        fun create(data: ByteArray): GptHeader {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return GptHeader(
                buffer.long.toULong(),
                buffer.int.toUInt(),
                buffer.int.toUInt(),
                buffer.int.toUInt(),
                buffer.int.toUInt(),
                buffer.long.toULong(),
                buffer.long.toULong(),
                buffer.long.toULong(),
                buffer.long.toULong(),
                buffer.getByteArray(16),
                buffer.long.toULong(),
                buffer.int.toUInt(),
                buffer.int.toUInt(),
                buffer.int.toUInt(),
            )
        }
    }

    private val backingDiskGuid: ByteArray = diskGuid
    val diskGuid: String
        get() = backingDiskGuid.readString()

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/GptUtils.cpp;l=35
    fun validate(): Boolean {
        if (signature != GptUtils.GPT_SIGNATURE) {
            Log.e(GptUtils.TAG, String.format("invalid gpt signature 0x%x", signature))
            return false
        }

        if (headerSize != 92U) {
            Log.e(GptUtils.TAG, String.format("invalid gpt header size %u", headerSize))
            return false
        }

        if (entrySize != 128U) {
            Log.e(GptUtils.TAG, String.format("invalid gpt entry size %u", entrySize))
            return false
        }

        return true
    }

    fun byteArray(): ByteArray {
        val data = ByteArray(92)
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(signature.toLong())
        buffer.putInt(revision.toInt())
        buffer.putInt(headerSize.toInt())
        buffer.putInt(crc32.toInt())
        buffer.putInt(reserved.toInt())
        buffer.putLong(currentLba.toLong())
        buffer.putLong(backupLba.toLong())
        buffer.putLong(firstUsableLba.toLong())
        buffer.putLong(lastUsableLba.toLong())
        buffer.put(backingDiskGuid)
        buffer.putLong(startLba.toLong())
        buffer.putInt(entryCount.toInt())
        buffer.putInt(entrySize.toInt())
        buffer.putInt(entriesCrc32.toInt())
        return data
    }
}
