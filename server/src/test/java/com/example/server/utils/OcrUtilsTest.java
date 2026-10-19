package com.example.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

/** Process-boundary regression tests; E2E separately uses actual Tesseract on real video frames. */
@EnabledOnOs(OS.LINUX)
class OcrUtilsTest {
    @TempDir Path directory;

    @Test void diagnosticsNeverBecomeVideoEvidenceText() throws Exception {
        var command = script("printf 'Warning: Invalid resolution 0 dpi\\n' >&2\nprintf 'FIVE RED SQUARES\\n'\n");
        var image = Files.createFile(directory.resolve("frame.png"));
        assertEquals("FIVE RED SQUARES", new OcrUtils(command.toString()).recognize(image.toFile()));
    }

    @Test void failedOcrKeepsBoundedDiagnosticsForInvestigation() throws Exception {
        var command = script("printf 'unsupported image format\\n' >&2\nexit 3\n");
        var image = Files.createFile(directory.resolve("frame.png"));
        var error = assertThrows(IllegalStateException.class, () -> new OcrUtils(command.toString()).recognize(image.toFile()));
        assertTrue(error.getCause().getMessage().contains("exit code 3: unsupported image format"));
    }

    private Path script(String body) throws Exception {
        Path path = directory.resolve("ocr-command");
        Files.writeString(path, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }
}
