package com.fantest.ownerguard;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class VaultCrypto {
    private static final String ALIAS = "OwnerGuardVaultKeyV1";
    private static final int LEGACY_IV_SIZE = 12;
    private static final int GCM_TAG_BYTES = 16;
    private static final byte[] MAGIC = new byte[] { 'O', 'G', 'V', '2' };
    private static final int FORMAT_VERSION = 2;

    private VaultCrypto() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (!(entry instanceof KeyStore.SecretKeyEntry)) {
            throw new IllegalStateException("Vault key is unavailable");
        }
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    static void encryptFile(File input, File output) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(input))) {
            encrypt(in, output);
        }
    }

    static void encryptBytes(byte[] data, File output) throws Exception {
        try (InputStream in = new ByteArrayInputStream(data)) {
            encrypt(in, output);
        }
    }

    private static void encrypt(InputStream in, File output) throws Exception {
        ensureParent(output);
        File temp = siblingTemp(output, ".encrypting");
        if (temp.exists() && !temp.delete()) throw new IllegalStateException("Cannot clear temporary vault file");
        boolean complete = false;
        try (FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Android Keystore must generate the encryption IV. Supplying our own IV is rejected
            // when randomized encryption is required (the default and safest configuration).
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length < 12 || iv.length > 32) {
                throw new IllegalStateException("Android Keystore returned an invalid IV");
            }

            out.write(MAGIC);
            out.write(FORMAT_VERSION);
            out.write(iv.length);
            out.write(iv);
            transform(in, out, cipher);
            out.flush();
            fileOut.getFD().sync();
            complete = true;
        } finally {
            if (!complete) temp.delete();
        }
        replaceAtomically(temp, output);
    }

    static byte[] decryptBytes(File input) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            decrypt(input, out);
            return out.toByteArray();
        }
    }

    static void decryptFile(File input, File output) throws Exception {
        ensureParent(output);
        File temp = siblingTemp(output, ".decrypting");
        if (temp.exists() && !temp.delete()) throw new IllegalStateException("Cannot clear temporary media file");
        boolean complete = false;
        try (FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            decrypt(input, out);
            out.flush();
            fileOut.getFD().sync();
            complete = true;
        } finally {
            if (!complete) temp.delete();
        }
        replaceAtomically(temp, output);
    }

    private static void decrypt(File input, OutputStream out) throws Exception {
        if (input == null || !input.isFile()) throw new IllegalStateException("Encrypted vault file is missing");
        long length = input.length();
        if (length <= LEGACY_IV_SIZE) {
            throw new IllegalStateException("Incomplete v1.0.2 capture: no encrypted media payload was written");
        }

        try (BufferedInputStream raw = new BufferedInputStream(new FileInputStream(input))) {
            raw.mark(64);
            byte[] prefix = new byte[MAGIC.length];
            readFully(raw, prefix);

            byte[] iv;
            if (Arrays.equals(prefix, MAGIC)) {
                int version = raw.read();
                int ivLength = raw.read();
                if (version != FORMAT_VERSION) throw new IllegalStateException("Unsupported vault format version " + version);
                if (ivLength < 12 || ivLength > 32) throw new IllegalStateException("Invalid vault IV length");
                if (length < MAGIC.length + 2L + ivLength + GCM_TAG_BYTES) {
                    throw new IllegalStateException("Incomplete encrypted media payload");
                }
                iv = new byte[ivLength];
                readFully(raw, iv);
            } else {
                // Backward compatibility for valid v1.0.2 files: [12-byte IV][ciphertext+tag].
                raw.reset();
                if (length < LEGACY_IV_SIZE + GCM_TAG_BYTES) {
                    throw new IllegalStateException("Incomplete v1.0.2 encrypted media payload");
                }
                iv = new byte[LEGACY_IV_SIZE];
                readFully(raw, iv);
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            try {
                transform(raw, out, cipher);
            } catch (AEADBadTagException e) {
                throw new IllegalStateException("Vault authentication failed; the file or encryption key does not match", e);
            }
        }
    }

    private static void transform(InputStream in, OutputStream out, Cipher cipher) throws Exception {
        byte[] input = new byte[32 * 1024];
        int count;
        while ((count = in.read(input)) != -1) {
            if (count == 0) continue;
            byte[] transformed = cipher.update(input, 0, count);
            if (transformed != null && transformed.length > 0) out.write(transformed);
        }
        byte[] finalBytes = cipher.doFinal();
        if (finalBytes != null && finalBytes.length > 0) out.write(finalBytes);
    }

    static String explainFailure(File input, Throwable error) {
        if (input == null || !input.exists()) return "Encrypted file is missing.";
        long bytes = input.length();
        if (bytes <= LEGACY_IV_SIZE) {
            return "This capture is incomplete from v1.0.2 (" + bytes + " bytes) and cannot be recovered. Delete this incident and create a new test capture after updating.";
        }
        String message = error == null ? "Unknown vault error" : error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message + " (encrypted file: " + bytes + " bytes)";
    }

    static boolean selfTest(Context context) {
        File encrypted = new File(context.getCacheDir(), "ownerguard_crypto_self_test.ogv");
        File clear = new File(context.getCacheDir(), "ownerguard_crypto_self_test.txt");
        byte[] expected = "OwnerGuard-v1.0.4-vault-self-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            encryptBytes(expected, encrypted);
            decryptFile(encrypted, clear);
            byte[] actual;
            try (FileInputStream in = new FileInputStream(clear); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[128]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                actual = out.toByteArray();
            }
            return Arrays.equals(expected, actual);
        } catch (Exception e) {
            return false;
        } finally {
            encrypted.delete();
            clear.delete();
            siblingTemp(encrypted, ".encrypting").delete();
            siblingTemp(clear, ".decrypting").delete();
        }
    }

    private static void readFully(InputStream in, byte[] target) throws Exception {
        int offset = 0;
        while (offset < target.length) {
            int n = in.read(target, offset, target.length - offset);
            if (n < 0) throw new IllegalStateException("Unexpected end of encrypted vault file");
            offset += n;
        }
    }

    private static void ensureParent(File output) {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Cannot create vault directory");
        }
    }

    private static File siblingTemp(File output, String suffix) {
        File parent = output.getParentFile();
        return new File(parent == null ? new File(".") : parent, output.getName() + suffix);
    }

    private static void replaceAtomically(File temp, File output) throws Exception {
        if (output.exists() && !output.delete()) {
            temp.delete();
            throw new IllegalStateException("Cannot replace existing vault file");
        }
        if (!temp.renameTo(output)) {
            temp.delete();
            throw new IllegalStateException("Cannot finalize vault file");
        }
    }
}
