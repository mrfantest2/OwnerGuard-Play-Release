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

    def test_emulator_avd_home_is_explicit_and_verified(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('AVD_HOME="${RUNNER_TEMP}/ownerguard-avd"', text)
        self.assertIn('echo "ANDROID_AVD_HOME=${AVD_HOME}" >> "${GITHUB_ENV}"', text)
        self.assertIn('test -f "${AVD_HOME}/ownerguard_api36.ini"', text)


if __name__ == "__main__":
    unittest.main()
