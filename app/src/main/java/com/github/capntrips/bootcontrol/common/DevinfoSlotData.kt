package com.github.capntrips.bootcontrol.common

import com.github.capntrips.bootcontrol.common.extensions.ByteBuffer.getByteArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

// https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/DevInfo.h;l=32
class DevinfoSlotData(
    val retryCount: UByte,
    flags: ByteArray,
    val unused: ByteArray,
) {
    companion object Factory {
        fun create(buffer: ByteBuffer): DevinfoSlotData {
            return DevinfoSlotData(
                buffer.get().toUByte(),
                buffer.getByteArray(1),
                buffer.getByteArray(2),
            )
        }
    }

    private var backingFlags: ByteArray = flags
    val flags: ByteArray
        get() = backingFlags
    val unbootable: Boolean
        get() = ByteBuffer.wrap(flags).get().toUByte() and DevinfoImageFlags.Unbootable.position != 0.toUByte()
    val successful: Boolean
        get() = ByteBuffer.wrap(flags).get().toUByte() and DevinfoImageFlags.Successful.position != 0.toUByte()
    var active: Boolean
        get() = ByteBuffer.wrap(flags).get().toUByte() and DevinfoImageFlags.Active.position != 0.toUByte()
        set(value) {
            var flags: UByte = backingFlags[0].toUByte()
            flags = if (value) {
                flags or DevinfoImageFlags.Active.position
            } else {
                flags and DevinfoImageFlags.Active.position.inv()
            }
            backingFlags[0] = flags.toByte()
        }
    val fastbootOk: Boolean
        get() = ByteBuffer.wrap(flags).get().toUByte() and DevinfoImageFlags.FastbootOk.position != 0.toUByte()

    fun byteArray(): ByteArray {
        val data = ByteArray(4)
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(retryCount.toByte())
        buffer.put(backingFlags)
        buffer.put(unused)
        return data
    }
}
