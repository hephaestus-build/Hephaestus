/**
 * Email transport and rendering. {@code EmailGateway} is the only class that talks SMTP; everything
 * else builds an {@code EmailMessage} and reads an {@code EmailDeliveryResult}.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.notification.email;
