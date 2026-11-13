package de.tum.cit.aet.hephaestus.testconfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Names the two integration proofs required by the closed SECURITY_RETENTION inventory. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SecurityRetentionDeletionProof {
    String table();

    String resolution();

    String adminRelease();
}
