import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]


class OnePlusPreviewEnrollmentFallbackTests(unittest.TestCase):
    def test_preview_fallback_transform_exists_and_is_wired(self):
        transform = TOOLS / "fix_oneplus_enrollment_preview_fallback.py"
        self.assertTrue(transform.is_file(), "missing OnePlus preview enrollment fallback transform")

        text = transform.read_text(encoding="utf-8")
        for token in (
            "pendingEnrollmentPreview",
            "preview.getBitmap(360, 480)",
            "if (!stillResult.accepted",
            "FaceSimilarity.enrollGuided(this, previewFallback, pose)",
            "previewFallback.recycle()",
        ):
            self.assertIn(token, text)

        prepare = (TOOLS / "prepare_release_1_0_44.py").read_text(encoding="utf-8")
        self.assertIn('"fix_oneplus_enrollment_preview_fallback.py"', prepare)


if __name__ == "__main__":
    unittest.main()
