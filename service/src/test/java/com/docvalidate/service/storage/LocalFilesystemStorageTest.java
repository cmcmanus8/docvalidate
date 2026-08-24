package com.docvalidate.service.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.docvalidate.service.config.DocValidateProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class LocalFilesystemStorageTest {

    @TempDir
    Path root;

    private LocalFilesystemStorage storage() {
        return new LocalFilesystemStorage(
                new DocValidateProperties(Duration.ofMinutes(15), DataSize.ofMegabytes(10), root));
    }

    @Test
    void storesUnderTheRequestIdAndReadsTheBytesBack() {
        UUID requestId = UUID.randomUUID();
        byte[] content = "hello".getBytes();

        String key = storage().store(requestId, "invoice.pdf", content);

        assertThat(key).isEqualTo(requestId + "/invoice.pdf");
        assertThat(root.resolve(key)).exists();
        assertThat(storage().read(key)).isEqualTo(content);
    }

    @Test
    void keepsAHostileFilenameInsideTheRequestDirectory() throws IOException {
        UUID requestId = UUID.randomUUID();

        String key = storage().store(requestId, "../../etc/passwd", "x".getBytes());

        assertThat(key).isEqualTo(requestId + "/.._.._etc_passwd");
        assertThat(root.resolve(key).normalize()).startsWith(root.resolve(requestId.toString()));
        assertThat(Files.exists(root.getParent().resolve("etc"))).isFalse();
    }

    @Test
    void failsLoudlyWhenTheDocumentIsMissing() {
        LocalFilesystemStorage storage = storage();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> storage.read(UUID.randomUUID() + "/absent.pdf"))
                .isInstanceOf(StorageException.class);
    }
}
