package com.fantest.ownerguard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EventDetailActivity extends SecureActivity {
    private static final String BG="#0A1220", SURFACE="#14213D", SURFACE_ALT="#1C2A48", PRIMARY="#0891B2", TEXT="#F8FAFC", MUTED="#B8C5D6";
    private File eventDir;
    private final List<File> temporary = new ArrayList<>();
    private ImageView image;
    private TextView mediaStatus;
    private int imageIndex;
    private File[] encryptedPhotos = new File[0];
    private JSONObject metadata = new JSONObject();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(!AuthSession.isUnlocked()){ routeToUnlock(); return; }
        String event=getIntent().getStringExtra("event");
        if(event==null||event.contains("/")||event.contains("..")){finish();return;}
        eventDir=new File(new File(getFilesDir(),"vault/events"),event);
        if(!eventDir.isDirectory()){finish();return;}
        metadata=readMetadataObject(); render();
    }

    private void routeToUnlock() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private void render(){
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(Color.parseColor(BG));
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(18)); scroll.addView(root);
        root.addView(heading("Incident details")); root.addView(label("Authenticated review and temporary export of encrypted evidence."));
        TextView meta=body(metadataText()); meta.setBackground(card(SURFACE)); meta.setPadding(dp(16),dp(16),dp(16),dp(16)); root.addView(meta,margin());
        mediaStatus=body("Ready"); mediaStatus.setBackground(card(SURFACE_ALT)); mediaStatus.setPadding(dp(14),dp(14),dp(14),dp(14)); root.addView(mediaStatus,margin());

        encryptedPhotos=eventDir.listFiles((d,n)->n.startsWith("photo_")&&n.endsWith(".ogv"));
        if(encryptedPhotos==null)encryptedPhotos=new File[0]; Arrays.sort(encryptedPhotos);
        LinearLayout photoCard=section("Captured photos"); root.addView(photoCard,margin());
        image=new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setMinimumHeight(dp(330)); image.setBackgroundColor(Color.parseColor("#08101D")); photoCard.addView(image,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        if(encryptedPhotos.length>0)showPhoto(0); else mediaStatus.setText("No encrypted photos are present.");
        LinearLayout photoNav=new LinearLayout(this); photoNav.setOrientation(LinearLayout.HORIZONTAL);
        Button prev=secondaryButton("Previous"); prev.setOnClickListener(v->showPhoto(Math.max(0,imageIndex-1)));
        Button next=secondaryButton("Next"); next.setOnClickListener(v->showPhoto(Math.min(encryptedPhotos.length-1,imageIndex+1)));
        photoNav.addView(prev,weighted()); photoNav.addView(next,weighted()); photoCard.addView(photoNav,top(12));
        Button sharePhoto=actionButton("Share current photo with timestamp and location"); sharePhoto.setOnClickListener(v->shareCurrentPhoto()); photoCard.addView(sharePhoto,top(12));

        LinearLayout videoCard=section("Captured 5-second video"); root.addView(videoCard,margin());
        File videoEncrypted=new File(eventDir,"video_05s.ogv");
        if(videoEncrypted.isFile()){
            TextView videoInfo=body("Playback opens in OwnerGuard’s Samsung-compatible MediaPlayer surface. External playback remains available as fallback.");
            videoInfo.setBackground(card("#0D2638")); videoInfo.setPadding(dp(12),dp(12),dp(12),dp(12)); videoCard.addView(videoInfo);
            Button play=actionButton("Play 5-second video"); play.setOnClickListener(v->playSecureVideo(videoEncrypted)); videoCard.addView(play,top(12));
            Button external=secondaryButton("Open in another video player"); external.setOnClickListener(v->openVideoExternally(videoEncrypted)); videoCard.addView(external,top(10));
            Button shareVideo=actionButton("Share video with timestamp and location card"); shareVideo.setOnClickListener(v->shareVideo(videoEncrypted)); videoCard.addView(shareVideo,top(10));
        } else videoCard.addView(body("No encrypted video is present in this incident."));

        Button shareAll=actionButton("Share all evidence"); shareAll.setOnClickListener(v->shareAll(videoEncrypted)); root.addView(shareAll,margin());
        root.addView(label("Temporary decrypted exports are created only after vault authentication and remain outside Gallery."),margin());
        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button back=secondaryButton("Back"); back.setOnClickListener(v->finish());
        Button delete=dangerButton("Delete incident"); delete.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("Delete incident?").setMessage("This permanently removes its encrypted evidence.").setPositiveButton("Delete",(d,w)->{deleteRecursively(eventDir);finish();}).setNegativeButton("Cancel",null).show());
        actions.addView(back,weighted()); actions.addView(delete,weighted()); root.addView(actions,margin());
        setContentView(scroll);
    }

    private JSONObject readMetadataObject(){
        try{return new JSONObject(new String(VaultCrypto.decryptBytes(new File(eventDir,"metadata.ogv")),StandardCharsets.UTF_8));}
        catch(Exception e){JSONObject o=new JSONObject();try{o.put("metadataError",VaultCrypto.explainFailure(new File(eventDir,"metadata.ogv"),e));}catch(Exception ignored){}return o;}
    }
    private String metadataText(){
        if(metadata.has("metadataError"))return "Metadata unavailable: "+metadata.optString("metadataError");
        String loc=EvidenceShare.locationLine(metadata); String link=EvidenceShare.mapsLink(metadata);
        return "Timestamp: "+EvidenceShare.timestamp(metadata)+"\nReason: "+metadata.optString("reason").replace('_',' ')+"\nFailed attempts: "+metadata.optInt("failedCredentialAttempts")+"\nPhotos: "+metadata.optInt("photosCaptured")+"\nVideo saved: "+metadata.optBoolean("videoSaved",new File(eventDir,"video_05s.ogv").isFile())+"\n"+loc+(link.isEmpty()?"":"\nMap: "+link)+(metadata.optString("error").isEmpty()?"":"\nCapture note: "+metadata.optString("error"));
    }

    private void showPhoto(int index){
        if(encryptedPhotos.length==0||index<0||index>=encryptedPhotos.length)return;
        try{imageIndex=index;File out=temp("photo_"+index+".jpg");VaultCrypto.decryptFile(encryptedPhotos[index],out);Bitmap b=BitmapFactory.decodeFile(out.getAbsolutePath());if(b==null)throw new IllegalStateException("Invalid JPEG");b=EvidenceShare.rotateIfNeeded(b,out);image.setImageBitmap(b);mediaStatus.setText("Photo "+(index+1)+" of "+encryptedPhotos.length+" ready.");}
        catch(Exception e){image.setImageDrawable(null);failure("photo",encryptedPhotos[index],e);}
    }

    private void playSecureVideo(File encrypted){
        try{
            File f=exportVideo(encrypted);
            Intent i=new Intent(this,VideoPlaybackActivity.class).putExtra("video_path",f.getAbsolutePath());
            startActivity(i);
            mediaStatus.setText("Secure player opened.");
        }catch(Exception e){failure("video",encrypted,e);}
    }
    private void validateVideo(File f)throws Exception{
        if(!f.isFile()||f.length()<2048)throw new IllegalStateException("Decrypted video is empty");
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try{r.setDataSource(f.getAbsolutePath());String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(d==null||Long.parseLong(d)<=0)throw new IllegalStateException("MP4 has no playable duration");}
        finally{r.release();}
    }
    private void openVideoExternally(File encrypted){
        try{File f=exportVideo(encrypted);Uri u=SecureShareProvider.uriForFile(this,f);Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(u,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Open OwnerGuard video"));}
        catch(Exception e){Toast.makeText(this,"Cannot open video: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private void shareCurrentPhoto(){
        if(encryptedPhotos.length==0)return;
        try{File raw=temp("share_photo.jpg");VaultCrypto.decryptFile(encryptedPhotos[imageIndex],raw);Bitmap b=BitmapFactory.decodeFile(raw.getAbsolutePath());if(b==null)throw new IllegalStateException("Invalid photo");b=EvidenceShare.rotateIfNeeded(b,raw);File stamped=EvidenceShare.stampedPhoto(this,b,metadata,imageIndex);b.recycle();shareSingle(stamped,"image/jpeg",EvidenceShare.shareText(metadata),"Share OwnerGuard photo");}
        catch(Exception e){Toast.makeText(this,"Could not share photo: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private File exportVideo(File encrypted)throws Exception{File out=new File(EvidenceShare.exportsDir(this),"OwnerGuard_"+EvidenceShare.safeTimestamp(metadata)+"_video_5s.mp4");VaultCrypto.decryptFile(encrypted,out);validateVideo(out);return out;}
    private void shareVideo(File encrypted){
        try{ArrayList<Uri> uris=new ArrayList<>();uris.add(SecureShareProvider.uriForFile(this,exportVideo(encrypted)));uris.add(SecureShareProvider.uriForFile(this,EvidenceShare.evidenceCard(this,metadata)));shareMultiple(uris,EvidenceShare.shareText(metadata),"Share OwnerGuard video evidence");}
        catch(Exception e){Toast.makeText(this,"Could not share video: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void shareAll(File videoEncrypted){
        try{ArrayList<Uri> uris=new ArrayList<>();for(int i=0;i<encryptedPhotos.length;i++){File raw=temp("all_"+i+".jpg");VaultCrypto.decryptFile(encryptedPhotos[i],raw);Bitmap b=BitmapFactory.decodeFile(raw.getAbsolutePath());if(b==null)continue;b=EvidenceShare.rotateIfNeeded(b,raw);File stamped=EvidenceShare.stampedPhoto(this,b,metadata,i);b.recycle();uris.add(SecureShareProvider.uriForFile(this,stamped));}if(videoEncrypted.isFile())uris.add(SecureShareProvider.uriForFile(this,exportVideo(videoEncrypted)));uris.add(SecureShareProvider.uriForFile(this,EvidenceShare.evidenceCard(this,metadata)));shareMultiple(uris,EvidenceShare.shareText(metadata),"Share all OwnerGuard evidence");}
        catch(Exception e){Toast.makeText(this,"Could not share evidence: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void shareSingle(File f,String type,String text,String title)throws Exception{Uri u=SecureShareProvider.uriForFile(this,f);Intent i=new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM,u).putExtra(Intent.EXTRA_TEXT,text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,title));}
    private void shareMultiple(ArrayList<Uri> uris,String text,String title){Intent i=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).putExtra(Intent.EXTRA_TEXT,text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,title));}

    private File temp(String name)throws Exception{File d=new File(getCacheDir(),"vault_view");if(!d.exists()&&!d.mkdirs())throw new IllegalStateException("Cannot create secure cache");File f=new File(d,eventDir.getName()+"_"+name);temporary.add(f);return f;}
    private void failure(String type,File encrypted,Exception e){String m="Unable to open "+type+": "+VaultCrypto.explainFailure(encrypted,e);mediaStatus.setText(m);Toast.makeText(this,m,Toast.LENGTH_LONG).show();}
    private LinearLayout section(String title){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setBackground(card(SURFACE));b.setPadding(dp(16),dp(16),dp(16),dp(16));b.addView(sectionTitle(title));return b;}
    private TextView heading(String s){TextView v=body(s);v.setTextSize(26);return v;} private TextView sectionTitle(String s){TextView v=body(s);v.setTextSize(18);v.setPadding(0,0,0,dp(12));return v;} private TextView label(String s){TextView v=body(s);v.setTextColor(Color.parseColor(MUTED));v.setTextSize(13);return v;} private TextView body(String s){TextView v=new TextView(this);v.setText(s);v.setTextColor(Color.parseColor(TEXT));v.setTextSize(14);return v;}
    private Button actionButton(String s){return button(s,PRIMARY);} private Button secondaryButton(String s){return button(s,SURFACE_ALT);} private Button dangerButton(String s){return button(s,"#B91C1C");} private Button button(String s,String c){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(card(c));b.setMinHeight(dp(52));return b;}
    private GradientDrawable card(String c){GradientDrawable d=new GradientDrawable();d.setColor(Color.parseColor(c));d.setCornerRadius(dp(16));return d;} private LinearLayout.LayoutParams margin(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,0);return p;} private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(n),0,0);return p;} private LinearLayout.LayoutParams weighted(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(4),0,dp(4),0);return p;} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static void deleteRecursively(File f){File[]c=f.listFiles();if(c!=null)for(File x:c)deleteRecursively(x);f.delete();}
    @Override protected void onDestroy(){for(File f:temporary)f.delete();super.onDestroy();}
    @Override protected void onResume(){
        super.onResume();
        if(!AuthSession.isUnlocked()){routeToUnlock(); return;}
    }

}
