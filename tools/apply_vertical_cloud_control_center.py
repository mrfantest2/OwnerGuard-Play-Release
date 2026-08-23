#!/usr/bin/env python3
"""Make OwnerGuard navigation local-only and move Cloud controls into Local setup."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MAIN = JAVA / "MainActivity.java"
SHELL = JAVA / "OwnerGuardDrawerShell.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Vertical control-center anchor missing in {label}: {old[:180]!r}")
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Vertical control-center method anchor missing: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Vertical control-center method brace missing: {signature}")
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
    raise SystemExit(f"Vertical control-center method did not terminate: {signature}")


# Drawer: only the three local destinations remain. Cloud account management and
# all Cloud metadata/admin destinations move to the long Local setup page.
shell = SHELL.read_text(encoding="utf-8")
nav_start = shell.find('        section(nav, "OVERVIEW");')
footer_start = shell.find('        LinearLayout footer = new LinearLayout(activity);', nav_start)
if nav_start < 0 or footer_start < 0:
    raise SystemExit("Drawer navigation range could not be located")
local_nav = '''        section(nav, "OVERVIEW");
        item(nav, "dashboard", "⌂", "Dashboard");

        section(nav, "DEVICE");
        item(nav, "protection", "◈", "Protection");
        item(nav, "setup", "⚙", "Local setup");

'''
shell = shell[:nav_start] + local_nav + shell[footer_start:]

# Recalculate the footer offset after shortening the navigation block.
footer_start = shell.find('        LinearLayout footer = new LinearLayout(activity);', nav_start)
cloud_footer_start = shell.find('        cloudLine = footerLine();', footer_start)
cloud_footer_end = shell.find('        refreshFooter();', cloud_footer_start)
if cloud_footer_start < 0 or cloud_footer_end < 0:
    raise SystemExit("Drawer Cloud footer range could not be located")
cloud_footer_end += len('        refreshFooter();')
local_footer = '''        cryptoLine = footerLine();
        versionLine = footerLine();
        footer.addView(cryptoLine);
        footer.addView(versionLine);
        refreshFooter();'''
shell = shell[:cloud_footer_start] + local_footer + shell[cloud_footer_end:]

shell = replace_method(shell, "    void refreshFooter() {", '''    void refreshFooter() {
        boolean signedIn = CloudAccountManager.loggedIn(activity);
        if (accountLine != null) {
            accountLine.setText(signedIn
                    ? "Signed in as @" + CloudAccountManager.username(activity)
                    : "Cloud account not signed in");
        }
        if (cloudLine != null) {
            cloudLine.setText(signedIn ? "●  Cloud vault connected" : "○  Cloud vault optional");
            cloudLine.setTextColor(signedIn ? GOOD : MUTED);
        }
        if (cryptoLine != null) {
            boolean crypto = VaultCrypto.selfTest(activity);
            cryptoLine.setText((crypto ? "●" : "○") + "  Cryptography "
                    + (crypto ? "ready" : "requires attention"));
            cryptoLine.setTextColor(crypto ? GOOD : Color.rgb(255, 138, 138));
        }
        if (versionLine != null) {
            versionLine.setText("●  Mandatory APK " + appVersion());
        }
    }
''')

for forbidden in (
    'section(nav, "CLOUD VAULT")',
    'section(nav, "ADMINISTRATION")',
    'item(nav, "incidents"',
    'item(nav, "owners"',
    'item(nav, "devices"',
    'item(nav, "users"',
    'item(nav, "audit"',
    'item(nav, "system"',
    'item(nav, "updates"',
    'signOut.setOnClickListener',
):
    if forbidden in shell:
        raise SystemExit("Cloud or administration action remains in drawer: " + forbidden)
for required in (
    'item(nav, "dashboard", "⌂", "Dashboard")',
    'item(nav, "protection", "◈", "Protection")',
    'item(nav, "setup", "⚙", "Local setup")',
    'cryptoLine = footerLine();',
    'versionLine = footerLine();',
):
    if required not in shell:
        raise SystemExit("Local-only drawer output incomplete: " + required)
SHELL.write_text(shell, encoding="utf-8")


# MainActivity: the drawer can no longer route to Cloud. The existing native Cloud
# screens remain available as secondary pages launched from Local setup.
main = MAIN.read_text(encoding="utf-8")
main = replace_method(main, "    private void navigateDrawer(String destination){", '''    private void navigateDrawer(String destination){
        if(destination==null)return;
        if(!OwnerGuardRequirements.allReady(this)){showRequirementsGate();return;}
        switch(destination){
            case "protection": showProtectionTab(); break;
            case "setup": showVaultTab(); break;
            case "dashboard":
            default: showOverviewTab(); break;
        }
    }
''')

old_optional = '''        page.addView(sectionTitle("Optional web vault"),topMargin(22));
        page.addView(labelCard("Create or link the web vault after local setup. A Cloud account is needed only for off-device encrypted backup and web administration."),topMargin(10));
        page.addView(infoCard("Cloud vault",CloudAccountManager.loggedIn(this)?CloudAccountManager.status(this):"Not created — optional"),topMargin(10));
        page.addView(button(CloudAccountManager.loggedIn(this)?"Manage cloud account":"Create or link cloud vault (optional)",v->showCloudAccountDialog()),topMargin(10));
        page.addView(secondaryButton(CloudAccountManager.loggedIn(this)?"Open cloud vault website":"Cloud vault can be created later",v->{if(CloudAccountManager.loggedIn(this))CloudAccountManager.openVault(this);else showCloudOptionalDialog("index.php","Dashboard");}),topMargin(10));'''
new_optional = '''        boolean cloudSignedIn=CloudAccountManager.loggedIn(this);
        boolean cloudAdministrator=cloudSignedIn&&"admin".equalsIgnoreCase(CloudAccountManager.role(this));
        page.addView(sectionTitle("Optional web vault"),topMargin(22));
        page.addView(labelCard("Create or link the web vault after local setup. It is needed only for off-device encrypted backup, native Cloud metadata, and administration."),topMargin(10));
        page.addView(infoCard("Cloud vault",cloudSignedIn?CloudAccountManager.status(this):"Not created — optional"),topMargin(10));
        page.addView(button(cloudSignedIn?"Manage cloud account":"Create or link cloud vault (optional)",v->showCloudAccountDialog()),topMargin(10));
        if(cloudSignedIn){
            page.addView(secondaryButton("Refresh cloud account",v->CloudAccountManager.refresh(this,this::showVaultTab)),topMargin(10));
            page.addView(secondaryButton("Sign out of cloud vault",v->{CloudAccountManager.logout(this);Toast.makeText(this,"Cloud account signed out. Local protection remains available.",Toast.LENGTH_LONG).show();showVaultTab();}),topMargin(10));
        }else{
            page.addView(secondaryButton("Cloud vault can be created later",v->showCloudOptionalDialog("index.php","Dashboard")),topMargin(10));
        }'''
main = replace_once(main, old_optional, new_optional, "optional web vault section")

cloud_backup_start = '        page.addView(sectionTitle("Encrypted cloud backup"),topMargin(22));'
main = replace_once(main, cloud_backup_start,
                    '        if(cloudSignedIn){\n' + cloud_backup_start,
                    "Cloud backup visibility start")

secure_updates = '        page.addView(sectionTitle("Secure app updates"),topMargin(22));'
cloud_control_insert = '''        appendCloudWorkspaceControlCenter(page,cloudAdministrator);
        }else{
            page.addView(sectionTitle("Encrypted cloud backup"),topMargin(22));
            page.addView(labelCard("Link the optional web vault to enable encrypted off-device backup, Cloud incident metadata, and administration. Local capture and the encrypted phone vault remain available without registration."),topMargin(10));
        }
        page.addView(sectionTitle("Secure app updates"),topMargin(22));'''
main = replace_once(main, secure_updates, cloud_control_insert, "Cloud control-center insertion")

helpers = r'''    private void appendCloudWorkspaceControlCenter(LinearLayout page,boolean administrator){
        page.addView(sectionTitle("Cloud workspace"),topMargin(22));
        page.addView(labelCard("Open encrypted Cloud metadata in native Android screens. Plaintext recovery remains a separate explicit and audited browser action."),topMargin(10));
        page.addView(button("All incidents",v->openNativeCloudFromSetup("incidents.php","All incidents")),topMargin(10));
        if(administrator){
            page.addView(secondaryButton("Vault owners",v->openNativeCloudFromSetup("owners.php","Vault owners")),topMargin(10));
        }
        page.addView(secondaryButton("Devices",v->openNativeCloudFromSetup("devices.php","Devices")),topMargin(10));

        if(administrator){
            page.addView(sectionTitle("Administration"),topMargin(22));
            page.addView(labelCard("Administrator-only Cloud controls. These pages remain native and are available only while the linked account reports the Administrator role."),topMargin(10));
            page.addView(button("Users",v->openNativeCloudFromSetup("users.php","Users")),topMargin(10));
            page.addView(secondaryButton("Audit log",v->openNativeCloudFromSetup("audit.php","Audit log")),topMargin(10));
            page.addView(secondaryButton("System status",v->openNativeCloudFromSetup("system.php","System status")),topMargin(10));
            page.addView(secondaryButton("Update management",v->openNativeCloudFromSetup("updates.php","Update management")),topMargin(10));
        }
    }

    private void openNativeCloudFromSetup(String route,String title){
        if(!CloudAccountManager.loggedIn(this)){
            Toast.makeText(this,"Create or link the optional Cloud vault first.",Toast.LENGTH_LONG).show();
            showCloudAccountDialog();
            return;
        }
        startActivity(NativeCloudActivity.intent(this,route,title));
    }

'''
main = replace_once(main,
                    '    private void showLockTimeoutChooser(){\n',
                    helpers + '    private void showLockTimeoutChooser(){\n',
                    "Cloud control-center helper methods")

for forbidden in (
    'case "incidents": openCloudWorkspace',
    'case "owners": openCloudWorkspace',
    'case "devices": openCloudWorkspace',
    'case "users": openCloudWorkspace',
    'case "audit": openCloudWorkspace',
    'case "system": openCloudWorkspace',
    'case "updates": openCloudWorkspace',
):
    if forbidden in main:
        raise SystemExit("Cloud route remains in drawer switch: " + forbidden)
for required in (
    'boolean cloudAdministrator=cloudSignedIn&&"admin".equalsIgnoreCase(CloudAccountManager.role(this));',
    'appendCloudWorkspaceControlCenter(page,cloudAdministrator);',
    'page.addView(sectionTitle("Cloud workspace"),topMargin(22));',
    'page.addView(sectionTitle("Administration"),topMargin(22));',
    'openNativeCloudFromSetup("incidents.php","All incidents")',
    'openNativeCloudFromSetup("users.php","Users")',
    'Sign out of cloud vault',
    'Link the optional web vault to enable encrypted off-device backup',
):
    if required not in main:
        raise SystemExit("Vertical Cloud control center output incomplete: " + required)
MAIN.write_text(main, encoding="utf-8")

print("Applied local-only drawer and vertical conditional Cloud control center")
