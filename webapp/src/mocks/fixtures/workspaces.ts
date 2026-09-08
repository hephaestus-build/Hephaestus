import type { WorkspaceListItem } from "@/api/types.gen";

/**
 * One entry of `GET /workspaces`. `createdAt` is a real `Date` — the shape the generated response
 * transformer produces — so the same value can seed the query cache directly and, through
 * `HttpResponse.json`, serialise to the ISO string a response carries.
 */
export function workspaceListItem(
	workspaceSlug: string,
	overrides: Partial<WorkspaceListItem> = {},
): WorkspaceListItem {
	return {
		id: 1,
		workspaceSlug,
		displayName: workspaceSlug,
		accountLogin: workspaceSlug,
		providerType: "GITHUB",
		createdAt: new Date("2026-01-01T00:00:00Z"),
		status: "ACTIVE",
		leaderboardEnabled: false,
		leaguesEnabled: false,
		mentorEnabled: false,
		practicesEnabled: false,
		progressionEnabled: false,
		...overrides,
	};
}
