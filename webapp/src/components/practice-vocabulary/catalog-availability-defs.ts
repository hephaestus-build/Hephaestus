import { Check, CircleAlert, CirclePlus } from "lucide-react";

import type { CatalogPracticeSummary } from "@/api/types.gen";

import type { StatusDef, StatusDefs } from "@/components/common/status-def";

export type CatalogAvailability = CatalogPracticeSummary["availability"];

export interface CatalogAvailabilityDef extends StatusDef {
	/**
	 * What activating the row does, as a verb phrase. A second grammatical form is a registry field
	 * rather than a lower-cased `label` at the call site, because "Added" and "already added" are
	 * different words, not different casing.
	 */
	action: string;
	/**
	 * Whether the value earns a chip. The ordinary case is the majority of a library and carries no
	 * badge, so the two exceptions are the only colour in the list.
	 */
	badged: boolean;
}

export const CATALOG_AVAILABILITY_DEFS: StatusDefs<CatalogAvailability> &
	Record<CatalogAvailability, CatalogAvailabilityDef> = {
	AVAILABLE: {
		label: "Available",
		icon: CirclePlus,
		badgeVariant: "secondary",
		description: "This workspace can add its own copy.",
		action: "review before adding",
		badged: false,
	},
	ADOPTED: {
		label: "Added",
		icon: Check,
		badgeVariant: "secondary",
		description: "This workspace already owns a copy.",
		action: "open the workspace copy",
		badged: true,
	},
	SLUG_CONFLICT: {
		label: "Name unavailable",
		icon: CircleAlert,
		badgeVariant: "outline",
		description: "Another workspace practice already uses this identifier.",
		action: "see why it cannot be added",
		badged: true,
	},
};
