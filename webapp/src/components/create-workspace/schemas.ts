import { z } from "zod";

/** Step 1: the GitLab access token. */
export const connectionSchema = z.object({
	personalAccessToken: z
		.string()
		.transform((v) => v.trim())
		.pipe(z.string().min(1, "Enter an access token")),
});

/** Step 3: Workspace display name and slug. */
export const workspaceDetailsSchema = z.object({
	displayName: z
		.string()
		.transform((v) => v.trim())
		.pipe(z.string().min(1, "Enter a display name").max(120, "Use 120 characters or fewer")),
	workspaceSlug: z
		.string()
		.trim()
		.min(3, "Use at least 3 characters")
		.max(51, "Use 51 characters or fewer")
		.regex(/^[a-z0-9]/u, "Start with a lowercase letter or digit")
		.regex(/[a-z0-9]$/u, "End with a lowercase letter or digit")
		.regex(/^[a-z0-9-]+$/u, "Use only lowercase letters, digits, and hyphens")
		.refine((s) => !s.includes("--"), "Do not use two hyphens in a row"),
});
export type WorkspaceDetailsData = z.infer<typeof workspaceDetailsSchema>;
