#!/usr/bin/env python3
"""Build OwnerGuard Android 1.0.29 with strict Cloud 1.3.27.2 compatibility checks."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
ACCOUNT = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "CloudAccountManager.java"

build = GRADLE.read_text(encoding="utf-8")
for old_code in ("versionCode 10027", "versionCode 10028"):
    build = build.replace(old_code, "versionCode 10029")
for old_name in ("versionName '1.0.27'", "versionName '1.0.28'"):
    build = build.replace(old_name, "versionName '1.0.29'")
if "versionCode 10029" not in build or "versionName '1.0.29'" not in build:
    raise SystemExit("OwnerGuard Android 1.0.29 version anchors were not found")
GRADLE.write_text(build, encoding="utf-8")

java = ACCOUNT.read_text(encoding="utf-8")
java = java.replace('private static final String MIN_SERVER_VERSION = "1.3.2";', 'private static final String MIN_SERVER_VERSION = "1.3.27.2";')
for old_agent in ('OwnerGuard-Android/1.0.26', 'OwnerGuard-Android/1.0.27', 'OwnerGuard-Android/1.0.28'):
    java = java.replace(old_agent, 'OwnerGuard-Android/1.0.29')
java = java.replace('OwnerGuard Cloud 1.3.2', 'OwnerGuard Cloud 1.3.27.2')
anchor = '''        if (!ping.optBoolean("ok",false) || !"OwnerGuard Cloud".equals(ping.optString("service"))) {
            throw new IllegalStateException("This URL is not an OwnerGuard Cloud API.");
        }
        String version=ping.optString("version","0.0.0");
'''
replacement = '''        if (!ping.optBoolean("ok",false) || !"OwnerGuard Cloud".equals(ping.optString("service"))) {
            throw new IllegalStateException("This URL is not an OwnerGuard Cloud API.");
        }
        String version=ping.optString("version","0.0.0");
        if (ping.optBoolean("setup_required",false)) {
            throw new IllegalStateException("OwnerGuard Cloud "+version+" is online but first-run setup is incomplete. Open "+cleanBase(base)+"/setup.php, confirm the page reports Cloud 1.3.27.2 with a ready cryptographic backend, and initialize the Administrator account before signing in from the app.");
        }
        if (!ping.optBoolean("crypto_ready",false)) {
            throw new IllegalStateException("OwnerGuard Cloud "+version+" reports that its cryptographic backend is unavailable. Deploy Cloud 1.3.27.2, enable PHP OpenSSL, and ensure either PHP RSA generation or the OpenSSL command-line fallback is available.");
        }
'''
if 'first-run setup is incomplete' not in java:
    if anchor not in java:
        raise SystemExit("OwnerGuard cloud verification anchor was not found")
    java = java.replace(anchor, replacement, 1)

old_show = '''    private static void show(Activity a,String title,String message){if(a==null||a.isFinishing())return;new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Close",null).show();}
'''
new_show = '''    private static void show(Activity a,String title,String message){
        if(a==null||a.isFinishing())return;
        AlertDialog.Builder dialog=new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Close",null);
        if(message!=null&&(message.contains("first-run setup")||message.contains("cryptographic backend"))){
            dialog.setNeutralButton("Open cloud setup",(d,w)->{
                try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(CloudBackupManager.baseUrl(a)+"/setup.php")));}
                catch(Exception ignored){Toast.makeText(a,"Open "+CloudBackupManager.baseUrl(a)+"/setup.php",Toast.LENGTH_LONG).show();}
            });
        }
        dialog.show();
    }
'''
if old_show in java:
    java = java.replace(old_show, new_show, 1)
elif 'Open cloud setup' not in java:
    raise SystemExit("OwnerGuard dialog anchor was not found")

required = ['MIN_SERVER_VERSION = "1.3.27.2"', 'OwnerGuard-Android/1.0.29', 'first-run setup is incomplete', 'Open cloud setup']
missing = [token for token in required if token not in java]
if missing:
    raise SystemExit("Android cloud compatibility patch incomplete: " + ", ".join(missing))
ACCOUNT.write_text(java, encoding="utf-8")
print("Applied OwnerGuard Android 1.0.29 Cloud 1.3.27.2 compatibility checks")
