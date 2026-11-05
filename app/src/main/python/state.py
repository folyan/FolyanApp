"""Сохранение полей экранов в JSON (аналог *.json из core.py)."""
import os


def load(name, base):
    try:
        with open(os.path.join(base, name), encoding="utf-8") as f:
            return f.read()
    except OSError:
        return "{}"


def save(name, base, text):
    try:
        with open(os.path.join(base, name), "w", encoding="utf-8") as f:
            f.write(text)
    except OSError:
        pass
