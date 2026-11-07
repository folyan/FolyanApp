package folyan.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class BvbAutomator(
    private val context: Context,
    private val rootContainer: ViewGroup,
    private val onLog: (String) -> Unit,
    private val onDone: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var isRunning = false
    private var testUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun start(phone: String, pass: String) {
        if (isRunning) return
        isRunning = true
        onLog("[*] Запуск автоматизатора BvB...")

        val wv = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(1080, 2400).apply {
                leftMargin = 10000
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""
                    if (url.contains("profline.online/test/session/")) {
                        testUrl = url
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }

        rootContainer.addView(wv)
        webView = wv

        Thread {
            runPipeline(phone, pass)
        }.start()
    }

    fun stop() {
        isRunning = false
        main.post {
            webView?.let {
                rootContainer.removeView(it)
                it.stopLoading()
                it.destroy()
            }
            webView = null
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: Exception) {}
    }

    private fun loadUrl(url: String) {
        main.post { webView?.loadUrl(url) }
    }

    private fun escapeJs(str: String): String {
        return str.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
    }

    private fun eval(js: String): String {
        var res = ""
        var done = false
        main.post {
            webView?.evaluateJavascript("(function(){ try { return ($js); } catch(e) { return 'ERR:' + e; } })()") { r ->
                res = r?.removeSurrounding("\"") ?: ""
                done = true
            }
        }
        val start = System.currentTimeMillis()
        while (!done && System.currentTimeMillis() - start < 6000) {
            sleep(40)
        }
        return res
    }

    private fun runPipeline(phone: String, pass: String) {
        onLog("[*] Проверка профиля...")
        loadUrl("https://bvbinfo.ru/lk-student/diagnostics")
        sleep(4500)

        val hasLogin = eval("document.querySelector('input[type=\"password\"]') !== null")
        if (hasLogin == "true") {
            onLog("[!] Требуется авторизация. Вводим данные...")
            eval("""
                (function() {
                    const tabs = Array.from(document.querySelectorAll('div, button, span'));
                    const phoneTab = tabs.find(el => el.innerText && el.innerText.trim() === 'Телефон');
                    if (phoneTab) phoneTab.click();
                })()
            """)
            sleep(500)

            val safePhone = escapeJs(phone)
            val safePass = escapeJs(pass)

            eval("""
                (function() {
                    const setVal = (el, val) => {
                        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                        setter.call(el, val);
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                    };
                    const log = document.querySelector('input:not([type="password"])');
                    if (log) setVal(log, '$safePhone');
                    const p = document.querySelector('input[type="password"]');
                    if (p) setVal(p, '$safePass');
                })()
            """)
            sleep(500)

            eval("""
                (function() {
                    const btn = Array.from(document.querySelectorAll('button')).find(b => b.innerText && b.innerText.trim().toLowerCase() === 'войти');
                    if (btn) btn.click();
                })()
            """)
            onLog("[*] Ожидаем входа...")
            sleep(7000)
        } else {
            onLog("[+] Успешный вход по сохранённой сессии!")
        }

        eval("""
            (function() {
                const btn = Array.from(document.querySelectorAll('button')).find(b => (b.innerText || '').includes('Хорошо'));
                if (btn) btn.click();
            })()
        """)

        onLog("\n=== [ЭТАП 1: ТЕСТЫ] ===")
        while (isRunning) {
            testUrl = null
            loadUrl("https://bvbinfo.ru/lk-student/diagnostics")
            sleep(3500)

            onLog("[*] Поиск тестов...")
            for (i in 0 until 4) {
                eval("""
                    (function() {
                        const btns = Array.from(document.querySelectorAll('button, a')).filter(el => {
                            const t = (el.innerText || '').toLowerCase().trim();
                            return (t.includes('пройти диагностику') || t.includes('начать')) && !el.disabled;
                        });
                        if (btns.length > 0) btns[0].click();
                        window.scrollBy(0, 400);
                    })()
                """)
                sleep(2000)
                if (testUrl != null) break
            }

            if (testUrl == null) {
                val ifr = eval("document.querySelector('iframe') ? document.querySelector('iframe').src : ''")
                if (ifr.isNotEmpty() && ifr != "null") testUrl = ifr
            }

            if (testUrl == null) {
                onLog("[+] Все доступные тесты сданы!")
                break
            }

            onLog("[+] Открываем сессию: $testUrl")
            loadUrl(testUrl!!)
            sleep(5000)

            var qStep = 1
            var failAttempts = 0
            while (isRunning) {
                sleep(1500)
                eval("""
                    (function() {
                        const btn = Array.from(document.querySelectorAll('button, div[role="button"]')).find(b => 
                            (b.innerText || '').toLowerCase().includes('продолжить')
                        );
                        if (btn) btn.click();
                    })()
                """)

                val isEnd = eval("""
                    (function() {
                        const t = document.body.innerText.toLowerCase();
                        return (t.includes('загрузка отчёта') || t.includes('тест завершен') || t.includes('спасибо'));
                    })()
                """)

                if (isEnd == "true") {
                    onLog("Тест полностью пройден!")
                    break
                }

                val answered = eval("""
                    (function() {
                        const rows = Array.from(document.querySelectorAll('div, label')).filter(el => {
                            const r = el.getBoundingClientRect();
                            const txt = (el.innerText || '').trim();
                            return r.width > 200 && r.height >= 30 &&
                                   !txt.includes('Вопрос') && !txt.includes('Время') && !txt.includes('Далее') && !txt.includes('Назад') &&
                                   el.children.length <= 2;
                        });
                        if (rows.length > 0) {
                            rows[0].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                            return 'true';
                        }
                        return 'false';
                    })()
                """)

                if (answered == "true") {
                    onLog("[Вопрос $qStep] Отвечено")
                    failAttempts = 0
                } else {
                    failAttempts++
                }
                sleep(500)

                val clickedNext = eval("""
                    (function() {
                        const btn = document.querySelector('button.next') || 
                                    Array.from(document.querySelectorAll('button')).reverse().find(b => {
                                        const t = (b.innerText || '').toLowerCase();
                                        return t.includes('далее') || t.includes('ответить') || t.includes('завершить');
                                    });
                        if (btn) { btn.click(); return 'true'; }
                        return 'false';
                    })()
                """)

                if (clickedNext != "true" && failAttempts > 5) {
                    onLog("[!] Нет элементов для клика, возможно тест завершен или завис")
                    break
                }
                qStep++
            }
        }

        onLog("\n=== [ЭТАП 2: АНКЕТЫ] ===")
        var aCount = 1
        while (isRunning) {
            loadUrl("https://bvbinfo.ru/lk-student/diagnostics")
            sleep(3500)

            val hasAnketa = eval("document.querySelector('button.understand__questionnaire-link') !== null")
            if (hasAnketa != "true") {
                onLog("[+] Все анкеты заполнены!")
                break
            }

            onLog("[*] Открываем анкету #$aCount...")
            eval("""
                (function() {
                    const btn = document.querySelector('button.understand__questionnaire-link');
                    if (btn) { btn.scrollIntoView(); btn.click(); }
                })()
            """)
            sleep(3000)
            eval("""
                (function() {
                    const icons = Array.from(document.querySelectorAll('svg')).filter(s => {
                        const r = s.getBoundingClientRect();
                        return r.width >= 15 && r.width <= 70;
                    });
                    if (icons.length >= 5) {
                        icons[4].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                    }
                })()
            """)
            sleep(800)
            eval("""
                (function() {
                    const ta = document.querySelector('textarea, div[class*="textarea"], [contenteditable="true"]');
                    if (ta) {
                        ta.focus();
                        if (ta.value !== undefined) {
                            const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
                            setter.call(ta, 'хорошо');
                        } else {
                            ta.innerText = 'хорошо';
                        }
                        ta.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                })()
            """)
            sleep(600)
            eval("""
                (function() {
                    const btns = Array.from(document.querySelectorAll('button')).filter(b => {
                        const t = (b.innerText || '').toLowerCase();
                        return t.includes('отправить анкету') || t.includes('отправить');
                    });
                    if (btns.length > 0) btns[btns.length - 1].click();
                })()
            """)
            sleep(4000)
            onLog("[+] Анкета #$aCount успешно сдана!")
            aCount++
        }

        onLog("\nВсе тесты и анкеты закрыты.")
        main.post { onDone() }
    }
}
