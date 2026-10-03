#!/usr/bin/env python3
from pathlib import Path
import re, subprocess, sys
ROOT=Path(__file__).resolve().parents[1]
TOOLS=ROOT/"tools"
GRADLE=ROOT/"app"/"build.gradle"
def run(*a): subprocess.run(list(a),cwd=ROOT,check=True)
def main():
    run(sys.executable,str(TOOLS/"prepare_release_1_0_47.py"))
    t=GRADLE.read_text(encoding="utf-8")
    t=re.sub(r"versionCode\s+10047\b","versionCode 10048",t,count=1)
    t=re.sub(r"versionName\s+'1\.0\.47'","versionName '1.0.48'",t,count=1)
    if "versionCode 10048" not in t or "versionName '1.0.48'" not in t:
        raise SystemExit("version promotion failed")
    GRADLE.write_text(t,encoding="utf-8")
    run(sys.executable,str(TOOLS/"release_contract_1_0_48.py"))
    print("OwnerGuard 1.0.48 production preparation: PASS")
    return 0
if __name__=="__main__": raise SystemExit(main())
