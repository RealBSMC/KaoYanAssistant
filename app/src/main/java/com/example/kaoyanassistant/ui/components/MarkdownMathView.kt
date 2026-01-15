package com.example.kaoyanassistant.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Markdown + MathJax 渲染组件
 * 使用 WebView 渲染 Markdown 和 LaTeX 数学公式
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownMathView(
    content: String,
    modifier: Modifier = Modifier,
    textColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Black
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // 计算初始估算高度
    val initialHeight = remember(content) {
        estimateHeight(content)
    }

    // 实际测量高度
    var webViewHeight by remember(content) { mutableStateOf(initialHeight) }

    val textColorHex = remember(textColor) {
        String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    }

    val htmlContent = remember(content) {
        markdownToHtml(content)
    }

    // 使用 key 确保内容变化时重新创建
    key(content) {
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = webViewHeight),
            factory = { ctx ->
                createWebView(ctx) { heightPx ->
                    // 将像素转换为 dp
                    val heightDp = with(density) { heightPx.toDp() }
                    if (heightDp > 20.dp) {
                        webViewHeight = heightDp
                    }
                }
            },
            update = { webView ->
                loadHtmlContent(webView, htmlContent, textColorHex)
            }
        )
    }
}

/**
 * 估算内容高度
 */
private fun estimateHeight(content: String): Dp {
    if (content.isEmpty()) return 40.dp

    val lines = content.count { it == '\n' } + 1
    val avgCharsPerLine = 30
    val estimatedLines = maxOf(lines, content.length / avgCharsPerLine + 1)

    // 基础行高
    var height = estimatedLines * 22

    // 数学公式额外高度
    if (content.contains("$$") || content.contains("\\[")) {
        val blockCount = Regex("""\$\$|\\\[""").findAll(content).count() / 2
        height += blockCount * 50
    }
    if (content.contains("$") || content.contains("\\(")) {
        height += 20
    }

    // 代码块额外高度
    if (content.contains("```")) {
        val codeBlocks = Regex("```").findAll(content).count() / 2
        height += codeBlocks * 30
    }

    return height.coerceAtLeast(40).dp
}

/**
 * 创建 WebView
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
private fun createWebView(
    context: Context,
    onHeightMeasured: (Int) -> Unit
): WebView {
    return WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)

        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            allowFileAccess = true
        }

        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // JavaScript 接口用于获取高度
        addJavascriptInterface(object {
            @JavascriptInterface
            fun reportHeight(height: Int) {
                Handler(Looper.getMainLooper()).post {
                    onHeightMeasured(height)
                }
            }
        }, "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 多次测量以确保 MathJax 渲染完成
                measureHeight(view, 100)
                measureHeight(view, 500)
                measureHeight(view, 1000)
                measureHeight(view, 2000)
            }

            private fun measureHeight(view: WebView?, delay: Long) {
                view?.postDelayed({
                    view.evaluateJavascript(
                        """
                        (function() {
                            var body = document.body;
                            var html = document.documentElement;
                            var height = Math.max(
                                body.scrollHeight, body.offsetHeight, body.clientHeight,
                                html.scrollHeight, html.offsetHeight, html.clientHeight
                            );
                            AndroidBridge.reportHeight(height);
                            return height;
                        })();
                        """.trimIndent()
                    ) { }
                }, delay)
            }
        }
    }
}

/**
 * 加载 HTML 内容
 */
private fun loadHtmlContent(webView: WebView, content: String, textColor: String) {
    val html = buildHtml(content, textColor)
    webView.loadDataWithBaseURL(
        "https://cdn.jsdelivr.net/",
        html,
        "text/html",
        "UTF-8",
        null
    )
}

/**
 * 构建 HTML
 */
private fun buildHtml(content: String, textColor: String): String {
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <script>
        MathJax = {
            tex: {
                inlineMath: [['$', '$'], ['\\(', '\\)']],
                displayMath: [['$$', '$$'], ['\\[', '\\]']],
                processEscapes: true
            },
            options: { skipHtmlTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code'] },
            startup: {
                pageReady: function() {
                    return MathJax.startup.defaultPageReady().then(function() {
                        // MathJax 渲染完成后报告高度
                        setTimeout(function() {
                            var height = Math.max(document.body.scrollHeight, document.body.offsetHeight);
                            if (window.AndroidBridge) AndroidBridge.reportHeight(height);
                        }, 100);
                    });
                }
            }
        };
    </script>
    <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js" async></script>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html { height: auto; overflow: visible; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 15px;
            line-height: 1.65;
            color: $textColor;
            background: transparent;
            padding: 2px 0;
            height: auto;
            overflow: visible;
        }
        p { margin: 0 0 10px 0; }
        p:last-child { margin-bottom: 0; }
        h1, h2, h3, h4, h5, h6 { margin: 14px 0 8px 0; font-weight: 600; line-height: 1.3; }
        h1 { font-size: 1.5em; }
        h2 { font-size: 1.3em; }
        h3 { font-size: 1.15em; }
        h4, h5, h6 { font-size: 1.05em; }
        code {
            background: rgba(128,128,128,0.2);
            padding: 2px 6px;
            border-radius: 4px;
            font-family: 'SF Mono', 'Courier New', monospace;
            font-size: 0.88em;
        }
        pre {
            background: rgba(128,128,128,0.12);
            padding: 12px;
            border-radius: 8px;
            overflow-x: auto;
            margin: 10px 0;
            white-space: pre-wrap;
            word-wrap: break-word;
        }
        pre code { background: transparent; padding: 0; font-size: 0.9em; }
        blockquote {
            border-left: 3px solid rgba(128,128,128,0.4);
            padding-left: 14px;
            margin: 10px 0;
            opacity: 0.85;
        }
        ul, ol { padding-left: 24px; margin: 8px 0; }
        li { margin: 4px 0; }
        strong { font-weight: 600; }
        em { font-style: italic; }
        del { text-decoration: line-through; opacity: 0.7; }
        a { color: #007AFF; text-decoration: none; }
        hr { border: none; border-top: 1px solid rgba(128,128,128,0.25); margin: 16px 0; }
        /* MathJax */
        mjx-container { overflow-x: auto; overflow-y: hidden; max-width: 100%; }
        mjx-container[display="true"] { display: block; margin: 12px 0 !important; text-align: center; overflow-x: auto; }
    </style>
</head>
<body>$content</body>
</html>
    """.trimIndent()
}

/**
 * Markdown 转 HTML
 */
private fun markdownToHtml(markdown: String): String {
    if (markdown.isEmpty()) return ""

    var html = markdown

    // 保护数学公式
    val preserved = mutableListOf<Pair<String, String>>()
    var idx = 0

    // 块级公式 $$...$$
    html = Regex("""\$\$[\s\S]*?\$\$""").replace(html) {
        val key = "%%BLOCK$idx%%"
        preserved.add(key to it.value)
        idx++
        key
    }

    // 行内公式 $...$
    html = Regex("""(?<!\$)\$(?!\$)([^\$\n]+?)\$(?!\$)""").replace(html) {
        val key = "%%INLINE$idx%%"
        preserved.add(key to it.value)
        idx++
        key
    }

    // \[...\]
    html = Regex("""\\\[[\s\S]*?\\\]""").replace(html) {
        val key = "%%BLOCK$idx%%"
        preserved.add(key to it.value)
        idx++
        key
    }

    // \(...\)
    html = Regex("""\\\([\s\S]*?\\\)""").replace(html) {
        val key = "%%INLINE$idx%%"
        preserved.add(key to it.value)
        idx++
        key
    }

    // HTML 转义
    html = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // 代码块
    html = Regex("```\\w*\\n?([\\s\\S]*?)```").replace(html) { "<pre><code>${it.groupValues[1]}</code></pre>" }

    // 行内代码
    html = Regex("`([^`]+)`").replace(html) { "<code>${it.groupValues[1]}</code>" }

    // 标题
    html = Regex("^#{1,6}\\s+(.+)$", RegexOption.MULTILINE).replace(html) {
        val level = it.value.takeWhile { c -> c == '#' }.length
        "<h$level>${it.groupValues[1]}</h$level>"
    }

    // 粗体
    html = Regex("\\*\\*(.+?)\\*\\*").replace(html) { "<strong>${it.groupValues[1]}</strong>" }

    // 斜体
    html = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").replace(html) { "<em>${it.groupValues[1]}</em>" }

    // 删除线
    html = Regex("~~(.+?)~~").replace(html) { "<del>${it.groupValues[1]}</del>" }

    // 链接
    html = Regex("\\[([^]]+)]\\(([^)]+)\\)").replace(html) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }

    // 水平线
    html = Regex("^[-*]{3,}$", RegexOption.MULTILINE).replace(html) { "<hr>" }

    // 列表
    html = Regex("^[*\\-+]\\s+(.+)$", RegexOption.MULTILINE).replace(html) { "<li>${it.groupValues[1]}</li>" }
    html = Regex("^\\d+\\.\\s+(.+)$", RegexOption.MULTILINE).replace(html) { "<li>${it.groupValues[1]}</li>" }

    // 引用
    html = Regex("^>\\s*(.+)$", RegexOption.MULTILINE).replace(html) { "<blockquote>${it.groupValues[1]}</blockquote>" }

    // 段落
    html = html.replace(Regex("\n{2,}")) { "</p><p>" }
    html = html.replace("\n", "<br>")

    if (!html.startsWith("<")) html = "<p>$html</p>"

    html = html.replace("<p></p>", "").replace("<p><br></p>", "")

    // 恢复公式
    preserved.forEach { (key, value) -> html = html.replace(key, value) }

    return html
}
