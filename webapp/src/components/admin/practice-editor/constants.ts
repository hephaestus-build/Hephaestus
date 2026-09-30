import type { FilterOption } from "@/components/common/FilterToggle";
import {
	ARTIFACT_KIND,
	artifactKindLabel,
	type ArtifactKindId,
	type KnownArtifactKind,
} from "@/lib/artifact-kinds";

export type WorkArtifact = ArtifactKindId;

const WORK_ARTIFACT_HINTS: Record<string, string> = {
	[ARTIFACT_KIND.pullRequest]: "Reviews work submitted in a pull or merge request",
	[ARTIFACT_KIND.issue]: "Reviews work described or discussed in an issue",
	[ARTIFACT_KIND.conversationThread]: "Reviews messages in a conversation thread",
	[ARTIFACT_KIND.document]: "Reviews the writing in a published document",
};

/** Undefined for a kind this build has never heard of, which is still offered under its own name. */
export function workArtifactHint(kind: string): string | undefined {
	return WORK_ARTIFACT_HINTS[kind];
}

export const WORK_ARTIFACT_FILTER_OPTIONS = [
	{ value: ARTIFACT_KIND.pullRequest, label: artifactKindLabel(ARTIFACT_KIND.pullRequest, 2) },
	{ value: ARTIFACT_KIND.issue, label: artifactKindLabel(ARTIFACT_KIND.issue, 2) },
	{
		value: ARTIFACT_KIND.conversationThread,
		label: artifactKindLabel(ARTIFACT_KIND.conversationThread, 2),
	},
	{ value: ARTIFACT_KIND.document, label: artifactKindLabel(ARTIFACT_KIND.document, 2) },
] as const;

/**
 * The same list with the "no filter" choice at its head. "ALL" is a value rather than an absent one
 * because a Base UI `Select` must hold one, and it is the sentinel the URL already carries.
 */
export const WORK_ARTIFACT_FILTER_ITEMS: {
	value: ArtifactKindId;
	label: string;
}[] = [{ value: "ALL", label: "All work" }, ...WORK_ARTIFACT_FILTER_OPTIONS];

/**
 * The same list for {@link FilterToggle}. "All" is shortened on screen to fit the row, so the full
 * name is restored for a screen reader (WCAG 2.2 SC 2.5.3).
 */
export const WORK_TYPE_FILTER_OPTIONS: FilterOption<"ALL" | KnownArtifactKind>[] = [
	{ value: "ALL", label: "All work", shortLabel: "All", srSuffix: "work" },
	...WORK_ARTIFACT_FILTER_OPTIONS,
];

export function generateSlug(name: string): string {
	return name
		.toLowerCase()
		.trim()
		.replaceAll(/[^a-z0-9]+/gu, "-")
		.replaceAll(/^-+|-+$/gu, "")
		.slice(0, 64);
}

export function isValidSlug(slug: string): boolean {
	return /^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(slug) && slug.length >= 3 && slug.length <= 64;
}
