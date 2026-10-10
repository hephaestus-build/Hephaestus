import { z } from "zod";

import {
	type DetailStackEntry,
	detailStackSchema,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";
import { toDayParam } from "@/lib/date-range-search";
import { multiValue } from "@/lib/search-params";

import { ACTIVITY_CATEGORIES, type ActivityCategory } from "./activity-kind-defs";
import { ACTIVITY_PRESETS, DEFAULT_ACTIVITY_PRESET } from "./activity-period";

/** Your Activity opens one category of your own activity: `activity:reviews`. */
export const SELF_ACTIVITY_LEVEL_KINDS = ["activity"] as const;

/**
 * Workspace activity opens a person by login, `person:ada`, and one category of that person's
 * activity over it: `[person:ada, activity:reviews]` is Ada's reviews.
 */
export const WORKSPACE_ACTIVITY_LEVEL_KINDS = ["person", "activity"] as const;

export type ActivityLevel = DetailStackEntry<(typeof WORKSPACE_ACTIVITY_LEVEL_KINDS)[number]>;

export function categoryLevel(category: ActivityCategory): ActivityLevel {
	return { kind: "activity", id: category };
}

export function personLevel(login: string): ActivityLevel {
	return { kind: "person", id: login };
}

type ActivityLevelTarget =
	| {
			kind: "activity";
			category: ActivityCategory;
			/** Whose activity the level lists; without one, the page's. */
			person?: string;
	  }
	| { kind: "person"; login: string };

/** A level of the stack, with what it shows. */
export type ActivityStackEntry = ActivityLevel & { target: ActivityLevelTarget };

/**
 * The levels the URL opens on a page with these kinds, up to the first a hand-edited URL could not
 * have reached: a category the page does not know, a second person, a second category level for
 * the same owner, or a category with no owner on a page that opens people. A level past that point
 * would open a drawer with nothing true to fill it.
 */
export function parseActivityStack(
	raw: string[] | undefined,
	kinds: readonly ActivityLevel["kind"][],
): ActivityStackEntry[] {
	const entries: ActivityStackEntry[] = [];
	const owners = new Set<string>();
	let person: string | undefined;
	for (const level of parseDetailStack(raw, kinds)) {
		const target = levelTarget(level, person);
		const owner = level.kind === "person" ? "person" : `activity:${person ?? ""}`;
		const ownerless =
			target?.kind === "activity" && person === undefined && kinds.includes("person");
		if (target === undefined || ownerless || owners.has(owner)) {
			break;
		}
		owners.add(owner);
		if (target.kind === "person") {
			person = target.login;
		}
		entries.push({ ...level, target });
	}
	return entries;
}

function levelTarget(
	level: ActivityLevel,
	person: string | undefined,
): ActivityLevelTarget | undefined {
	if (level.kind === "person") {
		return { kind: "person", login: level.id };
	}
	return isActivityCategory(level.id)
		? { kind: "activity", category: level.id, person }
		: undefined;
}

function isActivityCategory(value: string): value is ActivityCategory {
	return (ACTIVITY_CATEGORIES as readonly string[]).includes(value);
}

/**
 * A day of a custom range, from the first year any provider history could have to today. A day out
 * of that span drops, so the preset counts instead: a future day has nothing to count, and a day
 * centuries back would only draw a hundred thousand empty weeks.
 */
const customDay = z.iso
	.date()
	.refine((day) => day >= "2000-01-01" && day <= toDayParam(new Date()))
	.optional()
	.catch(undefined);

/**
 * The period every activity page counts: a preset, `?range=1y`, or the days of a custom range,
 * `?from=2026-01-01&to=2026-03-31`, which win over the preset when both are valid.
 */
const periodSearchSchema = z.object({
	range: z.enum(ACTIVITY_PRESETS).default(DEFAULT_ACTIVITY_PRESET).catch(DEFAULT_ACTIVITY_PRESET),
	from: customDay,
	to: customDay,
});

/** The keys that follow the reader to the other activity page, and to another workspace. */
export const PERIOD_SEARCH_KEYS = ["range", "from", "to"] as const;

export const ACTIVITY_SEARCH_DEFAULTS = periodSearchSchema.parse({});

export const activitySearchSchema = periodSearchSchema.extend(
	detailStackSchema(SELF_ACTIVITY_LEVEL_KINDS).shape,
);

export type ActivitySearch = z.infer<typeof activitySearchSchema>;

/** The columns the people table sorts by, as the URL names them. */
export const PEOPLE_SORTS = [
	"contributions",
	"pull-requests",
	"reviews",
	"issues",
	"active-weeks",
	"name",
] as const;

export type PeopleSort = (typeof PEOPLE_SORTS)[number];

/**
 * Workspace activity's scope and order, by the names a reader can read: a team by its slug, each
 * repository by its full path, `?team=core&repo=acme/api&sort=reviews`.
 */
const workspaceActivityFilterSchema = periodSearchSchema.extend({
	// The default parser reads `team=2024` as a number; a slug is text either way.
	team: z.coerce.string().min(1).optional().catch(undefined),
	repo: multiValue,
	sort: z.enum(PEOPLE_SORTS).default("contributions").catch("contributions"),
	dir: z.enum(["asc", "desc"]).default("desc").catch("desc"),
});

export const WORKSPACE_ACTIVITY_SEARCH_DEFAULTS = workspaceActivityFilterSchema.parse({});

export const workspaceActivitySearchSchema = workspaceActivityFilterSchema.extend(
	detailStackSchema(WORKSPACE_ACTIVITY_LEVEL_KINDS).shape,
);

export type WorkspaceActivitySearch = z.infer<typeof workspaceActivitySearchSchema>;
