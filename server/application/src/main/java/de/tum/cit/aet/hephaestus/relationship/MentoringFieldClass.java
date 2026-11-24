package de.tum.cit.aet.hephaestus.relationship;

/** The field classes in the personal-data map. Each sink accepts a subset of these classes. */
enum MentoringFieldClass {
    CONTENT,
    PERSON_IDENTIFIER,
    CORRELATION_HANDLE,
    OPERATIONAL_CLASS;

    enum Sink {
        LOG,
        TRACE,
        METRIC_LABEL
    }

    void requireAllowed(Sink sink) {
        boolean allowed = switch (this) {
            case CONTENT, PERSON_IDENTIFIER -> false;
            case CORRELATION_HANDLE -> sink == Sink.LOG || sink == Sink.TRACE;
            case OPERATIONAL_CLASS -> true;
        };
        if (!allowed) throw new IllegalArgumentException("Field class " + name() + " is refused by " + sink);
    }
}
