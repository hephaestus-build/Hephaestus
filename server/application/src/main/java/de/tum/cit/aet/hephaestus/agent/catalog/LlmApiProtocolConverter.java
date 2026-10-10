package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jspecify.annotations.Nullable;

/**
 * Maps an {@link LlmApiProtocol} onto the {@code api_protocol} column as its {@link
 * LlmApiProtocol#wire()} value. Auto-applied, so no connection column can persist the constant name.
 * A value that no constant names fails the read instead of reaching the proxy.
 */
@Converter(autoApply = true)
public class LlmApiProtocolConverter implements AttributeConverter<LlmApiProtocol, String> {

    @Override
    public @Nullable String convertToDatabaseColumn(@Nullable LlmApiProtocol attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public @Nullable LlmApiProtocol convertToEntityAttribute(@Nullable String dbData) {
        return dbData == null
                ? null
                : LlmApiProtocol.parse(dbData)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown api_protocol: " + dbData));
    }
}
