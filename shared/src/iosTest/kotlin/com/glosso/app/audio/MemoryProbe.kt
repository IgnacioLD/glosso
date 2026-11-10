package com.glosso.app.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.darwin.TASK_VM_INFO
import kotlinx.cinterop.UIntVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t

/** The process's `phys_footprint`, the figure iOS uses to kill an app for memory. */
@OptIn(ExperimentalForeignApi::class)
internal object MemoryProbe {
    fun footprint(): Long = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<UIntVar>()
        // TASK_VM_INFO_COUNT: the struct size in natural_t (32-bit) words.
        count.value = (sizeOf<task_vm_info_data_t>() / 4).toUInt()
        val result = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        if (result == 0) info.phys_footprint.toLong() else -1L
    }
}
