"""
Загрузка и конвертация для Chaquopy (Python внутри APK).

Портировано из nativebins.py: без kivy и jnius. Путь к ffmpeg передаётся аргументом.
Файл кладётся в app/src/main/python/dl.py.

Что вызывает Kotlin (DownloadBridge.kt):
  download(url, height, codec, ai, fmt, out_dir, ffmpeg_path) -> путь к файлу
  convert(src, fmt, out_dir, ffmpeg_path)                    -> путь к файлу
  merge(video, audio, out_dir, fmt, ffmpeg_path)             -> путь к файлу
  status()  -> последняя строка прогресса (Dart опрашивает таймером)
  cancel()  -> отменяет текущую операцию
"""
import os
import re
import select
import shutil
import subprocess
import threading
import time

try:
    import certifi

    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
except ImportError:
    pass

import yt_dlp
from yt_dlp.utils import DownloadCancelled

AUDIO_FORMATS = ("mp3", "wav", "m4a", "opus", "ogg", "flac", "aac")

AUDIO_CODECS = {
    "mp3": ["-c:a", "libmp3lame", "-b:a", "192k"],
    "wav": ["-c:a", "pcm_s16le"],
    "m4a": ["-c:a", "aac", "-b:a", "192k"],
    "opus": ["-c:a", "libopus", "-b:a", "128k"],
    "ogg": ["-c:a", "libvorbis", "-q:a", "5"],
    "flac": ["-c:a", "flac"],
    "aac": ["-c:a", "aac", "-b:a", "192k"],
}

DURATION_RE = re.compile(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)")
TIME_RE = re.compile(r"time=\s*(\d+):(\d+):(\d+(?:\.\d+)?)")

_cancel = threading.Event()
_status = ""


def _on_status(text):
    global _status
    _status = text


def _cancelled():
    return _cancel.is_set()


def status():
    return _status


def cancel():
    _cancel.set()


def seconds_of(match):
    return int(match.group(1)) * 3600 + int(match.group(2)) * 60 + float(match.group(3))


def clock(sec):
    sec = int(sec)
    return f"{sec // 3600}:{sec % 3600 // 60:02d}:{sec % 60:02d}"


def silent_remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


def unique_path(path):
    root, ext = os.path.splitext(path)
    candidate = path
    i = 1
    while os.path.exists(candidate):
        candidate = f"{root} ({i}){ext}"
        i += 1
    return candidate


def run_ffmpeg(ffmpeg, args, on_status, cancelled, label):
    cmd = [ffmpeg, "-hide_banner", "-nostdin", "-y"] + args
    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=0,
    )
    fd = proc.stdout.fileno()
    total = None
    tail = b""
    recent = []
    try:
        while True:
            if cancelled():
                proc.kill()
                proc.wait()
                raise InterruptedError
            if not select.select([fd], [], [], 0.3)[0]:
                if proc.poll() is not None:
                    break
                continue
            chunk = os.read(fd, 4096)
            if not chunk:
                break
            parts = re.split(rb"[\r\n]+", tail + chunk)
            tail = parts.pop()
            for part in parts:
                line = part.decode("utf-8", "replace").strip()
                if not line:
                    continue
                recent = (recent + [line])[-6:]
                if total is None:
                    m = DURATION_RE.search(line)
                    if m:
                        total = seconds_of(m)
                m = TIME_RE.search(line)
                if m:
                    cur = seconds_of(m)
                    if total:
                        on_status(f"{label}: {min(99, int(cur * 100 / total))}%")
                    else:
                        on_status(f"{label}: {clock(cur)}")
    finally:
        if proc.poll() is None:
            proc.kill()
        proc.wait()
        try:
            proc.stdout.close()
        except Exception:
            pass
    if proc.returncode != 0:
        raise RuntimeError(" | ".join(recent[-2:]) or f"ffmpeg код {proc.returncode}")


def run_attempts(ffmpeg, attempts, out, on_status, cancelled, label):
    error = None
    for args in attempts:
        try:
            run_ffmpeg(ffmpeg, args, on_status, cancelled, label)
            return out
        except InterruptedError:
            silent_remove(out)
            raise
        except RuntimeError as e:
            error = e
            silent_remove(out)
    raise error


def convert_media(ffmpeg, src, out_dir, fmt, on_status, cancelled, label="Конвертирую"):
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.splitext(os.path.basename(src))[0]
    out = unique_path(os.path.join(out_dir, f"{base}.{fmt}"))
    if fmt in AUDIO_FORMATS:
        attempts = [
            ["-i", src, "-vn", "-map", "0:a:0"] + AUDIO_CODECS[fmt] + [out],
            ["-i", src, "-vn", out],
        ]
    else:
        flags = ["-movflags", "+faststart"] if fmt == "mp4" else []
        maps = ["-map", "0:v:0", "-map", "0:a?", "-sn"]
        attempts = [
            ["-i", src] + maps + ["-c", "copy"] + flags + [out],
            ["-i", src] + maps
            + ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-b:a", "192k"]
            + flags + [out],
        ]
    return run_attempts(ffmpeg, attempts, out, on_status, cancelled, label)


def merge_media(ffmpeg, video, audio, out_dir, fmt, on_status, cancelled, label="Склеиваю"):
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.splitext(os.path.basename(video))[0]
    out = unique_path(os.path.join(out_dir, f"{base}_merged.{fmt}"))
    flags = ["-movflags", "+faststart"] if fmt == "mp4" else []
    maps = ["-map", "0:v:0", "-map", "1:a:0", "-shortest"]
    head = ["-i", video, "-i", audio] + maps
    attempts = [
        head + ["-c", "copy"] + flags + [out],
        head + ["-c:v", "copy", "-c:a", "aac", "-b:a", "192k"] + flags + [out],
        head
        + ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-b:a", "192k"]
        + flags + [out],
    ]
    return run_attempts(ffmpeg, attempts, out, on_status, cancelled, label)


def yt_format(height, codec, ai):
    sr = "" if ai else "[format_id!*=-sr]"
    return (
        f"bestvideo[vcodec*={codec}][height<={height}]{sr}+bestaudio[format_id!*=-sr]"
        f"/best[height<={height}]{sr}"
    )


class SilentLogger:
    def debug(self, msg):
        pass

    def warning(self, msg):
        pass

    def error(self, msg):
        pass


def download_python(url, height, codec, ai, fmt, path, ffmpeg_exe, on_status, cancelled):
    audio = fmt in AUDIO_FORMATS

    def hook(d):
        if cancelled():
            raise DownloadCancelled()
        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            done = d.get("downloaded_bytes") or 0
            pct = f"{done * 100 / total:.0f}%" if total else "..."
            on_status(f"Качаю: {pct}")
        elif d.get("status") == "finished":
            on_status("Обрабатываю..." if audio else "Склеиваю...")

    opts = {
        "paths": {"home": path},
        "outtmpl": "%(title)s.%(ext)s",
        "ffmpeg_location": ffmpeg_exe,
        "progress_hooks": [hook],
        "logger": SilentLogger(),
        "quiet": True,
        "noprogress": True,
        "no_warnings": True,
        "cachedir": False,
    }
    if audio:
        opts["format"] = "bestaudio/best" if ai else "bestaudio[format_id!*=-sr]/bestaudio/best"
    else:
        opts["format"] = yt_format(height, codec, ai)
        opts["merge_output_format"] = fmt

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
    try:
        src = info["requested_downloads"][0]["filepath"]
    except (KeyError, IndexError, TypeError):
        return path
    if audio and not src.lower().endswith("." + fmt):
        out = convert_media(ffmpeg_exe, src, path, fmt, on_status, cancelled)
        silent_remove(src)
        return out
    return src


# ---- точки входа для Kotlin ----

def download(url, height, codec, ai, fmt, out_dir, ffmpeg_path):
    _cancel.clear()
    os.makedirs(out_dir, exist_ok=True)
    _on_status("Начинаю...")
    try:
        out = download_python(
            url, int(height), codec, bool(ai), fmt, out_dir,
            ffmpeg_path, _on_status, _cancelled,
        )
    except (DownloadCancelled, InterruptedError):
        _on_status("Отменено")
        raise RuntimeError("cancelled")
    _on_status("Готово")
    return out


def convert(src, fmt, out_dir, ffmpeg_path):
    _cancel.clear()
    try:
        return convert_media(ffmpeg_path, src, out_dir, fmt, _on_status, _cancelled)
    except InterruptedError:
        _on_status("Отменено")
        raise RuntimeError("cancelled")


def merge(video, audio, out_dir, fmt, ffmpeg_path):
    _cancel.clear()
    try:
        return merge_media(ffmpeg_path, video, audio, out_dir, fmt, _on_status, _cancelled)
    except InterruptedError:
        _on_status("Отменено")
        raise RuntimeError("cancelled")
