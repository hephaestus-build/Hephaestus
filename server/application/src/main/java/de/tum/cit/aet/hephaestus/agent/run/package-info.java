/**
 * The one run model for agent work. A run is the lease of one piece of agent work on one subject: who may drive it
 * now, under which epoch, and until when. Each kind's domain row (a review job, a Heph turn) points to its run and keeps
 * its own lifecycle. Claim, renewal, requeue, end and the reaper live here and nowhere else.
 */
@NullMarked
package de.tum.cit.aet.hephaestus.agent.run;

import org.jspecify.annotations.NullMarked;
