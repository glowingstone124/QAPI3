import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import Types.*
import org.w3c.dom.NodeList
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import i18n
import org.qo.picgen.PicGen
import org.qo.picgen.PicGen.Companion.Text;

class XMLUtils {
    fun parseXML(): List<Text> {
        val current = LocalDateTime.now()
        val xml: String = "content.xml"
        val international = i18n();
        val content = Files.readString(Path.of(xml))
        val mt = MinecraftTool()
        var replacedContent = ""
        try {
            replacedContent = content.replace(Regex("\\$\\w+")) {
                when (it.value) {
                    "\$current" -> current.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    "\$mspt" -> mt.getStat().mspt.toString()
                    "\$onlinecount" -> mt.getStat().onlinecount.toString()
                    "\$ping" -> mt.getPing().toString()
                    else -> {
                        val errorMessageTemplate = international.key("notification.warnundefinedtag")
                        val errorMessage = errorMessageTemplate?.replace("\${it.value}", it.value)
                        println(errorMessage)
                        it.value
                    }
                }
            }
        } catch (e: IOException){
            return mutableListOf<Text>(
                Text("ERROR", TextType.TITLE),
                Text("generated at \$current", TextType.TEXT),
                Text("Could not retrive api messages from server.", TextType.TEXT)
            )
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(replacedContent)))
        val root = document.documentElement
        val textLines = mutableListOf<Text>()
        val nodeList = root.childNodes
        for (i in 0 until nodeList.length) {
            val node = nodeList.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val content = element.textContent
                val type = when (element.tagName) {
                    "title" -> TextType.TITLE
                    "text" -> TextType.TEXT
                    "boldtext" -> TextType.BOLDTEXT
                    else -> throw IllegalArgumentException("Unknown tag name: ${element.tagName}")
                }
                textLines.add(Text(content, type))
            }
        }

        return textLines
    }

    fun readConfig(configPath: String): PicGen.Companion.PicCfg {
        val file = File(configPath)
        if (!file.exists()) {
            throw IllegalArgumentException("Config file does not exist.")
        }
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document = documentBuilder.parse(file)
        val root = document.documentElement

        if (root == null || root.nodeName != "config") {
            throw IllegalArgumentException("Invalid or missing root element in the config file.")
        }

        fun getNodeTextContent(nodeName: String): String {
            val nodeList: NodeList = root.getElementsByTagName(nodeName)
            if (nodeList.length == 0) {
                throw IllegalArgumentException("Missing $nodeName element in the config file.")
            }
            return nodeList.item(0).textContent.trim()
        }

        val width = getNodeTextContent("width").toInt()
        val height = getNodeTextContent("height").toInt()
        val titleFontSize = getNodeTextContent("titleFontSize").toInt()
        val subTitleFontSize = getNodeTextContent("subTitleFontSize").toInt()
        val textFontSize = getNodeTextContent("textFontSize").toInt()
        val boldFontSize = getNodeTextContent("boldFontSize").toInt()
        val defaultOffset = getNodeTextContent("defaultOffset").toInt()
        val lineSpacing = getNodeTextContent("lineSpacing").toInt()
        val alignment = when (getNodeTextContent("alignment")) {
            "LEFT" -> TextAlignment.LEFT
            "CENTER" -> TextAlignment.CENTER
            "RIGHT" -> TextAlignment.RIGHT
            else -> throw IllegalArgumentException("Invalid alignment specified in the config.")
        }
        val font = getNodeTextContent("font")

        return PicGen.Companion.PicCfg(
            width,
            height,
            titleFontSize,
            subTitleFontSize,
            textFontSize,
            boldFontSize,
            defaultOffset,
            lineSpacing,
            alignment,
            font
        )
    }
}
