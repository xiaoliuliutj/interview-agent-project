package com.interviewguide.resume.service;

import com.interviewguide.utils.file.ResumeFileStorageUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeFileStorageUtilTest {

    @TempDir
    Path storageRoot;

    @Test
    void sameContentUsesIndependentPathsForDifferentResumeVersions() throws Exception {
        ResumeFileStorageUtil storage = new ResumeFileStorageUtil(storageRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.txt", "text/plain", "Java and Redis".getBytes());

        ResumeFileStorageUtil.FileDescriptor descriptor = storage.inspect(file);
        ResumeFileStorageUtil.StoredFile first = storage.store(descriptor, "resume-1");
        ResumeFileStorageUtil.StoredFile second = storage.store(descriptor, "resume-2");

        assertEquals(first.hash(), second.hash());
        assertNotEquals(first.key(), second.key());
        assertArrayEquals(file.getBytes(), storage.read(first.key()));
        assertArrayEquals(file.getBytes(), storage.read(second.key()));

        storage.delete(first.key());

        assertTrue(Files.notExists(storageRoot.resolve(first.key())));
        assertArrayEquals(file.getBytes(), storage.read(second.key()));
    }
}
