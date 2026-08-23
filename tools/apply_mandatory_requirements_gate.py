#!/usr/bin/env python3
"""Apply the OwnerGuard 1.0.34 hard requirements gate."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MAIN = JAVA / "MainActivity.java"
CLOUD = JAVA / "CloudConsoleActivity.java"
PROTECTION = JAVA / "ProtectionService.java"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Requirements patch anchor missing in {label}: {old[:180]!r}")
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Requirements method anchor missing: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Requirements method brace missing: {signature}")
    depth = 0
    in_string = False
    escaped = False
    quote = ""
    i = brace
    while i < len(text):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                in_string = False
        else:
            if ch in ('"', "'"):
                in_string = True
                quote = ch
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[:start] + replacement.rstrip() + text[i + 1:]
        i += 1
    raise SystemExit(f"Requirements method did not terminate: {signature}")


manifest = MANIFEST.read_text(encoding="utf-8")
manifest = replace_once(
    manifest,
    '    <uses-permission android:name="android.permission.WAKE_LOCK" />\n',
    '    <uses-permission android:name="android.permission.WAKE_LOCK" />\n'
    '    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />\n'
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />\n',
    "manifest battery and location foreground permissions",
)
manifest = replace_once(
    manifest,
    '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n',
    '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n'
    '    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />\n',
    "manifest background location",
)
manifest = replace_once(
    manifest,
    '            android:foregroundServiceType="camera" />',
    '            android:foregroundServiceType="camera|location" />',
    "protection service foreground types",
)
MANIFEST.write_text(manifest, encoding="utf-8")

main = MAIN.read_text(encoding="utf-8")
main = replace_once(
    main,
    '    private boolean authenticationUiVisible;\n',
    '    private boolean authenticationUiVisible;\n'
    '    private boolean requirementsUiVisible;\n',
    "MainActivity requirements field",
)

for old, new, label in [
    ('PinStore.setPin(this,a);AuthSession.unlock();d.dismiss();buildDashboard();',
     'PinStore.setPin(this,a);AuthSession.unlock();d.dismiss();continueAfterUnlock();',
     'create PIN continuation'),
    ('if(AuthSession.isUnlocked()){buildDashboard();return;}',
     'if(AuthSession.isUnlocked()){continueAfterUnlock();return;}',
     'existing session continuation'),
    ('if(PinStore.verify(this,pinBuffer)){AuthSession.unlock();buildDashboard();}',
     'if(PinStore.verify(this,pinBuffer)){AuthSession.unlock();continueAfterUnlock();}',
     'PIN pad continuation'),
    ('{AuthSession.unlock();d.dismiss();buildDashboard();}',
     '{AuthSession.unlock();d.dismiss();continueAfterUnlock();}',
     'keyboard PIN continuation'),
    ('{AuthSession.unlock();buildDashboard();}',
     '{AuthSession.unlock();continueAfterUnlock();}',
     'biometric continuation'),
]:
    main = replace_once(main, old, new, label)

main = replace_method(main, "    @Override protected void onNewIntent(Intent intent){", r'''    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        if(intent.getBooleanExtra("show_requirements",false)){
            intent.removeExtra("show_requirements");
            if(AuthSession.isUnlocked())showRequirementsGate();
            else buildPinScreen();
            return;
        }
        String destination=intent.getStringExtra("drawer_destination");
        if(destination!=null&&AuthSession.isUnlocked()){
            intent.removeExtra("drawer_destination");
            if(!OwnerGuardRequirements.allReady(this)){
                showRequirementsGate();
                return;
            }
            if("protection".equals(destination))showProtectionTab();
            else if("setup".equals(destination))showVaultTab();
            else showOverviewTab();
            return;
        }
        if(intent.getBooleanExtra("open_vault_after_unlock",false)){
            openVaultAfterUnlock=true;
            if(AuthSession.isUnlocked()){
                if(!OwnerGuardRequirements.allReady(this)){
                    showRequirementsGate();
                    return;
                }
                openVaultAfterUnlock=false;
                intent.removeExtra("open_vault_after_unlock");
                startActivity(new Intent(this,VaultActivity.class));
            }else buildPinScreen();
        }
    }
''')

main = replace_method(main, "    @Override protected void onResume(){", r'''    @Override protected void onResume(){
        super.onResume();
        if(PinStore.isConfigured(this) && !AuthSession.isUnlocked()){
            authenticationUiVisible=false;
            requirementsUiVisible=false;
            if(PinStore.requiresSixDigitMigration(this)) showLegacyMigration();
            else buildPinScreen();
        } else if (AuthSession.isUnlocked()) {
            if(!OwnerGuardRequirements.allReady(this)){
                showRequirementsGate();
                return;
            }
            if(requirementsUiVisible){
                buildDashboard();
                return;
            }
            AppUpdateManager.onActivityResumed(this);
        }
    }
''')

requirements_methods = r'''    private void continueAfterUnlock(){
        if(!OwnerGuardRequirements.allReady(this))showRequirementsGate();
        else buildDashboard();
    }

    private void showRequirementsGate(){
        if(!AuthSession.isUnlocked()){
            buildPinScreen();
            return;
        }
        if(OwnerGuardRequirements.allReady(this)){
            buildDashboard();
            return;
        }
        authenticationUiVisible=false;
        requirementsUiVisible=true;
        drawerShell=null;
        tabContent=null;

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#07101D"));
        LinearLayout page=vertical(20);
        page.setBackgroundColor(Color.parseColor("#07101D"));
        scroll.addView(page,new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView shield=text("⬢",42);
        shield.setTextColor(Color.parseColor("#30C5FF"));
        shield.setGravity(Gravity.CENTER);
        page.addView(shield);
        TextView title=text("Device setup required",27);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        page.addView(title,topMargin(8));
        page.addView(labelCard(
                "OwnerGuard is locked until every required permission and Android system setting is enabled. " +
                "Set battery to Unrestricted before continuing. There is no skip option because restricted background operation can prevent protection and encrypted uploads."),topMargin(14));

        TextView checklist=labelCard(OwnerGuardRequirements.summary(this));
        checklist.setTextSize(14);
        checklist.setTextColor(Color.WHITE);
        page.addView(checklist,topMargin(14));

        Button next=button(OwnerGuardRequirements.nextActionLabel(this),v->{
            OwnerGuardRequirements.performNext(this);
            page.postDelayed(this::showRequirementsGate,450L);
        });
        page.addView(next,topMargin(16));
        page.addView(secondaryButton("Recheck all requirements",v->showRequirementsGate()),topMargin(10));
        page.addView(secondaryButton("Exit OwnerGuard",v->finish()),topMargin(10));
        page.addView(labelCard(
                "Some Android and Samsung settings screens may appear black in a recording because the operating system protects those screens. Return to OwnerGuard after each step."),topMargin(14));

        OwnerGuardRequirements.applySafeInsets(scroll);
        setContentView(scroll);
        AppUpdateManager.check(this,false);
    }

    private void showRequirementsStatus(){
        new AlertDialog.Builder(this)
                .setTitle("Mandatory device access")
                .setMessage(OwnerGuardRequirements.summary(this))
                .setPositiveButton(OwnerGuardRequirements.allReady(this)?"OK":"Fix now",(d,w)->{
                    if(!OwnerGuardRequirements.allReady(this))showRequirementsGate();
                })
                .setNegativeButton("Close",null)
                .show();
    }

'''
main = replace_once(
    main,
    "    private void buildDashboard(){\n",
    requirements_methods + "    private void buildDashboard(){\n"
    "        if(!OwnerGuardRequirements.allReady(this)){showRequirementsGate();return;}\n"
    "        requirementsUiVisible=false;\n",
    "MainActivity hard gate methods",
)
main = replace_once(
    main,
    "    private void navigateDrawer(String destination){\n        if(destination==null)return;\n",
    "    private void navigateDrawer(String destination){\n"
    "        if(destination==null)return;\n"
    "        if(!OwnerGuardRequirements.allReady(this)){showRequirementsGate();return;}\n",
    "drawer hard gate",
)
main = replace_once(
    main,
    "    private void openCloudWorkspace(String route,String title){\n        if(!CloudAccountManager.loggedIn(this)){\n",
    "    private void openCloudWorkspace(String route,String title){\n"
    "        if(!OwnerGuardRequirements.allReady(this)){showRequirementsGate();return;}\n"
    "        if(!CloudAccountManager.loggedIn(this)){\n",
    "Cloud workspace hard gate",
)
main = replace_once(
    main,
    '        page.addView(button("Grant camera, notification and optional location",v->requestPermissionsNow()),topMargin(10));',
    '        page.addView(button("Review mandatory permissions and battery access",v->showRequirementsStatus()),topMargin(10));',
    "local setup permissions button",
)
main = replace_method(main, "    private void requestPermissionsNow(){", '''    private void requestPermissionsNow(){
        showRequirementsGate();
    }
''')
main = replace_method(main, "    private void arm(){", '''    private void arm(){
        if(!OwnerGuardRequirements.allReady(this)){showRequirementsGate();return;}
        LocationSnapshot.refresh(this);
        startForegroundService(new Intent(this,ProtectionService.class).setAction(ProtectionService.ACTION_ARM));
        getSharedPreferences("owner_guard_settings",MODE_PRIVATE).edit().putBoolean("armed",true).apply();
        if(CloudBackupManager.enabled(this)&&CloudBackupManager.configured(this))CloudBackupManager.backupAll(this);
        Toast.makeText(this,"OwnerGuard armed",Toast.LENGTH_SHORT).show();
        refreshStatus();
    }
''')
main = replace_method(main, "    private void testCapture(){", '''    private void testCapture(){
        if(!OwnerGuardRequirements.allReady(this)){showRequirementsGate();return;}
        LocationSnapshot.refresh(this);
        startForegroundService(new Intent(this,ProtectionService.class).setAction(ProtectionService.ACTION_TEST));
        Toast.makeText(this,"Capture test started",Toast.LENGTH_LONG).show();
    }
''')
main = replace_method(main, "    @Override protected void onActivityResult(int r,int c,Intent d){", r'''    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==REQ_ENROLL&&c==RESULT_OK)
            Toast.makeText(this,"Owner-face enrollment complete",Toast.LENGTH_LONG).show();
        refreshStatus();
        if(AuthSession.isUnlocked()&&!OwnerGuardRequirements.allReady(this))showRequirementsGate();
    }
''')
main = replace_method(main, "    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){", r'''    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){
        super.onRequestPermissionsResult(r,p,g);
        LocationSnapshot.refresh(this);
        refreshStatus();
        if(AuthSession.isUnlocked()){
            if(OwnerGuardRequirements.allReady(this))buildDashboard();
            else showRequirementsGate();
        }
    }
''')
main = replace_method(main, "    @Override public void onBackPressed(){", r'''    @Override public void onBackPressed(){
        if(requirementsUiVisible){
            finish();
            return;
        }
        if(drawerShell!=null&&drawerShell.closeIfOpen())return;
        super.onBackPressed();
    }
''')

for token in [
    "Device setup required",
    "OwnerGuardRequirements.allReady(this)",
    "Set battery to Unrestricted",
    "show_requirements",
    "Review mandatory permissions and battery access",
    "requirementsUiVisible",
]:
    if token not in main:
        raise SystemExit(f"MainActivity requirements output incomplete: {token}")
MAIN.write_text(main, encoding="utf-8")

cloud = CLOUD.read_text(encoding="utf-8")
cloud = replace_once(
    cloud,
    "        // Diagnostic Cloud-console recording is allowed; recovered media remains protected elsewhere.\n"
    "        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);\n"
    "        if (!AuthSession.isUnlocked()) {\n",
    "        // Diagnostic Cloud-console recording is allowed; recovered media remains protected elsewhere.\n"
    "        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);\n"
    "        if (!OwnerGuardRequirements.allReady(this)) {\n"
    "            returnToRequirements();\n"
    "            return;\n"
    "        }\n"
    "        if (!AuthSession.isUnlocked()) {\n",
    "Cloud onCreate requirements gate",
)
cloud = replace_once(
    cloud,
    "    @Override protected void onResume() {\n        super.onResume();\n        if (!AuthSession.isUnlocked()) {\n",
    "    @Override protected void onResume() {\n"
    "        super.onResume();\n"
    "        if (!OwnerGuardRequirements.allReady(this)) {\n"
    "            returnToRequirements();\n"
    "            return;\n"
    "        }\n"
    "        if (!AuthSession.isUnlocked()) {\n",
    "Cloud onResume requirements gate",
)
cloud = replace_once(
    cloud,
    "    private void returnToUnlock() {\n",
    "    private void returnToRequirements() {\n"
    "        startActivity(new Intent(this, MainActivity.class)\n"
    "                .putExtra(\"show_requirements\", true)\n"
    "                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));\n"
    "        finish();\n"
    "    }\n\n"
    "    private void returnToUnlock() {\n",
    "Cloud requirements return",
)
CLOUD.write_text(cloud, encoding="utf-8")

protection = PROTECTION.read_text(encoding="utf-8")
protection = replace_once(
    protection,
    '''    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Protection is active. Camera use remains visible."));
        String action = intent == null ? ACTION_ARM : intent.getAction();
        if (ACTION_DISARM.equals(action)) {
''',
    '''    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_ARM : intent.getAction();
        if (ACTION_DISARM.equals(action)) {
''',
    "ProtectionService start order",
)
protection = replace_once(
    protection,
    '''            return START_NOT_STICKY;
        }
        if (ACTION_TEST.equals(action)) {
''',
    '''            return START_NOT_STICKY;
        }
        if (!OwnerGuardRequirements.allReady(this)) {
            getSharedPreferences("owner_guard_settings", MODE_PRIVATE)
                    .edit().putBoolean("armed", false).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID,
                notification("Protection is active. Camera use remains visible."));
        if (ACTION_TEST.equals(action)) {
''',
    "ProtectionService requirements enforcement",
)
PROTECTION.write_text(protection, encoding="utf-8")

combined = manifest + main + cloud + protection
for token in [
    "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "ACCESS_BACKGROUND_LOCATION",
    'foregroundServiceType="camera|location"',
    "OwnerGuardRequirements.performNext(this)",
    "returnToRequirements()",
    "if (!OwnerGuardRequirements.allReady(this))",
]:
    if token not in combined:
        raise SystemExit(f"Mandatory requirements output incomplete: {token}")

print("Applied mandatory permissions, Device Admin, secure lock, update access, and unrestricted-battery gate")
