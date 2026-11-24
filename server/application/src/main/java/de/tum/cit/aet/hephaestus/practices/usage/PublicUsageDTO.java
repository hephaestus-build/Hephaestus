package de.tum.cit.aet.hephaestus.practices.usage;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Every released half-year of the public usage page, newest first (ADR 0153). */
public record PublicUsageDTO(@NonNull List<PublicUsagePeriodDTO> periods) {}
