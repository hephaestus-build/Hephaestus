/**
 * Push notifications to the native app: devices registered by a signed-in app installation, and a
 * durable per-device outbox sent through the Expo push service. A push notification is a nudge that
 * practice feedback is waiting, never the feedback itself; it carries no feedback text and opening it
 * delivers nothing. See ADR 0045 and {@code docs/admin/mobile-app.mdx}.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.notification.push;
