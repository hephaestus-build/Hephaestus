package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import java.util.List;
import java.util.stream.Stream;

/**
 * The Outline lifecycle events Hephaestus subscribes to upstream. Single source of truth for the
 * subscription registrar; document events drive a targeted refresh of the named document, collection
 * events refresh the mirrored-collection catalog (or tombstone on delete).
 */
public final class OutlineWebhookEvents {

    static final String DOCUMENT_PREFIX = "documents.";
    static final String COLLECTION_PREFIX = "collections.";

    static boolean hasHandler(String event) {
        return event.startsWith(DOCUMENT_PREFIX) || event.startsWith(COLLECTION_PREFIX);
    }

    /** Dotted document event names as Outline emits and subscribes them. */
    public static final List<String> DOCUMENT_EVENTS = List.of(
            "documents.create",
            "documents.publish",
            "documents.unpublish",
            "documents.update",
            "documents.archive",
            "documents.unarchive",
            "documents.delete",
            "documents.move");

    /** Collection lifecycle events: renames/recolors refresh the catalog, deletes tombstone the mirror. */
    public static final List<String> COLLECTION_EVENTS =
            List.of("collections.create", "collections.update", "collections.delete");

    /** Everything the registrar subscribes upstream. */
    public static final List<String> SUBSCRIBED_EVENTS =
            Stream.concat(DOCUMENT_EVENTS.stream(), COLLECTION_EVENTS.stream()).toList();

    private OutlineWebhookEvents() {}
}
