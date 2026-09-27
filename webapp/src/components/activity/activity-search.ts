import { z } from "zod";

import {
	type DetailStackEntry,
	detailStackSchema,
	parseDetailStack,
} from "@/components/layout/detail-drawer/detail-stack";

import { ACTIVITY_CATEGORIES, type ActivityCategory } from "./activity-kind-defs";
import { ACTIVITY_RANGES, DEFAULT_ACTIVITY_RANGE } from "./activity-range";

/** Your Activity opens one category of your own activity: `activity:reviews`. */
export const SELF_ACTIVITY_LEVEL_KINDS = ["activity"] as const;

/**
 * Workspace activity also opens a member: `member:ada`. A category level belongs to the member level
 * beneath it — `[member:ada, activity:reviews]` is Ada's reviews — and without one to the page's scope,
 * the workspace or a team.
 */
export const WORKSPACE_ACTIVITY_LEVEL_KINDS = ["activity", "member"] as const;

export type ActivityLevel = DetailStackEntry<(typeof WORKSPACE_ACTIVITY_LEVEL_KINDS)[number]>;

export function categoryLevel(category: ActivityCategory): ActivityLevel {
	return { kind: "activity", id: category };
}

export function memberLevel(login: string): ActivityLevel {
	return { kind: "member", id: login };
}

type ActivityLevelTarget =
	| {
			kind: "activity";
			category: ActivityCategory;
			/** Whose activity the level lists; without one, the page's. */
			member?: string;
	  }
	| { kind: "member"; login: string };

/** A level of the stack, with what it shows. */
export type ActivityStackEntry = ActivityLevel & { target: ActivityLevelTarget };

/**
 * The levels the URL opens on a page with these kinds, up to the first a hand-edited URL could not
 * have reached: a category the page does not know, a second member, or a second category level for
 * the same owner. A level past that point would open a drawer with nothing true to fill it.
 */
export function parseActivityStack(
	raw: string[] | undefined,
	kinds: readonly ActivityLevel["kind"][],
): ActivityStackEntry[] {
	const entries: ActivityStackEntry[] = [];
	const owners = new Set<string>();
	let member: string | undefined;
	for (const level of parseDetailStack(raw, kinds)) {
		const target = levelTarget(level, member);
		const owner = level.kind === "member" ? "member" : `activity:${member ?? ""}`;
		if (target === undefined || owners.has(owner)) {
			break;
		}
		owners.add(owner);
		if (target.kind === "member") {
			member = target.login;
		}
		entries.push({ ...level, target });
	}
	return entries;
}

function levelTarget(
	level: ActivityLevel,
	member: string | undefined,
): ActivityLevelTarget | undefined {
	if (level.kind === "member") {
		return { kind: "member", login: level.id };
	}
	return isActivityCategory(level.id)
		? { kind: "activity", category: level.id, member }
		: undefined;
}

function isActivityCategory(value: string): value is ActivityCategory {
	return (ACTIVITY_CATEGORIES as readonly string[]).includes(value);
}

const rangeSchema = z
	.enum(ACTIVITY_RANGES)
	.default(DEFAULT_ACTIVITY_RANGE)
	.catch(DEFAULT_ACTIVITY_RANGE);

const activityFilterSchema = z.object({ range: rangeSchema });

export const ACTIVITY_SEARCH_DEFAULTS = activityFilterSchema.parse({});

export const activitySearchSchema = activityFilterSchema.extend(
	detailStackSchema(SELF_ACTIVITY_LEVEL_KINDS).shape,
);

export type ActivitySearch = z.infer<typeof activitySearchSchema>;

/**
 * The team whose activity workspace activity shows: its members, in the repositories and labels its
 * team settings name. No team is the whole workspace.
 */
const workspaceActivityFilterSchema = z.object({
	range: rangeSchema,
	team: z.coerce.number().int().positive().optional().catch(undefined),
});

export const WORKSPACE_ACTIVITY_SEARCH_DEFAULTS = workspaceActivityFilterSchema.parse({});

export const workspaceActivitySearchSchema = workspaceActivityFilterSchema.extend(
	detailStackSchema(WORKSPACE_ACTIVITY_LEVEL_KINDS).shape,
);

export type WorkspaceActivitySearch = z.infer<typeof workspaceActivitySearchSchema>;
