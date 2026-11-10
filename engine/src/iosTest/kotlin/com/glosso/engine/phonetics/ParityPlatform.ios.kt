@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.glosso.engine.phonetics

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.integer_tVar
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.posix.getenv
import platform.posix.memcpy
import kotlinx.cinterop.toKString

internal actual val ParityPlatform: ParityIo = object : ParityIo {
    override val name: String = "ios"

    override fun env(name: String): String? = getenv(name)?.toKString()

    override fun readBytes(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path) ?: error("cannot read $path")
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }

    override fun readText(path: String): String =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: error("cannot read $path")

    override fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

    override fun writeBytes(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile(path, atomically = true)) { "cannot write $path" }
    }

    override fun mkdirs(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    }

    /** Peak phys_footprint of the process so far: the high-water mark iOS's memory limit acts on. */
    override fun peakMemoryBytes(): Long? = vmInfo { it.ledger_phys_footprint_peak }

    /** Current phys_footprint: the number iOS uses to decide when to kill an app for memory. */
    override fun footprintBytes(): Long? = vmInfo { it.phys_footprint.toLong() }

    private fun vmInfo(read: (task_vm_info_data_t) -> Long): Long? = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).toUInt()
        val kr = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        if (kr == KERN_SUCCESS) read(info) else null
    }
}
