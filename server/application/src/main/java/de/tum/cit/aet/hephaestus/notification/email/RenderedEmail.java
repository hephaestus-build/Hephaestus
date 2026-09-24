package de.tum.cit.aet.hephaestus.notification.email;

/** The three parts every email carries: a subject, a plain-text body and an HTML body. */
public record RenderedEmail(String subject, String text, String html) {}
