package com.uploadservers3.storage;

import java.io.File;

public interface StorageClient {
    void putObject(String bucket, String objectKey, File file) throws Exception;
}