package com.fantest.ownerguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;

final class CloudEscrow {
    private static final String PREF="owner_guard_admin_escrow";
    private static final String KEY_ID="key_id", KEY_N="n", KEY_E="e", KEY_AT="fetched_at";
    private CloudEscrow(){}
    static final class PublicKeyInfo {
        final String keyId; final PublicKey publicKey;
        PublicKeyInfo(String id,PublicKey key){keyId=id;publicKey=key;}
    }
    static PublicKeyInfo require(Context c)throws Exception{
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String id=p.getString(KEY_ID,""),n=p.getString(KEY_N,""),e=p.getString(KEY_E,"");
        long at=p.getLong(KEY_AT,0L);
        if(!id.isEmpty()&&!n.isEmpty()&&!e.isEmpty()&&System.currentTimeMillis()-at<24L*60L*60L*1000L)return decode(id,n,e);
        URL u=new URL(CloudBackupManager.baseUrl(c)+"/api/escrow_public.php");
        HttpURLConnection h=(HttpURLConnection)u.openConnection();h.setConnectTimeout(15000);h.setReadTimeout(25000);h.setRequestMethod("GET");h.setUseCaches(false);h.setRequestProperty("Accept","application/json");h.setRequestProperty("User-Agent","OwnerGuard-Android/1.0.26");
        String token=CloudAccountManager.token(c);if(!token.isEmpty())h.setRequestProperty("Authorization","Bearer "+token);
        int code=h.getResponseCode();String raw=read(code>=400?h.getErrorStream():h.getInputStream());if(code<200||code>=300)throw new IllegalStateException("Administrator escrow endpoint returned HTTP "+code);
        JSONObject r=new JSONObject(raw);if(!r.optBoolean("ok",false)||!r.optBoolean("enabled",false))throw new IllegalStateException("Administrator recovery escrow is not initialized on OwnerGuard Cloud");
        JSONObject jwk=r.getJSONObject("public_jwk");id=r.getString("key_id");n=jwk.getString("n");e=jwk.getString("e");PublicKeyInfo info=decode(id,n,e);
        p.edit().putString(KEY_ID,id).putString(KEY_N,n).putString(KEY_E,e).putLong(KEY_AT,System.currentTimeMillis()).apply();return info;
    }
    static void clear(Context c){c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().clear().apply();}
    private static PublicKeyInfo decode(String id,String n,String e)throws Exception{
        if(!id.matches("aek_[a-f0-9]{24}"))throw new IllegalStateException("Invalid Administrator escrow key ID");
        BigInteger modulus=new BigInteger(1,Base64.decode(n,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING));
        BigInteger exponent=new BigInteger(1,Base64.decode(e,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING));
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,exponent));return new PublicKeyInfo(id,key);
    }
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[4096];int k,total=0;while((k=x.read(b))!=-1){total+=k;if(total>256*1024)throw new IllegalStateException("Escrow response is too large");out.write(b,0,k);}return out.toString("UTF-8");}}
}
