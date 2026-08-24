package com.docvalidate.service.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "docvalidate")
public record DocValidateProperties(Duration uploadWindow, DataSize maxUploadSize, Path storageRoot) {
}
