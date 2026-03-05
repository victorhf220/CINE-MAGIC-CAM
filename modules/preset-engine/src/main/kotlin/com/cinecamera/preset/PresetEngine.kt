package com.cinecamera.preset

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PresetEngine
 *
 * Manages the full lifecycle of user-defined recording and streaming presets.
 *
 * A preset captures the complete application state:
 *   - Camera configuration (codec, resolution, fps, bitrate)
 *   - Image processing settings (LOG profile, LUT, contrast, saturation)
 *   - Audio configuration (gain, source, noise gate, limiter)
 *   - Streaming configuration (RTMP/SRT endpoints, bitrate targets)
 *   - Stabilization parameters
 *
 * Default presets (read-only system presets) are seeded at first launch.
 * User presets are stored in Room and can be exported as JSON files for
 * sharing between devices or as backup.
 *
 * Import/export format: JSON with schema version for forward compatibility.
 */
@Singleton
class PresetEngine @Inject constructor(
    private val context: Context,
    private val presetDao: PresetDao,
    private val gson: Gson
) {
    companion object {
        const val SCHEMA_VERSION = 1
        private val DEFAULT_PRESETS = listOf(
            CameraPreset(
                id = "default_youtube_1080p",
                name = "YouTube 1080p",
                description = "Optimized for YouTube live streaming at 1080p60",
                isSystem = true,
                cameraConfig = CameraPresetConfig(
                    codec = "H264", width = 1920, height = 1080, fps = 60,
                    bitrateKbps = 9000, bitrateMode = "CBR",
                    colorProfile = "STANDARD"
                ),
                streamConfig = StreamPresetConfig(
                    protocol = "RTMP",
                    host = "a.rtmp.youtube.com",
                    port = 1935,
                    appName = "live2"
                )
            ),
            CameraPreset(
                id = "default_cinema_log",
                name = "Cinema LOG",
                description = "CineLog™ profile at 150 Mbps for post-production",
                isSystem = true,
                cameraConfig = CameraPresetConfig(
                    codec = "H265", width = 3840, height = 2160, fps = 24,
                    bitrateKbps = 150_000, bitrateMode = "VBR",
                    colorProfile = "CINELOG", logIntensity = 1.0f
                )
            ),
            CameraPreset(
                id = "default_srt_broadcast",
                name = "SRT Broadcast 8Mbps",
                description = "Low-latency SRT streaming for professional broadcast",
                isSystem = true,
                cameraConfig = CameraPresetConfig(
                    codec = "H264", width = 1920, height = 1080, fps = 30,
                    bitrateKbps = 8000, bitrateMode = "CBR"
                ),
                streamConfig = StreamPresetConfig(
                    protocol = "SRT",
                    port = 9000,
                    latencyMs = 120
                )
            ),
            CameraPreset(
                id = "default_interview",
                name = "Interview / Run & Gun",
                description = "Handheld interview mode with strong stabilization",
                isSystem = true,
                cameraConfig = CameraPresetConfig(
                    codec = "H264", width = 1920, height = 1080, fps = 30,
                    bitrateKbps = 50_000, bitrateMode = "CBR"
                ),
                stabilizationConfig = StabilizationPresetConfig(
                    enabled = true, intensity = 0.9f
                ),
                audioConfig = AudioPresetConfig(
                    gainDb = 6f, noiseGateEnabled = true, limiterEnabled = true
                )
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read operations
    // ─────────────────────────────────────────────────────────────────────────

    fun observeAllPresets(): Flow<List<PresetEntity>> = presetDao.observeAll()

    fun observeSystemPresets(): Flow<List<PresetEntity>> = presetDao.observeByType(isSystem = true)

    fun observeUserPresets(): Flow<List<PresetEntity>> = presetDao.observeByType(isSystem = false)

    suspend fun getPresetById(id: String): CameraPreset? {
        val entity = presetDao.getById(id) ?: return null
        return deserialize(entity)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write operations
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun savePreset(preset: CameraPreset): Boolean {
        return try {
            val entity = serialize(preset)
            presetDao.upsert(entity)
            Timber.d("Preset saved: ${preset.name}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save preset: ${preset.name}")
            false
        }
    }

    suspend fun deletePreset(id: String): Boolean {
        val entity = presetDao.getById(id) ?: return false
        if (entity.isSystem) {
            Timber.w("Attempted to delete system preset $id — rejected")
            return false
        }
        presetDao.deleteById(id)
        Timber.d("Preset deleted: $id")
        return true
    }

    suspend fun duplicatePreset(id: String, newName: String): CameraPreset? {
        val original = getPresetById(id) ?: return null
        val copy = original.copy(
            id = "user_${System.currentTimeMillis()}",
            name = newName,
            isSystem = false,
            createdAt = System.currentTimeMillis()
        )
        savePreset(copy)
        return copy
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Import / Export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Exports a preset to a JSON file in the app's shared storage directory.
     * Returns the path of the created file.
     */
    suspend fun exportPreset(id: String): String? {
        val preset = getPresetById(id) ?: return null
        val exportDir = File(context.getExternalFilesDir(null), "CineCamera/Presets")
        exportDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitizedName = preset.name.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val file = File(exportDir, "preset_${sanitizedName}_$timestamp.json")

        val export = PresetExportPackage(
            schemaVersion = SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            preset = preset
        )
        file.writeText(gson.toJson(export))
        Timber.i("Preset exported: ${file.absolutePath}")
        return file.absolutePath
    }

    /**
     * Imports a preset from a JSON file. System preset flags are stripped —
     * imported presets are always user-owned.
     */
    suspend fun importPreset(filePath: String): ImportResult {
        return try {
            val json = File(filePath).readText()
            val export = gson.fromJson(json, PresetExportPackage::class.java)

            if (export.schemaVersion > SCHEMA_VERSION) {
                return ImportResult.Failure("Preset requires app version $SCHEMA_VERSION+")
            }

            val imported = export.preset.copy(
                id = "imported_${System.currentTimeMillis()}",
                isSystem = false,
                createdAt = System.currentTimeMillis()
            )
            savePreset(imported)
            Timber.i("Preset imported: ${imported.name}")
            ImportResult.Success(imported)
        } catch (e: Exception) {
            Timber.e(e, "Preset import failed: $filePath")
            ImportResult.Failure(e.message ?: "Parse error")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // System preset seeding
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun seedDefaultPresetsIfNeeded() {
        val existing = presetDao.countSystemPresets()
        if (existing >= DEFAULT_PRESETS.size) return

        DEFAULT_PRESETS.forEach { preset ->
            if (presetDao.getById(preset.id) == null) {
                savePreset(preset)
            }
        }
        Timber.d("System presets seeded: ${DEFAULT_PRESETS.size}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Serialization helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun serialize(preset: CameraPreset): PresetEntity = PresetEntity(
        id = preset.id,
        name = preset.name,
        description = preset.description,
        isSystem = preset.isSystem,
        createdAt = preset.createdAt,
        json = gson.toJson(preset)
    )

    private fun deserialize(entity: PresetEntity): CameraPreset =
        gson.fromJson(entity.json, CameraPreset::class.java)
}

// ─── Room Entity ─────────────────────────────────────────────────────────────

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val isSystem: Boolean,
    val createdAt: Long,
    val json: String
)

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY isSystem DESC, name ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE isSystem = :isSystem ORDER BY name ASC")
    fun observeByType(isSystem: Boolean): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PresetEntity?

    @Upsert
    suspend fun upsert(entity: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM presets WHERE isSystem = 1")
    suspend fun countSystemPresets(): Int
}

// ─── Domain models ────────────────────────────────────────────────────────────

data class CameraPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val isSystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val cameraConfig: CameraPresetConfig = CameraPresetConfig(),
    val streamConfig: StreamPresetConfig? = null,
    val audioConfig: AudioPresetConfig = AudioPresetConfig(),
    val stabilizationConfig: StabilizationPresetConfig = StabilizationPresetConfig(),
    val imageProcessingConfig: ImageProcessingPresetConfig = ImageProcessingPresetConfig()
)

data class CameraPresetConfig(
    val codec: String = "H264",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrateKbps: Int = 50_000,
    val bitrateMode: String = "CBR",
    val colorProfile: String = "STANDARD",
    val logIntensity: Float = 0f,
    val isoManual: Int? = null,
    val shutterManualNs: Long? = null,
    val whiteBalanceKelvin: Int? = null
)

data class StreamPresetConfig(
    val protocol: String = "RTMP",
    val host: String = "",
    val port: Int = 1935,
    val appName: String = "live",
    val streamKey: String = "",
    val latencyMs: Int = 120,
    val encrypted: Boolean = false
)

data class AudioPresetConfig(
    val gainDb: Float = 0f,
    val noiseGateEnabled: Boolean = false,
    val noiseGateThresholdDb: Float = -60f,
    val limiterEnabled: Boolean = true,
    val bitrateKbps: Int = 256
)

data class StabilizationPresetConfig(
    val enabled: Boolean = true,
    val intensity: Float = 0.75f
)

data class ImageProcessingPresetConfig(
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpening: Float = 0.3f,
    val lutPath: String? = null,
    val lutIntensity: Float = 1.0f
)

data class PresetExportPackage(
    val schemaVersion: Int,
    val exportedAt: Long,
    val preset: CameraPreset
)

sealed class ImportResult {
    data class Success(val preset: CameraPreset) : ImportResult()
    data class Failure(val reason: String) : ImportResult()
}
