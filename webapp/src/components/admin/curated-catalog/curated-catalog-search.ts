import { z } from "zod";

import {
	type DetailStackEntry,
	detailStackSchema,
} from "@/components/core/detail-drawer/detail-stack";
import { ARTIFACT_KIND_VALUES } from "@/lib/artifact-kinds";

export const CURATED_CATALOG_SEARCH_PARAMS: (keyof CuratedCatalogSearch)[] = [
	"q",
	"status",
	"artifact",
	"review",
];

/** The levels the instance catalog can render. Anything else in the URL is dropped by the schema. */
export const CURATED_LEVEL_KINDS = [
	"practice-edit",
	"practice-new",
	"group-edit",
	"group-new",
] as const;

export type CuratedLevelKind = (typeof CURATED_LEVEL_KINDS)[number];

/** Every level here is an editor, so every one holds a draft — see `GUARDED_LEVEL_KINDS`. */
export const GUARDED_CURATED_LEVEL_KINDS = CURATED_LEVEL_KINDS;

/** A stack id is always a slug; a practice or group that does not exist yet has none. */
const NEW_ENTRY_ID = "draft";

export function curatedPracticeLevel(slug?: string): DetailStackEntry<CuratedLevelKind> {
	return slug === undefined
		? { kind: "practice-new", id: NEW_ENTRY_ID }
		: { kind: "practice-edit", id: slug };
}

export function curatedGroupLevel(slug?: string): DetailStackEntry<CuratedLevelKind> {
	return slug === undefined
		? { kind: "group-new", id: NEW_ENTRY_ID }
		: { kind: "group-edit", id: slug };
}

const curatedCatalogFilterSchema = z.object({
	q: z.string().max(200).optional().catch(undefined),
	status: z.enum(["OFFERED", "NOT_OFFERED"]).optional().catch(undefined),
	artifact: z.enum(ARTIFACT_KIND_VALUES).optional().catch(undefined),
	review: z.literal(true).optional().catch(undefined),
});

export const curatedCatalogSearchSchema = curatedCatalogFilterSchema.extend(
	detailStackSchema(CURATED_LEVEL_KINDS).shape,
);

export type CuratedCatalogSearch = z.infer<typeof curatedCatalogFilterSchema>;
