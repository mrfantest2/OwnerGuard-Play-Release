package com.fantest.ownerguard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider for authenticated temporary exports and verified APK updates. */
public class SecureShareProvider extends ContentProvider {
    static final String AUTHORITY = "com.fantest.ownerguard.share";

    static Uri uriForFile(Context context, File file) throws Exception { return uriFor(context,file,"exports"); }
    static Uri uriForUpdate(Context context, File file) throws Exception { return uriFor(context,file,"updates"); }

    private static Uri uriFor(Context context, File file, String area) throws Exception {
        File root = new File(context.getCacheDir(), area).getCanonicalFile();
        File target = file.getCanonicalFile();
        if (!target.getPath().startsWith(root.getPath() + File.separator)) throw new SecurityException("File is outside the secure cache");
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(area).appendPath(target.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) throw new FileNotFoundException("Invalid secure URI");
        java.util.List<String> parts = uri.getPathSegments();
        String area, name;
        if (parts.size()==1) { area="exports"; name=parts.get(0); }
        else if (parts.size()==2) { area=parts.get(0); name=parts.get(1); }
        else throw new FileNotFoundException("Invalid secure path");
        if (!("exports".equals(area)||"updates".equals(area))) throw new FileNotFoundException("Invalid secure area");
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) throw new FileNotFoundException("Invalid filename");
        try {
            File root = new File(getContext().getCacheDir(), area).getCanonicalFile();
            File file = new File(root,name).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath()+File.separator) || !file.isFile()) throw new FileNotFoundException("File no longer exists");
            return file;
        } catch (java.io.IOException e) { throw new FileNotFoundException("Cannot resolve secure file"); }
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        try { String n=resolve(uri).getName().toLowerCase(); if(n.endsWith(".apk"))return "application/vnd.android.package-archive";if(n.endsWith(".mp4"))return "video/mp4";if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return "image/jpeg";if(n.endsWith(".txt"))return "text/plain"; } catch(Exception ignored){}
        return "application/octet-stream";
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(mode==null||!mode.equals("r"))throw new FileNotFoundException("Read-only provider");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[]projection,String selection,String[]selectionArgs,String sortOrder){try{File f=resolve(uri);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(Exception e){return null;}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[]selectionArgs){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[]selectionArgs){throw new UnsupportedOperationException();}
}
