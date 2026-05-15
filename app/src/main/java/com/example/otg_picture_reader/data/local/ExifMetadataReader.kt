package com.example.otg_picture_reader.data.local

import androidx.exifinterface.media.ExifInterface
import com.example.otg_picture_reader.data.model.ImageExifInfo
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object ExifMetadataReader {
    fun read(input: InputStream): ImageExifInfo {
        val exif = ExifInterface(input)
        return ImageExifInfo(
            takenTime = readTakenTime(exif),
            cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE),
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL),
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
            focalLengthMm = formatRationalAsDecimal(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)),
            focalLength35Mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1)
                .takeIf { it > 0 }
                ?.let { "$it mm" },
            lensSpecification = formatLensSpecification(exif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION)),
            aperture = formatAperture(exif),
            shutterSpeed = formatExposure(exif),
            iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 },
            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
        )
    }

    private fun readTakenTime(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
            ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        return parseExifDate(raw, offset)
    }

    private fun parseExifDate(raw: String, offset: String?): Long? {
        val dateText = raw.trim().take(19)
        if (dateText.length < 19) return null
        return runCatching {
            val formatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                if (!offset.isNullOrBlank()) {
                    timeZone = TimeZone.getTimeZone("GMT${offset.trim()}")
                }
            }
            formatter.parse(dateText)?.time
        }.getOrNull()
    }

    private fun formatAperture(exif: ExifInterface): String? {
        val fNumber = parseRational(exif.getAttribute(ExifInterface.TAG_F_NUMBER))
            ?: exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0).takeIf { it > 0.0 }
        return fNumber?.let { "f/${formatDecimal(it)}" }
    }

    private fun formatExposure(exif: ExifInterface): String? {
        val exposureTime = parseRational(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
            ?: exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0).takeIf { it > 0.0 }
            ?: return null
        return if (exposureTime >= 1.0) {
            "${formatDecimal(exposureTime)} s"
        } else {
            val denominator = kotlin.math.round(1.0 / exposureTime).toInt().coerceAtLeast(1)
            "1/$denominator s"
        }
    }

    private fun formatLensSpecification(value: String?): String? {
        val raw = value.orEmpty().trim()
        if (raw.isBlank()) return null
        val values = raw.split(",").mapNotNull { parseRational(it.trim()) }
        if (values.size >= 2) {
            val min = formatDecimal(values[0])
            val max = formatDecimal(values[1])
            return if (min == max) "$min mm" else "$min-$max mm"
        }
        return raw
    }

    private fun formatRationalAsDecimal(value: String?): String? {
        val parsed = parseRational(value.orEmpty().trim()) ?: return value?.takeIf { it.isNotBlank() }
        return "${formatDecimal(parsed)} mm"
    }

    private fun parseRational(value: String?): Double? {
        val raw = value.orEmpty().trim()
        if (raw.isBlank()) return null
        val first = raw.split(",", limit = 2).first().replace(" ", "")
        if (first.contains("/")) {
            val parts = first.split("/", limit = 2)
            val numerator = parts.getOrNull(0)?.toDoubleOrNull()
            val denominator = parts.getOrNull(1)?.toDoubleOrNull()
            return if (numerator != null && denominator != null && denominator != 0.0) {
                numerator / denominator
            } else {
                null
            }
        }
        return first.toDoubleOrNull()
    }

    private fun formatDecimal(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')
        }
    }
}
