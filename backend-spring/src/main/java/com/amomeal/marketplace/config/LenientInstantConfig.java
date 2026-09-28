package com.amomeal.marketplace.config;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * pydantic's {@code datetime} accepts ISO strings with or without an offset; FE-admin's
 * {@code <input type="datetime-local">} sends naive values ("2026-01-01T10:00") to every voucher endpoint,
 * and Django reads them as UTC (TIME_ZONE=UTC). Jackson's stock {@code Instant} deserializer rejects
 * those (found in the first FE-admin end-to-end run: PATCH /api/vouchers/{uid} failed). This module makes
 * every request-body {@code Instant} tolerant in the same way as {@code AdminService.parseDateTime};
 * strictly-valid inputs (with "Z"/offset) parse exactly as before.
 */
@Configuration
public class LenientInstantConfig {

    @Bean
    public JacksonModule lenientInstantModule() {
        SimpleModule module = new SimpleModule("lenient-instant");
        module.addDeserializer(Instant.class, new ValueDeserializer<Instant>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) {
                return parse(p.getString(), ctxt);
            }
        });
        return module;
    }

    static Instant parse(String value, DeserializationContext ctxt) {
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(v.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return (Instant) ctxt.handleWeirdStringValue(Instant.class, value, "Input should be a valid datetime");
        }
    }
}
