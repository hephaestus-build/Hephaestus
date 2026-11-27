package de.tum.cit.aet.hephaestus.mentor.spi;

/** Admission of verified provider events into the mentor runtime. Database only, in the transport's receive transaction:
 * no provider read, no network, no model. */
public interface MentorIngress {
    /** The producer of new events on this route. The transport persists it on its own event row. */
    Producer route(Binding binding, Audience audience);

    /** Admits one event in the caller's transaction. */
    Admission admit(IngressEnvelope envelope);
}
