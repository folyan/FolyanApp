"""
Логика разделов, портированная из core.py / nativebins.py без Kivy.
Kotlin вызывает эти функции через Chaquopy.
"""
import builtins
import contextlib
import io

import native

STEGO_LINE_MAX = 2047  # nativebins.py: STEGO_LINE_MAX


def _one_line(text):
    # nativebins.py: one_line
    return " ".join(text.splitlines()).strip()


# ---------- Менеджер паролей ----------

def password(master, salt, site, iterations):
    if not (master and salt and site):
        raise ValueError("Заполни все поля!")
    if int(iterations) < 1:
        raise ValueError("Итерации: целое число от 1")
    return native.generate_password(master, salt, site, int(iterations))


# ---------- Стеганография ----------

def stego_hide(cover, secret):
    cover, secret = _one_line(cover), _one_line(secret)
    if not secret:
        raise ValueError("Впиши, что прятать, в поле «Что спрятать»")
    if not cover:
        raise ValueError("Впиши маскировку")
    limit = native.stego_max_secret(len(cover.encode()), STEGO_LINE_MAX)
    if len(secret.encode()) > limit:
        raise ValueError(
            f"слишком длинно: при такой маскировке максимум {limit} байт секрета"
        )
    out = native.stego_hide(cover.encode("utf-8"), secret.encode("utf-8"))
    return out.decode("utf-8")


def stego_reveal(text):
    text = _one_line(text)
    data = text.encode("utf-8")
    if not data:
        raise ValueError("Вставь текст в первое поле")
    if len(data) > STEGO_LINE_MAX:
        raise ValueError(f"текст длиннее {STEGO_LINE_MAX} байт")
    return native.stego_reveal(data, cap=STEGO_LINE_MAX).decode("utf-8", "replace")


# ---------- Интерпретатор ----------
# Упрощение: input() не интерактивный. Ответы берутся из answers, иначе ошибка.

def interp_run(code, answers=None):
    out = io.StringIO()
    pending = list(answers or [])

    def fake_input(prompt=""):
        out.write(str(prompt))
        if not pending:
            raise RuntimeError("input(): ответа нет (интерактивный ввод пока не поддержан)")
        answer = pending.pop(0)
        out.write(answer + "\n")
        return answer

    env = {"__builtins__": builtins, "input": fake_input}
    with contextlib.redirect_stdout(out):
        try:
            exec(code, env)
        except (Exception, SystemExit) as e:
            out.write(f"Ошибка: {e}\n")
    return out.getvalue()
