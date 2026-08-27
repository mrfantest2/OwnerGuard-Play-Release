from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
BUILD_GRADLE = ROOT / "app" / "build.gradle"
EXPECTED_SHA256 = "BC2E0B8928F9D9F981DB1AF5595D0168D54F73FC9A7AB52D8CED6AD0E5979C9E"


class OwnerGuardSigningIdentityContractTest(unittest.TestCase):
    def test_historical_owner_guard_signer_is_pinned(self):
        text = BUILD_GRADLE.read_text(encoding="utf-8")
        self.assertIn(EXPECTED_SHA256, text)
        self.assertIn("MessageDigest.getInstance('SHA-256')", text)
        self.assertIn("signing certificate fingerprint mismatch", text)

    def test_signing_identity_check_precedes_release_signing_config(self):
        text = BUILD_GRADLE.read_text(encoding="utf-8")
        check = text.index("actualSigningCertSha256 != OWNERGUARD_HISTORICAL_SIGNING_CERT_SHA256")
        config = text.index("android.signingConfigs")
        self.assertLess(check, config)


if __name__ == "__main__":
    unittest.main()
