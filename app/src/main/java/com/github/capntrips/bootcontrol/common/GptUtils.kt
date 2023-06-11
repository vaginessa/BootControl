package com.github.capntrips.bootcontrol.common

import android.util.Base64
import android.util.Log
import com.github.capntrips.bootcontrol.BootControl
import com.github.capntrips.bootcontrol.common.extensions.ByteArray.toHex
import com.topjohnwu.superuser.Shell
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

// https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/GptUtils.h;l=57
class GptUtils(private val devPath: String) {
    companion object {
        const val TAG: String = "BootControl/GptUtils"

        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/GptUtils.h;l=29
        const val GPT_SIGNATURE: ULong = 0x5452415020494645UL
    }

    private var blockSize: UInt? = null
    private var gptPrimary: GptHeader? = null
    private var gptBackup: GptHeader? = null
    private var entryArray: MutableList<GptEntry> = ArrayList()
    private var entries: MutableMap<String, GptEntry> = HashMap()

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/GptUtils.cpp;l=58
    fun load(): Boolean {
        val ret = Shell.su("blockdev --getbsz $devPath").exec()
        if (!ret.isSuccess || ret.out.size != 1) {
            Log.e(TAG, "failed to get block size")
            return false
        }
        blockSize = ret.out[0].toUInt()

        var data = Base64.decode(Shell.su("dd if=$devPath skip=$blockSize bs=1 count=92 status=none | base64 -w 0").exec().out[0], Base64.DEFAULT)
        gptPrimary = GptHeader.create(data)

        if (!gptPrimary!!.validate()) {
            Log.e(TAG, "error validating gpt header")
            return false
        }

        // TODO: Remove before release
        val debug = !Shell.su("[ -e /sdcard/Download/gpt-primary-header-original.img ]").exec().isSuccess
        if (debug) {
            Shell.su("dd if=$devPath skip=$blockSize of=/sdcard/Download/gpt-primary-header-original.img bs=1 count=92 status=none").exec()
            Shell.su("dd if=$devPath skip=$blockSize of=/sdcard/Download/gpt-primary-header-working.img bs=1 count=92 status=none").exec()

            val crc = CRC32()
            crc.update(data)
            println("gpt-primary-header: ${crc.value}")

            crc.reset()
            crc.update(gptPrimary!!.byteArray())
            println("gpt-primary-header: ${crc.value}")
        }

        entryArray.clear()
        val entriesStart: ULong = blockSize!! * gptPrimary!!.startLba
        val entriesSize: UInt = gptPrimary!!.entrySize * gptPrimary!!.entryCount

        data = Base64.decode(Shell.su("dd if=$devPath skip=$entriesStart bs=1 count=$entriesSize status=none | base64 -w 0").exec().out[0], Base64.DEFAULT)
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until gptPrimary!!.entryCount.toInt()) {
            entryArray.add(GptEntry.create(buffer))
        }

        // TODO: Remove before release
        if (debug) {
            Shell.su("dd if=$devPath skip=$entriesStart of=/sdcard/Download/gpt-primary-entries-original.img bs=1 count=$entriesSize status=none").exec()
            Shell.su("dd if=$devPath skip=$entriesStart of=/sdcard/Download/gpt-primary-entries-working.img bs=1 count=$entriesSize status=none").exec()

            println("gpt-primary-entries: ${gptPrimary!!.entriesCrc32}")

            val crc = CRC32()
            crc.update(data)
            println("gpt-primary-entries: ${crc.value}")

            crc.reset()
            for (entry in entryArray) {
                crc.update(entry.byteArray())
            }
            println("gpt-primary-entries: ${crc.value}")
        }

        val backupStart: ULong = blockSize!! * gptPrimary!!.backupLba
        data = Base64.decode(Shell.su("dd if=$devPath skip=$backupStart bs=1 count=92 status=none | base64 -w 0").exec().out[0], Base64.DEFAULT)
        gptBackup = GptHeader.create(data)

        if (!gptBackup!!.validate()) {
            Log.e(TAG, "error validating gpt backup")
            return false
        }

        // TODO: Remove before release
        if (debug) {
            Shell.su("dd if=$devPath skip=$backupStart of=/sdcard/Download/gpt-backup-header-original.img bs=1 count=92 status=none").exec()
            Shell.su("dd if=$devPath skip=$backupStart of=/sdcard/Download/gpt-backup-header-working.img bs=1 count=92 status=none").exec()

            val crc = CRC32()
            crc.update(data)
            println("gptBackup: ${crc.value}")

            crc.reset()
            crc.update(gptBackup!!.byteArray())
            println("gptBackup: ${crc.value}")

            val backupEntryArray: MutableList<GptEntry> = ArrayList()
            @Suppress("NAME_SHADOWING")
            val entriesStart = blockSize!! * gptBackup!!.startLba
            @Suppress("NAME_SHADOWING")
            val entriesSize: UInt = gptBackup!!.entrySize * gptBackup!!.entryCount

            data = Base64.decode(Shell.su("dd if=$devPath skip=$entriesStart bs=1 count=$entriesSize status=none | base64 -w 0").exec().out[0], Base64.DEFAULT)
            @Suppress("NAME_SHADOWING")
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until gptBackup!!.entryCount.toInt()) {
                backupEntryArray.add(GptEntry.create(buffer))
            }

            Shell.su("dd if=$devPath skip=$entriesStart of=/sdcard/Download/gpt-backup-entries-original.img bs=1 count=$entriesSize status=none").exec()
            Shell.su("dd if=$devPath skip=$entriesStart of=/sdcard/Download/gpt-backup-entries-working.img bs=1 count=$entriesSize status=none").exec()

            println("gpt-backup-entries: ${gptBackup!!.entriesCrc32}")

            crc.reset()
            crc.update(data)
            println("gpt-backup-entries: ${crc.value}")

            crc.reset()
            for (entry in backupEntryArray) {
                crc.update(entry.byteArray())
            }
            println("gpt-backup-entries: ${crc.value}")
        }

        for (entry in entryArray) {
            entries[entry.name] = entry
        }

        return true
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/GptUtils.cpp;l=123
    fun getPartitionEntry(name: String): GptEntry? {
        return entries[name]
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/GptUtils.cpp;l=127
    fun sync() {
        val checksum = CRC32()
        for (entry in entryArray) {
            checksum.update(entry.byteArray())
        }
        gptPrimary!!.entriesCrc32 = checksum.value.toUInt()

        val crc = gptPrimary!!.crc32
        gptPrimary!!.crc32 = 0U

        checksum.reset()
        checksum.update(gptPrimary!!.byteArray())
        gptPrimary!!.crc32 = checksum.value.toUInt()

        if (crc == gptPrimary!!.crc32) {
            return
        }

        Log.i(TAG, "updating GPT")

        // TODO: restore before release
        // of=$devPath skip=$blockSize
        var ret = Shell.su("echo ${Base64.encodeToString(gptPrimary!!.byteArray(), Base64.NO_WRAP)} | base64 -d | dd of=/sdcard/Download/gpt-primary-header-working.img bs=1 count=92 conv=notrunc status=none").exec()
        if (!ret.isSuccess) {
            Log.e(TAG, "failed to write gpt primary header")
            return
        }


        // TODO: restore before release
        // var entriesStart: ULong = blockSize!! * gptPrimary!!.startLba
        var entriesStart: ULong = 0UL
        for (i in 0 until gptPrimary!!.entryCount.toInt()) {
            val entry = entryArray[i]
            if (entry.name.startsWith("boot_")) {
                val entryStart = entriesStart + i.toUInt() * 128U
                // TODO: restore before release
                // of=$devPath
                ret = Shell.su("echo ${Base64.encodeToString(entry.byteArray(), Base64.NO_WRAP)} | base64 -d | dd of=/sdcard/Download/gpt-primary-entries-working.img seek=$entryStart bs=1 count=128 conv=notrunc status=none").exec()
                if (!ret.isSuccess) {
                    Log.e(TAG, String.format("failed to write gpt partition entry %s", entry.name))
                    return
                }
            }
        }

        // TODO: restore before release
        // entriesStart = blockSize!! * gptBackup!!.startLba
        entriesStart = 0UL
        for (i in 0 until gptBackup!!.entryCount.toInt()) {
            val entry = entryArray[i]
            if (entry.name.startsWith("boot_")) {
                val entryStart = entriesStart + i.toUInt() * 128U
                // TODO: restore before release
                // of=$devPath
                ret = Shell.su("echo ${Base64.encodeToString(entry.byteArray(), Base64.NO_WRAP)} | base64 -d | dd of=/sdcard/Download/gpt-backup-entries-working.img seek=$entryStart bs=1 count=128 conv=notrunc status=none").exec()
                if (!ret.isSuccess) {
                    Log.e(TAG, String.format("failed to write gpt backup partition entry %s", entry.name))
                    return
                }
            }
        }

        gptBackup!!.entriesCrc32 = gptPrimary!!.entriesCrc32
        gptBackup!!.crc32 = 0U

        checksum.reset()
        checksum.update(gptBackup!!.byteArray())
        gptBackup!!.crc32 = checksum.value.toUInt()

        // TODO: restore before release
        // of=$devPath skip=${blockSize * gptBackup!!.startLba}
        ret = Shell.su("echo ${Base64.encodeToString(gptBackup!!.byteArray(), Base64.NO_WRAP)} | base64 -d | dd of=/sdcard/Download/gpt-backup-header-working.img bs=1 count=92 conv=notrunc status=none").exec()
        if (!ret.isSuccess) {
            Log.e(TAG, "failed to write gpt backup header")
            return
        }
    }
}
