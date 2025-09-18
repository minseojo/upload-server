package com.uploadservers3.storage;

import com.uploadservers3.manifest.Manifest;
import com.uploadservers3.manifest.ManifestFile;
import com.uploadservers3.manifest.ManifestRepository;
import com.uploadservers3.storage.StorageClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class UploadService {

    private final ManifestRepository repo;
    private final StorageClient storage;
    private final String bucket;

    // Zip Bomb 가드 (필요에 맞게 조정)
    private static final long MAX_ENTRIES = 1_000_000L;
    private static final long MAX_UNCOMPRESSED_TOTAL = 2L * 1024 * 1024 * 1024; // 2GB
    private static final long MAX_RATIO = 100L;

    public UploadService(ManifestRepository repo,
                         StorageClient storage,
                         @Value("${storage.bucket:upload-test}") String bucket) {
        this.repo = repo;
        this.storage = storage;
        this.bucket = bucket;
    }

    // ====== Public APIs ======

    public Map<String, Object> init(Manifest m) {
        if (m == null) throw new IllegalArgumentException("manifest required");
        requireNonBlank("txnId", m.txnId());
        requireNonBlank("archiveHashAlgo", m.archiveHashAlgo());
        requireNonBlank("archiveHash", m.archiveHash());
        requireNonBlank("entryHashAlgo", m.entryHashAlgo());

        // 알고리즘 유효성 + 해시 길이 점검
        String archiveAlgo = normalizeAlgo(m.archiveHashAlgo());
        String entryAlgo   = normalizeAlgo(m.entryHashAlgo());
        requireHexLength("archiveHash", m.archiveHash(), expectedHexLen(archiveAlgo));

        // files 검증(형식)
        if (m.files() == null) throw new IllegalArgumentException("files required");
        if (m.count() != m.files().size())
            throw new IllegalArgumentException("count != files.size()");
        for (ManifestFile f : m.files()) {
            requireNonBlank("files.path", f.path());
            if (f.size() < 0) throw new IllegalArgumentException("files.size must be >= 0 for " + f.path());
            requireHexLength("files.hash(" + f.path() + ")", f.hash(), expectedHexLen(entryAlgo));
        }

        repo.save(m);
        return Map.of("ok", true, "txnId", m.txnId());
    }

    /** 안전 최우선: 1) 아카이브 해시 검증(원본 ZIP 바이트) → 2) ZipFile 메타/CRC → 3) 중요도와 무관하게 entryHashAlgo로 hash 검증 */
    public Map<String, Object> stream(String txnId, InputStream body) throws Exception {
        Manifest manifest = Optional.ofNullable(repo.findByTxnId(txnId))
                .orElseThrow(() -> new IllegalArgumentException("unknown txnId: " + txnId));

        String archiveAlgo = normalizeAlgo(manifest.archiveHashAlgo());
        String entryAlgo   = normalizeAlgo(manifest.entryHashAlgo());

        // 1) 스트림을 임시 ZIP 파일로 저장하며 동시에 아카이브 해시 계산
        TempWithHash t = saveToTempAndDigest(body, archiveAlgo);
        if (!t.hash.equalsIgnoreCase(manifest.archiveHash())) {
            t.file.delete();
            throw new IllegalStateException("archive hash mismatch: expected=" +
                    manifest.archiveHash() + ", actual=" + t.hash);
        }

        // 2) ZipFile 기반 검증
        Map<String, ManifestFile> expect = new HashMap<>();
        for (ManifestFile f : manifest.files()) {
            expect.put(normalizeZipPath(f.path()), f);
        }

        Set<String> seen = new HashSet<>();
        AtomicLong totalUncomp = new AtomicLong(0);
        AtomicLong totalComp = new AtomicLong(0);
        long entryCount = 0;

        try (ZipFile zf = new ZipFile(t.file)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[8192];

            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;

                entryCount++;
                if (entryCount > MAX_ENTRIES) throw new IllegalStateException("too many entries: " + entryCount);

                String path = normalizeZipPath(ze.getName());
                ManifestFile mf = expect.get(path);
                if (mf == null) throw new IllegalStateException("unexpected entry: " + path);

                long declaredSize = ze.getSize();           // -1일 수 있음
                long declaredComp = ze.getCompressedSize(); // -1일 수 있음
                if (declaredSize >= 0) totalUncomp.addAndGet(declaredSize);
                if (declaredComp >= 0) totalComp.addAndGet(declaredComp);
                checkTotals(totalUncomp, totalComp);

                // CRC32 빠른 1차 필터
                long expectedCrc = ze.getCrc(); // -1일 수 있음
                CRC32 crc = new CRC32();
                long actualSize = 0;
                try (InputStream in = zf.getInputStream(ze)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        crc.update(buf, 0, n);
                        actualSize += n;
                    }
                }
                long actualCrc = crc.getValue();
                if (expectedCrc != -1 && actualCrc != expectedCrc)
                    throw new IllegalStateException("crc mismatch for " + path);

                // 사이즈 검증 (manifest.size vs 실제 해제 사이즈)
                if (mf.size() != actualSize)
                    throw new IllegalStateException("size mismatch for " + path + " expected=" + mf.size() + " actual=" + actualSize);

                // 3) 엔트리 해시(entryHashAlgo) 검증
                String actualEntryHash;
                try (InputStream in = zf.getInputStream(ze)) {
                    actualEntryHash = digestStream(in, entryAlgo);
                }
                if (!actualEntryHash.equalsIgnoreCase(mf.hash()))
                    throw new IllegalStateException("hash mismatch for " + path);

                seen.add(path);
            }
        }

        if (seen.size() != expect.size())
            throw new IllegalStateException("entry count mismatch: expected=" + expect.size() + " actual=" + seen.size());
        if (manifest.count() != expect.size())
            throw new IllegalStateException("manifest count mismatch: manifest.count=" + manifest.count() + " files.size=" + expect.size());

        // 4) 업로드 (원하면 archiveHash 기반 콘텐츠 주소화로 변경 가능)
        String objectKey = txnId + ".zip";
        storage.putObject(bucket, objectKey, t.file);
        t.file.delete();

        return Map.of("ok", true, "status", "PROMOTED", "txnId", txnId, "objectKey", objectKey, "bucket", bucket);
    }

    // ====== Helpers ======

    private record TempWithHash(File file, String hash) {}

    private TempWithHash saveToTempAndDigest(InputStream in, String algo) throws Exception {
        File tmp = File.createTempFile("upload-", ".zip");
        tmp.deleteOnExit();
        MessageDigest md = MessageDigest.getInstance(algo);
        byte[] buf = new byte[8192];
        int n;
        try (in; OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        return new TempWithHash(tmp, toHex(md.digest()));
    }

    private static String digestStream(InputStream in, String algo) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        return toHex(md.digest());
    }

    private static String normalizeAlgo(String s) {
        String up = s.trim().toUpperCase();
        return switch (up) {
            case "SHA-1", "SHA1" -> "SHA-1";
            case "SHA-256", "SHA256" -> "SHA-256";
            default -> throw new IllegalArgumentException("unsupported hash algo: " + s);
        };
    }

    private static int expectedHexLen(String algo) {
        return switch (algo) {
            case "SHA-1" -> 40;
            case "SHA-256" -> 64;
            default -> throw new IllegalArgumentException("unsupported hash algo: " + algo);
        };
    }

    private static void requireNonBlank(String field, String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    private static void requireHexLength(String field, String hex, int len) {
        if (hex == null) throw new IllegalArgumentException(field + " required");
        String s = hex.toLowerCase(Locale.ROOT);
        if (s.length() != len) throw new IllegalArgumentException(field + " length != " + len);
        if (!s.matches("[0-9a-f]+")) throw new IllegalArgumentException(field + " not hex");
    }

    private static void checkTotals(AtomicLong totalUncomp, AtomicLong totalComp) {
        if (totalUncomp.get() > MAX_UNCOMPRESSED_TOTAL)
            throw new IllegalStateException("too large uncompressed total: " + totalUncomp.get());
        long comp = Math.max(1, totalComp.get());
        long ratio = totalUncomp.get() / comp;
        if (ratio > MAX_RATIO)
            throw new IllegalStateException("suspicious compression ratio: " + ratio + "x");
    }

    /** 디렉터리 traversal 및 절대 경로 방지 */
    private static String normalizeZipPath(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("empty entry name");
        String p = name.replace('\\', '/');
        if (p.startsWith("/") || p.startsWith("\\")) throw new IllegalArgumentException("absolute path not allowed: " + name);
        if (p.contains("../")) throw new IllegalArgumentException("path traversal detected: " + name);
        return p;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >>> 4) & 0xF, 16))
                .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
