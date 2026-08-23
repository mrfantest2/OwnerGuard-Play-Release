import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class UnusedAppManualConfirmationTests(unittest.TestCase):
    def test_manual_confirmation_fix_exists_and_is_wired_into_release_preparation(self):
        fix = ROOT / "tools" / "fix_unused_app_manual_confirmation.py"
        self.assertTrue(fix.is_file(), "manual pause-if-unused confirmation fix is missing")

        prepare = (ROOT / "tools" / "prepare_release_1_0_44.py").read_text(encoding="utf-8")
        self.assertIn('"fix_unused_app_manual_confirmation.py"', prepare)

        text = fix.read_text(encoding="utf-8")
        self.assertIn("KEY_UNUSED_OPENED", text)
        self.assertIn("KEY_UNUSED_CONFIRMED", text)
        self.assertIn("Confirm pause-if-unused is Off", text)
        self.assertIn("isAutoRevokeWhitelisted", text)
        self.assertIn("Pause-if-unused confirmation saved", text)


if __name__ == "__main__":
    unittest.main()
