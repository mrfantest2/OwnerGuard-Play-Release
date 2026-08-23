#!/usr/bin/env python3
"""Make a complete five-angle owner-face profile mandatory for OwnerGuard use."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
REQ = JAVA / "OwnerGuardRequirements.java"
MAIN = JAVA / "MainActivity.java"
FACE = JAVA / "FaceSimilarity.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Owner-face gate anchor missing in {label}: {old[:180]!r}")
    return text.replace(old, new, 1)


# Validate the stored profile itself rather than trusting only the sample counter.
face = FACE.read_text(encoding="utf-8")
face = replace_once(
    face,
    '''    static boolean isEnrolled(Context c) {
        return sampleCount(c) == MAX_SAMPLES;
    }
''',
    '''    static boolean isEnrolled(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (prefs.getInt(OWNER_COUNT, 0) != MAX_SAMPLES) return false;
        for (int i = 0; i < MAX_SAMPLES; i++) {
            String raw = prefs.getString(OWNER_SLOT + i, null);
            String pose = prefs.getString(OWNER_POSE + i, null);
            if (raw == null || raw.trim().isEmpty() || pose == null || pose.trim().isEmpty()) {
                return false;
            }
            try {
                byte[] descriptor = Base64.decode(raw, Base64.NO_WRAP);
                if (descriptor.length != N * N) return false;
            } catch (Throwable invalid) {
                return false;
            }
        }
        return true;
    }
''',
    "FaceSimilarity enrollment validation",
)
FACE.write_text(face, encoding="utf-8")

req = REQ.read_text(encoding="utf-8")
req = replace_once(
    req,
    '''    static final int REQUEST_RUNTIME = 2040;
    static final int REQUEST_ADMIN = 2041;
''',
    '''    static final int REQUEST_RUNTIME = 2040;
    static final int REQUEST_ADMIN = 2041;
    static final int REQUEST_OWNER_FACE = 2042;
''',
    "requirements request codes",
)
req = replace_once(
    req,
    '''    private static final String CAMERA = "camera";
    private static final String NOTIFICATIONS = "notifications";
''',
    '''    private static final String CAMERA = "camera";
    private static final String OWNER_FACE = "owner_face";
    private static final String NOTIFICATIONS = "notifications";
''',
    "owner-face requirement key",
)
req = replace_once(
    req,
    '''        if (!granted(context, Manifest.permission.CAMERA)) out.add(CAMERA);
        if (!notificationsReady(context)) out.add(NOTIFICATIONS);
''',
    '''        if (!granted(context, Manifest.permission.CAMERA)) out.add(CAMERA);
        if (!FaceSimilarity.isEnrolled(context)) out.add(OWNER_FACE);
        if (!notificationsReady(context)) out.add(NOTIFICATIONS);
''',
    "owner-face missing-state check",
)
req = replace_once(
    req,
    '''        if (CAMERA.equals(next)) return "Allow camera";
        if (NOTIFICATIONS.equals(next)) return "Allow notifications";
''',
    '''        if (CAMERA.equals(next)) return "Allow camera";
        if (OWNER_FACE.equals(next)) return "Enroll owner face — five angles";
        if (NOTIFICATIONS.equals(next)) return "Allow notifications";
''',
    "owner-face next action label",
)
req = replace_once(
    req,
    '''        append(out, granted(context, Manifest.permission.CAMERA), "Camera permission",
                "Required for visible incident photos and video.");
        append(out, notificationsReady(context), "Notifications",
''',
    '''        append(out, granted(context, Manifest.permission.CAMERA), "Camera permission",
                "Required for visible incident photos and video.");
        append(out, FaceSimilarity.isEnrolled(context), "Owner face enrollment",
                "Five valid front-camera angles are required before protection can be armed or OwnerGuard can be used.");
        append(out, notificationsReady(context), "Notifications",
''',
    "owner-face requirements summary",
)
req = replace_once(
    req,
    '''            if (NOTIFICATIONS.equals(next)) {
''',
    '''            if (OWNER_FACE.equals(next)) {
                OwnerGuardApp.markInternalTransition();
                activity.startActivityForResult(
                        new Intent(activity, EnrollmentActivity.class), REQUEST_OWNER_FACE);
                return;
            }
            if (NOTIFICATIONS.equals(next)) {
''',
    "owner-face enrollment launcher",
)
for token in (
    "REQUEST_OWNER_FACE = 2042",
    'OWNER_FACE = "owner_face"',
    "FaceSimilarity.isEnrolled(context)",
    "Enroll owner face — five angles",
    "Five valid front-camera angles are required",
    "EnrollmentActivity.class",
):
    if token not in req:
        raise SystemExit("OwnerGuardRequirements owner-face gate incomplete: " + token)
REQ.write_text(req, encoding="utf-8")

# Clearing or replacing the profile must immediately return to the mandatory gate.
main = MAIN.read_text(encoding="utf-8")
old_clear = 'page.addView(secondaryButton("Clear owner-face enrollment",v->{FaceSimilarity.clear(this);Toast.makeText(this,"Owner face enrollment cleared",Toast.LENGTH_SHORT).show();refreshStatus();showVaultTab();}),topMargin(10));'
new_clear = 'page.addView(secondaryButton("Clear owner-face enrollment",v->{FaceSimilarity.clear(this);Toast.makeText(this,"Owner face enrollment cleared — setup is now required",Toast.LENGTH_LONG).show();showRequirementsGate();}),topMargin(10));'
main = replace_once(main, old_clear, new_clear, "clear enrollment hard gate")
MAIN.write_text(main, encoding="utf-8")

combined = req + main + face
for token in (
    "Owner face enrollment",
    "FaceSimilarity.isEnrolled(context)",
    "showRequirementsGate();",
    "descriptor.length != N * N",
):
    if token not in combined:
        raise SystemExit("Mandatory owner-face enrollment output incomplete: " + token)

print("Applied mandatory validated five-angle owner-face enrollment gate")
