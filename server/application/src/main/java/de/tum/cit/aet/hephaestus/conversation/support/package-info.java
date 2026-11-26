/**
 * The support share: a person shares one conversation turn with one instance admin (ADR 0125 decision 9, ADR 0164).
 * It holds no copy of the person's words. It names the turn and the digest of the text that the person confirmed, and
 * every read renders the turn from its current rows. A deleted, erased or changed message is then never shown.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.conversation.support;
