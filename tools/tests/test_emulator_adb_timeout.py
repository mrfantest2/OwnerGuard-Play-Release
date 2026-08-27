#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "ownerguard-1.0.44-play-pro-drive.yml"


class EmulatorAdbTimeoutTests(unittest.TestCase):
    def test_emulator_adb_wait_is_bounded_and_diagnostic(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("timeout 240s adb wait-for-device", text)
        self.assertIn("ADB did not become available within 240 seconds", text)
        self.assertIn("tail -120 emulator-evidence/emulator-console.log", text)


if __name__ == "__main__":
    unittest.main()
