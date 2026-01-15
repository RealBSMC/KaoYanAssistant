package com.example.kaoyanassistant.utils

import android.content.Context
import android.text.Spanned
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin

/**
 * Markdown渲染器 - 将Markdown文本转换为可显示的格式
 * 支持LaTeX数学公式渲染
 * 对应Qt版本的MarkdownRenderer和MathRenderer类
 */
class MarkdownRenderer private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: MarkdownRenderer? = null

        fun getInstance(context: Context): MarkdownRenderer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MarkdownRenderer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val markwon: Markwon = try {
        Markwon.builder(context)
            .usePlugin(HtmlPlugin.create())
            .build()
    } catch (e: Exception) {
        Logger.error("MarkdownRenderer", "Markwon初始化失败", e)
        // 降级到基础Markwon
        Markwon.create(context)
    }

    /**
     * 将Markdown文本转换为Spanned（用于TextView显示）
     */
    fun render(markdown: String): Spanned {
        if (markdown.isEmpty()) {
            return android.text.SpannedString("")
        }
        // 预处理：确保LaTeX公式格式正确
        val processed = preprocessLatex(markdown)
        return markwon.toMarkdown(processed)
    }

    /**
     * 直接设置到TextView
     */
    fun setMarkdown(textView: TextView, markdown: String) {
        if (markdown.isEmpty()) {
            textView.text = ""
            return
        }
        try {
            val processed = preprocessLatex(markdown)
            markwon.setMarkdown(textView, processed)
        } catch (e: Exception) {
            Logger.warning("MarkdownRenderer", "渲染Markdown失败: ${e.message}")
            textView.text = markdown
        }
    }

    /**
     * 预处理LaTeX公式
     * 将常见的LaTeX格式转换为Markwon支持的格式
     */
    private fun preprocessLatex(text: String): String {
        var result = text

        // 处理块级公式 \[...\] -> $$...$$
        result = result.replace(Regex("""\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)) { match ->
            "$$${match.groupValues[1]}$$"
        }

        // 处理行内公式 \(...\) -> $...$
        result = result.replace(Regex("""\\\((.*?)\\\)""")) { match ->
            "$${match.groupValues[1]}$"
        }

        return result
    }

    /**
     * 检查文本是否包含数学公式
     */
    fun containsMath(text: String): Boolean {
        return text.contains("$") ||
                text.contains("\\[") ||
                text.contains("\\(") ||
                text.contains("\\frac") ||
                text.contains("\\sum") ||
                text.contains("\\int")
    }

    /**
     * 提取文本中的所有数学公式
     */
    fun extractMathFormulas(text: String): List<String> {
        val formulas = mutableListOf<String>()

        // 提取块级公式 $$...$$
        Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL).findAll(text).forEach {
            formulas.add(it.groupValues[1])
        }

        // 提取行内公式 $...$（排除$$）
        Regex("""(?<!\$)\$(?!\$)(.*?)(?<!\$)\$(?!\$)""").findAll(text).forEach {
            formulas.add(it.groupValues[1])
        }

        return formulas
    }
}

/**
 * 简单的HTML转换器（用于不需要完整Markwon的场景）
 */
object SimpleMarkdownConverter {

    /**
     * 将Markdown转换为简单HTML
     */
    fun toHtml(markdown: String): String {
        var html = escapeHtml(markdown)

        // 代码块
        html = html.replace(Regex("```(\\w*)\\n([\\s\\S]*?)```")) { match ->
            val language = match.groupValues[1]
            val code = match.groupValues[2]
            "<pre><code class=\"language-$language\">$code</code></pre>"
        }

        // 行内代码
        html = html.replace(Regex("`([^`]+)`")) { match ->
            "<code>${match.groupValues[1]}</code>"
        }

        // 标题
        html = html.replace(Regex("^### (.+)$", RegexOption.MULTILINE)) { match ->
            "<h3>${match.groupValues[1]}</h3>"
        }
        html = html.replace(Regex("^## (.+)$", RegexOption.MULTILINE)) { match ->
            "<h2>${match.groupValues[1]}</h2>"
        }
        html = html.replace(Regex("^# (.+)$", RegexOption.MULTILINE)) { match ->
            "<h1>${match.groupValues[1]}</h1>"
        }

        // 粗体
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { match ->
            "<strong>${match.groupValues[1]}</strong>"
        }

        // 斜体
        html = html.replace(Regex("\\*(.+?)\\*")) { match ->
            "<em>${match.groupValues[1]}</em>"
        }

        // 链接
        html = html.replace(Regex("\\[(.+?)\\]\\((.+?)\\)")) { match ->
            "<a href=\"${match.groupValues[2]}\">${match.groupValues[1]}</a>"
        }

        // 换行
        html = html.replace("\n", "<br>")

        return html
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
