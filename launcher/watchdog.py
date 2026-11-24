"""Polling log-tail watchdog (PLAN.md Chapter 4.1)."""

import os
import threading
import time
from typing import Callable


class LogWatchdog:
    """Polls log_path every tail_ms milliseconds and invokes on_line for new lines."""

    def __init__(self, log_path: str, on_line: Callable[[str], None], tail_ms: int = 800):
        self.log_path = log_path
        self.on_line = on_line
        self.tail_ms = max(10, int(tail_ms))
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._offset = 0

    def start(self) -> None:
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2.0)

    def _run(self) -> None:
        while not self._stop.is_set():
            if os.path.isfile(self.log_path):
                with open(self.log_path, "r", encoding="utf-8", errors="replace") as f:
                    f.seek(self._offset)
                    for line in f:
                        self.on_line(line.rstrip("\r\n"))
                    self._offset = f.tell()
            self._stop.wait(self.tail_ms / 1000.0)
