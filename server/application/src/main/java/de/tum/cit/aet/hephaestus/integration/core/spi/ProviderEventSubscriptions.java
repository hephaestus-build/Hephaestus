package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;

/**
 * Which workspaces receive a provider delivery. The receiver stores one event for each, in the transaction that stores
 * the delivery, so a workspace receives every delivery that arrives after its subscription commits.
 */
public interface ProviderEventSubscriptions {

    /** The workspaces whose subscription matches {@code subject}, in ascending id order; empty when none does. */
    List<Long> subscribers(String subject);
}
