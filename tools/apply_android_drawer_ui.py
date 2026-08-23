#!/usr/bin/env python3
"""Apply the native OwnerGuard Android drawer with local-first onboarding."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "MainActivity.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Android drawer patch anchor missing in {label}: {old[:140]!r}")
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Android drawer method anchor missing: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Android drawer method brace missing: {signature}")
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
    raise SystemExit(f"Android drawer method did not terminate: {signature}")


text = MAIN.read_text(encoding="utf-8")

text = replace_once(
    text,
    "    private FrameLayout tabContent;\n",
    "    private FrameLayout tabContent;\n"
    "    private OwnerGuardDrawerShell drawerShell;\n"
    "    private String drawerRoute=\"dashboard\";\n",
    "MainActivity drawer fields",
)

new_on_new_intent = r'''    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        String destination=intent.getStringExtra("drawer_destination");
        if(destination!=null&&AuthSession.isUnlocked()){
            intent.removeExtra("drawer_destination");
            if("protection".equals(destination))showProtectionTab();
            else if("setup".equals(destination))showVaultTab();
            else showOverviewTab();
            return;
        }
        if(intent.getBooleanExtra("open_vault_after_unlock",false)){
            openVaultAfterUnlock=true;
            if(AuthSession.isUnlocked()){
                openVaultAfterUnlock=false;
                intent.removeExtra("open_vault_after_unlock");
                startActivity(new Intent(this,VaultActivity.class));
            }else buildPinScreen();
        }
    }
'''
text = replace_method(text, "    @Override protected void onNewIntent(Intent intent){", new_on_new_intent)

new_dashboard = r'''    private void buildDashboard(){
        authenticationUiVisible=false;
        drawerShell=new OwnerGuardDrawerShell(this,new OwnerGuardDrawerShell.Handler(){
            @Override public void onNavigate(String destination){navigateDrawer(destination);}
            @Override public void onRefresh(){refreshDrawerPage();}
            @Override public void onCloudSignOut(){
                CloudAccountManager.logout(MainActivity.this);
                try{
                    android.webkit.CookieManager cookies=android.webkit.CookieManager.getInstance();
                    cookies.removeAllCookies(value->cookies.flush());
                }catch(Throwable ignored){}
                Toast.makeText(MainActivity.this,"Cloud account signed out. Local protection remains available.",Toast.LENGTH_SHORT).show();
                if(drawerShell!=null)drawerShell.refreshFooter();
            }
        });
        tabContent=drawerShell.content();
        setContentView(drawerShell.rootView());
        String requested=getIntent().getStringExtra("drawer_destination");
        getIntent().removeExtra("drawer_destination");
        if("protection".equals(requested))showProtectionTab();
        else if("setup".equals(requested))showVaultTab();
        else showOverviewTab();
        AppUpdateManager.check(this,false);
        if(openVaultAfterUnlock){
            openVaultAfterUnlock=false;
            getIntent().removeExtra("open_vault_after_unlock");
            tabContent.post(()->startActivity(new Intent(this,VaultActivity.class)));
        }
    }

    private void navigateDrawer(String destination){
        if(destination==null)return;
        switch(destination){
            case "dashboard": showOverviewTab(); break;
            case "protection": showProtectionTab(); break;
            case "setup": showVaultTab(); break;
            case "incidents": openCloudWorkspace("incidents.php","All incidents"); break;
            case "owners": openCloudWorkspace("owners.php","Vault owners"); break;
            case "devices": openCloudWorkspace("devices.php","Devices"); break;
            case "users": openCloudWorkspace("users.php","Users"); break;
            case "audit": openCloudWorkspace("audit.php","Audit log"); break;
            case "system": openCloudWorkspace("system.php","System status"); break;
            case "updates": openCloudWorkspace("updates.php","Update management"); break;
            default: showOverviewTab();
        }
    }

    private void openCloudWorkspace(String route,String title){
        if(!CloudAccountManager.loggedIn(this)){
            showCloudOptionalDialog(route,title);
            return;
        }
        drawerRoute="cloud";
        startActivity(CloudConsoleActivity.intent(this,route,title));
    }

    private void showCloudOptionalDialog(String route,String title){
        new AlertDialog.Builder(this)
                .setTitle("Cloud vault is optional")
                .setMessage("OwnerGuard is ready for local use without registration. Finish your PIN, permissions, protection, owner-face enrollment, and local encrypted vault first.\n\nCreate or link the web vault later when you want encrypted off-device backup and Cloud administration.")
                .setPositiveButton("Create or sign in",(d,w)->showCloudAccountDialog())
                .setNeutralButton("Local setup",(d,w)->showVaultTab())
                .setNegativeButton("Not now",null)
                .show();
    }

    private void refreshDrawerPage(){
        if("protection".equals(drawerRoute))showProtectionTab();
        else if("setup".equals(drawerRoute))showVaultTab();
        else showOverviewTab();
    }
'''
text = replace_method(text, "    private void buildDashboard(){", new_dashboard)

text = replace_once(
    text,
    "    private void showOverviewTab(){\n        LinearLayout page=page();\n        page.addView(sectionTitle(\"Security status\"));",
    "    private void showOverviewTab(){\n"
    "        drawerRoute=\"dashboard\";\n"
    "        if(drawerShell!=null)drawerShell.setPage(\"Dashboard\",\"dashboard\");\n"
    "        LinearLayout page=page();\n"
    "        status=text(\"\",14);status.setBackground(card(\"#14213D\"));status.setPadding(dp(14),dp(12),dp(14),dp(12));\n"
    "        page.addView(status,topMargin(4));refreshStatus();\n"
    "        page.addView(labelCard(\"Local protection and the encrypted phone vault work without a Cloud account. Cloud setup is optional and can be completed later.\"),topMargin(12));\n"
    "        page.addView(sectionTitle(\"Security status\"),topMargin(18));",
    "showOverviewTab header",
)

text = replace_once(
    text,
    "        Button test=secondaryButton(\"Run visible capture test\",v->testCapture());page.addView(test,topMargin(10));\n        setPage(page);",
    "        Button test=secondaryButton(\"Run visible capture test\",v->testCapture());page.addView(test,topMargin(10));\n"
    "        page.addView(sectionTitle(\"Device controls\"),topMargin(22));\n"
    "        page.addView(button(\"Protection controls\",v->showProtectionTab()),topMargin(10));\n"
    "        page.addView(secondaryButton(\"Local vault and device setup\",v->showVaultTab()),topMargin(10));\n"
    "        setPage(page);",
    "Dashboard quick actions",
)

text = replace_once(
    text,
    "    private void showProtectionTab(){\n        LinearLayout page=page();page.addView(sectionTitle(\"Protection controls\"));",
    "    private void showProtectionTab(){\n"
    "        drawerRoute=\"protection\";\n"
    "        if(drawerShell!=null)drawerShell.setPage(\"Protection\",\"protection\");\n"
    "        LinearLayout page=page();page.addView(sectionTitle(\"Protection controls\"));",
    "showProtectionTab header",
)

text = replace_once(
    text,
    "    private void showVaultTab(){\n        LinearLayout page=page();page.addView(sectionTitle(\"Vault and setup\"));",
    "    private void showVaultTab(){\n"
    "        drawerRoute=\"setup\";\n"
    "        if(drawerShell!=null)drawerShell.setPage(\"Local setup\",\"setup\");\n"
    "        LinearLayout page=page();page.addView(sectionTitle(\"Local vault and device setup\"));\n"
    "        page.addView(labelCard(\"Complete these device steps first. Registration is not required for local protection, incident capture, or the encrypted phone vault.\"),topMargin(10));",
    "showVaultTab header",
)

text = replace_once(
    text,
    "        page.addView(sectionTitle(\"Cloud account\"),topMargin(22));\n        page.addView(labelCard(\"Each cloud user has a separate username and password. New accounts created in the app are active immediately and sign in automatically.\"),topMargin(10));\n        page.addView(infoCard(\"Cloud account\",CloudAccountManager.status(this)),topMargin(10));\n        page.addView(button(CloudAccountManager.loggedIn(this)?\"Manage cloud account\":\"Sign up or log in\",v->showCloudAccountDialog()),topMargin(10));\n        page.addView(secondaryButton(\"Open cloud vault website\",v->CloudAccountManager.openVault(this)),topMargin(10));",
    "        page.addView(sectionTitle(\"Optional web vault\"),topMargin(22));\n"
    "        page.addView(labelCard(\"Create or link the web vault after local setup. A Cloud account is needed only for off-device encrypted backup and web administration.\"),topMargin(10));\n"
    "        page.addView(infoCard(\"Cloud vault\",CloudAccountManager.loggedIn(this)?CloudAccountManager.status(this):\"Not created — optional\"),topMargin(10));\n"
    "        page.addView(button(CloudAccountManager.loggedIn(this)?\"Manage cloud account\":\"Create or link cloud vault (optional)\",v->showCloudAccountDialog()),topMargin(10));\n"
    "        page.addView(secondaryButton(CloudAccountManager.loggedIn(this)?\"Open cloud vault website\":\"Cloud vault can be created later\",v->{if(CloudAccountManager.loggedIn(this))CloudAccountManager.openVault(this);else showCloudOptionalDialog(\"index.php\",\"Dashboard\");}),topMargin(10));",
    "Optional Cloud account section",
)

text = replace_once(
    text,
    "        box.addView(text(\"Sign up creates an active Viewer account and signs you in immediately. An administrator can disable or change the role later.\",14));",
    "        box.addView(text(\"This step is optional. Sign up creates your web vault and an active Viewer account after local setup; login links an existing vault.\",14));",
    "Cloud account dialog copy",
)

text = replace_once(
    text,
    "    @Override protected void onDestroy(){",
    "    @Override public void onBackPressed(){\n"
    "        if(drawerShell!=null&&drawerShell.closeIfOpen())return;\n"
    "        super.onBackPressed();\n"
    "    }\n\n"
    "    @Override protected void onDestroy(){",
    "MainActivity drawer back behavior",
)

required = [
    "OwnerGuardDrawerShell drawerShell",
    "drawer_destination",
    "case \"protection\": showProtectionTab()",
    "case \"setup\": showVaultTab()",
    "Cloud vault is optional",
    "Create or link cloud vault (optional)",
    "openCloudWorkspace(\"incidents.php\",\"All incidents\")",
    "drawerShell.closeIfOpen()",
]
for token in required:
    if token not in text:
        raise SystemExit(f"Android drawer output is incomplete: {token}")

MAIN.write_text(text, encoding="utf-8")
print("Applied OwnerGuard local-first native drawer workspace")
