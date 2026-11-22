import { z } from "zod";

import type { PracticeEditedIn } from "@/api/types.gen";

import {
	type DetailStackEntry,
	detailStackSchema,
} from "@/components/layout/detail-drawer/detail-stack";
import { ITEM_KIND_VALUES } from "@/lib/item-kinds";

export const PRACTICE_SEARCH_PARAMS: ("focus" | "library")[] = ["focus", "library"];

export const practiceSearchSchema = z.object({
	focus: z.enum(ITEM_KIND_VALUES).optional().catch(undefined),
	library: z.boolean().optional().catch(undefined),
});

/** The levels Practice setup can render. Anything else in the URL is dropped by the schema. */
export const DETAIL_LEVEL_KINDS = [
	"catalog-group",
	"catalog-practice",
	"practice",
	"practice-edit",
	"practice-new",
] as const;

type PracticeSetupLevelKind = (typeof DETAIL_LEVEL_KINDS)[number];

/**
 * What each level is called: the last crumb of its own path, and its title and the crumb it leaves
 * behind until its record has loaded.
 */
export const PRACTICE_SETUP_LEVEL_LABELS = {
	"catalog-group": "Catalog group",
	"catalog-practice": "Catalog practice",
	practice: "Practice",
	"practice-edit": "Edit practice",
	"practice-new": "Create practice",
} as const satisfies Record<PracticeSetupLevelKind, string>;

/** Levels holding a draft, so their close must reach the URL before anything animates out. */
export const GUARDED_LEVEL_KINDS = ["practice-edit", "practice-new"] as const;

/** A stack id is always a slug; a practice that does not exist yet has none, so it is a draft. */
const NEW_PRACTICE_ID = "draft";

/** A workspace practice, read-only: what a link from elsewhere in the console opens. */
export function practiceSetupLevel(practiceSlug: string): DetailStackEntry<PracticeSetupLevelKind> {
	return { kind: "practice", id: practiceSlug };
}

/** The editor level for `practiceSlug`, or for a practice about to be written. */
export function practiceFormLevel(practiceSlug?: string): DetailStackEntry<PracticeSetupLevelKind> {
	return practiceSlug === undefined
		? { kind: "practice-new", id: NEW_PRACTICE_ID }
		: { kind: "practice-edit", id: practiceSlug };
}

/**
 * Where a link to a practice in Practice setup lands. A practice edited in the practice repository has
 * no editor there: its read-only level says so and offers Edit in Hephaestus.
 */
export function practiceSetupLevelFor(
	practiceSlug: string,
	editedIn: PracticeEditedIn,
): DetailStackEntry<PracticeSetupLevelKind> {
	return editedIn === "REPOSITORY"
		? practiceSetupLevel(practiceSlug)
		: practiceFormLevel(practiceSlug);
}

export const practiceSetupSearchSchema = practiceSearchSchema.extend(
	detailStackSchema(DETAIL_LEVEL_KINDS).shape,
);
