package com.docvalidate.service.storage;

import com.docvalidate.service.config.DocValidateProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LocalFilesystemStorage implements DocumentStorage {

    private final Path root;

    public LocalFilesystemStorage(DocValidateProperties properties) {
        this.root = properties.storageRoot();
    }

    @Override
    public String store(UUID requestId, String filename, byte[] content) {
        String key = requestId + "/" + sanitise(filename);
        Path target = root.resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new StorageException("Could not write document " + key, e);
        }
        return key;
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(root.resolve(storageKey));
        } catch (IOException e) {
            throw new StorageException("Could not read document " + storageKey, e);
        }
    }

    /**
     * The filename arrives from the client, so it never reaches the filesystem intact:
     * anything that could climb out of the request directory becomes an underscore.
     */
    private static String sanitise(String filename) {
        String cleaned = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() || cleaned.replace(".", "").isEmpty() ? "document" : cleaned;
    }
}
