package de.tum.cit.aet.hephaestus.core.runtime.hub;

import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.MentorSessionEvent;

/** Includes the authenticated connection identity, not an identity supplied in the payload. */
public record WorkerMentorSessionEvent(WorkerSession worker, MentorSessionEvent frame) {}
