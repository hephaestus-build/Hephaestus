import { z } from "zod";

import {
	type DetailStackEntry,
	detailStackSchema,
} from "@/components/layout/detail-drawer/detail-stack";
import { ARTIFACT_KIND_VALUES } from "@/lib/artifact-kinds";

export const PRACTICE_SEARCH_PARAMS: ("focus" | "library")[] = ["focus", "library"];

export const practiceSearchSchema = z.object({
	focus: z.enum(ARTIFACT_KIND_VALUES).optional().catch(undefined),
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

/** Levels holding a draft, so their close must reach the URL before anything animates out. */
export const GUARDED_LEVEL_KINDS = ["practice-edit", "practice-new"] as const;

/** A stack id is always a slug; a practice that does not exist yet has none, so it is a draft. */
const NEW_PRACTICE_ID = "draft";

/** The editor level for `practiceSlug`, or for a practice about to be written. */
export function practiceFormLevel(
	practiceSlug?: string,
): DetailStackEntry<(typeof DETAIL_LEVEL_KINDS)[number]> {
	return practiceSlug === undefined
		? { kind: "practice-new", id: NEW_PRACTICE_ID }
		: { kind: "practice-edit", id: practiceSlug };
}

export const practiceSetupSearchSchema = practiceSearchSchema.extend(
	detailStackSchema(DETAIL_LEVEL_KINDS).shape,
);
