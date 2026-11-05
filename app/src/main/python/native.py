"""
Доступ к Zig-библиотекам из Python через ctypes (Chaquopy).
Kotlin вызывает init(nativeLibraryDir) один раз при старте.
Библиотеки лежат в jniLibs как libfolyan_*.so и извлекаются в nativeLibraryDir.
"""
import ctypes
import os
from ctypes import (c_bool, c_char_p, c_int, c_size_t, c_uint8, c_uint32,
                    c_void_p, c_ssize_t, byref, create_string_buffer)

PASSWORD_LEN = 32  # passwordmanager.zig: password_len = 30 + 2
PATH_CAP = 4096

_dir = None
_libs = {}


def init(native_dir):
    global _dir
    _dir = native_dir


def _lib(name):
    if _dir is None:
        raise RuntimeError("native.init() не вызван")
    if name not in _libs:
        path = os.path.join(_dir, f"lib{name}.so")
        if not os.path.isfile(path):
            raise RuntimeError(f"нет {path}: проверь jniLibs/arm64-v8a")
        _libs[name] = ctypes.CDLL(path)
    return _libs[name]


# ---------------- пароли: libfolyan_pass.so ----------------

def generate_password(master, salt, site, iterations):
    fn = _lib("folyan_pass").folyan_generate_password
    fn.argtypes = [c_char_p, c_size_t, c_char_p, c_size_t,
                   c_char_p, c_size_t, c_uint32, c_void_p]
    fn.restype = c_int

    m, s, t = master.encode(), salt.encode(), site.encode()
    out = create_string_buffer(PASSWORD_LEN)
    rc = fn(m, len(m), s, len(s), t, len(t), int(iterations), out)
    if rc == -1:
        raise ValueError("слабые параметры")
    if rc != 0:
        raise RuntimeError(f"ошибка генерации пароля: {rc}")
    return out.raw.decode("utf-8", errors="replace")


# ---------------- стеганография: libfolyan_stego.so ----------------

def stego_max_secret(cover_len, out_cap):
    fn = _lib("folyan_stego").folyan_stego_max_secret
    fn.argtypes = [c_size_t, c_size_t]
    fn.restype = c_size_t
    return fn(cover_len, out_cap)


def stego_hide(cover: bytes, secret: bytes) -> bytes:
    lib = _lib("folyan_stego")
    enc = lib.folyan_stego_encode
    enc.argtypes = [c_char_p, c_size_t, c_char_p, c_size_t, c_void_p, c_size_t]
    enc.restype = c_ssize_t
    cap = len(cover) + len(secret) * 24
    out = create_string_buffer(cap)
    n = enc(cover, len(cover), secret, len(secret), out, cap)
    if n < 0:
        raise ValueError(f"не влезло (код {n})")
    return out.raw[:n]


def stego_reveal(data: bytes, cap=None) -> bytes:
    lib = _lib("folyan_stego")
    dec = lib.folyan_stego_decode
    dec.argtypes = [c_char_p, c_size_t, c_void_p, c_size_t]
    dec.restype = c_ssize_t
    cap = cap or len(data) // 24 + 1
    out = create_string_buffer(cap)
    n = dec(data, len(data), out, cap)
    if n < 0:
        raise ValueError(f"не удалось извлечь (код {n})")
    return out.raw[:n]


# ---------------- конвертер Zig: libfolyan_convert.so ----------------
# Альтернатива ffmpeg-обвязке из dl.py. Вызов: convert(exe, src, out_dir, fmt, progress, cancelled)

class _Callbacks(ctypes.Structure):
    _fields_ = [
        ("ctx", c_void_p),
        ("on_progress", c_void_p),
        ("is_cancelled", c_void_p),
        ("remove_file", c_void_p),
        ("exists", c_void_p),
    ]


def convert(exe, src, out_dir, fmt, on_progress=None, is_cancelled=None):
    lib = _lib("folyan_convert")
    lib.folyan_init()

    progress_t = ctypes.CFUNCTYPE(None, c_void_p, c_uint8)
    cancel_t = ctypes.CFUNCTYPE(c_bool, c_void_p)
    remove_t = ctypes.CFUNCTYPE(None, c_void_p, c_char_p)
    exists_t = ctypes.CFUNCTYPE(c_bool, c_void_p, c_char_p)

    # ссылки держим в переменных, иначе сборщик мусора уберёт колбэки
    cb_progress = progress_t(lambda _c, p: on_progress and on_progress(p))
    cb_cancel = cancel_t(lambda _c: bool(is_cancelled and is_cancelled()))
    cb_remove = remove_t(lambda _c, p: os.path.exists(p.decode()) and os.remove(p.decode()))
    cb_exists = exists_t(lambda _c, p: os.path.exists(p.decode()))

    cbs = _Callbacks(None, ctypes.cast(cb_progress, c_void_p),
                     ctypes.cast(cb_cancel, c_void_p),
                     ctypes.cast(cb_remove, c_void_p),
                     ctypes.cast(cb_exists, c_void_p))

    fn = lib.folyan_convert
    fn.argtypes = [c_char_p, c_char_p, c_char_p, c_char_p,
                   c_void_p, c_void_p, c_size_t]
    fn.restype = c_int

    out = create_string_buffer(PATH_CAP)
    rc = fn(exe.encode(), src.encode(), out_dir.encode(), fmt.encode(),
            byref(cbs), out, PATH_CAP)
    if rc != 0:
        raise RuntimeError(f"zig convert: код {rc}")
    return out.value.decode("utf-8")
