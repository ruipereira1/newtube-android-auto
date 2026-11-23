// SPDX-License-Identifier: Apache-2.0
// NEWTUBE(android-auto): written for this app, in place of spake2-java's experimental pure-Java
// port (it printed private keys to stdout and failed its own Alice/Bob exchange; the published
// Android artifact is native code instead). Same API as spake2-java, so the vendored
// io.github.muntashirakon.adb.PairingAuthCtx is unchanged.

package io.github.muntashirakon.crypto.spake2;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.security.auth.Destroyable;

/**
 * SPAKE2 over edwards25519, step for step what BoringSSL's spake25519.c does (and so adbd's
 * Wireless debugging pairing): message = 8x·B + w·M (Alice) or + w·N (Bob), where w is the
 * password's SHA-512 reduced mod ℓ plus the multiple of ℓ that makes it ≡ 0 mod 8; key = SHA-512
 * of the length-prefixed names, both messages, the shared point and the password hash.
 *
 * <p>Plain BigInteger arithmetic in extended coordinates: a pairing does a handful of scalar
 * multiplications once, so speed doesn't matter and there are no tables to carry. It isn't
 * constant-time; the only secret is a one-off pairing code typed on this same phone.</p>
 */
public class Spake2Context implements Destroyable {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    /** The generator. */
    static final Point B = decodeHex("5866666666666666666666666666666666666666666666666666666666666666");
    /** BoringSSL's kSpakeM / kSpakeN: SHA-256 chains of "edwards25519 point generation seed (M)/(N)". */
    private static final Point M = decodeHex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");
    private static final Point N = decodeHex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");

    private final Spake2Role mMyRole;
    private final byte[] mMyName;
    private final byte[] mTheirName;
    private BigInteger mPrivateKey;
    private BigInteger mPasswordScalar;
    private byte[] mPasswordHash;
    private byte[] mMyMsg;
    private boolean mMessageGenerated;
    private boolean mKeyGenerated;
    private boolean mIsDestroyed;

    public Spake2Context(Spake2Role myRole, final byte[] myName, final byte[] theirName) {
        mMyRole = myRole;
        mMyName = myName.clone();
        mTheirName = theirName.clone();
    }

    public Spake2Role getMyRole() {
        return mMyRole;
    }

    public byte[] getMyMsg() {
        return mMyMsg != null ? mMyMsg.clone() : null;
    }

    /** @return this side's 32-byte message for the peer */
    public byte[] generateMessage(byte[] password) throws IllegalArgumentException, IllegalStateException {
        if (mIsDestroyed || mMessageGenerated) {
            throw new IllegalStateException("Invalid state");
        }
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        // x mod ℓ, times the cofactor 8 so the peer's small-order part cancels.
        mPrivateKey = littleEndian(random).mod(L).shiftLeft(3);
        Arrays.fill(random, (byte) 0);

        mPasswordHash = sha512().digest(password);
        BigInteger w = littleEndian(mPasswordHash).mod(L);
        for (int bit = 0; bit < 3; bit++) {
            if (w.testBit(bit)) {
                w = w.add(L.shiftLeft(bit));
            }
        }
        mPasswordScalar = w;

        Point mask = multiply(mMyRole == Spake2Role.Alice ? M : N, mPasswordScalar);
        mMyMsg = multiply(B, mPrivateKey).add(mask).encode();
        mMessageGenerated = true;
        return mMyMsg.clone();
    }

    /** @return the 64-byte shared key */
    public byte[] processMessage(final byte[] theirMsg) throws IllegalArgumentException, IllegalStateException {
        if (mIsDestroyed || !mMessageGenerated || mKeyGenerated) {
            throw new IllegalStateException("Invalid state");
        }
        if (theirMsg.length != 32) {
            throw new IllegalArgumentException("Peer's message is not 32 bytes");
        }
        Point qStar = decode(theirMsg);
        if (qStar == null) {
            throw new IllegalArgumentException("Point received from peer was not on the curve.");
        }
        Point peersMask = multiply(mMyRole == Spake2Role.Alice ? N : M, mPasswordScalar);
        byte[] dhShared = multiply(qStar.add(peersMask.negate()), mPrivateKey).encode();

        MessageDigest sha = sha512();
        if (mMyRole == Spake2Role.Alice) {
            update(sha, mMyName);
            update(sha, mTheirName);
            update(sha, mMyMsg);
            update(sha, theirMsg);
        } else {
            update(sha, mTheirName);
            update(sha, mMyName);
            update(sha, theirMsg);
            update(sha, mMyMsg);
        }
        update(sha, dhShared);
        update(sha, mPasswordHash);
        mKeyGenerated = true;
        return sha.digest();
    }

    @Override
    public boolean isDestroyed() {
        return mIsDestroyed;
    }

    @Override
    public void destroy() {
        mIsDestroyed = true;
        mPrivateKey = null;
        mPasswordScalar = null;
        if (mPasswordHash != null) {
            Arrays.fill(mPasswordHash, (byte) 0);
        }
    }

    // --- edwards25519 -----------------------------------------------------------------------

    /** A point in extended coordinates (X:Y:Z:T): x = X/Z, y = Y/Z, xy = T/Z. */
    static final class Point {
        final BigInteger x, y, z, t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }

        static Point affine(BigInteger x, BigInteger y) {
            return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
        }

        /** Complete addition for a = -1 (add-2008-hwcd-3); doubles too. */
        Point add(Point o) {
            BigInteger a = y.subtract(x).multiply(o.y.subtract(o.x)).mod(P);
            BigInteger b = y.add(x).multiply(o.y.add(o.x)).mod(P);
            BigInteger c = t.multiply(D2).multiply(o.t).mod(P);
            BigInteger d = z.shiftLeft(1).multiply(o.z).mod(P);
            BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
            return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P),
                    f.multiply(g).mod(P), e.multiply(h).mod(P));
        }

        Point negate() {
            return new Point(P.subtract(x).mod(P), y, z, P.subtract(t).mod(P));
        }

        byte[] encode() {
            BigInteger zi = z.modInverse(P);
            BigInteger ax = x.multiply(zi).mod(P);
            BigInteger ay = y.multiply(zi).mod(P);
            byte[] out = toLittleEndian(ay);
            if (ax.testBit(0)) {
                out[31] |= (byte) 0x80;
            }
            return out;
        }
    }

    static Point multiply(Point p, BigInteger k) {
        Point r = Point.affine(BigInteger.ZERO, BigInteger.ONE);
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = r.add(r);
            if (k.testBit(i)) {
                r = r.add(p);
            }
        }
        return r;
    }

    /** As BoringSSL's x25519_ge_frombytes_vartime: null if no x fits y on the curve. */
    static Point decode(byte[] s) {
        byte[] bytes = s.clone();
        boolean sign = (bytes[31] & 0x80) != 0;
        bytes[31] &= 0x7f;
        BigInteger y = littleEndian(bytes).mod(P);
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
        BigInteger xx = u.multiply(v.modInverse(P)).mod(P);
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (x.multiply(x).subtract(xx).mod(P).signum() != 0) {
            x = x.multiply(SQRT_M1).mod(P);
            if (x.multiply(x).subtract(xx).mod(P).signum() != 0) {
                return null;
            }
        }
        if (x.testBit(0) != sign) {
            x = P.subtract(x).mod(P);
        }
        return Point.affine(x, y);
    }

    private static Point decodeHex(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return decode(b);
    }

    static BigInteger littleEndian(byte[] le) {
        byte[] be = new byte[le.length + 1]; // leading 0: positive
        for (int i = 0; i < le.length; i++) {
            be[le.length - i] = le[i];
        }
        return new BigInteger(be);
    }

    private static byte[] toLittleEndian(BigInteger n) {
        byte[] be = n.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < be.length && i < 32; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    private static void update(MessageDigest sha, byte[] data) {
        long length = data.length;
        for (int i = 0; i < 8; i++) {
            sha.update((byte) (length >>> (8 * i)));
        }
        sha.update(data);
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
