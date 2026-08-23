package com.fantest.ownerguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-side cloud account signup/login. The signed-in token also owns new encrypted vault uploads. */
final class CloudAccountManager {
    private static final String PREF = "owner_guard_cloud_account";
    private static final String KEY_TOKEN = "api_token";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_DISPLAY = "display_name";
    private static final String KEY_ROLE = "role";
    private static final String MIN_SERVER_VERSION = "1.3.2";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CloudAccountManager() {}

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    static boolean loggedIn(Context c) { return !prefs(c).getString(KEY_TOKEN, "").trim().isEmpty(); }
    static String token(Context c) { return prefs(c).getString(KEY_TOKEN, "").trim(); }
    static String username(Context c) { return prefs(c).getString(KEY_USERNAME, ""); }
    static String displayName(Context c) { return prefs(c).getString(KEY_DISPLAY, ""); }
    static String role(Context c) { return prefs(c).getString(KEY_ROLE, "viewer"); }
    static String status(Context c) {
        if (!loggedIn(c)) return "Not signed in";
        String display = displayName(c).trim();
        return (display.isEmpty() ? username(c) : display) + " • " + role(c);
    }

    static void signUp(Activity activity, String baseUrl, String username, String display, String password, Runnable refresh) {
        if (!validBase(baseUrl)) { show(activity,"Sign-up failed","Use a valid HTTPS OwnerGuard Cloud URL."); return; }
        if (!username.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,31}")) { show(activity,"Sign-up failed","Username must contain 3–32 letters, numbers, dots, dashes, or underscores."); return; }
        if (display.trim().isEmpty()) { show(activity,"Sign-up failed","Enter your display name."); return; }
        if (password.length() < 10) { show(activity,"Sign-up failed","Password must contain at least 10 characters."); return; }
        Toast.makeText(activity,"Checking cloud server…",Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            String error = null; String message = null;
            try {
                verifyServer(baseUrl);
                JSONObject r = postWithFallback(baseUrl, "mobile_signup", "signup", form(
                        "username",username.trim(),"display_name",display.trim(),"password",password));
                if (!r.optBoolean("ok",false)) throw new IllegalStateException(r.optString("error","Account could not be created"));
                String token=r.optString("token","").trim();
                JSONObject user=r.optJSONObject("user");
                if(token.isEmpty()||user==null)throw new IllegalStateException("The cloud server created the account but did not return an active login session. Update OwnerGuard Cloud to version 1.3.2 or newer.");
                prefs(activity).edit().putString(KEY_TOKEN,token).putString(KEY_USERNAME,user.optString("username"))
                        .putString(KEY_DISPLAY,user.optString("display_name")).putString(KEY_ROLE,user.optString("role","viewer")).apply();
                message = r.optString("message","Account created, activated, and signed in.");
                CloudBackupManager.prefs(activity).edit().putString(CloudBackupManager.KEY_URL, cleanBase(baseUrl)).apply();
            } catch (Exception e) { error = safe(e); }
            final String fError=error, fMessage=message;
            MAIN.post(() -> { if(fError!=null)show(activity,"Sign-up failed",fError);else{show(activity,"Account ready",fMessage);if(refresh!=null)refresh.run();} });
        });
    }

    static void login(Activity activity, String baseUrl, String username, String password, Runnable refresh) {
        if (!validBase(baseUrl)) { show(activity,"Login failed","Use a valid HTTPS OwnerGuard Cloud URL."); return; }
        Toast.makeText(activity,"Checking cloud server…",Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            String error=null; JSONObject user=null; String token=null;
            try {
                verifyServer(baseUrl);
                JSONObject r=postWithFallback(baseUrl,"mobile_login","login",form("username",username.trim(),"password",password));
                if(!r.optBoolean("ok",false))throw new IllegalStateException(r.optString("error","Login failed"));
                token=r.getString("token"); user=r.getJSONObject("user");
                prefs(activity).edit().putString(KEY_TOKEN,token).putString(KEY_USERNAME,user.optString("username"))
                        .putString(KEY_DISPLAY,user.optString("display_name")).putString(KEY_ROLE,user.optString("role","viewer")).apply();
                CloudBackupManager.prefs(activity).edit().putString(CloudBackupManager.KEY_URL,cleanBase(baseUrl)).apply();
            }catch(Exception e){error=safe(e);}
            final String fError=error;
            MAIN.post(()->{if(fError!=null)show(activity,"Login failed",fError);else{Toast.makeText(activity,"Cloud account signed in",Toast.LENGTH_LONG).show();if(refresh!=null)refresh.run();}});
        });
    }

    static void refresh(Activity activity, Runnable refreshUi) {
        String token=prefs(activity).getString(KEY_TOKEN,"").trim(); if(token.isEmpty())return;
        String base=CloudBackupManager.baseUrl(activity);
        EXECUTOR.execute(()->{
            try{
                JSONObject r=getWithFallback(base,"mobile_me","me",token);
                if(!r.optBoolean("ok",false))throw new IllegalStateException(r.optString("error","Account session could not be refreshed"));
                JSONObject u=r.getJSONObject("user");prefs(activity).edit().putString(KEY_USERNAME,u.optString("username"))
                        .putString(KEY_DISPLAY,u.optString("display_name")).putString(KEY_ROLE,u.optString("role","viewer")).apply();
            }catch(Exception e){logout(activity);}
            MAIN.post(()->{if(refreshUi!=null)refreshUi.run();});
        });
    }

    static void logout(Context c) { prefs(c).edit().clear().apply(); }

    static void openVault(Activity activity) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CloudBackupManager.baseUrl(activity)))); }
        catch (Exception e) { show(activity,"Cannot open cloud vault",safe(e)); }
    }

    private static void verifyServer(String base) throws Exception {
        JSONObject ping;
        try {
            ping=request("GET",cleanBase(base)+"/api/ping.php",null,null);
        } catch (ApiException first) {
            if (first.status==404 || first.html) ping=request("GET",cleanBase(base)+"/mobile_api.php?action=ping",null,null);
            else throw first;
        }
        if (!ping.optBoolean("ok",false) || !"OwnerGuard Cloud".equals(ping.optString("service"))) {
            throw new IllegalStateException("This URL is not an OwnerGuard Cloud API.");
        }
        String version=ping.optString("version","0.0.0");
        if (compareVersion(version,MIN_SERVER_VERSION)<0) {
            throw new IllegalStateException("OwnerGuard Cloud "+MIN_SERVER_VERSION+" or newer is required for app sign-up. The server reports version "+version+".");
        }
    }

    private static JSONObject postWithFallback(String base,String endpoint,String action,String body)throws Exception{
        try { return request("POST",cleanBase(base)+"/api/"+endpoint+".php",body,null); }
        catch(ApiException e){
            if(e.status==404) return request("POST",cleanBase(base)+"/mobile_api.php?action="+action,body,null);
            throw e;
        }
    }

    private static JSONObject getWithFallback(String base,String endpoint,String action,String token)throws Exception{
        try { return request("GET",cleanBase(base)+"/api/"+endpoint+".php",null,token); }
        catch(ApiException e){
            if(e.status==404) return request("GET",cleanBase(base)+"/mobile_api.php?action="+action,null,token);
            throw e;
        }
    }

    private static JSONObject request(String method,String url,String body,String token)throws Exception{
        URL u=new URL(url);
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setRequestMethod(method);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","OwnerGuard-Android/1.0.26");
        c.setRequestProperty("X-OwnerGuard-Client","android");
        c.setUseCaches(false);
        if(token!=null&&!token.trim().isEmpty())c.setRequestProperty("Authorization","Bearer "+token.trim());
        if(body!=null){
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
            byte[]data=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);
            try(OutputStream out=c.getOutputStream()){out.write(data);}
        }
        int code=c.getResponseCode();
        String type=c.getContentType();
        String location=c.getHeaderField("Location");
        String raw=read(code>=400?c.getErrorStream():c.getInputStream()).trim();
        if(code>=300&&code<400){
            throw new ApiException(code,false,"The cloud API redirected to a web page"+(location==null?"":" at "+location)+". Cloudflare Access must bypass the OwnerGuard mobile API paths.");
        }
        boolean html=looksHtml(raw,type);
        if(html){
            throw new ApiException(code,true,htmlMessage(code,url));
        }
        if(raw.isEmpty())throw new ApiException(code,false,"The cloud API returned an empty response (HTTP "+code+").");
        JSONObject r;
        try { r=new JSONObject(raw); }
        catch(JSONException e){ throw new ApiException(code,false,"The cloud API returned invalid JSON (HTTP "+code+"). Update the server to OwnerGuard Cloud 1.3.2."); }
        if(code<200||code>=300)throw new ApiException(code,false,r.optString("error","Server returned HTTP "+code));
        return r;
    }

    private static boolean looksHtml(String raw,String contentType){
        String t=raw==null?"":raw.trim().toLowerCase(Locale.US);
        String ct=contentType==null?"":contentType.toLowerCase(Locale.US);
        return ct.contains("text/html")||t.startsWith("<!doctype")||t.startsWith("<html")||t.startsWith("<head")||t.startsWith("<body");
    }

    private static String htmlMessage(int code,String url){
        return "OwnerGuard Cloud returned a web page instead of JSON (HTTP "+code+"). " +
                "This means the Cloud 1.3.2 API is not deployed at the entered URL, the URL is incorrect, or Cloudflare Access is intercepting the app request. " +
                "The administrator should open "+cleanBaseFromRequest(url)+"/api/ping.php and confirm that it displays JSON, then bypass Cloudflare Access for the mobile API paths. The vault website itself can remain protected.";
    }

    private static String cleanBaseFromRequest(String url){
        int p=url.indexOf("/api/"); if(p>0)return url.substring(0,p);
        p=url.indexOf("/mobile_api.php"); if(p>0)return url.substring(0,p);
        return url;
    }

    private static int compareVersion(String a,String b){
        String[]aa=a.split("\\.");String[]bb=b.split("\\.");int n=Math.max(aa.length,bb.length);
        for(int i=0;i<n;i++){int x=part(aa,i),y=part(bb,i);if(x!=y)return x<y?-1:1;}return 0;
    }
    private static int part(String[]v,int i){if(i>=v.length)return 0;String s=v[i].replaceAll("[^0-9].*$","");try{return s.isEmpty()?0:Integer.parseInt(s);}catch(Exception e){return 0;}}
    private static String form(String...values)throws Exception{StringBuilder b=new StringBuilder();for(int i=0;i<values.length;i+=2){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(values[i],"UTF-8")).append('=').append(URLEncoder.encode(values[i+1],"UTF-8"));}return b.toString();}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[4096];int n,total=0;while((n=x.read(b))!=-1){total+=n;if(total>256*1024)throw new IllegalStateException("Server response is too large");out.write(b,0,n);}return out.toString("UTF-8");}}
    private static boolean validBase(String value){try{URL u=new URL(cleanBase(value));return "https".equalsIgnoreCase(u.getProtocol())&&!u.getHost().trim().isEmpty();}catch(Exception e){return false;}}
    private static String cleanBase(String value){String s=value==null?"":value.trim();while(s.endsWith("/"))s=s.substring(0,s.length()-1);return s;}
    private static String safe(Exception e){String s=e.getMessage();return s==null||s.trim().isEmpty()?"Cloud request failed":s.trim();}
    private static void show(Activity a,String title,String message){if(a==null||a.isFinishing())return;new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Close",null).show();}

    private static final class ApiException extends Exception {
        final int status; final boolean html;
        ApiException(int status,boolean html,String message){super(message);this.status=status;this.html=html;}
    }
}
