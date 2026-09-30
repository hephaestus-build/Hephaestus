package de.tum.cit.aet.hephaestus.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The words a reader sees for a value of an open vocabulary — a signal, a source kind, a kind of work, an
 * integration — that no module in this build declares. A stored value can outlive the declaration that named
 * it, and the reader is owed a generic word rather than the identifier; the operator is owed one warning per
 * value rather than one per page view.
 */
public final class UnknownVocabulary {

    private static final Logger log = LoggerFactory.getLogger(UnknownVocabulary.class);

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private UnknownVocabulary() {}

    /**
     * @param vocabulary what the value is, for the log line: {@code "signal"}, {@code "source kind"}, …
     * @param value      the undeclared identifier; logged, never returned
     * @param words      what to show the reader instead
     */
    public static String label(String vocabulary, String value, String words) {
        if (WARNED.add(vocabulary + '\u0000' + value)) {
            log.warn(
                    "No display name declared for {} {}; showing a generic label",
                    vocabulary,
                    LoggingUtils.sanitizeForLog(value));
        }
        return words;
    }
}
