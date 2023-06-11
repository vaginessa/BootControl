package com.github.capntrips.bootcontrol.common

import com.github.capntrips.bootcontrol.common.extensions.ByteArray.readString
import com.github.capntrips.bootcontrol.common.extensions.ByteBuffer.getByteArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

// https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/GptUtils.h;l=31
class GptEntry (
    typeGuid: ByteArray,
    guid: ByteArray,
    val firstLba: ULong,
    val lastLba: ULong,
    var attr: ULong,
    name: ByteArray,
) {
    companion object Factory {
        fun create(data: ByteArray): GptEntry {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return create(buffer)
        }
        fun create(buffer: ByteBuffer): GptEntry {
            return GptEntry(
                buffer.getByteArray(16),
                buffer.getByteArray(16),
                buffer.long.toULong(),
                buffer.long.toULong(),
                buffer.long.toULong(),
                buffer.getByteArray(72),
            )
        }
    }

    private val backingTypeGuid: ByteArray = typeGuid
    val typeGuid: String
        get() = backingTypeGuid.readString()

    private val backingGuid: ByteArray = guid
    val guid: String
        get() = backingGuid.readString()

    private val backingName: ByteArray = name
    val name: String
        get() = backingName.readString(Charsets.UTF_16LE)

    fun byteArray(): ByteArray {
        val data = ByteArray(128)
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(backingTypeGuid)
        buffer.put(backingGuid)
        buffer.putLong(firstLba.toLong())
        buffer.putLong(lastLba.toLong())
        buffer.putLong(attr.toLong())
        buffer.put(backingName)
        return data
    }
}
