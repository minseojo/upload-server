package com.uploadservers3.manifest;

import java.util.List;

public record Manifest(
        String txnId,
        String archiveHashAlgo,   // 예: "SHA-256"
        String archiveHash,       // archiveHashAlgo 기준 HEX (소문자 권장)
        String entryHashAlgo,     // 예: "SHA-1" — files[*].hash의 알고리즘
        int    count,             // files.size()
        List<ManifestFile> files,
        String createdAt          // ISO-8601 (Z)
) {}
