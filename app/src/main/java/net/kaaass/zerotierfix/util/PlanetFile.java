package net.kaaass.zerotierfix.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parser for ZeroTier planet (world) files.
 * <p>
 * The binary layout is defined by {@code node/World.hpp} in the ZeroTier core. ZeroTier 1.12 and
 * 1.16 use the same layout (64 byte C25519/Ed25519 public key set and 96 byte signature), so a
 * planet file can be shared between both. Parsing the file here lets the UI reject corrupt or
 * truncated files instead of silently falling back to the public network, and lets us show the
 * planet ID in the settings screen.
 */
public final class PlanetFile {

    /**
     * World::TYPE_PLANET
     */
    public static final int TYPE_PLANET = 1;

    /**
     * World::TYPE_MOON
     */
    public static final int TYPE_MOON = 127;

    /**
     * Node/World.hpp: ZT_WORLD_MAX_ROOTS
     */
    private static final int MAX_ROOTS = 8;

    /**
     * C25519/Ed25519 public key set (32 + 32 bytes).
     */
    private static final int PUBLIC_KEY_SET_LEN = 64;

    /**
     * Ed25519 signature.
     */
    private static final int SIGNATURE_LEN = 96;

    /**
     * Identity: address(5) + type(1) + public key set(64) + private key length(1).
     */
    private static final int IDENTITY_LEN = 5 + 1 + PUBLIC_KEY_SET_LEN + 1;

    private final int type;
    private final long id;
    private final long timestamp;
    private final List<String> rootAddresses;

    private PlanetFile(int type, long id, long timestamp, List<String> rootAddresses) {
        this.type = type;
        this.id = id;
        this.timestamp = timestamp;
        this.rootAddresses = rootAddresses;
    }

    public int getType() {
        return type;
    }

    public long getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public List<String> getRootAddresses() {
        return rootAddresses;
    }

    /**
     * Planet id as the 16 digit lowercase hex string used by ZeroTier tooling.
     */
    public String getIdString() {
        return String.format("%016x", id);
    }

    public static PlanetFile parse(File file) throws IOException, FormatException {
        byte[] data = readAll(file);
        return parse(data);
    }

    public static PlanetFile parse(byte[] data) throws FormatException {
        if (data.length < 178) {
            throw new FormatException("file too short: " + data.length);
        }

        int type = data[0] & 0xff;
        if (type != TYPE_PLANET) {
            throw new FormatException("not a planet file, world type is " + type);
        }

        long id = readUInt64(data, 1);
        long timestamp = readUInt64(data, 9);

        // bytes 17..80: updatesMustBeSignedBy, bytes 81..176: signature
        int pos = 17 + PUBLIC_KEY_SET_LEN + SIGNATURE_LEN;
        int numRoots = data[pos++] & 0xff;
        if (numRoots < 1 || numRoots > MAX_ROOTS) {
            throw new FormatException("invalid root count " + numRoots);
        }

        List<String> roots = new ArrayList<>(numRoots);
        for (int i = 0; i < numRoots; i++) {
            require(data, pos, IDENTITY_LEN, "root identity");
            roots.add(toHex(data, pos, 5));
            int identityType = data[pos + 5] & 0xff;
            int privateKeyLength = data[pos + IDENTITY_LEN - 1] & 0xff;
            if (identityType != 0 || privateKeyLength != 0) {
                throw new FormatException("unsupported root identity");
            }
            pos += IDENTITY_LEN;

            require(data, pos, 1, "stable endpoint count");
            int numEndpoints = data[pos++] & 0xff;
            for (int k = 0; k < numEndpoints; k++) {
                require(data, pos, 1, "stable endpoint");
                switch (data[pos++] & 0xff) {
                    case 0:
                        break;
                    case 1:
                    case 2:
                        pos += 6;
                        break;
                    case 4:
                        pos += 6;
                        break;
                    case 6:
                        pos += 18;
                        break;
                    case 3:
                        require(data, pos, 2, "extended endpoint");
                        pos += 2 + readUInt16(data, pos);
                        break;
                    default:
                        throw new FormatException("invalid endpoint type");
                }
            }
        }

        if (pos != data.length) {
            throw new FormatException("unexpected trailing data: " + (data.length - pos) + " bytes");
        }

        return new PlanetFile(type, id, timestamp, Collections.unmodifiableList(roots));
    }

    private static void require(byte[] data, int pos, int len, String what) throws FormatException {
        if (pos < 0 || len < 0 || pos + len > data.length) {
            throw new FormatException("truncated " + what);
        }
    }

    private static long readUInt64(byte[] data, int pos) throws FormatException {
        require(data, pos, 8, "uint64");
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[pos + i] & 0xffL);
        }
        return value;
    }

    private static int readUInt16(byte[] data, int pos) throws FormatException {
        require(data, pos, 2, "uint16");
        return ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
    }

    private static String toHex(byte[] data, int pos, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", data[pos + i] & 0xff));
        }
        return sb.toString();
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, (int) file.length()));
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * Thrown when a planet file is not a structurally valid ZeroTier planet file.
     */
    public static class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }
}
