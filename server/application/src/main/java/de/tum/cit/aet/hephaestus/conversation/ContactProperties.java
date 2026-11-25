package de.tum.cit.aet.hephaestus.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * When Hephaestus may reach a person (ADR 0139). Each workspace has its own switch, which can only narrow this one.
 *
 * @param startedMessagesEnabled Lets Hephaestus send check-ins and updates of its own on this instance. When it is
 *     off, Hephaestus sends neither, in any workspace. Answers, results that people asked for and the weekly digest
 *     still arrive.
 */
@ConfigurationProperties(prefix = "hephaestus.contact")
public record ContactProperties(@DefaultValue("true") boolean startedMessagesEnabled) {}
