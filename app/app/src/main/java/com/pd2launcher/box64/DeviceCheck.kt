package com.pd2launcher.box64

import android.os.Build
import android.system.Os
import android.system.OsConstants

/**
 * What the first-start device check found: the memory page size, whether the GPU is an Adreno
 * (KGSL's device opens), else the Mali that Arm's kbase driver reports ([mali], null when none
 * answers), the GPU's name when Android lets the app read it ("Adreno 740", "Mali-G715"), and
 * Android's own version name. Android 13 and ARM64 come from the APK itself (its minSdk and its
 * one ABI).
 */
data class DeviceFacts(
    val pageSize: Long,
    val adreno: Boolean,
    val mali: MaliGpu?,
    val gpu: String?,
    val androidRelease: String,
)

/**
 * Lite's device rules: an Adreno GPU (the bundled Turnip driver) or a Mali the bundled PanVK runs
 * ([MaliGpu.supported]), and 4 KB memory pages.
 */
object DeviceCheck {
    const val PAGE_SIZE = 4096L

    /** The facts of this device; quick (no GPU work), off the main thread all the same. */
    fun run(): DeviceFacts {
        val adreno = HostGpu.adreno
        val mali = HostGpu.mali
        return DeviceFacts(
            pageSize = Os.sysconf(OsConstants._SC_PAGESIZE),
            adreno = adreno,
            mali = mali,
            gpu = gpuName(adreno, GameCopy.adrenoModel(), mali),
            androidRelease = Build.VERSION.RELEASE ?: "",
        )
    }

    /** "Adreno 740" (or "Adreno" without the model) on an Adreno, the Mali's name on a Mali, else null. */
    fun gpuName(adreno: Boolean, adrenoModel: Int?, mali: MaliGpu?): String? = when {
        adrenoModel != null -> "Adreno $adrenoModel"
        adreno -> "Adreno"
        else -> mali?.name
    }

    /** Why this device cannot run the game; empty when it can. */
    fun problems(facts: DeviceFacts): List<Line> = buildList {
        if (!facts.adreno && facts.mali?.supported != true) {
            add(if (facts.gpu.isNullOrBlank()) Line(R.string.device_gpu_unknown) else Line(R.string.device_gpu_not_supported, facts.gpu))
        }
        if (facts.pageSize != PAGE_SIZE) add(Line(R.string.device_page_size, facts.pageSize))
    }
}
