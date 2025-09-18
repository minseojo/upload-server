package com.uploadservers3.manifest;

import org.springframework.stereotype.Repository;

import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryManifestRepository implements ManifestRepository {
    private final ConcurrentHashMap<String, Manifest> store = new ConcurrentHashMap<>();

    @Override
    public void save(Manifest manifest) { store.put(manifest.txnId(), manifest); }

    @Override
    public Manifest findByTxnId(String txnId) { return store.get(txnId); }
}