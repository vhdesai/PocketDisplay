"""Configuration loader for the PocketDisplay server."""

from pathlib import Path
import os as _os
import shutil as _shutil

import yaml


ROOT_DIR = Path(__file__).resolve().parent.parent
CONFIG_PATH = ROOT_DIR / "configuration.yaml"

_DEFAULTS = {
    "display": {
        "monitor_index": 1,
        "capture_width": 1920,
        "capture_height": 1080,
        "capture_fps": 30,
    },
    "encoding": {
        "preset": "ultrafast",
        "tune": "zerolatency",
        "bitrate": "4M",
        "keyframe_interval": 30,
        "profile": "baseline",
        "level": "3.1",
        "slices": 1,
        "threads": 1,
    },
    "network": {
        "video_port": 5000,
        "control_port": 5002,
        "host": "0.0.0.0",
    },
    "android": {
        "default_server": "127.0.0.1",
    },
}


def _find_executable(name, extra_candidates=None):
    """Find an executable, checking PATH then common install locations."""
    found = _shutil.which(name)
    if found:
        return found
    for path in (extra_candidates or []):
        expanded = _os.path.expandvars(path)
        if _os.path.isfile(expanded):
            return expanded
    return name


def _load_yaml_config():
    """Load configuration.yaml if present, otherwise return defaults only."""
    if not CONFIG_PATH.exists():
        return {}

    try:
        with CONFIG_PATH.open("r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle) or {}
        return data if isinstance(data, dict) else {}
    except Exception as exc:
        print(f"WARNING: Failed to load configuration from {CONFIG_PATH}: {exc}")
        return {}


_CONFIG = _load_yaml_config()


def _get_value(section, key):
    section_data = _CONFIG.get(section, {})
    if isinstance(section_data, dict) and key in section_data and section_data[key] is not None:
        return section_data[key]
    return _DEFAULTS[section][key]


MONITOR_INDEX = int(_get_value("display", "monitor_index"))
CAPTURE_WIDTH = int(_get_value("display", "capture_width"))
CAPTURE_HEIGHT = int(_get_value("display", "capture_height"))
CAPTURE_FPS = int(_get_value("display", "capture_fps"))

FFMPEG_PATH = _find_executable("ffmpeg", [
    r"%LOCALAPPDATA%\ffmpegio\ffmpeg-downloader\ffmpeg\bin\ffmpeg.exe",
    r"C:\ffmpeg\bin\ffmpeg.exe",
    r"C:\Program Files\ffmpeg\bin\ffmpeg.exe",
])
H264_PRESET = str(_get_value("encoding", "preset"))
H264_TUNE = str(_get_value("encoding", "tune"))
H264_BITRATE = str(_get_value("encoding", "bitrate"))
KEYFRAME_INTERVAL = int(_get_value("encoding", "keyframe_interval"))
H264_PROFILE = str(_get_value("encoding", "profile"))
H264_LEVEL = str(_get_value("encoding", "level"))
H264_SLICES = int(_get_value("encoding", "slices"))
H264_THREADS = int(_get_value("encoding", "threads"))

VIDEO_PORT = int(_get_value("network", "video_port"))
CONTROL_PORT = int(_get_value("network", "control_port"))
HOST = str(_get_value("network", "host"))

ANDROID_DEFAULT_SERVER = str(_get_value("android", "default_server"))

ADB_PATH = _find_executable("adb", [
    r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe",
    r"C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe",
    r"%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe",
])
