/**
 * The mentor runtime's contract with its transports and evidence adapters: what a verified provider event admits, what
 * an effect may carry to a person, and how a transport proves an effect is still allowed before it renders and writes.
 * Every port is database only and denies by default when it is not wired.
 */
@NullMarked
@NamedInterface("spi")
package de.tum.cit.aet.hephaestus.mentor.spi;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
