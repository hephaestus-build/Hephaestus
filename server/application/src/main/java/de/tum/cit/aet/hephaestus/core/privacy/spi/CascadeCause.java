package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * The causes that remove a source or narrow who may read it (ADR 0128 decision 3). Each {@link CascadeDependent} has one
 * cell for each cause in {@code CascadeTableTest}: the action that the cause takes on that kind, with the test that
 * proves it. A new cause adds its constant here and one cell for each dependent kind, in one change.
 */
public enum CascadeCause {
    /**
     * A conversation entry loses its words and keeps its row: the developer deletes the message, or its retention ends.
     * Both call {@code ConversationRedaction}. A Slack edit is not a removal: the message stays with new words. The
     * edit runs the {@link CascadeDependent#CONVERSATION_COPY} cascade on the earlier words
     * ({@code ConversationRedaction.eraseCopiesOf}) and changes no other dependent.
     */
    REMOVAL,

    /**
     * A source of the workspace is erased: a Slack disconnect or a channel erasure, an Outline disconnect or a
     * collection removal, an SCM mirror erasure, or a repository removal that leaves the workspace nothing of it. Each
     * changes what the workspace's turns may read. A Slack disconnect also erases every entry written in Slack and every
     * tool result that could have copied a message.
     */
    SOURCE_ERASURE,

    /**
     * A person-data erasure. It deletes the person's own conversations. It redacts the copies of the person's data in
     * other people's conversations: the tool results that read it, and the feedback that Hephaestus showed about the
     * person.
     */
    ERASURE,

    /**
     * What the turns of a workspace may read narrows while the source stays: a stopped repository keeps its indexed
     * work, a channel's consent pauses, or the person's AI choice changes.
     */
    NARROWING
}
