@org.springframework.modulith.ApplicationModule(
        displayName = "Product Feedback",
        allowedDependencies = {
            "core",
            "core::action",
            "core::auth-reference",
            "core::auth-spi",
            "core::current-account",
            "core::exception",
            "core::person-data",
            "core::runtime",
            "core::web",
            "integration.scm::spi",
            "workspace::context",
            "workspace::spi",
        })
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.productfeedback;
