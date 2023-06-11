package com.github.capntrips.bootcontrol

import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.capntrips.bootcontrol.common.Devinfo
import com.github.capntrips.bootcontrol.common.GptEntry
import com.github.capntrips.bootcontrol.common.GptUtils
import com.github.capntrips.bootcontrol.common.extensions.ByteArray.toHex
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.zip.CRC32


// https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.h;l=37
class BootControl constructor(
    context: Context,
    private val _isRefreshing : MutableStateFlow<Boolean>
) : ViewModel() {
    companion object {
        const val TAG: String = "BootControl/BootControl"

        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/DevInfo.h;l=29
        const val MAGIC: UInt = 0x49564544U

        // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=48
        const val BOOT_A_PATH: String = "/dev/block/by-name/boot_a"
        const val BOOT_B_PATH: String = "/dev/block/by-name/boot_b"
        const val DEVINFO_PATH: String = "/dev/block/by-name/devinfo"

        val AB_ATTR_ACTIVE: ULong = 1UL shl 54
        val AB_ATTR_SUCCESSFUL: ULong = 1UL shl 58
        val AB_ATTR_UNBOOTABLE: ULong = 1UL shl 59
    }

    private var _slotA: MutableStateFlow<SlotState>
    private var _slotB: MutableStateFlow<SlotState>
    val slotSuffix: String

    private var isDevinfoValid: Boolean = false
    private lateinit var devinfo: Devinfo
    private var initialized: Boolean = false

    val slotA: StateFlow<SlotState>
        get() = _slotA.asStateFlow()
    val slotB: StateFlow<SlotState>
        get() = _slotB.asStateFlow()

    private fun _refresh(context: Context) {
        isDevinfoValid = isDevinfoValid()
        if (!isDevinfoValid && !isGptValid()) {
            throw Error("Failed to validate BootControl")
        }
        slotA.value.unbootable = !isSlotBootable(0U)
        slotA.value.successful = isSlotMarkedSuccessful(0U)
        slotB.value.unbootable = !isSlotBootable(1U)
        slotB.value.successful = isSlotMarkedSuccessful(1U)
        val activeSlot = getActiveBootSlot()
        slotA.value.active = activeSlot == 0U
        slotB.value.active = activeSlot == 1U
    }

    fun refresh(context: Context) {
        launch {
            _refresh(context)
        }
    }

    init {
        _slotA = MutableStateFlow(SlotState())
        _slotB = MutableStateFlow(SlotState())
        slotSuffix = getSuffix(getCurrentSlot())
        _refresh(context)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.emit(true)
            block()
            _isRefreshing.emit(false)
        }
    }

    private fun log(context: Context, message: String, shouldThrow: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, message)
        if (shouldThrow) {
            throw Exception(message)
        }
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=69
    fun getDevPath(slot: UInt): String {
        val path = if (slot == 0U) BOOT_A_PATH else BOOT_B_PATH
        val ret = Shell.su("readlink $path").exec()
        if (!ret.isSuccess || ret.out.size != 1) {
            Log.e(TAG, String.format("readlink failed for boot device %s", path))
            return ""
        }
        return ret.out[0].substring(0, "/dev/block/sdX".length)
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=85
    fun isSlotFlagSet(slot: UInt, flag: ULong): Boolean {
        val devPath = getDevPath(slot)
        if (devPath.isEmpty()) {
            Log.i(TAG, String.format("Could not get device path for slot %d", slot))
            return false
        }

        val gpt = GptUtils(devPath)
        if (!gpt.load()) {
            Log.i(TAG, "failed to load gpt data")
            return false
        }

        val e: GptEntry? = gpt.getPartitionEntry(if (slot != 0U) "boot_b" else "boot_a")
        if (e == null) {
            Log.i(TAG, "failed to get gpt entry")
            return false
        }

        return e.attr and flag != 0UL
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=107
    fun setSlotFlag(slot: UInt, flag: ULong): Boolean {
        val devPath = getDevPath(slot)
        if (devPath.isEmpty()) {
            Log.i(TAG, String.format("Could not get device path for slot %d", slot))
            return false
        }

        val gpt = GptUtils(devPath)
        if (!gpt.load()) {
            Log.i(TAG, "failed to load gpt data")
            return false
        }

        val e: GptEntry? = gpt.getPartitionEntry(if (slot != 0U) "boot_b" else "boot_a")
        if (e == null) {
            Log.i(TAG, "failed to get gpt entry")
            return false
        }

        e.attr = e.attr or flag
        gpt.sync()

        return true
    }

    private fun isGptValid(): Boolean {
        val devPath0 = getDevPath(0U)
        if (devPath0.isEmpty()) {
            Log.e(TAG, "Could not get device path for slot 0")
            return false
        }

        val devPath1 = getDevPath(1U)
        if (devPath1.isEmpty()) {
            Log.e(TAG, "Could not get device path for slot 1")
            return false
        }

        if (devPath0 != devPath1) {
            Log.e(TAG, "Unexpected device paths")
            return false
        }

        val gpt = GptUtils(devPath0)
        if (!gpt.load()) {
            Log.e(TAG, "failed to load gpt data")
            return false
        }

        val entry0 = gpt.getPartitionEntry("boot_a")
        val entry1 = gpt.getPartitionEntry("boot_b")
        if (entry0 == null || entry1 == null) {
            Log.e(TAG,  "failed to get entries for boot partitions")
            return false
        }

        return true
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=137
    private fun isDevinfoValid(): Boolean {
        val data = Base64.decode(Shell.su("dd if=$DEVINFO_PATH bs=1 count=128 status=none | base64 -w 0").exec().out[0], Base64.DEFAULT)
        devinfo = Devinfo.create(data)

        if (devinfo.magic != MAGIC) {
            return false
        }

        // TODO: Remove before release
        val debug = !Shell.su("[ -e /sdcard/Download/devinfo-original.img ]").exec().isSuccess
        if (debug) {
            Log.w(TAG, "backing up devinfo")

            Shell.su("dd if=$DEVINFO_PATH of=/sdcard/Download/devinfo-original.img bs=1 count=128 status=none").exec()
            Shell.su("dd if=$DEVINFO_PATH of=/sdcard/Download/devinfo-working.img bs=1 count=128 status=none").exec()

            val crc = CRC32()
            crc.update(data)
            println("devinfo: ${crc.value}")

            crc.reset()
            crc.update(devinfo.byteArray())
            println("devinfo: ${crc.value}")
        } else {
            Log.w(TAG, "skipping devinfo backup")
        }

        val version: UInt = (devinfo.verMajor.toUInt() shl 16) or devinfo.verMinor.toUInt()
        if (version >= 0x00030003U) {
            return true
        }

        return false
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=162
    private fun devinfoSync(): Boolean {
        if (!isDevinfoValid) {
            return false
        }

        // TODO: remove before release
        println(devinfo.byteArray().toHex())

        // TODO: restore before release
        // of=$DEVINFO_PATH
        val ret = Shell.su("echo ${Base64.encodeToString(devinfo.byteArray(), Base64.NO_WRAP)} | base64 -d | dd of=/sdcard/Download/devinfo-working.img bs=1 count=128 conv=notrunc status=none").exec()
        if (!ret.isSuccess) {
            Log.e(GptUtils.TAG, "failed to write devinfo")
            return false
        }

        return true
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=187
    fun getNumberSlots(): UInt {
        var slots: UInt = 0U

        if (Shell.su("[ -e $BOOT_A_PATH ]").exec().isSuccess) ++slots

        if (Shell.su("[ -e $BOOT_B_PATH ]").exec().isSuccess) ++slots

        return slots
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=199
    fun getCurrentSlot(): UInt {
        return if (Shell.su("getprop ro.boot.slot_suffix").exec().out[0] == "_b") 1U else 0U
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=235
    fun setActiveBootSlot(slot: UInt): Boolean {
        if (slot >= 2U) {
            Log.e(TAG, "Invalid slot")
            return false
        }

        if (isDevinfoValid) {
            val activeSlotData = devinfo.slots[slot.toInt()]
            val inactiveSlotData = devinfo.slots[(slot.toInt() + 1).mod(2)]

            // TODO: remove before release
            // println("active flags:   " + activeSlotData.flags[0])
            // println("inactive flags: " + inactiveSlotData.flags[0])
            // println(activeSlotData.byteArray().toHex())
            // println(inactiveSlotData.byteArray().toHex())
            println(devinfo.byteArray().toHex())

            inactiveSlotData.active = false
            activeSlotData.active = true


            // TODO: remove before release
            // println("active flags:   " + activeSlotData.flags[0])
            // println("inactive flags: " + inactiveSlotData.flags[0])
            // println(activeSlotData.byteArray().toHex())
            // println(inactiveSlotData.byteArray().toHex())
            println(devinfo.byteArray().toHex())

            if (!devinfoSync()) {
                Log.e(TAG, "Could not update DevInfo data")
                return false
            }
        } else {
            val devPath = getDevPath(slot)
            if (devPath.isEmpty()) {
                Log.e(TAG, String.format("Could not get device path for slot %d", slot))
                return false
            }

            val gpt = GptUtils(devPath)
            if (!gpt.load()) {
                Log.e(TAG, "failed to load gpt data")
                return false
            }

            val activeEntry = gpt.getPartitionEntry(if (slot == 0U) "boot_a" else "boot_b")
            val inactiveEntry = gpt.getPartitionEntry(if (slot == 0U) "boot_b" else "boot_a")
            if (activeEntry == null || inactiveEntry == null) {
                Log.e(TAG,  "failed to get entries for boot partitions")
                return false
            }

            Log.v(TAG, String.format("slot active attributes 0x%x", activeEntry.attr.toLong()))
            Log.v(TAG, String.format("slot inactive attributes 0x%x", inactiveEntry.attr.toLong()))

            inactiveEntry.attr = inactiveEntry.attr and AB_ATTR_ACTIVE.inv()
            activeEntry.attr = activeEntry.attr or AB_ATTR_ACTIVE

            Log.v(TAG, String.format("slot active attributes 0x%x", activeEntry.attr.toLong()))
            Log.v(TAG, String.format("slot inactive attributes 0x%x", inactiveEntry.attr.toLong()))

            gpt.sync()
        }
        return true
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=352
    fun isSlotBootable(slot: UInt): Boolean {
        if (getNumberSlots() == 0U) {
            return false
        }
        if (slot >= getNumberSlots()) {
            throw Error("Invalid slot")
        }

        val unbootable = if (isDevinfoValid) {
            val slotData = devinfo.slots[slot.toInt()]
            slotData.unbootable
        } else {
            isSlotFlagSet(slot, AB_ATTR_UNBOOTABLE)
        }

        return !unbootable
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=369
    fun isSlotMarkedSuccessful(slot: UInt): Boolean {
        if (getNumberSlots() == 0U) {
            return true
        }
        if (slot >= getNumberSlots()) {
            throw Error("Invalid slot")
        }

        val successful = if (isDevinfoValid) {
            val slotData = devinfo.slots[slot.toInt()]
            slotData.successful
        } else {
            isSlotFlagSet(slot, AB_ATTR_SUCCESSFUL)
        }

        return successful
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=390
    fun getSuffix(slot: UInt): String {
        return if (slot == 0U) "_a" else if (slot == 1U) "_b" else ""
    }

    // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r49:device/google/gs101/interfaces/boot/1.2/BootControl.cpp;l=414
    fun getActiveBootSlot(): UInt {
        if (getNumberSlots() == 0U) {
            return 0U
        }

        return if (isDevinfoValid) {
            if (devinfo.slots[1].active) 1U else 0U
        } else {
            if (isSlotFlagSet(1U, AB_ATTR_ACTIVE)) 1U else 0U
        }
    }
}
