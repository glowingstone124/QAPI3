package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class LLMImageStore private constructor(
    private val root: Path,
    private val maxImageBytes: Long,
) {
    private val images = ConcurrentHashMap<String, StoredImage>()

    constructor() : this(
        defaultRoot(),
        readLongEnv("LLM_IMAGE_MAX_BYTES", 20L * 1024L * 1024L)
            .coerceIn(64L * 1024L, 50L * 1024L * 1024L),
    )

    init {
        Files.createDirectories(root)
        cleanupOldOrphans()
    }

    fun compactContent(content: JsonElement): JsonElement {
        if (!content.isJsonArray) {
            return content.deepCopy()
        }

        return JsonArray().apply {
            content.asJsonArray.forEach { part ->
                val obj = part.takeIf { it.isJsonObject }?.asJsonObject
                if (obj == null) {
                    add(part.deepCopy())
                    return@forEach
                }

                val type = obj.get("type")?.asString
                if (type != "image_url" && type != "input_image") {
                    add(obj.deepCopy())
                    return@forEach
                }

                val url = extractImageUrl(obj)
                if (url == null || !url.startsWith("data:", ignoreCase = true)) {
                    add(obj.deepCopy())
                    return@forEach
                }

                val stored = storeDataUrl(url)
                add(JsonObject().apply {
                    addProperty("type", INTERNAL_IMAGE_REF)
                    addProperty("image_id", stored.id)
                    extractDetail(obj)?.let { addProperty("detail", it) }
                })
            }
        }
    }

    fun hydrateContent(content: JsonElement): JsonElement {
        if (!content.isJsonArray) {
            return content.deepCopy()
        }

        val hydrated = JsonArray()
        content.asJsonArray.forEach { part ->
            val obj = part.takeIf { it.isJsonObject }?.asJsonObject
            if (obj?.get("type")?.asString != INTERNAL_IMAGE_REF) {
                hydrated.add(part.deepCopy())
                return@forEach
            }

            val imageId = obj.get("image_id")?.asString
            val dataUrl = imageId?.let(::dataUrl)
            if (dataUrl == null) {
                hydrated.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "[历史图片已失效]")
                })
                return@forEach
            }

            hydrated.add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", dataUrl)
                    obj.get("detail")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.let { addProperty("detail", it) }
                })
            })
        }

        return if (hydrated.size() == 0) JsonPrimitive("") else hydrated
    }

    fun deleteContentImages(content: JsonElement) {
        if (!content.isJsonArray) return
        content.asJsonArray.forEach { part ->
            val obj = part.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (obj.get("type")?.asString != INTERNAL_IMAGE_REF) return@forEach
            obj.get("image_id")?.asString?.let(::delete)
        }
    }

    private fun cleanupOldOrphans() {
        val maxAgeMs = readLongEnv("LLM_IMAGE_ORPHAN_MAX_AGE_MS", 24L * 60L * 60L * 1000L)
            .coerceAtLeast(60_000L)
        val cutoff = System.currentTimeMillis() - maxAgeMs

        Files.list(root).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { path ->
                val modifiedAt = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrNull()
                    ?: return@forEach
                if (modifiedAt < cutoff) {
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }

    private fun storeDataUrl(dataUrl: String): StoredImage {
        val comma = dataUrl.indexOf(',')
        require(dataUrl.startsWith("data:", ignoreCase = true) && comma > 5) {
            "Invalid image data URL"
        }

        val metadata = dataUrl.substring(5, comma)
        val mimeType = metadata.substringBefore(';').lowercase()
        require(mimeType.startsWith("image/")) {
            "Only image data URLs are supported"
        }
        require(metadata.split(';').drop(1).any { it.equals("base64", ignoreCase = true) }) {
            "Image data URL must use base64 encoding"
        }

        val payload = dataUrl.substring(comma + 1)
        val estimatedBytes = payload.length.toLong() * 3L / 4L
        require(estimatedBytes <= maxImageBytes + 3L) {
            "Image is too large; maximum is $maxImageBytes bytes"
        }

        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64 image data", error)
        }
        require(bytes.size.toLong() <= maxImageBytes) {
            "Image is too large; maximum is $maxImageBytes bytes"
        }

        val id = UUID.randomUUID().toString()
        val path = root.resolve("$id.bin")
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

        return StoredImage(id, mimeType, path).also {
            images[id] = it
        }
    }

    private fun dataUrl(imageId: String): String? {
        val image = images[imageId] ?: return null
        val bytes = runCatching { Files.readAllBytes(image.path) }.getOrNull() ?: return null
        return "data:${image.mimeType};base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    private fun delete(imageId: String) {
        val image = images.remove(imageId) ?: return
        runCatching { Files.deleteIfExists(image.path) }
    }

    private fun extractImageUrl(part: JsonObject): String? {
        val imageUrl = part.get("image_url") ?: return null
        return when {
            imageUrl.isJsonPrimitive -> imageUrl.asString
            imageUrl.isJsonObject -> imageUrl.asJsonObject.get("url")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            else -> null
        }
    }

    private fun extractDetail(part: JsonObject): String? {
        val nested = part.get("image_url")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("detail")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
        return nested ?: part.get("detail")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
    }

    private data class StoredImage(
        val id: String,
        val mimeType: String,
        val path: Path,
    )

    companion object {
        private const val INTERNAL_IMAGE_REF = "qapi_image_ref"

        private fun defaultRoot(): Path {
            val configured = System.getenv("LLM_IMAGE_STORE_DIR")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            return if (configured != null) {
                Path.of(configured).toAbsolutePath().normalize()
            } else {
                Path.of(System.getProperty("java.io.tmpdir"), "qapi3-llm-images")
                    .toAbsolutePath()
                    .normalize()
            }
        }

        private fun readLongEnv(name: String, defaultValue: Long): Long =
            System.getenv(name)?.trim()?.toLongOrNull() ?: defaultValue

        internal fun forTest(root: Path, maxImageBytes: Long = 20L * 1024L * 1024L): LLMImageStore =
            LLMImageStore(root.toAbsolutePath().normalize(), maxImageBytes)
    }
}
