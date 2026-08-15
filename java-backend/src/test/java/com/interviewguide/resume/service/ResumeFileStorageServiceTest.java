package com.interviewguide.resume.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeFileStorageServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void sameContentUsesIndependentPathsForDifferentResumeVersions() throws Exception {
        ResumeFileStorageService storage = new ResumeFileStorageService(storageRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.txt", "text/plain", "Java and Redis".getBytes());

        ResumeFileStorageService.FileDescriptor descriptor = storage.inspect(file);
        ResumeFileStorageService.StoredFile first = storage.store(descriptor, "resume-1");
        ResumeFileStorageService.StoredFile second = storage.store(descriptor, "resume-2");

        assertEquals(first.hash(), second.hash());
        assertNotEquals(first.key(), second.key());
        assertArrayEquals(file.getBytes(), storage.read(first.key()));
        assertArrayEquals(file.getBytes(), storage.read(second.key()));

        storage.delete(first.key());

        assertTrue(Files.notExists(storageRoot.resolve(first.key())));
        assertArrayEquals(file.getBytes(), storage.read(second.key()));
    }
}
