/*
 * Copyright (c) 2003, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.crypto.provider;

import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.security.auth.DestroyFailedException;

/**
 * This class implements password-base encryption algorithm with
 * SHA1 digest and the following Ciphers (in CBC mode, where applicable):
 * - DESede cipher and
 * - RC2 Cipher with 40-bit or 128-bit effective key length and
 * - RC4 Cipher with 40-bit or 128-bit effective key length
 * as defined by PKCS #12 version 1.0 standard.
 *
 * @author Valerie Peng
 * @see javax.crypto.CipherSpi
 */
final class PKCS12PBECipherCore {
    private final int keySize; // in bytes
    private final String algo;
    private final String pbeAlgo;
    private byte[] salt = null;
    private int iCount = 0;

    private static final int DEFAULT_SALT_LENGTH = 20;
    private static final int DEFAULT_COUNT = 1024;

    static final int CIPHER_KEY = 1;
    static final int CIPHER_IV = 2;
    static final int MAC_KEY = 3;

    // Uses default hash algorithm (SHA-1)
    static byte[] derive(char[] chars, byte[] salt,
                         int ic, int n, int type) {
        return derive(chars, salt, ic, n, type, "SHA-1", 64);
    }

    // Uses supplied hash algorithm
    static byte[] derive(char[] chars, byte[] salt, int ic, int n, int type,
        String hashAlgo, int blockLength) {

        // Add in trailing NULL terminator.  Special case:
        // no terminator if password is "\0".
        int length = chars.length*2;
        if (length == 2 && chars[0] == 0) {
            chars = new char[0];
            length = 0;
        } else {
            length += 2;
        }

        byte[] passwd = new byte[length];
        for (int i = 0, j = 0; i < chars.length; i++, j+=2) {
            passwd[j] = (byte) ((chars[i] >>> 8) & 0xFF);
            passwd[j+1] = (byte) (chars[i] & 0xFF);
        }
        byte[] key = new byte[n];

        try {
            MessageDigest sha = MessageDigest.getInstance(hashAlgo);

            int v = blockLength;
            int u = sha.getDigestLength();
            int c = roundup(n, u) / u;
            byte[] D = new byte[v];
            int s = roundup(salt.length, v);
            int p = roundup(passwd.length, v);
            byte[] I = new byte[s + p];

            Arrays.fill(D, (byte)type);
            concat(salt, I, 0, s);
            concat(passwd, I, s, p);
            Arrays.fill(passwd, (byte) 0x00);

            byte[] Ai;
            byte[] B = new byte[v];

            int i = 0;
            for (; ; i++, n -= u) {
                sha.update(D);
                sha.update(I);
                Ai = sha.digest();
                for (int r = 1; r < ic; r++)
                    Ai = sha.digest(Ai);
                System.arraycopy(Ai, 0, key, u * i, Math.min(n, u));
                if (i + 1 == c) {
                    break;
                }
                concat(Ai, B, 0, v);
                addOne(v, B);   // add 1 into B

                for (int j = 0; j < I.length; j += v) {
                    addTwo(v, B, I, j); // add B into I from j
                }
            }
            Arrays.fill(I, (byte)0);
        } catch (Exception e) {
            throw new RuntimeException("internal error: " + e);
        }
        return key;
    }

    // Add 1 to b (as integer)
    private static void addOne(int len, byte[] b) {
        for (int i = len - 1; i >= 0; i--) {
            if ((b[i] & 0xff) != 255) {
                b[i]++;
                break;
            } else {
                b[i] = 0;
            }
        }
    }

    // Add src (as integer) to dst from offset (as integer)
    private static void addTwo(int len, byte[] src, byte[] dst, int offset) {
        int carry = 0;
        for (int i = len - 1; i >= 0; i--) {
            int sum = (src[i] & 0xff) + (dst[i + offset] & 0xff) + carry;
            carry = sum >> 8;
            dst[i + offset] = (byte)sum;
        }
    }

    private static int roundup(int x, int y) {
        return ((x + (y - 1)) / y) * y;
    }

    private static void concat(byte[] src, byte[] dst, int start, int len) {
        if (src.length == 0) {
            return;
        }
        int loop = len / src.length;
        int off, i;
        for (i = 0, off = 0; i < loop; i++, off += src.length)
            System.arraycopy(src, 0, dst, off + start, src.length);
        System.arraycopy(src, 0, dst, off + start, len - off);
    }

    PKCS12PBECipherCore(String symmCipherAlg, int keySizeInBits)
        throws NoSuchAlgorithmException {

        algo = symmCipherAlg;
        if (algo.equals("RC4")) {
            pbeAlgo = "PBEWithSHA1AndRC4_" + keySizeInBits;
        } else {
            if (algo.equals("DESede")) {
                pbeAlgo = "PBEWithSHA1AndDESede";
            } else if (algo.equals("RC2")) {
                pbeAlgo = "PBEWithSHA1AndRC2_" + keySizeInBits;
            } else {
                throw new NoSuchAlgorithmException("No Cipher implementation " +
                       "for PBEWithSHA1And" + algo);
            }
        }
        keySize = keySizeInBits >> 3;
    }

    AlgorithmParameters implGetParameters() {
        AlgorithmParameters params = null;
        if (salt == null) {
            // Cipher is not initialized with parameters;
            // follow the recommendation in PKCS12 v1.0
            // section B.4 to generate salt and iCount.
            salt = new byte[DEFAULT_SALT_LENGTH];
            SunJCE.getRandom().nextBytes(salt);
            iCount = DEFAULT_COUNT;
        }
        PBEParameterSpec pbeSpec = new PBEParameterSpec(salt, iCount);
        try {
            params = AlgorithmParameters.getInstance(pbeAlgo,
                SunJCE.getInstance());
            params.init(pbeSpec);
        } catch (NoSuchAlgorithmException nsae) {
            // should never happen
            throw new RuntimeException(
                "SunJCE provider is not configured properly");
        } catch (InvalidParameterSpecException ipse) {
            // should never happen
            throw new RuntimeException("PBEParameterSpec not supported");
        }
        return params;
    }

    void implInit(int opmode, Key key, AlgorithmParameterSpec params,
                  SecureRandom random, CipherSpi cipher)
        throws InvalidKeyException, InvalidAlgorithmParameterException {
        char[] passwdChars = null;
        salt = null;
        iCount = 0;
        if (key instanceof javax.crypto.interfaces.PBEKey) {
            javax.crypto.interfaces.PBEKey pbeKey =
                (javax.crypto.interfaces.PBEKey) key;
            passwdChars = pbeKey.getPassword();
            salt = pbeKey.getSalt(); // maybe null if unspecified
            iCount = pbeKey.getIterationCount(); // maybe 0 if unspecified
        } else if (key instanceof SecretKey) {
            byte[] passwdBytes;
            if (!(key.getAlgorithm().regionMatches(true, 0, "PBE", 0, 3)) ||
                    (passwdBytes = key.getEncoded()) == null) {
                throw new InvalidKeyException("Missing password");
            }
            passwdChars = new char[passwdBytes.length];
            for (int i=0; i<passwdChars.length; i++) {
                passwdChars[i] = (char) (passwdBytes[i] & 0x7f);
            }
            Arrays.fill(passwdBytes, (byte)0x00);
        } else {
            throw new InvalidKeyException("SecretKey of PBE type required");
        }

        try {
            if (((opmode == Cipher.DECRYPT_MODE) ||
                    (opmode == Cipher.UNWRAP_MODE)) &&
                    ((params == null) && ((salt == null) || (iCount == 0)))) {
                throw new InvalidAlgorithmParameterException
                        ("Parameters missing");
            }

            if (params == null) {
                // generate default for salt and iteration count if necessary
                if (salt == null) {
                    salt = new byte[DEFAULT_SALT_LENGTH];
                    if (random != null) {
                        random.nextBytes(salt);
                    } else {
                        SunJCE.getRandom().nextBytes(salt);
                    }
                }
                if (iCount == 0) iCount = DEFAULT_COUNT;
            } else if (!(params instanceof PBEParameterSpec)) {
                throw new InvalidAlgorithmParameterException
                        ("PBEParameterSpec type required");
            } else {
                PBEParameterSpec pbeParams = (PBEParameterSpec) params;
                // make sure the parameter values are consistent
                if (salt != null) {
                    if (!Arrays.equals(salt, pbeParams.getSalt())) {
                        throw new InvalidAlgorithmParameterException
                                ("Inconsistent value of salt between key and params");
                    }
                } else {
                    salt = pbeParams.getSalt();
                }
                if (iCount != 0) {
                    if (iCount != pbeParams.getIterationCount()) {
                        throw new InvalidAlgorithmParameterException
                                ("Different iteration count between key and params");
                    }
                } else {
                    iCount = pbeParams.getIterationCount();
                }
            }
            // salt is recommended to be ideally as long as the output
            // of the hash function. However, it may be too strict to
            // force this; so instead, we'll just require the minimum
            // salt length to be 8-byte which is what PKCS#5 recommends
            // and openssl does.
            if (salt.length < 8) {
                throw new InvalidAlgorithmParameterException
                        ("Salt must be at least 8 bytes long");
            }
            if (iCount <= 0) {
                throw new InvalidAlgorithmParameterException
                        ("IterationCount must be a positive number");
            }
            byte[] derivedKey = derive(passwdChars, salt, iCount,
                    keySize, CIPHER_KEY);
            SecretKey cipherKey = new SecretKeySpec(derivedKey, algo);
            Arrays.fill(derivedKey, (byte)0);

            try {
                if (cipher instanceof ARCFOURCipher rc4) {
                    rc4.engineInit(opmode, cipherKey, random);
                } else {
                    byte[] derivedIv = derive(passwdChars, salt, iCount, 8,
                            CIPHER_IV);
                    IvParameterSpec ivSpec = new IvParameterSpec(derivedIv, 0,
                            8);

                    // initialize the underlying cipher
                    if (cipher instanceof RC2Cipher rc2) {
                        rc2.engineInit(opmode, cipherKey, ivSpec, random);
                    } else if (cipher instanceof DESedeCipher tripleDes) {
                        tripleDes.engineInit(opmode, cipherKey, ivSpec, random);
                    } else {
                        throw new ProviderException("Unsupported cipher impl: "
                                + cipher);
                    }
                }
            } finally {
                try {
                    cipherKey.destroy();
                } catch (DestroyFailedException e) {
                    // Ignore the failure
                }
            }
        } finally {
           Arrays.fill(passwdChars, '\0');
        }
    }

    void implInit(int opmode, Key key, AlgorithmParameters params,
                  SecureRandom random, CipherSpi cipher)
        throws InvalidKeyException, InvalidAlgorithmParameterException {
        AlgorithmParameterSpec paramSpec = null;
        if (params != null) {
            try {
                paramSpec = params.getParameterSpec(PBEParameterSpec.class);
            } catch (InvalidParameterSpecException ipse) {
                throw new InvalidAlgorithmParameterException(
                    "requires PBE parameters");
            }
        }
        implInit(opmode, key, paramSpec, random, cipher);
    }

    void implInit(int opmode, Key key, SecureRandom random, CipherSpi cipher)
        throws InvalidKeyException {
        try {
            implInit(opmode, key, (AlgorithmParameterSpec) null, random,
                    cipher);
        } catch (InvalidAlgorithmParameterException iape) {
            throw new InvalidKeyException("requires PBE parameters");
        }
    }

    int implGetKeySize(Key key) throws InvalidKeyException {
        return keySize << 3; // in bits
    }

    public static final class PBEWithSHA1AndDESede extends CipherSpi {
        private final PKCS12PBECipherCore core;
        private final DESedeCipher cipher;

        public PBEWithSHA1AndDESede() throws NoSuchAlgorithmException {
            cipher = new DESedeCipher();
            try {
                cipher.engineSetMode("CBC");
                cipher.engineSetPadding("PKCS5Padding");
            } catch (NoSuchPaddingException nspe) {
                // should never happen
            }
            core = new PKCS12PBECipherCore("DESede", 192);
        }
        protected byte[] engineDoFinal(byte[] in, int inOff, int inLen)
            throws IllegalBlockSizeException, BadPaddingException {
            return cipher.engineDoFinal(in, inOff, inLen);
        }
        protected int engineDoFinal(byte[] in, int inOff, int inLen,
                                    byte[] out, int outOff)
            throws ShortBufferException, IllegalBlockSizeException,
                   BadPaddingException {
            return cipher.engineDoFinal(in, inOff, inLen, out, outOff);
        }
        protected int engineGetBlockSize() {
            return cipher.engineGetBlockSize();
        }
        protected byte[] engineGetIV() {
            return cipher.engineGetIV();
        }
        protected int engineGetKeySize(Key key) throws InvalidKeyException {
            return 112; // effective key size
        }
        protected int engineGetOutputSize(int inLen) {
            return cipher.engineGetOutputSize(inLen);
        }
        protected AlgorithmParameters engineGetParameters() {
            return core.implGetParameters();
        }
        protected void engineInit(int opmode, Key key,
                                  AlgorithmParameterSpec params,
                                  SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
            core.implInit(opmode, key, params, random, cipher);
        }
        protected void engineInit(int opmode, Key key,
                                  AlgorithmParameters params,
                                  SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
            core.implInit(opmode, key, params, random, cipher);
        }
        protected void engineInit(int opmode, Key key, SecureRandom random)
            throws InvalidKeyException {
            core.implInit(opmode, key, random, cipher);
        }
        protected void engineSetMode(String mode)
            throws NoSuchAlgorithmException {
            if (!"CBC".equalsIgnoreCase(mode)) {
                throw new NoSuchAlgorithmException("Unsupported mode: " + mode);
            }
        }
        protected void engineSetPadding(String paddingScheme)
            throws NoSuchPaddingException {
            if (!"PKCS5Padding".equalsIgnoreCase(paddingScheme)) {
                throw new NoSuchPaddingException("Unsupported padding: " +
                        paddingScheme);
            }
        }
        protected Key engineUnwrap(byte[] wrappedKey,
                                   String wrappedKeyAlgorithm,
                                   int wrappedKeyType)
            throws InvalidKeyException, NoSuchAlgorithmException {
            return cipher.engineUnwrap(wrappedKey, wrappedKeyAlgorithm,
                                   wrappedKeyType);
        }
        protected byte[] engineUpdate(byte[] in, int inOff, int inLen) {
            return cipher.engineUpdate(in, inOff, inLen);
        }
        protected int engineUpdate(byte[] in, int inOff, int inLen,
                                   byte[] out, int outOff)
            throws ShortBufferException {
            return cipher.engineUpdate(in, inOff, inLen, out, outOff);
        }
        protected byte[] engineWrap(Key key)
            throws IllegalBlockSizeException, InvalidKeyException {
            return cipher.engineWrap(key);
        }
    }

    static class PBEWithSHA1AndRC2 extends CipherSpi {
        private final PKCS12PBECipherCore core;
        private final RC2Cipher cipher;

        public PBEWithSHA1AndRC2(int keysize) throws NoSuchAlgorithmException {
            core = new PKCS12PBECipherCore("RC2", keysize);
            cipher = new RC2Cipher();
            try {
                cipher.engineSetMode("CBC");
                cipher.engineSetPadding("PKCS5Padding");
            } catch (NoSuchPaddingException nspe) {
                // should never happen
            }
        }
        protected byte[] engineDoFinal(byte[] in, int inOff, int inLen)
            throws IllegalBlockSizeException, BadPaddingException {
            return cipher.engineDoFinal(in, inOff, inLen);
        }
        protected int engineDoFinal(byte[] in, int inOff, int inLen,
                                    byte[] out, int outOff)
            throws ShortBufferException, IllegalBlockSizeException,
                   BadPaddingException {
            return cipher.engineDoFinal(in, inOff, inLen, out, outOff);
        }
        protected int engineGetBlockSize() {
            return cipher.engineGetBlockSize();
        }
        protected byte[] engineGetIV() {
            return cipher.engineGetIV();
        }
        protected int engineGetKeySize(Key key) throws InvalidKeyException {
            return core.implGetKeySize(key);
        }
        protected int engineGetOutputSize(int inLen) {
            return cipher.engineGetOutputSize(inLen);
        }
        protected AlgorithmParameters engineGetParameters() {
            return core.implGetParameters();
        }
        protected void engineInit(int opmode, Key key,
                                  AlgorithmParameterSpec params,
                                  SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
            core.implInit(opmode, key, params, random, cipher);

        }
        protected void engineInit(int opmode, Key key,
                                  AlgorithmParameters params,
                                  SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
            core.implInit(opmode, key, params, random, cipher);
        }
        protected void engineInit(int opmode, Key key, SecureRandom random)
            throws InvalidKeyException {
            core.implInit(opmode, key, random, cipher);
        }
        protected void engineSetMode(String mode)
            throws NoSuchAlgorithmException {
            if (!"CBC".equalsIgnoreCase(mode)) {
                throw new NoSuchAlgorithmException("Unsupported mode: " + mode);
            }
        }
        protected void engineSetPadding(String paddingScheme)
            throws NoSuchPaddingException {
            if (!"PKCS5Padding".equalsIgnoreCase(paddingScheme)) {
                throw new NoSuchPaddingException("Unsupported padding: " +
                        paddingScheme);
            }
        }
        protected Key engineUnwrap(byte[] wrappedKey,
                                   String wrappedKeyAlgorithm,
                                   int wrappedKeyType)
            throws InvalidKeyException, NoSuchAlgorithmException {
            return cipher.engineUnwrap(wrappedKey, wrappedKeyAlgorithm,
                                   wrappedKeyType);
        }
        protected byte[] engineUpdate(byte[] in, int inOff, int inLen) {
            return cipher.engineUpdate(in, inOff, inLen);
        }
        protected int engineUpdate(byte[] in, int inOff, int inLen,
                                   byte[] out, int outOff)
            throws ShortBufferException {
            return cipher.engineUpdate(in, inOff, inLen, out, outOff);
        }
        protected byte[] engineWrap(Key key)
            throws IllegalBlockSizeException, InvalidKeyException {
            return cipher.engineWrap(key);
        }
    }

    public static final class PBEWithSHA1AndRC2_40 extends PBEWithSHA1AndRC2 {
        public PBEWithSHA1AndRC2_40() throws NoSuchAlgorithmException {
            super(40);
        }
    }

    public static final class PBEWithSHA1AndRC2_128 extends PBEWithSHA1AndRC2 {
        public PBEWithSHA1AndRC2_128() throws NoSuchAlgorithmException {
            super(128);
        }
    }

    static class PBEWithSHA1AndRC4 extends CipherSpi {
        private final PKCS12PBECipherCore core;
        private final ARCFOURCipher cipher;

        public PBEWithSHA1AndRC4(int keysize) throws NoSuchAlgorithmException {
            core = new PKCS12PBECipherCore("RC4", keysize);
            cipher = new ARCFOURCipher();
        }
        protected byte[] engineDoFinal(byte[] in, int inOff, int inLen)
            throws IllegalBlockSizeException, BadPaddingException {
            return cipher.engineDoFinal(in, inOff, inLen);
        }
        protected int engineDoFinal(byte[] in, int inOff, int inLen,
                                    byte[] out, int outOff)
            throws ShortBufferException, IllegalBlockSizeException,
                   BadPaddingException {
            return cipher.engineDoFinal(in, inOff, inLen, out, outOff);
        }
        protected int engineGetBlockSize() {
            return cipher.engineGetBlockSize();
        }
        protected byte[] engineGetIV() {
            return cipher.engineGetIV();
        }
        protected int engineGetKeySize(Key key) throws InvalidKeyException {
            return core.implGetKeySize(key);
        }
        protected int engineGetOutputSize(int inLen) {
            return cipher.engineGetOutputSize(inLen);
        }
        protected AlgorithmParameters engineGetParameters() {
            return core.implGetParameters();
        }
        protected void engineInit(int opmode, Key key,
                                  AlgorithmParameterSpec params,
                                  SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
            core.implInit(opmode, key, params, random, cipher);
        }
        protected void engineInit(int opmode, Key key,
                                  AlgorithmParameters params,
                                  SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
            core.implInit(opmode, key, params, random, cipher);
        }
        protected void engineInit(int opmode, Key key, SecureRandom random)
            throws InvalidKeyException {
            core.implInit(opmode, key, random, cipher);
        }
        protected void engineSetMode(String mode)
            throws NoSuchAlgorithmException {
            if (!"ECB".equalsIgnoreCase(mode)) {
                throw new NoSuchAlgorithmException("Unsupported mode: " + mode);
            }
        }
        protected void engineSetPadding(String paddingScheme)
            throws NoSuchPaddingException {
            if (!"NoPadding".equalsIgnoreCase(paddingScheme)) {
                throw new NoSuchPaddingException("Unsupported padding: " +
                        paddingScheme);
            }
        }
        protected Key engineUnwrap(byte[] wrappedKey,
                                   String wrappedKeyAlgorithm,
                                   int wrappedKeyType)
            throws InvalidKeyException, NoSuchAlgorithmException {
            return cipher.engineUnwrap(wrappedKey, wrappedKeyAlgorithm,
                                   wrappedKeyType);
        }
        protected byte[] engineUpdate(byte[] in, int inOff, int inLen) {
            return cipher.engineUpdate(in, inOff, inLen);
        }
        protected int engineUpdate(byte[] in, int inOff, int inLen,
                                   byte[] out, int outOff)
            throws ShortBufferException {
            return cipher.engineUpdate(in, inOff, inLen, out, outOff);
        }
        protected byte[] engineWrap(Key key)
            throws IllegalBlockSizeException, InvalidKeyException {
            return cipher.engineWrap(key);
        }
    }

    public static final class PBEWithSHA1AndRC4_40 extends PBEWithSHA1AndRC4 {
        public PBEWithSHA1AndRC4_40() throws NoSuchAlgorithmException {
            super(40);
        }
    }
    public static final class PBEWithSHA1AndRC4_128 extends PBEWithSHA1AndRC4 {
        public PBEWithSHA1AndRC4_128() throws NoSuchAlgorithmException {
            super(128);
        }
    }
}
