package com.uploadservers3.manifest;

public enum HashAlgo {
    SHA1("SHA-1", 40),
    SHA256("SHA-256", 64);

    public final String jcaName;
    public final int hexLen;
    HashAlgo(String jcaName, int hexLen) { this.jcaName = jcaName; this.hexLen = hexLen; }

    public static HashAlgo of(String s) {
        return switch (s.toUpperCase()) {
            case "SHA-1", "SHA1" -> SHA1;
            case "SHA-256", "SHA256" -> SHA256;
            default -> throw new IllegalArgumentException("unsupported hash algo: " + s);
        };
    }
}
