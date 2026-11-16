import { CircleHelpIcon, type LucideIcon } from "lucide-react";

import type { BrandIcon } from "@/components/icons/brand";

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
	/** A provider's own mark where the value names a provider: GitHub, GitLab, Slack, Hephaestus. */
	icon: LucideIcon | BrandIcon;
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
 * rendering as a blank cell in production. An open enum, which the API types as a plain string,
 * keeps its registry's literal keys with `satisfies StatusDefs<string>`, and
 * `open-enum-registries.test.ts` holds those keys to the values that the API names.
 */
export type StatusDefs<TValue extends string> = Record<TValue, StatusDef>;

export const UNKNOWN_STATUS_DEF: StatusDef = {
	label: "Unknown",
	icon: CircleHelpIcon,
	badgeVariant: "outline",
	description: "This version of Hephaestus does not recognize this value.",
};

export function definitionFor(defs: Readonly<Record<string, StatusDef>>, value: string): StatusDef;
export function definitionFor<T, TFallback>(
	defs: Readonly<Record<string, T>>,
	value: string,
	fallback: TFallback,
): T | TFallback;
/**
 * A newer server can send a value that this build's generated union does not yet contain. A fallback
 * given as `undefined` stays `undefined`; only a call without one falls back to Unknown.
 */
export function definitionFor<T, TFallback>(
	defs: Readonly<Record<string, T>>,
	value: string,
	...rest: [] | [fallback: TFallback]
): T | TFallback | StatusDef {
	const fallback = rest.length === 1 ? rest[0] : UNKNOWN_STATUS_DEF;
	const known = Object.hasOwn(defs, value) ? defs[value] : undefined;
	return known ?? fallback;
}

/**
 * Whether an open value, which the API types as a plain string, is one that this build's registry
 * names. A newer server can send another value; a surface then shows its fallback instead.
 */
export function isKnownValue<TKey extends string>(
	defs: Readonly<Record<TKey, unknown>>,
	value: string,
): value is TKey {
	return Object.hasOwn(defs, value);
}

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
 * is a programming error, and the throw below is what backs that type up at runtime. It takes any
 * record keyed by the values, so a route's `z.enum` can read a non-badge registry too.
 */
export function statusValues<TValue extends string>(
	defs: Record<TValue, unknown>,
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
