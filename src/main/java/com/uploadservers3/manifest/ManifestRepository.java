package com.uploadservers3.manifest;

public interface ManifestRepository {
    void save(Manifest manifest);
    Manifest findByTxnId(String txnId);
}
