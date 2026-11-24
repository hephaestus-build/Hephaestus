package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * The kinds of record that depend on a source and must follow a {@link CascadeCause}: derived records, references
 * outside Hephaestus, and copies in memory or in a sandbox (ADR 0128 decision 3). Each constant has one cell for each
 * cause in {@code CascadeTableTest}. A producer of a new kind adds its constant here and its cells there, in one change.
 * A kind that lives in a person-data store names that store there too, so a new store must say which kinds it holds.
 */
public enum CascadeDependent {
    /**
     * An entry that holds words of a source in a conversation: a summary that covers the source, a
     * {@code search_conversation} result that quotes it, or a tool result that read a person's data. It includes one
     * that a running turn stores after the cause.
     */
    CONVERSATION_COPY(Home.STORE),

    /** The turn that answers a source question and has not ended. */
    ANSWERING_TURN(Home.STORE),

    /** The partial answer of a running attempt, which each server keeps in memory and serves while the turn runs. */
    PARTIAL_ANSWER(Home.MEMORY),

    /** A Hephaestus answer or digest that can repeat the words of a source that its turn read. */
    DERIVED_ANSWER(Home.STORE),

    /** A Slack reply or digest of an entry that Hephaestus has not posted yet. */
    SLACK_POST(Home.STORE),

    /** A Slack reply or digest that Hephaestus has posted: a copy outside Hephaestus. */
    POSTED_SLACK_MESSAGE(Home.OUTSIDE),

    /** A conversation sandbox of the workspace: a physical copy of conversations outside PostgreSQL. */
    SANDBOX(Home.OUTSIDE),

    /** The prompts and responses that the configured model provider received for a turn. */
    MODEL_PROVIDER_COPY(Home.OUTSIDE),

    /** A database or filesystem backup that holds the conversation. */
    BACKUP(Home.OUTSIDE),

    /** A ready account export: the bundle of the account's conversation messages that waits for its download. */
    EXPORT(Home.STORE),

    /**
     * A practice result about a source's work, with the feedback based on it and the scan-report members that name it.
     */
    PRACTICE_RESULT(Home.STORE),

    /** A record whose {@code basis_edge} references cite the source, or cite a record that is stale. */
    CITING_RECORD(Home.STORE);

    /** Where the dependent lives. */
    public enum Home {
        /** Rows of a store in the person-data map. */
        STORE,
        /** The memory of each server. */
        MEMORY,
        /** Outside PostgreSQL: a provider or a sandbox. */
        OUTSIDE
    }

    private final Home home;

    CascadeDependent(Home home) {
        this.home = home;
    }

    public Home home() {
        return home;
    }
}
