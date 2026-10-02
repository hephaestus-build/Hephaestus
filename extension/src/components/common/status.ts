import { CircleHelpIcon } from "lucide-react";

import type { StatusDef } from "@/components/common/status-def";

export type { BadgeVariant, StatusDef } from "@/components/common/status-def";

function isKnown<TValue extends string>(
	defs: Record<TValue, unknown>,
	value: string,
): value is TValue {
	return Object.hasOwn(defs, value);
}

/**
 * A registry lookup that survives a value this build has never heard of. The registries are total
 * over the generated unions, but a newer server can send a newer value; it renders as itself,
 * visibly unknown, rather than as a blank badge or a crash.
 */
export function statusDefOr<TValue extends string>(
	defs: Record<TValue, StatusDef>,
	value: string,
): StatusDef {
	if (isKnown(defs, value)) {
		return defs[value];
	}
	return {
		label: `Unknown (${value})`,
		icon: CircleHelpIcon,
		badgeVariant: "outline",
		description: "This version of the extension does not know this state yet.",
	};
}
