package com.fantest.ownerguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Executor;

public class MainActivity extends SecureActivity {
    private static final int REQ_CAMERA=40, REQ_ADMIN=41, REQ_ENROLL=42;
    private TextView status;
    private FrameLayout tabContent;
    private CancellationSignal biometricCancel;
    private String pinBuffer="";
    private TextView pinDots;
    private boolean screenReceiverRegistered;
    private boolean openVaultAfterUnlock;
    private boolean authenticationUiVisible;

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver(){
        @Override public void onReceive(Context c, Intent i){
            if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){
                AuthSession.onBackground(MainActivity.this);
                finishAndRemoveTask();
            }
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        openVaultAfterUnlock=getIntent().getBooleanExtra("open_vault_after_unlock",false);
        registerReceiver(screenOffReceiver,new IntentFilter(Intent.ACTION_SCREEN_OFF)); screenReceiverRegistered=true;
        if(!PinStore.isConfigured(this)) showCreatePin(false); else if(PinStore.requiresSixDigitMigration(this)) showLegacyMigration(); else requireAuthentication();
    }


    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        if(intent.getBooleanExtra("open_vault_after_unlock",false)){
            openVaultAfterUnlock=true;
            if(AuthSession.isUnlocked()){
                openVaultAfterUnlock=false;
                intent.removeExtra("open_vault_after_unlock");
                startActivity(new Intent(this,VaultActivity.class));
            }else buildPinScreen();
        }
    }

    @Override protected void onResume(){
        super.onResume();
        if(PinStore.isConfigured(this) && !AuthSession.isUnlocked()){
            authenticationUiVisible=false;
            if(PinStore.requiresSixDigitMigration(this)) showLegacyMigration();
            else buildPinScreen();
        } else if (AuthSession.isUnlocked()) {
            AppUpdateManager.onActivityResumed(this);
        }
    }

    @Override protected void onDestroy(){
        if(biometricCancel!=null)biometricCancel.cancel();
        if(screenReceiverRegistered)try{unregisterReceiver(screenOffReceiver);}catch(Exception ignored){}
        super.onDestroy();
    }

    private void showCreatePin(boolean migration){
        authenticationUiVisible=true;
        LinearLayout box=vertical(20);
        TextView note=text(migration
                ? "Create a new exact 6-digit OwnerGuard PIN. Your previous PIN has been authenticated and will be replaced."
                : "Create an exact 6-digit OwnerGuard PIN. This protects the encrypted vault and is separate from the phone lock.",15);
        EditText p1=secretField("New 6-digit PIN"); p1.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText p2=secretField("Confirm 6-digit PIN"); p2.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(note);box.addView(p1,buttonParams());box.addView(p2,buttonParams());
        AlertDialog d=new AlertDialog.Builder(this).setTitle(migration?"Upgrade OwnerGuard PIN":"Secure OwnerGuard").setView(box).setCancelable(false).setPositiveButton("Save 6-digit PIN",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            String a=p1.getText().toString(),b=p2.getText().toString();
            if(!a.matches("\\d{6}")||!a.equals(b)){Toast.makeText(this,"Enter matching exact 6-digit PINs",Toast.LENGTH_LONG).show();return;}
            try{PinStore.setPin(this,a);AuthSession.unlock();d.dismiss();buildDashboard();}catch(Exception e){Toast.makeText(this,"Could not save 6-digit PIN",Toast.LENGTH_LONG).show();}
        })); d.show();
    }

    private void showLegacyMigration(){
        if(authenticationUiVisible)return;
        authenticationUiVisible=true;
        LinearLayout box=vertical(20);
        box.addView(text("OwnerGuard now requires an exact 6-digit PIN. Enter your current PIN once, then create the new 6-digit PIN.",15));
        EditText old=secretField("Current OwnerGuard PIN"); old.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(old,buttonParams());
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Upgrade security").setView(box).setCancelable(false)
                .setPositiveButton("Continue",null).setNeutralButton("Use fingerprint / face",null).setNegativeButton("Exit",(a,b)->finish()).create();
        d.setOnShowListener(v->{
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
                if(PinStore.verify(this,old.getText().toString())){authenticationUiVisible=false;d.dismiss();showCreatePin(true);}else Toast.makeText(this,"Current PIN is incorrect",Toast.LENGTH_LONG).show();
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->launchMigrationBiometric(d));
        });
        d.show();
    }

    private void launchMigrationBiometric(AlertDialog dialog){
        try{Executor executor=getMainExecutor();biometricCancel=new CancellationSignal();BiometricPrompt prompt=new BiometricPrompt.Builder(this)
                .setTitle("Verify OwnerGuard owner").setSubtitle("Authenticate before upgrading to a 6-digit PIN")
                .setNegativeButton("Cancel",executor,(d,w)->{}).build();
            prompt.authenticate(biometricCancel,executor,new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){authenticationUiVisible=false;dialog.dismiss();showCreatePin(true);}
                @Override public void onAuthenticationError(int c,CharSequence m){if(c!=BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)Toast.makeText(MainActivity.this,m,Toast.LENGTH_SHORT).show();}
            });
        }catch(Throwable t){Toast.makeText(this,"Biometric authentication unavailable",Toast.LENGTH_LONG).show();}
    }

    private void requireAuthentication(){
        if(AuthSession.isUnlocked()){buildDashboard();return;}
        buildPinScreen();
    }

    private void buildPinScreen(){
        authenticationUiVisible=true;
        pinBuffer="";
        LinearLayout root=vertical(22); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setBackgroundColor(Color.parseColor("#07101D"));
        TextView shield=text("⬢",48); shield.setTextColor(Color.parseColor("#30C5FF")); shield.setGravity(Gravity.CENTER); root.addView(shield);
        TextView title=text("Unlock OwnerGuard",27); title.setTextColor(Color.WHITE); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub=text("Enter your exact 6-digit vault PIN",14); sub.setGravity(Gravity.CENTER); root.addView(sub,bottomMargin(18));
        pinDots=text("○  ○  ○  ○  ○  ○",28); pinDots.setTextColor(Color.WHITE); pinDots.setGravity(Gravity.CENTER); pinDots.setBackground(card("#14213D")); pinDots.setPadding(dp(18),dp(14),dp(18),dp(14)); root.addView(pinDots,bottomMargin(18));

        LinearLayout pad=new LinearLayout(this);pad.setOrientation(LinearLayout.VERTICAL);
        int n=1;for(int r=0;r<3;r++){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);for(int c=0;c<3;c++){final int digit=n++;Button b=key(String.valueOf(digit));b.setOnClickListener(v->appendDigit(digit));row.addView(b,keyParams());}pad.addView(row,rowParams());}
        LinearLayout last=new LinearLayout(this);last.setOrientation(LinearLayout.HORIZONTAL);
        Button bio=key("◉");bio.setOnClickListener(v->launchBiometric(null));
        Button zero=key("0");zero.setOnClickListener(v->appendDigit(0));
        Button back=key("⌫");back.setOnClickListener(v->deleteDigit());
        last.addView(bio,keyParams());last.addView(zero,keyParams());last.addView(back,keyParams());pad.addView(last,rowParams());
        root.addView(pad);
        Button keyboard=secondaryButton("Use keyboard for 6-digit PIN",v->showKeyboardPin()); root.addView(keyboard,topMargin(14));
        Button exit=secondaryButton("Exit",v->finish()); root.addView(exit,topMargin(10));
        setContentView(root);
    }

    private void appendDigit(int d){
        if(pinBuffer.length()>=6)return;
        pinBuffer+=d; updateDots();
        if(pinBuffer.length()==6){
            if(PinStore.verify(this,pinBuffer)){AuthSession.unlock();buildDashboard();}
            else{
                Toast.makeText(this,"Incorrect 6-digit PIN",Toast.LENGTH_SHORT).show();
                pinDots.postDelayed(()->{pinBuffer="";updateDots();},350L);
            }
        }
    }
    private void deleteDigit(){if(pinBuffer.length()>0)pinBuffer=pinBuffer.substring(0,pinBuffer.length()-1);updateDots();}
    private void updateDots(){StringBuilder s=new StringBuilder();for(int i=0;i<6;i++){if(i>0)s.append("  ");s.append(i<pinBuffer.length()?"●":"○");}pinDots.setText(s.toString());}

    private void showKeyboardPin(){
        EditText pin=secretField("Exact 6-digit OwnerGuard PIN"); pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Keyboard PIN").setView(pin).setPositiveButton("Unlock",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{String value=pin.getText().toString();if(value.matches("\\d{6}")&&PinStore.verify(this,value)){AuthSession.unlock();d.dismiss();buildDashboard();}else Toast.makeText(this,"Incorrect 6-digit PIN",Toast.LENGTH_SHORT).show();}));d.show();
    }

    private void launchBiometric(AlertDialog ignored){
        try{Executor executor=getMainExecutor();biometricCancel=new CancellationSignal();BiometricPrompt prompt=new BiometricPrompt.Builder(this).setTitle("Unlock OwnerGuard").setSubtitle("Use an enrolled biometric").setNegativeButton("Cancel",executor,(d,w)->{}).build();prompt.authenticate(biometricCancel,executor,new BiometricPrompt.AuthenticationCallback(){@Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){AuthSession.unlock();buildDashboard();}@Override public void onAuthenticationError(int c,CharSequence m){if(c!=BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)Toast.makeText(MainActivity.this,m,Toast.LENGTH_SHORT).show();}});}catch(Throwable t){Toast.makeText(this,"Biometric authentication unavailable",Toast.LENGTH_LONG).show();}
    }

    private void buildDashboard(){
        authenticationUiVisible=false;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.parseColor("#0A1220"));
        LinearLayout header=vertical(16);header.setBackgroundColor(Color.parseColor("#0A1220"));
        TextView title=text("OwnerGuard",28);title.setTextColor(Color.WHITE);header.addView(title);
        status=text("",14);status.setBackground(card("#14213D"));status.setPadding(dp(14),dp(12),dp(14),dp(12));header.addView(status,topMargin(8));
        root.addView(header);

        HorizontalScrollView tabs=new HorizontalScrollView(this);tabs.setHorizontalScrollBarEnabled(false);tabs.setFillViewport(true);
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setPadding(dp(10),dp(6),dp(10),dp(8));tabs.addView(bar,new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        Button overview=tabButton("Overview");Button protection=tabButton("Protection");Button vault=tabButton("Vault & settings");
        bar.addView(overview,tabParams());bar.addView(protection,tabParams());bar.addView(vault,tabParams());root.addView(tabs);
        tabContent=new FrameLayout(this);root.addView(tabContent,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        overview.setOnClickListener(v->showOverviewTab());protection.setOnClickListener(v->showProtectionTab());vault.setOnClickListener(v->showVaultTab());
        setContentView(root);refreshStatus();showOverviewTab();
        AppUpdateManager.check(this,false);
        if(openVaultAfterUnlock){openVaultAfterUnlock=false;getIntent().removeExtra("open_vault_after_unlock");tabContent.post(()->startActivity(new Intent(this,VaultActivity.class)));}
    }

    private void showOverviewTab(){
        LinearLayout page=page();
        page.addView(sectionTitle("Security status"));
        page.addView(infoCard("Owner face samples",FaceSimilarity.sampleCount(this)+" / 5"),topMargin(10));
        page.addView(infoCard("Encrypted incidents",String.valueOf(new FileCounter(this).count())),topMargin(10));
        page.addView(infoCard("Evidence location",LocationSnapshot.hasPermission(this)?"Enabled":"Optional"),topMargin(10));
        Button open=button("Open encrypted incident vault",v->startActivity(new Intent(this,VaultActivity.class)));page.addView(open,topMargin(16));
        Button test=secondaryButton("Run visible capture test",v->testCapture());page.addView(test,topMargin(10));
        setPage(page);
    }

    private void showProtectionTab(){
        LinearLayout page=page();page.addView(sectionTitle("Protection controls"));
        Button arm=button("Arm protection",v->arm());Button disarm=secondaryButton("Disarm protection",v->disarm());page.addView(arm,topMargin(10));page.addView(disarm,topMargin(10));
        CheckBox instant=check("Capture immediately after failed pattern / PIN / password","instant_failed_capture",true);page.addView(instant,topMargin(14));
        CheckBox autoDelete=check("Auto-delete very strong owner matches","auto_delete_owner",false);page.addView(autoDelete,topMargin(8));
        CheckBox minimal=check("Minimal notification content on lock screen","minimal_notification",true);page.addView(minimal,topMargin(8));
        page.addView(labelCard("Android requires a visible foreground-service notification while camera protection is armed. OwnerGuard can minimize its content, but it cannot safely or reliably hide it."),topMargin(14));
        setPage(page);
    }

    private void showVaultTab(){
        LinearLayout page=page();page.addView(sectionTitle("Vault and setup"));
        page.addView(button("Grant camera, notification and optional location",v->requestPermissionsNow()),topMargin(10));
        page.addView(button("Enable pattern / PIN / password monitoring",v->enableAdmin()),topMargin(10));
        page.addView(button(FaceSimilarity.sampleCount(this)>=5?"Replace owner-face enrollment":"Start owner-face enrollment",v->startGuidedEnrollment()),topMargin(10));
        page.addView(button("Test owner selfie recognition",v->startSelfieTest()),topMargin(10));
        page.addView(secondaryButton("Clear owner-face enrollment",v->{FaceSimilarity.clear(this);Toast.makeText(this,"Owner face enrollment cleared",Toast.LENGTH_SHORT).show();refreshStatus();showVaultTab();}),topMargin(10));
        page.addView(secondaryButton("Run vault encryption self-test",v->runVaultSelfTest()),topMargin(10));
        page.addView(sectionTitle("App lock"),topMargin(22));
        page.addView(labelCard("Choose when OwnerGuard locks after you leave the app. The vault still locks immediately when you tap Lock now or after the selected timeout expires."),topMargin(10));
        page.addView(button("Lock after leaving: "+AuthSession.timeoutLabel(this),v->showLockTimeoutChooser()),topMargin(10));
        page.addView(secondaryButton("Lock OwnerGuard now",v->{AuthSession.lock();buildPinScreen();}),topMargin(10));
        page.addView(sectionTitle("Cloud account"),topMargin(22));
        page.addView(labelCard("Each cloud user has a separate username and password. New accounts created in the app are active immediately and sign in automatically."),topMargin(10));
        page.addView(infoCard("Cloud account",CloudAccountManager.status(this)),topMargin(10));
        page.addView(button(CloudAccountManager.loggedIn(this)?"Manage cloud account":"Sign up or log in",v->showCloudAccountDialog()),topMargin(10));
        page.addView(secondaryButton("Open cloud vault website",v->CloudAccountManager.openVault(this)),topMargin(10));
        page.addView(sectionTitle("Encrypted cloud backup"),topMargin(22));
        boolean cloudReady=CloudBackupManager.configured(this);
        page.addView(labelCard("End-to-end encrypted backup to master_pc. The server stores ciphertext only and exposes no media-delete action. Uploaded incidents: "+CloudBackupManager.uploadedCount(this)+" • Pending: "+CloudBackupManager.pendingCount(this)),topMargin(10));
        CheckBox cloudEnabled=check("Automatically back up every new incident","cloud_enabled_proxy",false);
        cloudEnabled.setChecked(CloudBackupManager.enabled(this));
        cloudEnabled.setOnCheckedChangeListener((b,v)->CloudBackupManager.prefs(this).edit().putBoolean(CloudBackupManager.KEY_ENABLED,v).apply());
        page.addView(cloudEnabled,topMargin(10));
        CheckBox adminRecovery=new CheckBox(this);adminRecovery.setText("Allow authorized Administrator recovery for cloud evidence");adminRecovery.setTextColor(Color.parseColor("#D9E4F0"));adminRecovery.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#30C5FF")));adminRecovery.setChecked(CloudBackupManager.adminEscrowConsent(this));
        adminRecovery.setOnCheckedChangeListener((button,checked)->{if(!button.isPressed())return;if(!checked){CloudBackupManager.setAdminEscrowConsent(this,false);return;}button.setChecked(false);new AlertDialog.Builder(this).setTitle("Administrator recovery disclosure").setMessage("When enabled, every new cloud evidence object is dual-encrypted: once for your recovery key and once for the organization Administrator escrow. An authorized Administrator with the separate escrow passphrase can decrypt the evidence, and each access is audited. The server still stores ciphertext only. Enable this?").setPositiveButton("Enable",(d,w)->{CloudBackupManager.setAdminEscrowConsent(this,true);button.setChecked(true);Toast.makeText(this,"Administrator recovery enabled",Toast.LENGTH_LONG).show();}).setNegativeButton("Not now",null).show();});
        page.addView(adminRecovery,topMargin(8));
        CheckBox wifiOnly=check("Upload on Wi-Fi only","cloud_wifi_proxy",true);
        wifiOnly.setChecked(CloudBackupManager.wifiOnly(this));
        wifiOnly.setOnCheckedChangeListener((b,v)->CloudBackupManager.prefs(this).edit().putBoolean(CloudBackupManager.KEY_WIFI_ONLY,v).apply());
        page.addView(wifiOnly,topMargin(8));
        page.addView(button(cloudReady?"Edit master-PC backup connection":"Configure master-PC backup",v->showCloudSetup()),topMargin(10));
        page.addView(secondaryButton("Back up all existing incidents",v->{if(!CloudBackupManager.configured(this)){showCloudSetup();return;}CloudBackupManager.backupAll(this);Toast.makeText(this,"Encrypted backup queued",Toast.LENGTH_LONG).show();}),topMargin(10));
        page.addView(labelCard("Dual-envelope cloud protection is enabled for new uploads. Evidence can be opened with your recovery key or by an authorized Administrator using the separate audited escrow passphrase. The server never stores plaintext media."),topMargin(10));
        page.addView(secondaryButton("Migrate retained incidents for Administrator recovery",v->confirmEscrowMigration()),topMargin(10));
        page.addView(secondaryButton("Show / copy cloud recovery key",v->showCloudRecoveryKey()),topMargin(10));
        page.addView(secondaryButton("Import cloud recovery key",v->showImportCloudKey()),topMargin(10));
        page.addView(labelCard("Keep the recovery key offline. It opens your vault and is never uploaded automatically. Authorized Administrators can open migrated OGC2 evidence only with the separate audited escrow passphrase."),topMargin(10));
        page.addView(sectionTitle("Secure app updates"),topMargin(22));
        page.addView(labelCard("OwnerGuard checks daily using Android WorkManager and accepts only a newer APK with the same package ID, exact byte count, matching SHA-256, and the exact installed signing certificate. Downloads finalize atomically, and Android always shows the final Update confirmation."),topMargin(10));
        page.addView(labelCard(AppUpdateManager.statusSummary(this)),topMargin(10));
        page.addView(button("Check for signed OwnerGuard update",v->AppUpdateManager.check(this,true)),topMargin(10));
        setPage(page);
    }


    private void showLockTimeoutChooser(){
        final String[] labels={"Instantly","After 1 minute","After 3 minutes","After 5 minutes","After 10 minutes"};
        final long[] values={0L,60_000L,180_000L,300_000L,600_000L};
        long current=AuthSession.timeout(this);int selected=1;for(int i=0;i<values.length;i++)if(values[i]==current)selected=i;
        new AlertDialog.Builder(this).setTitle("Lock after leaving OwnerGuard")
                .setSingleChoiceItems(labels,selected,(dialog,which)->{AuthSession.setTimeout(this,values[which]);dialog.dismiss();Toast.makeText(this,"App lock: "+labels[which],Toast.LENGTH_LONG).show();showVaultTab();})
                .setNegativeButton("Cancel",null).show();
    }

    private void showCloudAccountDialog(){
        if(CloudAccountManager.loggedIn(this)){
            new AlertDialog.Builder(this).setTitle("Cloud account")
                    .setMessage("Signed in as " + CloudAccountManager.status(this) + ".\n\n" +
                            "The app PIN protects this phone. Your cloud username and password control access to the multi-user website.")
                    .setPositiveButton("Refresh",(d,w)->CloudAccountManager.refresh(this,this::showVaultTab))
                    .setNeutralButton("Open vault",(d,w)->CloudAccountManager.openVault(this))
                    .setNegativeButton("Sign out",(d,w)->{CloudAccountManager.logout(this);Toast.makeText(this,"Cloud account signed out",Toast.LENGTH_SHORT).show();showVaultTab();}).show();
            return;
        }
        LinearLayout box=vertical(14);
        EditText url=secretField("OwnerGuard Cloud URL");url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);url.setText(CloudBackupManager.baseUrl(this));
        EditText username=secretField("Username");username.setInputType(InputType.TYPE_CLASS_TEXT);
        EditText display=secretField("Display name (for sign-up)");display.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText password=secretField("Password — at least 10 characters");
        EditText confirm=secretField("Confirm password (sign-up only)");
        box.addView(text("Sign up creates an active Viewer account and signs you in immediately. An administrator can disable or change the role later.",14));
        box.addView(url,topMargin(8));box.addView(username,topMargin(8));box.addView(display,topMargin(8));box.addView(password,topMargin(8));box.addView(confirm,topMargin(8));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("OwnerGuard Cloud account").setView(box)
                .setPositiveButton("Log in",null).setNeutralButton("Sign up",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{String u=username.getText().toString().trim(),pw=password.getText().toString();if(u.isEmpty()||pw.isEmpty()){Toast.makeText(this,"Enter username and password",Toast.LENGTH_SHORT).show();return;}CloudAccountManager.login(this,url.getText().toString(),u,pw,()->{dialog.dismiss();showVaultTab();});});
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{String u=username.getText().toString().trim(),name=display.getText().toString().trim(),pw=password.getText().toString(),pw2=confirm.getText().toString();if(!pw.equals(pw2)){Toast.makeText(this,"Passwords do not match",Toast.LENGTH_LONG).show();return;}CloudAccountManager.signUp(this,url.getText().toString(),u,name,pw,()->{dialog.dismiss();showVaultTab();});});
        });dialog.show();
    }

    private void showCloudSetup(){
        LinearLayout box=vertical(16);
        EditText url=secretField("Server URL"); url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); url.setText(CloudBackupManager.baseUrl(this));
        EditText token=secretField("Legacy upload token (optional when signed in)"); token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(CloudBackupManager.prefs(this).getString(CloudBackupManager.KEY_TOKEN,""));
        boolean accountOwned=CloudAccountManager.loggedIn(this);
        box.addView(text(accountOwned
                ?"New evidence will be assigned to @"+CloudAccountManager.username(this)+". The account token is used automatically. Keep the legacy upload token blank unless an administrator specifically asks for it."
                :"Sign in to an OwnerGuard Cloud account so new evidence belongs to that user. A legacy upload token remains available for older server setups.",14));
        box.addView(url,topMargin(10)); box.addView(token,topMargin(10));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Encrypted cloud backup").setView(box).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            String u=url.getText().toString().trim(),k=token.getText().toString().trim();
            boolean validUrl=u.startsWith("https://")||u.startsWith("http://");
            if(!validUrl||(!CloudAccountManager.loggedIn(this)&&k.length()<24)){
                Toast.makeText(this,CloudAccountManager.loggedIn(this)?"Enter a valid cloud URL":"Sign in or enter a valid legacy upload token",Toast.LENGTH_LONG).show();return;
            }
            CloudBackupManager.prefs(this).edit().putString(CloudBackupManager.KEY_URL,u).putString(CloudBackupManager.KEY_TOKEN,k).apply();
            d.dismiss();Toast.makeText(this,CloudAccountManager.loggedIn(this)?"Account-owned cloud backup saved":"Legacy cloud backup saved",Toast.LENGTH_LONG).show();showVaultTab();
        }));d.show();
    }

    private void confirmEscrowMigration(){
        if(!CloudAccountManager.loggedIn(this)){Toast.makeText(this,"Sign in to your cloud account first",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("Enable Administrator recovery for retained incidents")
                .setMessage("OwnerGuard will re-encrypt and re-upload locally retained incidents using OGC2 dual-envelope encryption. Existing cloud objects remain append-only. This grants authorized Administrators access using the audited Administrator escrow passphrase. Continue?")
                .setPositiveButton("Migrate",(d,w)->{CloudBackupManager.setAdminEscrowConsent(this,true);int n=CloudBackupManager.prepareLegacyMigration(this);CloudBackupManager.backupAll(this);Toast.makeText(this,"Migration queued for "+n+" retained incident(s)",Toast.LENGTH_LONG).show();showVaultTab();})
                .setNegativeButton("Cancel",null).show();
    }

    private void showCloudRecoveryKey(){
        try{String key=CloudCrypto.recoveryKey(this);TextView view=text(key,16);view.setTextColor(Color.WHITE);view.setTextIsSelectable(true);view.setPadding(dp(12),dp(12),dp(12),dp(12));view.setBackground(card("#14213D"));new AlertDialog.Builder(this).setTitle("Cloud recovery key").setMessage("Enter this key in the selected user vault on OwnerGuard Cloud. This key opens your own vault. OwnerGuard v1.0.26 also wraps new evidence for the audited Administrator escrow; this recovery key is never uploaded to the server.").setView(view).setPositiveButton("Copy",(d,w)->{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("OwnerGuard cloud recovery key",key));Toast.makeText(this,"Recovery key copied",Toast.LENGTH_LONG).show();}).setNegativeButton("Close",null).show();}catch(Exception e){Toast.makeText(this,"Could not load recovery key",Toast.LENGTH_LONG).show();}
    }

    private void showImportCloudKey(){
        EditText key=secretField("43-character recovery key");key.setInputType(InputType.TYPE_CLASS_TEXT);new AlertDialog.Builder(this).setTitle("Import cloud recovery key").setMessage("This replaces the cloud encryption key used for future uploads. Existing cloud evidence requires its original key.").setView(key).setPositiveButton("Import",(d,w)->{try{CloudCrypto.importRecoveryKey(this,key.getText().toString());Toast.makeText(this,"Recovery key imported",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"Invalid recovery key",Toast.LENGTH_LONG).show();}}).setNegativeButton("Cancel",null).show();
    }

    private LinearLayout page(){ScrollView s=new ScrollView(this);s.setBackgroundColor(Color.parseColor("#0A1220"));LinearLayout p=vertical(18);s.addView(p);p.setTag(s);return p;}
    private void setPage(LinearLayout page){tabContent.removeAllViews();ScrollView s=(ScrollView)page.getTag();tabContent.addView(s);}

    private void requestPermissionsNow(){java.util.ArrayList<String>req=new java.util.ArrayList<>();if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)req.add(Manifest.permission.CAMERA);if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)req.add(Manifest.permission.POST_NOTIFICATIONS);if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)req.add(Manifest.permission.ACCESS_COARSE_LOCATION);if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)req.add(Manifest.permission.ACCESS_FINE_LOCATION);if(req.isEmpty())Toast.makeText(this,"Permissions already granted",Toast.LENGTH_SHORT).show();else requestPermissions(req.toArray(new String[0]),REQ_CAMERA);}
    private void enableAdmin(){ComponentName admin=new ComponentName(this,TheftAdminReceiver.class);DevicePolicyManager dpm=(DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);if(dpm.isAdminActive(admin)){Toast.makeText(this,"Credential monitoring already enabled",Toast.LENGTH_SHORT).show();refreshStatus();return;}Intent i=new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,admin);i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,"OwnerGuard records failed pattern, PIN and password attempts for personal device protection.");startActivityForResult(i,REQ_ADMIN);}
    private void startSelfieTest(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissionsNow();return;}if(!FaceSimilarity.isEnrolled(this)){Toast.makeText(this,"Complete five-angle enrollment first",Toast.LENGTH_LONG).show();return;}startActivity(new Intent(this,SelfieVerificationActivity.class));}
    private void startGuidedEnrollment(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissionsNow();return;}startActivityForResult(new Intent(this,EnrollmentActivity.class),REQ_ENROLL);}
    private void arm(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissionsNow();return;}LocationSnapshot.refresh(this);startForegroundService(new Intent(this,ProtectionService.class).setAction(ProtectionService.ACTION_ARM));getSharedPreferences("owner_guard_settings",MODE_PRIVATE).edit().putBoolean("armed",true).apply();if(CloudBackupManager.enabled(this)&&CloudBackupManager.configured(this))CloudBackupManager.backupAll(this);Toast.makeText(this,"OwnerGuard armed",Toast.LENGTH_SHORT).show();refreshStatus();}
    private void disarm(){startService(new Intent(this,ProtectionService.class).setAction(ProtectionService.ACTION_DISARM));getSharedPreferences("owner_guard_settings",MODE_PRIVATE).edit().putBoolean("armed",false).apply();Toast.makeText(this,"OwnerGuard disarmed",Toast.LENGTH_SHORT).show();refreshStatus();}
    private void runVaultSelfTest(){boolean ok=VaultCrypto.selfTest(this);Toast.makeText(this,ok?"Vault encryption test passed":"Vault test failed",Toast.LENGTH_LONG).show();refreshStatus();}
    private void testCapture(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissionsNow();return;}LocationSnapshot.refresh(this);startForegroundService(new Intent(this,ProtectionService.class).setAction(ProtectionService.ACTION_TEST));Toast.makeText(this,"Capture test started",Toast.LENGTH_LONG).show();}

    private void refreshStatus(){if(status==null)return;DevicePolicyManager dpm=(DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);boolean admin=dpm.isAdminActive(new ComponentName(this,TheftAdminReceiver.class));boolean armed=getSharedPreferences("owner_guard_settings",MODE_PRIVATE).getBoolean("armed",false);status.setText((armed?"● ARMED":"○ DISARMED")+"   •   Credential monitor: "+(admin?"ON":"OFF")+"\nVault: "+(VaultCrypto.selfTest(this)?"Ready":"Error")+"   •   Incidents: "+new FileCounter(this).count());}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r==REQ_ENROLL&&c==RESULT_OK)Toast.makeText(this,"Owner-face enrollment complete",Toast.LENGTH_LONG).show();refreshStatus();}
    @Override public void onRequestPermissionsResult(int r,String[]p,int[]g){super.onRequestPermissionsResult(r,p,g);LocationSnapshot.refresh(this);refreshStatus();}

    private CheckBox check(String text,String key,boolean def){CheckBox c=new CheckBox(this);c.setText(text);c.setTextColor(Color.parseColor("#D9E4F0"));c.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#30C5FF")));c.setChecked(getSharedPreferences("owner_guard_settings",MODE_PRIVATE).getBoolean(key,def));c.setOnCheckedChangeListener((b,v)->getSharedPreferences("owner_guard_settings",MODE_PRIVATE).edit().putBoolean(key,v).apply());return c;}
    private LinearLayout infoCard(String label,String value){LinearLayout b=vertical(12);b.setBackground(card("#14213D"));TextView l=text(label,13);TextView v=text(value,22);v.setTextColor(Color.WHITE);b.addView(l);b.addView(v);return b;}
    private TextView labelCard(String s){TextView t=text(s,13);t.setBackground(card("#10213A"));t.setPadding(dp(14),dp(14),dp(14),dp(14));return t;}
    private TextView sectionTitle(String s){TextView t=text(s,22);t.setTextColor(Color.WHITE);return t;}
    private Button tabButton(String s){Button b=secondaryButton(s,v->{});b.setMinWidth(dp(125));return b;}
    private Button key(String s){Button b=new Button(this);b.setText(s);b.setTextSize(23);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setBackground(card("#14213D"));b.setMinHeight(dp(62));return b;}
    private LinearLayout.LayoutParams keyParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);p.setMargins(dp(5),dp(5),dp(5),dp(5));return p;}
    private LinearLayout.LayoutParams rowParams(){return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);}
    private LinearLayout.LayoutParams tabParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);p.setMargins(dp(4),0,dp(4),0);return p;}
    private LinearLayout vertical(int pad){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);int p=dp(pad);l.setPadding(p,p,p,p);return l;}
    private TextView text(String s,int sp){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.parseColor(sp>=18?"#F8FAFC":"#B8C5D6"));return v;}
    private EditText secretField(String h){EditText e=new EditText(this);e.setHint(h);e.setTextColor(Color.WHITE);e.setHintTextColor(Color.parseColor("#94A3B8"));e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);e.setBackground(card("#1C2A48"));e.setPadding(dp(14),dp(14),dp(14),dp(14));return e;}
    private Button button(String s,View.OnClickListener l){return themedButton(s,"#0891B2",l);}private Button secondaryButton(String s,View.OnClickListener l){return themedButton(s,"#1C2A48",l);}private Button themedButton(String s,String c,View.OnClickListener l){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(card(c));b.setOnClickListener(l);b.setMinHeight(dp(52));return b;}
    private GradientDrawable card(String c){GradientDrawable d=new GradientDrawable();d.setColor(Color.parseColor(c));d.setCornerRadius(dp(16));return d;}
    private LinearLayout.LayoutParams topMargin(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.setMargins(0,dp(n),0,0);return p;}private LinearLayout.LayoutParams bottomMargin(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.setMargins(0,0,0,dp(n));return p;}private LinearLayout.LayoutParams buttonParams(){return topMargin(8);}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static final class FileCounter{private final Context c;FileCounter(Context c){this.c=c;}int count(){java.io.File d=new java.io.File(c.getFilesDir(),"vault/events");java.io.File[]f=d.listFiles();return f==null?0:f.length;}}
}
