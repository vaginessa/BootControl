package com.github.capntrips.bootcontrol.common.extensions

import java.io.DataInputStream
import java.nio.charset.Charset
import kotlin.ByteArray

object ByteArray {
    fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
    fun ByteArray.readString(charset: Charset = Charsets.UTF_8): String = inputStream().bufferedReader(charset).readLine().trimEnd('\u0000')
}
