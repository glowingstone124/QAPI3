package org.qo.picgen

import Timer
import Types
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.naming.ConfigurationException
import Types.*
import XMLUtils
import i18n
import org.qo.Logger
import org.qo.Logger.LogLevel
import java.nio.file.Paths

class PicGen {
    companion object {
        data class Text(val content: String, val type: TextType)
        class PicCfg(
            val width: Int,
            val height: Int,
            val titleFontSize: Int,
            val subTitleFontSize: Int,
            val textFontSize: Int,
            val boldFontSize: Int,
            val defaultOffset: Int,
            val lineSpacing: Int,
            val alignment: TextAlignment,
            val font: String
        )
        private val i18nFun = i18n()
        val outputPath = "output.png"
        fun callinits(){
            default()
        }
        @JvmStatic
        fun default(){
            val xu = XMLUtils()
            val textLines = xu.parseXML()
            val configPath = "config.xml"
            val config = xu.readConfig(configPath)
            val timer: Timer = Timer()
            if (!availableCfg()) {
                throw ConfigurationException(i18nFun.key("config.notfound"))
            }
            //Logger.log(i18nFun.key("notification.finish") + timer.measure {
                imgFactory(outputPath, textLines, config, 0)
            //}, LogLevel.INFO)
            //println(outputPath)
        }

        fun availableCfg(): Boolean {
            val configs = mutableListOf(
                "config.xml",
                "content.xml"
            )
            for (config in configs) {
                try {
                    Files.readString(Path.of(config))
                } catch (e: IOException) {
                    Logger.log("Config file $config doesn't exist.", LogLevel.ERROR)
                    return false
                }
            }
            return true
        }

        fun imgFactory(path: String, texts: List<Text>, cfg: PicCfg, stat: Int) {
            val tp = Types()
            val width = cfg.width
            val height = cfg.height
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics: Graphics2D = image.createGraphics()
            graphics.color = when (stat) {
                0 -> tp.parseColor("#292929")
                1 -> Color.YELLOW
                else -> Color.LIGHT_GRAY
            }
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            var yOffset = cfg.defaultOffset

            for (text in texts) {
                graphics.color = when (text.type) {
                    TextType.TITLE -> tp.parseColor("#f3f2f2")
                    TextType.SUBTITLE -> tp.parseColor("#aeaeae")
                    TextType.TEXT -> tp.parseColor("#f3f2f2")
                    TextType.BOLDTEXT -> tp.parseColor("#f3f2f2")
                }

                val font = when (text.type) {
                    TextType.TITLE -> Font(cfg.font, Font.BOLD, cfg.titleFontSize)
                    TextType.SUBTITLE -> Font(cfg.font, Font.PLAIN, cfg.subTitleFontSize)
                    TextType.TEXT -> Font(cfg.font, Font.PLAIN, cfg.textFontSize)
                    TextType.BOLDTEXT -> Font(cfg.font, Font.BOLD, cfg.boldFontSize)
                }
                graphics.font = font

                val textMetrics = graphics.fontMetrics
                val y = yOffset + textMetrics.ascent
                val leftMargin = when (cfg.alignment) {
                    TextAlignment.LEFT -> cfg.defaultOffset
                    TextAlignment.CENTER -> (width - textMetrics.stringWidth(text.content)) / 2
                    TextAlignment.RIGHT -> width - cfg.defaultOffset - textMetrics.stringWidth(text.content)
                }

                if (leftMargin < 0) {
                    throw IllegalArgumentException("文本长度超出画布宽度限制")
                }

                graphics.drawString(text.content, leftMargin, y)
                yOffset += textMetrics.height + cfg.lineSpacing
            }

            graphics.dispose()
            val outputFile = File(path)
            ImageIO.write(image, "png", outputFile)
        }
    }
}
