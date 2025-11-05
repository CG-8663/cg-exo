package com.exo.android.node.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.FileReader

/**
 * Device compute capabilities in TFLOPS
 * Mirrors exo/topology/device_capabilities.py:12-22
 */
@Serializable
data class DeviceFlops(
    val fp32: Float,  // FP32 TFLOPS
    val fp16: Float,  // FP16 TFLOPS
    val int8: Float   // INT8 TFLOPS
) {
    override fun toString(): String {
        return "fp32: %.2f TFLOPS, fp16: %.2f TFLOPS, int8: %.2f TFLOPS".format(fp32, fp16, int8)
    }

    fun toMap(): Map<String, Float> = mapOf(
        "fp32" to fp32,
        "fp16" to fp16,
        "int8" to int8
    )
}

/**
 * Complete device capabilities including model, chip, memory, and compute power
 * Mirrors exo/topology/device_capabilities.py:25-39
 */
@Serializable
data class DeviceCapabilities(
    val model: String,      // Device model (e.g., "Pixel 8 Pro")
    val chip: String,       // Chip name (e.g., "Qualcomm Snapdragon 8 Gen 3")
    val memory: Int,        // Total RAM in MB
    val flops: DeviceFlops
) {
    override fun toString(): String {
        return "Model: $model. Chip: $chip. Memory: ${memory}MB. Flops: $flops"
    }

    fun toMap(): Map<String, Any> = mapOf(
        "model" to model,
        "chip" to chip,
        "memory" to memory,
        "flops" to flops.toMap()
    )

    companion object {
        val UNKNOWN = DeviceCapabilities(
            model = "Unknown Model",
            chip = "Unknown Chip",
            memory = 0,
            flops = DeviceFlops(0f, 0f, 0f)
        )
    }
}

/**
 * Database of known Android chip performance characteristics
 * Based on exo/topology/device_capabilities.py CHIP_FLOPS
 */
object AndroidChipDatabase {
    private val CHIP_FLOPS = mapOf(
        // Qualcomm Snapdragon 8 Gen Series
        "Qualcomm Snapdragon 8 Gen 3" to DeviceFlops(3.5f, 7.0f, 14.0f),
        "Qualcomm Snapdragon 8 Gen 2" to DeviceFlops(3.0f, 6.0f, 12.0f),
        "Qualcomm Snapdragon 8 Gen 1" to DeviceFlops(2.7f, 5.4f, 10.8f),
        "Qualcomm Snapdragon 888" to DeviceFlops(2.5f, 5.0f, 10.0f),
        "Qualcomm Snapdragon 870" to DeviceFlops(2.3f, 4.6f, 9.2f),
        "Qualcomm Snapdragon 865" to DeviceFlops(2.0f, 4.0f, 8.0f),

        // Qualcomm Snapdragon 7 Series
        "Qualcomm Snapdragon 7 Gen 3" to DeviceFlops(2.0f, 4.0f, 8.0f),
        "Qualcomm Snapdragon 7 Gen 2" to DeviceFlops(1.8f, 3.6f, 7.2f),
        "Qualcomm Snapdragon 780G" to DeviceFlops(1.5f, 3.0f, 6.0f),

        // MediaTek Dimensity
        "MediaTek Dimensity 9300" to DeviceFlops(2.8f, 5.6f, 11.2f),
        "MediaTek Dimensity 9200" to DeviceFlops(2.5f, 5.0f, 10.0f),
        "MediaTek Dimensity 9000" to DeviceFlops(2.3f, 4.6f, 9.2f),
        "MediaTek Dimensity 8200" to DeviceFlops(2.0f, 4.0f, 8.0f),

        // Google Tensor
        "Google Tensor G3" to DeviceFlops(2.0f, 4.0f, 8.0f),
        "Google Tensor G2" to DeviceFlops(1.8f, 3.6f, 7.2f),
        "Google Tensor" to DeviceFlops(1.5f, 3.0f, 6.0f),

        // Samsung Exynos
        "Samsung Exynos 2400" to DeviceFlops(2.3f, 4.6f, 9.2f),
        "Samsung Exynos 2200" to DeviceFlops(0.0f, 0.0f, 0.0f),  // Zero FLOPS - DummyEngine can't handle inference

        // Apple A-series (for reference, though not Android)
        "Apple A17 Pro" to DeviceFlops(2.15f, 4.30f, 8.60f),
        "Apple A16 Bionic" to DeviceFlops(1.79f, 3.58f, 7.16f),
        "Apple A15 Bionic" to DeviceFlops(1.37f, 2.74f, 5.48f)
    )

    /**
     * Database mapping specific phone models to their chipsets
     * More reliable than hardware string parsing
     */
    private val PHONE_MODEL_TO_CHIP = mapOf(
        // Samsung Galaxy S22 Ultra variants
        "SM-S908U" to "Qualcomm Snapdragon 8 Gen 1",  // US
        "SM-S908U1" to "Qualcomm Snapdragon 8 Gen 1", // US unlocked
        "SM-S908N" to "Qualcomm Snapdragon 8 Gen 1",  // Korea
        "SM-S908W" to "Qualcomm Snapdragon 8 Gen 1",  // Canada
        "SM-S908E" to "Samsung Exynos 2200",          // Europe/International
        "SM-S908B" to "Samsung Exynos 2200",          // UK/International

        // Samsung Galaxy S23 Ultra (all Snapdragon 8 Gen 2)
        "SM-S918U" to "Qualcomm Snapdragon 8 Gen 2",
        "SM-S918U1" to "Qualcomm Snapdragon 8 Gen 2",
        "SM-S918B" to "Qualcomm Snapdragon 8 Gen 2",
        "SM-S918N" to "Qualcomm Snapdragon 8 Gen 2",

        // Samsung Galaxy S24 Ultra (all Snapdragon 8 Gen 3)
        "SM-S928U" to "Qualcomm Snapdragon 8 Gen 3",
        "SM-S928U1" to "Qualcomm Snapdragon 8 Gen 3",
        "SM-S928B" to "Qualcomm Snapdragon 8 Gen 3",
        "SM-S928N" to "Qualcomm Snapdragon 8 Gen 3",

        // Google Pixel models
        "Pixel 8 Pro" to "Google Tensor G3",
        "Pixel 8" to "Google Tensor G3",
        "Pixel 7 Pro" to "Google Tensor G2",
        "Pixel 7" to "Google Tensor G2",
        "Pixel 6 Pro" to "Google Tensor",
        "Pixel 6" to "Google Tensor"
    )

    fun getChipForModel(model: String): String? {
        // Exact match
        PHONE_MODEL_TO_CHIP[model]?.let { return it }

        // Partial match (for variants)
        PHONE_MODEL_TO_CHIP.entries.find {
            model.contains(it.key, ignoreCase = true)
        }?.let { return it.value }

        return null
    }

    fun getFlops(chipName: String): DeviceFlops {
        // Try exact match first
        CHIP_FLOPS[chipName]?.let { return it }

        // Try partial match (case-insensitive)
        val normalizedChip = chipName.lowercase()
        CHIP_FLOPS.entries.find {
            normalizedChip.contains(it.key.lowercase())
        }?.let { return it.value }

        // Return unknown if no match
        return DeviceFlops(0f, 0f, 0f)
    }
}

/**
 * Detects device capabilities for Android devices
 * Mirrors exo/topology/device_capabilities.py:150-163
 */
object DeviceCapabilitiesDetector {
    /**
     * Detect current device capabilities
     */
    suspend fun detect(context: Context): DeviceCapabilities = withContext(Dispatchers.IO) {
        val model = Build.MODEL
        val chipName = getChipName()
        val actualMemory = getTotalMemoryMB(context)
        val flops = AndroidChipDatabase.getFlops(chipName)

        // CRITICAL: DummyInferenceEngine cannot produce valid intermediate tensor representations
        // for real inference engines to continue from. Android's dummy tensors have wrong shapes
        // and cause errors like "[rms_norm] weight must have the same size as the last dimension".
        //
        // Solution: Report minimal memory (1 MB) so Android gets 0 or near-0 layer allocation.
        // Mac will handle all/most layers with real MLX inference engine.
        //
        // TODO: When real inference engine is implemented on Android (TFLite/ONNX/MLX),
        // remove this workaround and report actual memory for proper distributed inference.
        val reportedMemory = if (chipName.contains("Exynos 2200")) {
            1  // 1 MB - minimal allocation to avoid processing with DummyEngine
        } else {
            actualMemory
        }

        DeviceCapabilities(
            model = model,
            chip = chipName,
            memory = reportedMemory,
            flops = flops
        )
    }

    /**
     * Get chip/SoC name from system properties
     * First checks phone model database, then falls back to hardware detection
     */
    private fun getChipName(): String {
        // First, try phone model database (most reliable)
        AndroidChipDatabase.getChipForModel(Build.MODEL)?.let { return it }

        // Fall back to hardware string parsing
        return try {
            // Try to read from /proc/cpuinfo
            val cpuInfo = readCpuInfo()
            parseCpuInfo(cpuInfo) ?: Build.HARDWARE
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }

    /**
     * Read /proc/cpuinfo
     */
    private fun readCpuInfo(): String {
        return try {
            BufferedReader(FileReader("/proc/cpuinfo")).use { reader ->
                reader.readText()
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Parse CPU info to extract SoC name
     */
    private fun parseCpuInfo(cpuInfo: String): String? {
        // Look for "Hardware" line which typically contains SoC info
        val hardwareLine = cpuInfo.lines().find { it.startsWith("Hardware") }
        if (hardwareLine != null) {
            val parts = hardwareLine.split(":")
            if (parts.size >= 2) {
                val hardware = parts[1].trim()
                // Map common hardware strings to chip names
                return mapHardwareToChipName(hardware)
            }
        }
        return null
    }

    /**
     * Map hardware string to user-friendly chip name
     * Note: Phone model database is checked first in getChipName()
     * This is only for fallback when model isn't in database
     */
    private fun mapHardwareToChipName(hardware: String): String {
        return when {
            hardware.contains("Qualcomm", ignoreCase = true) || hardware.contains("qcom", ignoreCase = true) -> {
                // Try to extract Snapdragon model
                when {
                    hardware.contains("8gen3", ignoreCase = true) || hardware.contains("kalama", ignoreCase = true) ->
                        "Qualcomm Snapdragon 8 Gen 3"
                    hardware.contains("8gen2", ignoreCase = true) || hardware.contains("taro", ignoreCase = true) ->
                        "Qualcomm Snapdragon 8 Gen 2"
                    hardware.contains("8gen1", ignoreCase = true) || hardware.contains("lahaina", ignoreCase = true) ->
                        "Qualcomm Snapdragon 8 Gen 1"
                    hardware.contains("888") ->
                        "Qualcomm Snapdragon 888"
                    hardware.contains("870") ->
                        "Qualcomm Snapdragon 870"
                    hardware.contains("865") ->
                        "Qualcomm Snapdragon 865"
                    else -> "Qualcomm Snapdragon 8 Gen 1"  // Default for unknown qcom chips
                }
            }
            hardware.contains("MediaTek", ignoreCase = true) -> {
                when {
                    hardware.contains("9300") -> "MediaTek Dimensity 9300"
                    hardware.contains("9200") -> "MediaTek Dimensity 9200"
                    hardware.contains("9000") -> "MediaTek Dimensity 9000"
                    else -> hardware
                }
            }
            hardware.contains("Tensor", ignoreCase = true) -> {
                when {
                    hardware.contains("G3") -> "Google Tensor G3"
                    hardware.contains("G2") -> "Google Tensor G2"
                    else -> "Google Tensor"
                }
            }
            hardware.contains("Exynos", ignoreCase = true) -> {
                when {
                    hardware.contains("2400") -> "Samsung Exynos 2400"
                    hardware.contains("2200") -> "Samsung Exynos 2200"
                    else -> hardware
                }
            }
            else -> hardware
        }
    }

    /**
     * Get total device memory in MB
     */
    private fun getTotalMemoryMB(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }
}
