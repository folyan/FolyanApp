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


# ---------- Интерпретатор (интерактивный, как InterpreterScreen в core.py) ----------
# Код выполняется в фоновом потоке. input() ждёт ответ из очереди replies.
# Kotlin опрашивает drain() и рисует пузыри. stop() прерывает выполнение.

import ctypes
import queue
import threading

_STOP = object()


class _StopRun(Exception):
    """Поднимается внутри потока интерпретатора при нажатии «Стоп»."""


class _Pipe:
    """Перенаправляет print() построчно в пузыри."""

    def __init__(self, emit):
        self.emit = emit
        self.buf = ""

    def write(self, s):
        self.buf += s
        while "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            self.emit(line)
        return len(s)

    def flush(self):
        if self.buf:
            self.emit(self.buf)
            self.buf = ""


class _Interpreter:
    def __init__(self):
        self.env = None
        self.running = False
        self.pending = None
        self.thread_id = None
        self.replies = queue.Queue()
        self.messages = queue.Queue()

    def _say(self, text, right=False):
        self.messages.put([text, "right" if right else "left"])

    def start(self, code, fresh, first=None):
        if self.running:
            return False
        if fresh or self.env is None:
            self.env = {"__builtins__": builtins, "input": self._input}
        self.pending = first
        self.running = True
        while not self.replies.empty():
            self.replies.get_nowait()
        threading.Thread(target=self._run, args=(code,), daemon=True).start()
        return True

    def _input(self, prompt=""):
        if prompt:
            self._say(str(prompt))
        if self.pending is not None:
            answer, self.pending = self.pending, None
            return answer
        reply = self.replies.get()
        if reply is _STOP:
            raise _StopRun()
        return reply

    def answer(self, text):
        if self.running:
            self.replies.put(text)

    def stop(self):
        if not self.running:
            return
        # разблокировать input(), если он ждёт ответа
        self.replies.put(_STOP)
        # прервать выполнение кода в потоке (работает для циклов Python)
        if self.thread_id is not None:
            ctypes.pythonapi.PyThreadState_SetAsyncExc(
                ctypes.c_ulong(self.thread_id), ctypes.py_object(_StopRun)
            )

    def _run(self, code):
        self.thread_id = threading.get_ident()
        pipe = _Pipe(self._say)
        try:
            with contextlib.redirect_stdout(pipe):
                exec(code, self.env)
        except _StopRun:
            pipe.flush()
            self._say("Остановлено")
        except (Exception, SystemExit) as e:
            pipe.flush()
            self._say(f"Ошибка: {e}")
        finally:
            pipe.flush()
            self.thread_id = None
            self.running = False

    def drain(self):
        out = []
        while True:
            try:
                out.append(self.messages.get_nowait())
            except queue.Empty:
                return out


_interp = _Interpreter()


def interp_is_running():
    return _interp.running


def interp_start(code, fresh, first=None):
    return _interp.start(code, fresh, first)


def interp_answer(text):
    _interp.answer(text)


def interp_stop():
    _interp.stop()


def interp_drain():
    return _interp.drain()


def interp_reset():
    _interp.env = None
