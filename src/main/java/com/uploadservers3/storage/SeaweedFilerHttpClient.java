package com.uploadservers3.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

@Component
public class SeaweedFilerHttpClient implements StorageClient {

    private final String filerBase; // ex) http://localhost:8888

    public SeaweedFilerHttpClient(@Value("${filer.base:http://localhost:8888}") String filerBase) {
        this.filerBase = filerBase.replaceAll("/$", "");
    }

    @Override
    public void putObject(String bucket, String objectKey, File file) throws Exception {
        URL url = new URL(filerBase + "/buckets/" + bucket + "/" + objectKey);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setFixedLengthStreamingMode(file.length());

        try (OutputStream out = c.getOutputStream();
             InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            in.transferTo(out);
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readAll(c.getErrorStream());
            c.disconnect();
            throw new IOException("Filer PUT failed: " + code + " " + err);
        }
        c.disconnect();
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        try (is) { return new String(is.readAllBytes()); } catch (Exception e) { return ""; }
    }
}
