import type { PracticeDefinition } from "@/api/types.gen";

/** A role a practice's result is about, or null when the practice is about the work itself. */
export type AboutRole = NonNullable<PracticeDefinition["about"]>;

const ABOUT_ROLE_LABELS = new Map<string, string>(
	Object.entries({
		AUTHOR: "Author",
		ASSIGNEE: "Assignee",
		REVIEWER: "Reviewer",
		MERGER: "Whoever merged it",
	}),
);

export function aboutLabel(about: AboutRole | null): string {
	return about === null ? "The work" : (ABOUT_ROLE_LABELS.get(about) ?? "Other role");
}
