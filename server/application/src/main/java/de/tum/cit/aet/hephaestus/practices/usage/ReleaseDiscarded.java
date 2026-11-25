package de.tum.cit.aet.hephaestus.practices.usage;

/**
 * The page went off, or the settings changed, while a release ran: its transaction rolls back and stores nothing,
 * and the next run counts again (ADR 0153).
 */
final class ReleaseDiscarded extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ReleaseDiscarded() {
        super("The settings of Hephaestus in numbers changed while the release ran", null, false, false);
    }
}
