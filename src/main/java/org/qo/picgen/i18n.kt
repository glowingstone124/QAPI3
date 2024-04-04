import java.io.InputStream
import java.util.*
import org.yaml.snakeyaml.Yaml

class i18n {
    private val data: Map<String, Any>

    init {
        val defaultLocale: Locale = Locale.getDefault()
        println(defaultLocale.language)
        val langFile = when(defaultLocale.language) {
            "zh" -> "zh-cn.yml"
            "en" -> "en-us.yml"
            else -> "en-us.yml"
        }
        val inputStream: InputStream = object {}.javaClass.getResourceAsStream(langFile)
        val content = inputStream.bufferedReader().use { it.readText() }
        val yaml = Yaml()
        data = yaml.load(content)
    }

    fun key(input: String): String? {
        return data[input] as? String
    }
}
