package com.pd2launcher.box64

import android.util.Log

/**
 * The GPU as Arm's kbase driver reports it on its CSF interface: [gpuId], the GPU_ID register
 * (arch major:4 arch minor:4 arch rev:4 product major:4 version major:4 version minor:8 status:4,
 * from the top), and the interface version 1.[uapiMinor] the driver agreed to.
 */
data class MaliGpu(val gpuId: Long, val uapiMinor: Int) {
    /** The architecture: 10 for the Mali-G610 and G310, 11 for the G715 and G615, 12, 13. */
    val archMajor: Int get() = ((gpuId shr 28) and 0xF).toInt()

    /** Mesa's product id (PAN_PROD_ID: arch major, arch minor, product major), as PanVK matches it. */
    val productId: Int get() = (archMajor shl 16) or (((gpuId shr 24) and 0xF).toInt() shl 8) or ((gpuId shr 16) and 0xF).toInt()

    /** A readable name: "Mali-G715", or the architecture and the GPU id for a Mali PanVK does not know. */
    val name: String get() = PANVK_MODELS[productId] ?: "Mali v%d (GPU id 0x%08x)".format(archMajor, gpuId)

    /**
     * Whether the bundled PanVK runs this GPU: a CSF Mali of [ARCHES] on an interface our kbase
     * backend takes ([MIN_UAPI_MINOR]), of a model PanVK knows ([PANVK_MODELS]; it refuses any other).
     */
    val supported: Boolean get() = archMajor in ARCHES && uapiMinor >= MIN_UAPI_MINOR && productId in PANVK_MODELS

    companion object {
        /** The CSF Malis our kbase backend is for: v10 to v13. */
        val ARCHES = 10..13

        /** The oldest kbase CSF interface our backend runs on (1.9: MEM_ALLOC_EX; kbase_kmod.c). */
        const val MIN_UAPI_MINOR = 9

        /**
         * The CSF Malis (v10 to v13) the bundled PanVK knows, by Mesa's product id, as the PanVK
         * Mesa pin's src/panfrost/model/pan_model.c (pan_model_list) lists them; PanVK refuses any
         * other GPU (pan_get_model). The G310's five variants share one product id.
         */
        val PANVK_MODELS = mapOf(
            0x0A0807 to "Mali-G610",
            0x0A0C04 to "Mali-G310",
            0x0B0802 to "Mali-G715",
            0x0B0803 to "Mali-G615",
            0x0C0800 to "Mali-G720",
            0x0D0800 to "Mali-G725",
        )
    }
}

/**
 * Reads the Mali GPU id from `/dev/mali0` (mali_probe.c in libpd2lite.so, so it runs before the
 * runtime is unpacked), with the CSF handshake our PanVK backend uses.
 */
object MaliProbe {
    init {
        System.loadLibrary("pd2lite")
    }

    /** {errno (0 on success), gpu_id, uAPI major, uAPI minor}. */
    @JvmStatic
    private external fun nativeProbe(): LongArray

    /** The GPU, or null when `/dev/mali0` is absent, does not answer or is not a CSF kbase. */
    fun probe(): MaliGpu? {
        val (error, gpuId, major, minor) = nativeProbe()
        if (error != 0L) {
            Log.i(Native.TAG, "/dev/mali0: no CSF kbase GPU (errno $error, uAPI $major.$minor)")
            return null
        }
        return MaliGpu(gpuId, minor.toInt())
    }
}
