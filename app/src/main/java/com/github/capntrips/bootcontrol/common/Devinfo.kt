package com.github.capntrips.bootcontrol.common

import com.github.capntrips.bootcontrol.common.extensions.ByteBuffer.getByteArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

// https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/DevInfo.h;l=46
class Devinfo(
    val magic: UInt,
    val verMajor: UShort,
    val verMinor: UShort,
    val unused: ByteArray,
    val slots: List<DevinfoSlotData>,
    val unused1: ByteArray
) {
    companion object Factory {
        fun create(data: ByteArray): Devinfo {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return Devinfo(
                buffer.int.toUInt(),
                buffer.short.toUShort(),
                buffer.short.toUShort(),
                buffer.getByteArray(40),
                listOf(DevinfoSlotData.create(buffer), DevinfoSlotData.create(buffer)),
                buffer.getByteArray(72),
            )
        }
    }

    fun byteArray(): ByteArray {
        val data = ByteArray(128)
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(magic.toInt())
        buffer.putShort(verMajor.toShort())
        buffer.putShort(verMinor.toShort())
        buffer.put(unused)
        for (slot in slots) {
            buffer.put(slot.byteArray())
        }
        buffer.put(unused1)
        return data
    }
}
