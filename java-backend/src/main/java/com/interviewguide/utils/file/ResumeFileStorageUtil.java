package com.interviewguide.utils.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

@Service
/** Generic filesystem storage helper for uploaded file bytes and content hashes. */
public class ResumeFileStorageUtil {
    private final Path root;

    public ResumeFileStorageUtil(@Value("${agent.file-storage.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public FileDescriptor inspect(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String hash = sha256(bytes);
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IOException("resume filename is required");
        }
        String safeName = Path.of(originalFilename).getFileName().toString();
        return new FileDescriptor(hash, bytes.length, safeName,
                file.getContentType(), bytes);
    }

    public StoredFile store(FileDescriptor file, String resumeId) throws IOException {
        if (resumeId == null || resumeId.isBlank()) throw new IOException("resumeId is required");
        String key = resumeId + "/" + file.filename();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IOException("invalid storage path");
        Files.createDirectories(target.getParent());
        Files.write(target, file.bytes(), StandardOpenOption.CREATE_NEW);
        return new StoredFile(file.hash(), key, file.size(), file.filename(), file.contentType(), file.bytes());
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

    public record FileDescriptor(String hash, long size, String filename,
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
