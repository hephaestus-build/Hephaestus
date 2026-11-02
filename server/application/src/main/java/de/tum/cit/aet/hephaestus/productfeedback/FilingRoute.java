package de.tum.cit.aet.hephaestus.productfeedback;

/** Where a report goes. Code decides it through {@link RoutingGate}; the model only supplies inputs. */
enum FilingRoute {
    PUBLIC,
    PRIVATE,
    EMAIL
}
