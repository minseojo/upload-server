package com.uploadservers3.storage;

import com.uploadservers3.manifest.Manifest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.Map;

@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService service;

    @PostMapping("/init")
    public Map<String, Object> init(@RequestBody Manifest manifest) {
        try {
            return service.init(manifest);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping(value="/stream", consumes="application/octet-stream")
    public Map<String, Object> stream(HttpServletRequest req, @RequestParam String txnId) {
        try (InputStream in = req.getInputStream()) {
            return service.stream(txnId, in);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
