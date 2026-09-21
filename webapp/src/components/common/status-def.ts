import type { LucideIcon } from "lucide-react";

import type { FacetOption } from "@/components/common/FacetMultiSelect";

/**
 * One entry in a status registry: everything a surface needs to render one value of one enum.
 *
 * `icon` is required and must be unique within its own enum. Badge variants collapse — several
 * values share one — so the icon is the only channel left when colour is unavailable
 * (WCAG 2.2 SC 1.4.1). `label` is operator-facing words, never the wire constant.
 */
export interface StatusDef {
	label: string;
	icon: LucideIcon;
	badgeVariant: BadgeVariant;
	description: string;
}

export type BadgeVariant =
	| "default"
	| "secondary"
	| "destructive"
	| "outline"
	| "success"
	| "warning";

/**
 * A total registry over an enum. `Record` rather than `Partial<Record>` on purpose: a value the
 * server adds fails `typecheck:webapp` on the registry that has no words for it, instead of
 * rendering as a blank cell in production.
 */
export type StatusDefs<TValue extends string> = Record<TValue, StatusDef>;

/** Icon colour matching a badge variant, for the places an icon appears outside a badge. */
const TONE_CLASS: Record<BadgeVariant, string> = {
	default: "text-primary",
	secondary: "text-muted-foreground",
	destructive: "text-destructive",
	outline: "text-muted-foreground",
	success: "text-success",
	warning: "text-warning",
};

export function statusToneClass(variant: BadgeVariant): string {
	return TONE_CLASS[variant];
}

/**
 * The registry's values in declaration order, typed as a non-empty tuple: a registry with no values
 * is a programming error, and the throw below is what backs that type up at runtime.
 */
export function statusValues<TValue extends string>(
	defs: StatusDefs<TValue>,
): [TValue, ...TValue[]] {
	const isValue = (key: string): key is TValue => Object.hasOwn(defs, key);
	const [first, ...rest] = Object.keys(defs).filter(isValue);
	if (first === undefined) {
		throw new Error("A status registry must define at least one value.");
	}
	return [first, ...rest];
}

/** Every value as a facet option, in registry order. A facet wanting fewer filters the result. */
export function statusFacetOptions<TValue extends string>(
	defs: StatusDefs<TValue>,
): FacetOption<TValue>[] {
	return statusValues(defs).map((value) => {
		const def = defs[value];
		return {
			value,
			label: def.label,
			icon: def.icon,
			iconClassName: statusToneClass(def.badgeVariant),
		};
	});
}
