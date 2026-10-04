#!/usr/bin/env python3
"""Reconstruct OwnerGuard 1.0.49 with Samsung/Play-safe location gating."""
from pathlib import Path
import re, subprocess, sys

ROOT=Path(__file__).resolve().parents[1]
TOOLS=ROOT/"tools"
GRADLE=ROOT/"app"/"build.gradle"
REQ=ROOT/"app"/"src"/"main"/"java"/"com"/"fantest"/"ownerguard"/"OwnerGuardRequirements.java"

def run(*a): subprocess.run(list(a),cwd=ROOT,check=True)

def remove_block(text,start_marker,end_marker):
    s=text.find(start_marker)
    if s<0: return text
    e=text.find(end_marker,s)
    if e<0: raise SystemExit("could not find end marker for "+start_marker)
    return text[:s]+text[e:]

def main():
    run(sys.executable,str(TOOLS/"prepare_release_1_0_48.py"))

    t=GRADLE.read_text(encoding="utf-8")
    t=re.sub(r"versionCode\s+10048\b","versionCode 10049",t,count=1)
    t=re.sub(r"versionName\s+'1\.0\.48'","versionName '1.0.49'",t,count=1)
    if "versionCode 10049" not in t or "versionName '1.0.49'" not in t:
        raise SystemExit("version promotion failed")
    GRADLE.write_text(t,encoding="utf-8")

    q=REQ.read_text(encoding="utf-8")
    q=q.replace('    private static final String BACKGROUND_LOCATION = "background_location";\n',"")
    q=q.replace('        if (!backgroundLocationReady(context)) out.add(BACKGROUND_LOCATION);\n',"")
    q=q.replace('        if (BACKGROUND_LOCATION.equals(next)) return "Allow location all the time";\n',"")
    q=q.replace(
        '        append(out, preciseLocationReady(context), "Precise location",\n'
        '                "Required for incident evidence metadata.");\n'
        '        append(out, backgroundLocationReady(context), "Background location",\n'
        '                "Required when a protected-device event occurs while OwnerGuard is not open.");\n',
        '        append(out, preciseLocationReady(context), "Precise location (While using the app)",\n'
        '                "Required for current or last-known incident evidence metadata. Background location is not required.");\n'
    )
    q=q.replace(
        '            if (BACKGROUND_LOCATION.equals(next)) {\n'
        '                requestBackgroundLocation(activity);\n'
        '                return;\n'
        '            }\n',"")
    q=remove_block(q,
        '    private static boolean backgroundLocationReady(Context context) {\n',
        '    private static boolean deviceAdminReady(Context context) {\n')
    q=remove_block(q,
        '    private static void requestBackgroundLocation(Activity activity) {\n',
        '    private static void openNotificationSettings(Activity activity) {\n')
    REQ.write_text(q,encoding="utf-8")

    run(sys.executable,str(TOOLS/"release_contract_1_0_49.py"))
    print("OwnerGuard 1.0.49 production preparation: PASS")
    return 0

if __name__=="__main__": raise SystemExit(main())
