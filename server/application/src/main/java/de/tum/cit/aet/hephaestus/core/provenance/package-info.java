/**
 * Signed provenance records for text that Hephaestus generates (EU AI Act Art. 50(2): marked in a machine-readable
 * format and detectable as artificially generated). Each record is a compact JWS, signed with an Ed25519 key that the
 * system key seals. The public keys are published at {@code /.well-known/hephaestus-provenance-keys.json}, so anyone
 * can verify a record without this instance.
 *
 * <p>Other modules see only the types annotated {@code @NamedInterface("provenance")}: the signer, the verifier and
 * the record. The keys stay internal, so only the start-up bootstrap here can make one.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.provenance;
