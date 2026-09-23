package io.github.thorfusion.solderpyloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureReportTest {
    @TempDir Path temporary;

    @Test
    void persistsAnActionableWorkerFailureForTheParentAgent() {
        RecoverableBootstrapException failure = new RecoverableBootstrapException(
            "Download failed for Applied Energistics 2 rv3 [applied-energistics-2]: HTTP 503",
            null);

        FailureReport.write(temporary, failure);
        String report = FailureReport.read(temporary);

        assertTrue(report.contains("Applied Energistics 2"));
        assertTrue(report.contains("HTTP 503"));
        assertTrue(report.contains("previously installed modpack files were left intact"));
        assertTrue(Files.isRegularFile(temporary.resolve(FailureReport.FILE_NAME)));

        FailureReport.clear(temporary);
        assertNull(FailureReport.read(temporary));
        assertFalse(Files.exists(temporary.resolve(FailureReport.FILE_NAME)));
    }
}
