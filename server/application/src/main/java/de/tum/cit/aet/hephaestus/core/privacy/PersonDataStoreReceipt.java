package de.tum.cit.aet.hephaestus.core.privacy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Operational facts only; the account key is erased separately when its administrator is erased. */
public record PersonDataStoreReceipt(
        long count, Instant completedAt, @Nullable Long administratorAccountId) {
    public static Map<String, PersonDataStoreReceipt> read(ObjectMapper mapper, String json) {
        return mapper.readValue(json, new TypeReference<Map<String, PersonDataStoreReceipt>>() {});
    }

    public static Map<String, Long> counts(ObjectMapper mapper, String json) {
        Map<String, Long> counts = new LinkedHashMap<>();
        read(mapper, json).forEach((store, receipt) -> counts.put(store, receipt.count()));
        return Map.copyOf(counts);
    }
}
