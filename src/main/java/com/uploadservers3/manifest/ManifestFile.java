package com.uploadservers3.manifest;

public record ManifestFile(
        String path,   // 내부 경로
        long   size,   // uncompressed size
        String hash    // entryHashAlgo 기준 HEX
) {}
