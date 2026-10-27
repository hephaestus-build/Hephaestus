package de.tum.cit.aet.hephaestus.core.runtime;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A component outside the adapters' {@code webhook} packages that the webhook role needs: HTTP security, the NATS
 * connection, logging, release identity, and the startup guards every role runs. {@link WebhookReceiverScope} admits
 * it, and the architecture tier holds it to the receiver's rules: it depends on no persistence type.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReceiverComponent {}
