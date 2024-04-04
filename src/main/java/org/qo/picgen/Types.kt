import java.awt.Color

class Types {
    enum class TextType {
        TITLE,
        SUBTITLE,
        BOLDTEXT,
        TEXT
    }

    enum class TextAlignment {
        LEFT,
        CENTER,
        RIGHT
    }
    fun parseColor(hex: String): Color {
        val rgb = hex.substring(1).toInt(16)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return Color(r, g, b)
    }

}