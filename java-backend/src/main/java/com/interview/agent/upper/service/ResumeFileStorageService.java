package com.interview.agent.upper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

@Service
public class ResumeFileStorageService {
    private final Path root;

    public ResumeFileStorageService(@Value("${agent.file-storage.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public StoredFile store(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String hash = sha256(bytes);
        String safeName = Path.of(file.getOriginalFilename() == null ? "resume.bin" : file.getOriginalFilename())
                .getFileName().toString();
        String key = hash + "/" + safeName;
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IOException("invalid storage path");
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        return new StoredFile(hash, key, bytes.length, safeName,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(), bytes);
    }

    public byte[] read(String key) throws IOException {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IOException("invalid storage path");
        return Files.readAllBytes(path);
    }

    public void delete(String key) throws IOException {
        if (key == null || key.isBlank()) return;
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new IOException("invalid storage path");
        Files.deleteIfExists(path);
    }

    public record StoredFile(String hash, String key, long size, String filename,
                             String contentType, byte[] bytes) { }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
}
