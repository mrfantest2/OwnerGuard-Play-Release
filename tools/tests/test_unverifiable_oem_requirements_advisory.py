import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class UnverifiableOemRequirementsAdvisoryTests(unittest.TestCase):
    def test_unverifiable_oem_requirements_are_removed_from_hard_gate(self):
        fix = ROOT / "tools" / "fix_unverifiable_oem_requirements_advisory.py"
        prepare = ROOT / "tools" / "prepare_release_1_0_44.py"

        self.assertTrue(fix.is_file(), "OEM advisory hard-gate fix is missing")
        text = fix.read_text(encoding="utf-8")
        self.assertIn('if (!unusedAppProtectionReady(context)) out.add(UNUSED_APP);', text)
        self.assertIn('if (!samsungNeverSleepingReady(context)) out.add(SAMSUNG_NEVER_SLEEPING);', text)
        self.assertIn('UNVERIFIABLE_OEM_SETTINGS_ARE_ADVISORY = true', text)

        prepare_text = prepare.read_text(encoding="utf-8")
        self.assertIn('"fix_unverifiable_oem_requirements_advisory.py"', prepare_text)


if __name__ == "__main__":
    unittest.main()
