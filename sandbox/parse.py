"""Fixed TXT/Markdown parser. No file paths or executable content are accepted."""
import json
import sys

LIMIT = 2 * 1024 * 1024
try:
    raw = sys.stdin.buffer.read(LIMIT + 1)
    if not raw or len(raw) > LIMIT:
        raise ValueError("input_limit")
    text = raw.decode("utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
    if "\x00" in text:
        raise ValueError("binary_material")
    print(json.dumps({"text": text}, ensure_ascii=False))
except (UnicodeError, ValueError):
    print(json.dumps({"error": "invalid_text_material"}))
    sys.exit(1)
