package com.minigoogle.cluster.placement;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.Instant;

/**
 * The project's shared {@link ObjectMapper} carries no {@code java.time}
 * support (no {@code jackson-datatype-jsr310} dependency), so
 * {@link IngestedDocument#crawlTime()} needs its own (de)serializer, the same
 * ISO-8601 string shape {@code CrawledDocumentStore} already uses for the
 * on-disk record.
 *
 * <p>{@link #mapper(ObjectMapper)} returns an independent copy of the given
 * mapper with that support added, leaving the original untouched so other
 * cluster JSON traffic is unaffected.
 */
final class IngestJson {

    private IngestJson() {
    }

    static ObjectMapper mapper(ObjectMapper base) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Instant.parse(p.getValueAsString());
            }
        });
        return base.copy().registerModule(module);
    }
}
