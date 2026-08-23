package com.fantest.ownerguard;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/** End-to-end dual-envelope cloud encryption. User and Administrator keys never encrypt media directly. */
final class CloudCrypto {
    private static final byte[] MAGIC2=new byte[]{'O','G','C','2'};
    private static final int VERSION2=2;
    private CloudCrypto(){}
    static final class Envelope {
        final int cryptoVersion; final String userEnvelope,adminEnvelope,adminKeyId;
        Envelope(int v,String u,String a,String id){cryptoVersion=v;userEnvelope=u;adminEnvelope=a;adminKeyId=id;}
    }
    static byte[] getOrCreateKey(Context context)throws Exception{
        File keyFile=keyFile(context);if(keyFile.isFile()){byte[]key=VaultCrypto.decryptBytes(keyFile);if(key.length!=32)throw new IllegalStateException("Cloud recovery key is invalid");return key;}
        byte[]key=new byte[32];new SecureRandom().nextBytes(key);VaultCrypto.encryptBytes(key,keyFile);return key;
    }
    static String recoveryKey(Context context)throws Exception{return Base64.encodeToString(getOrCreateKey(context),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    static void importRecoveryKey(Context context,String value)throws Exception{if(value==null)throw new IllegalArgumentException("Recovery key is required");String clean=value.trim().replace(" ","");byte[]key=Base64.decode(clean,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);if(key.length!=32)throw new IllegalArgumentException("Recovery key must decode to 32 bytes");VaultCrypto.encryptBytes(key,keyFile(context));}
    static Envelope encryptFile(Context context,File clear,File encrypted,CloudEscrow.PublicKeyInfo escrow)throws Exception{try(InputStream in=new BufferedInputStream(new FileInputStream(clear))){return encrypt(context,in,encrypted,escrow);}}
    static Envelope encryptBytes(Context context,byte[]clear,File encrypted,CloudEscrow.PublicKeyInfo escrow)throws Exception{try(InputStream in=new ByteArrayInputStream(clear)){return encrypt(context,in,encrypted,escrow);}}
    private static Envelope encrypt(Context context,InputStream in,File encrypted,CloudEscrow.PublicKeyInfo escrow)throws Exception{
        if(escrow==null||escrow.publicKey==null)throw new IllegalStateException("Administrator escrow public key is unavailable");File parent=encrypted.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs()&&!parent.isDirectory())throw new IllegalStateException("Cannot create cloud cache");
        SecureRandom random=new SecureRandom();byte[]contentKey=new byte[32];random.nextBytes(contentKey);byte[]iv=new byte[12];random.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(contentKey,"AES"),new GCMParameterSpec(128,iv));
        try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(encrypted))){out.write(MAGIC2);out.write(VERSION2);out.write(iv.length);out.write(iv);byte[]buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1){byte[]c=cipher.update(buf,0,n);if(c!=null)out.write(c);}byte[]last=cipher.doFinal();if(last!=null)out.write(last);}
        byte[]wrapIv=new byte[12];random.nextBytes(wrapIv);Cipher user=Cipher.getInstance("AES/GCM/NoPadding");user.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(getOrCreateKey(context),"AES"),new GCMParameterSpec(128,wrapIv));byte[]userCipher=user.doFinal(contentKey);byte[]userEnvelope=new byte[wrapIv.length+userCipher.length];System.arraycopy(wrapIv,0,userEnvelope,0,wrapIv.length);System.arraycopy(userCipher,0,userEnvelope,wrapIv.length,userCipher.length);
        Cipher admin=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");OAEPParameterSpec oaep=new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT);admin.init(Cipher.ENCRYPT_MODE,escrow.publicKey,oaep);byte[]adminEnvelope=admin.doFinal(contentKey);
        java.util.Arrays.fill(contentKey,(byte)0);
        return new Envelope(2,b64(userEnvelope),b64(adminEnvelope),escrow.keyId);
    }
    static String sha256(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(file))){byte[]b=new byte[64*1024];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(java.util.Locale.US,"%02x",x));return s.toString();}
    private static String b64(byte[]v){return Base64.encodeToString(v,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private static File keyFile(Context context){return new File(context.getFilesDir(),"cloud/cloud_recovery_key.ogv");}
}
