package com.github.capntrips.bootcontrol.common

enum class DevinfoImageFlags(val position: UByte) {
    Unbootable(1U),
    Successful(2U),
    Active(4U),
    FastbootOk(8U),
}
