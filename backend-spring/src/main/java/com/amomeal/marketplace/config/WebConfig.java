package com.amomeal.marketplace.config;

import com.amomeal.marketplace.attachment.AttachmentStorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Serves locally-stored attachment files at {@code /media/**}, mirroring Django's
 * {@code MEDIA_URL="/media/"} / {@code MEDIA_ROOT} being served directly by the
 * web server in its non-S3 branch (../../backend/marketplace/settings.py). Only
 * active when {@code app.storage.backend=local} — the default backend is real S3
 * (see com.amomeal.marketplace.attachment.service.S3AttachmentStorageService),
 * which serves files straight from the S3 bucket instead, needing no local
 * resource handler.
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.storage", name = "backend", havingValue = "local")
public class WebConfig implements WebMvcConfigurer {

    private final AttachmentStorageProperties storageProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String rootLocation = Path.of(storageProperties.localRoot()).toUri().toString();
        registry.addResourceHandler("/media/**").addResourceLocations(rootLocation);
    }
}
