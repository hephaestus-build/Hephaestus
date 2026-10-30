import { z } from "zod";

/** Step 1: the GitLab access token. */
export const connectionSchema = z.object({
	personalAccessToken: z
		.string()
		.transform((v) => v.trim())
		.pipe(z.string().min(1, "Enter an access token")),
});

/**
 * What a typed slug means: lowercase, with spaces and underscores as hyphens, so "My Team" and
 * "Team_A" are accepted. The field keeps what was typed until the reader leaves it, so the caret
 * never jumps; the checks below and the request read this form.
 */
export function normalizeTypedSlug(typed: string): string {
	return typed
		.trim()
		.toLowerCase()
		.replaceAll(/[\s_]+/gu, "-");
}

/**
 * Step 3: Workspace display name and slug. The form shows the first problem with each field, so the
 * checks go from the most general to the most specific.
 */
export const workspaceDetailsSchema = z.object({
	displayName: z
		.string()
		.transform((v) => v.trim())
		.pipe(z.string().min(1, "Enter a display name").max(120, "Use 120 characters or fewer")),
	workspaceSlug: z
		.string()
		.transform(normalizeTypedSlug)
		.pipe(
			z
				.string()
				.min(3, "Enter a URL slug of 3 to 51 characters: a to z, 0 to 9, or hyphens")
				.max(51, "Use 51 characters or fewer")
				.regex(/^[a-z0-9-]+$/u, "Use only lowercase letters, digits, and hyphens")
				.regex(/^[a-z0-9]/u, "Start with a lowercase letter or digit")
				.regex(/[a-z0-9]$/u, "End with a lowercase letter or digit")
				.refine((s) => !s.includes("--"), "Do not use two hyphens in a row"),
		),
});
export type WorkspaceDetailsData = z.infer<typeof workspaceDetailsSchema>;
