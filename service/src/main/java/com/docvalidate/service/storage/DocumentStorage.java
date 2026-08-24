package com.docvalidate.service.storage;

import java.util.UUID;

/**
 * The database holds the key this returns, never the bytes, so swapping the
 * filesystem for S3 is a new implementation of this interface and nothing else.
 */
public interface DocumentStorage {

    String store(UUID requestId, String filename, byte[] content);

    byte[] read(String storageKey);
}
