import { z } from "zod";

/** Step 1: the GitLab access token. */
export const connectionSchema = z.object({
	personalAccessToken: z
		.string()
		.transform((v) => v.trim())
		.pipe(z.string().min(1, "Personal access token is required")),
});

/** Step 3: Workspace display name and slug. */
export const workspaceDetailsSchema = z.object({
	displayName: z
		.string()
		.transform((v) => v.trim())
		.pipe(z.string().min(1, "Display name is required").max(120, "Display name is too long")),
	workspaceSlug: z
		.string()
		.trim()
		.min(3, "Must be at least 3 characters")
		.max(51, "Must be at most 51 characters")
		.regex(/^[a-z0-9]/u, "Must start with a lowercase letter or digit")
		.regex(/[a-z0-9]$/u, "Must end with a lowercase letter or digit")
		.regex(/^[a-z0-9-]+$/u, "Only lowercase letters, digits, and hyphens allowed")
		.refine((s) => !s.includes("--"), "No consecutive hyphens allowed"),
});
export type WorkspaceDetailsData = z.infer<typeof workspaceDetailsSchema>;
