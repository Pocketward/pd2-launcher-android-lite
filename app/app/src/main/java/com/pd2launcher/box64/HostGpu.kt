package com.pd2launcher.box64

import android.system.Os
import android.system.OsConstants

/**
 * The device's GPU as its kernel driver reports it, probed once per process: an Adreno through
 * KGSL (the bundled Turnip driver), else a Mali through Arm's kbase (the bundled PanVK with our
 * kbase backend). The device check, the session's Vulkan driver ([VulkanDriver]) and Automatic's
 * game resolution and graphics profile follow it.
 */
object HostGpu {
    private const val KGSL = "/dev/kgsl-3d0"

    /** Whether KGSL's device opens: an Adreno. */
    val adreno: Boolean by lazy {
        try {
            Os.close(Os.open(KGSL, OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** kbase's Mali on a device without KGSL; null on an Adreno or when `/dev/mali0` does not answer. */
    val mali: MaliGpu? by lazy { if (adreno) null else MaliProbe.probe() }

    /** Whether the game runs on PanVK: no Adreno, and a Mali of a generation the bundled PanVK runs ([MaliGpu.supported]). */
    val panvk: Boolean get() = !adreno && mali?.supported == true

    /**
     * Whether Automatic's graphics profile is Performance ([GraphicsProfiles.automatic]): an older
     * or entry-level Adreno ([GameCopy.smallAdreno]), or a Mali, whose PanVK path is tuned for it.
     */
    val lowTier: Boolean get() = panvk || GameCopy.smallAdreno()
}

/**
 * The Vulkan driver of a session's Mesa (Zink draws the game's OpenGL on it) and its values:
 * Turnip on an Adreno, PanVK on a supported Mali ([HostGpu.panvk]).
 */
object VulkanDriver {
    /**
     * The environment for the driver under the userland [ul]: [panvk] or Turnip for an Adreno of
     * model [adrenoModel] (null when unknown).
     */
    fun environment(ul: String, panvk: Boolean, adrenoModel: Int?): List<String> {
        val icd = "$ul/usr/share/vulkan/icd.d/" + if (panvk) "panfrost_icd.aarch64.json" else "freedreno_icd.aarch64.json"
        val driver = listOf("VK_DRIVER_FILES=$icd", "VK_ICD_FILENAMES=$icd")
        return driver + if (panvk) {
            listOf(
                // PanVK refuses a v11 Mali (such as the Mali-G715) as "not well-tested" without this;
                // v10, v12 and v13 do not read it (panvk_physical_device.c at the PanVK Mesa pin).
                "PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1",
                // PanVK has no transform feedback, so Zink offers GL 2.1 only and D2GL gets no 3.3
                // core context; with these Zink reports 3.3 with GLSL 3.30, and D2GL, which uses
                // neither transform feedback nor geometry shaders, draws correctly.
                "MESA_GL_VERSION_OVERRIDE=3.3",
                "MESA_GLSL_VERSION_OVERRIDE=330",
            )
        } else {
            // The first Adreno 6xx generation (610 to 619) has no hardware multiview, so Turnip
            // reports Vulkan 1.0 without VK_KHR_maintenance5, which Mesa 26's Zink requires: Mesa
            // then falls back to softpipe. "noconform" makes Turnip report 1.3 (multiview emulated);
            // Zink, D2GL and Wine do not use multiview.
            listOf("TU_DEBUG=" + if ((adrenoModel ?: 0) in 600..619) "sysmem,noconform" else "sysmem")
        }
    }
}
