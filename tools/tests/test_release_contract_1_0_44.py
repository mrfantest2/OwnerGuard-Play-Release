import tempfile
import unittest
from pathlib import Path
import sys

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from release_contract_1_0_44 import FORBIDDEN, REQUIRED, validate_tree


class ReleaseContractTests(unittest.TestCase):
    def make_valid_tree(self, root: Path) -> None:
        for relative, tokens in REQUIRED.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join(tokens) + "\n", encoding="utf-8")

    def test_valid_release_tree_has_no_contract_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_valid_tree(root)
            self.assertEqual([], validate_tree(root))

    def test_version_drift_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_valid_tree(root)
            gradle = root / "app/build.gradle"
            gradle.write_text(
                gradle.read_text(encoding="utf-8").replace("versionCode 10044", "versionCode 10045"),
                encoding="utf-8",
            )
            errors = validate_tree(root)
            self.assertTrue(any("versionCode 10044" in error for error in errors), errors)

    def test_legacy_sideload_gate_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_valid_tree(root)
            relative, tokens = next(iter(FORBIDDEN.items()))
            path = root / relative
            path.write_text(path.read_text(encoding="utf-8") + tokens[0] + "\n", encoding="utf-8")
            errors = validate_tree(root)
            self.assertTrue(any("forbidden Play-release token" in error for error in errors), errors)

    def test_repository_signing_material_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_valid_tree(root)
            key = root / "signing/ownerguard-release.jks"
            key.parent.mkdir(parents=True, exist_ok=True)
            key.write_bytes(b"test-keystore")
            errors = validate_tree(root)
            self.assertTrue(any("credential" in error for error in errors), errors)

    def test_fantest_hosted_cloud_domain_is_rejected_anywhere_in_android_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_valid_tree(root)
            legacy = root / "app/src/main/java/com/fantest/ownerguard/LegacyHostedCloud.java"
            legacy.parent.mkdir(parents=True, exist_ok=True)
            legacy.write_text('final class LegacyHostedCloud { String u = "https://ki.fantest.win/ownerguard_cloud"; }\n', encoding="utf-8")
            errors = validate_tree(root)
            self.assertTrue(any("fantest.win" in error for error in errors), errors)

    def test_forced_cloud_registration_copy_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_valid_tree(root)
            main = root / "app/src/main/java/com/fantest/ownerguard/MainActivity.java"
            main.write_text(
                main.read_text(encoding="utf-8")
                + 'String legacy = "Create your username and six-digit OwnerGuard PIN. Cloud backup is enabled automatically.";\n',
                encoding="utf-8",
            )
            errors = validate_tree(root)
            self.assertTrue(any("forced hosted-cloud registration" in error for error in errors), errors)

    def test_oneplus_safe_enrollment_orientation_contract_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_valid_tree(root)
            face = root / "app/src/main/java/com/fantest/ownerguard/FaceSimilarity.java"
            face.parent.mkdir(parents=True, exist_ok=True)
            face.write_text("final class FaceSimilarity {}\n", encoding="utf-8")
            errors = validate_tree(root)
            self.assertTrue(any("ENROLLMENT_ORIENTATIONS" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
