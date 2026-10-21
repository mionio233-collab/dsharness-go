package io.github.hakunm.deepseekharness.agent

import io.github.hakunm.deepseekharness.local.PhoneNode
import java.util.Locale

/** Only bounded, sanitized public task prose is eligible. Reasoning is never an input. */
object PhoneProgress {
    const val MAX_LENGTH = 80
    fun safe(value: String?): String? {
        val text = value?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_LENGTH || text.any { it.isISOControl() || it.isWhitespace() && it != ' ' }) return null
        val lower = text.lowercase(Locale.ROOT)
        if (listOf("@", "://", "www.", "?", "=", "token", "secret", "password", "api key", "bearer", "authorization", "cookie", "credential", "clipboard", "otp", "account", "payment", "email", "密码", "验证码", "账号", "账户", "支付", "转账", "剪贴板", "密钥", "邮箱", "手机号").any { lower.contains(it) }) return null
        if (text.any { it.isDigit() } || text.any { it in "<>[]{}`\\" }) return null
        return text
    }
    fun appLabel(value: String?): String? = safe(value)?.takeIf { it.length <= 24 && it.all { c -> c.isLetter() || c in " -_." } }
    private val targets = listOf(
        Triple("address", "地址栏", "address bar"), Triple("地址", "地址栏", "address bar"),
        Triple("download", "下载按钮", "Download"), Triple("下载", "下载按钮", "Download"),
        Triple("search", "搜索框", "search field"), Triple("搜索", "搜索框", "search field"),
        Triple("install", "安装按钮", "Install"), Triple("安装", "安装按钮", "Install"),
        Triple("next", "下一步按钮", "Next"), Triple("下一步", "下一步按钮", "Next"))
    fun target(node: PhoneNode?, english: Boolean): String? {
        if (node == null || node.sensitive || node.paymentAction || node.editable && node.label.isBlank()) return null
        val label = safe(node.label)?.lowercase(Locale.ROOT) ?: return null
        if (label in setOf("search or type web address", "search or type url", "搜索或输入网址", "搜索或输入网站地址"))
            return if (english) "address bar" else "地址栏"
        // Exact UI vocabulary: arbitrary screen text and values are never rendered.
        return targets.firstOrNull { label == it.first || label == it.second || label == it.third || label == "${it.first} field" || label == "${it.first} button" }?.let { if (english) it.third else it.second }
    }
    @Suppress("UNUSED_PARAMETER")
    fun publicIntent(content: String?, english: Boolean): String? {
        val raw = content?.trim().orEmpty()
        // Reject sensitive prose before sentence splitting, which can otherwise cut a URL.
        if (Regex("@|://|www\\.|[0-9]|token|secret|password|credential|api key|bearer|authorization|cookie|密码|验证码|密钥|账号|账户|手机号", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return null
        if (listOf("reasoning", "think>", "推理", "思考过程").any { raw.contains(it, ignoreCase = true) }) return null
        // Select one latest sentence, never join earlier rounds or paragraphs into the overlay.
        val sentence = Regex("[^。！？.!?\\n\\r]+[。！？.!?]?").findAll(raw).lastOrNull()?.value?.trim()
        val text = safe(sentence) ?: return null
        if (!Regex("观察|界面|打开|搜索|查找|读取|点击|输入|滚动|返回|下载|安装|检查|observe|screen|open|search|locat|read|tap|enter|scroll|back|download|install|check", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return text
    }
    fun action(action: PhoneStatusAction, app: String?, target: String?, english: Boolean, confirmed: Boolean = false): String {
        val place = listOfNotNull(appLabel(app), safe(target)).joinToString(" · ")
        val verb = if (english) when (action) {
            PhoneStatusAction.OBSERVE -> "Reading screen controls"
            PhoneStatusAction.OPEN_APP -> "Opening app"
            PhoneStatusAction.CLICK -> "Tapping"
            PhoneStatusAction.LONG_PRESS -> "Long pressing"
            PhoneStatusAction.PATH -> "Drawing in authorized canvas"
            PhoneStatusAction.SWIPE -> "Swiping in authorized canvas"
            PhoneStatusAction.TAP -> "Tapping in authorized canvas"
            PhoneStatusAction.DRAG -> "Dragging"
            PhoneStatusAction.SET_TEXT -> "Entering text"
            PhoneStatusAction.SUBMIT_TEXT -> "Submitting input"
            PhoneStatusAction.SCROLL_FORWARD -> "Scrolling down"
            PhoneStatusAction.SCROLL_BACKWARD -> "Scrolling up"
            PhoneStatusAction.BACK -> "Going back"
            PhoneStatusAction.HOME -> "Returning home"
            else -> "Checking the next phone step"
        } else when (action) {
            PhoneStatusAction.OBSERVE -> "正在读取页面控件"
            PhoneStatusAction.OPEN_APP -> "正在打开应用"
            PhoneStatusAction.CLICK -> "正在点击"
            PhoneStatusAction.LONG_PRESS -> "正在长按"
            PhoneStatusAction.PATH -> "正在授权画布中绘画"
            PhoneStatusAction.SWIPE -> "正在授权画布中滑动"
            PhoneStatusAction.TAP -> "正在授权画布中点击"
            PhoneStatusAction.DRAG -> "正在拖动"
            PhoneStatusAction.SET_TEXT -> "正在输入文本"
            PhoneStatusAction.SUBMIT_TEXT -> "正在提交输入"
            PhoneStatusAction.SCROLL_FORWARD -> "正在向下滚动"
            PhoneStatusAction.SCROLL_BACKWARD -> "正在向上滚动"
            PhoneStatusAction.BACK -> "正在返回上一页"
            PhoneStatusAction.HOME -> "正在返回桌面"
            else -> "正在检查手机下一步操作"
        }
        val result = if (!confirmed) "$verb${if (place.isEmpty()) "" else " · $place"}" else {
            val done = if (english) when (action) {
                PhoneStatusAction.CLICK -> "Tapped"
                PhoneStatusAction.SET_TEXT -> "Text entered"
                PhoneStatusAction.OPEN_APP -> "Launch requested"
                PhoneStatusAction.OBSERVE -> "Screen read"
                else -> "Action performed"
            } else when (action) {
                PhoneStatusAction.CLICK -> "已点击"
                PhoneStatusAction.SET_TEXT -> "已输入文本"
                PhoneStatusAction.OPEN_APP -> "已请求打开应用"
                PhoneStatusAction.OBSERVE -> "已读取页面控件"
                else -> "已执行操作"
            }
            "$done${if (place.isEmpty()) "" else " · $place"}${if (english) "; checking page changes" else "，正在检查页面变化"}"
        }
        return safe(result) ?: if (english) "Checking page controls" else "正在检查页面控件"
    }
}
