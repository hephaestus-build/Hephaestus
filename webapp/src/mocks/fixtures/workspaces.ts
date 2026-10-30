import type { WorkspaceListItem, WorkspaceMembership } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";

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
		workspaceAddress: `https://hephaestus.build/w/${workspaceSlug}`,
		displayName: workspaceSlug,
		accountLogin: workspaceSlug,
		providerType: "GITHUB",
		createdAt: new Date("2026-01-01T00:00:00Z"),
		status: "ACTIVE",
		practicesEnabled: false,
		publishesPublicActivity: false,
		...overrides,
	};
}

export function ownMembership(
	role: WorkspaceMembership["role"] = "MEMBER",
	overrides: Partial<Wire<WorkspaceMembership>> = {},
): Wire<WorkspaceMembership> {
	return {
		account: {
			id: "42",
			status: "ACTIVE",
			displayName: "The Octocat",
			avatarUrl: null,
			identities: [
				{ provider: "GITHUB", login: "octocat", displayName: "The Octocat", avatarUrl: null },
			],
		},
		role,
		joinedAt: "2026-01-01T00:00:00Z",
		leftAt: null,
		hidden: false,
		reviewSelected: true,
		eligibleForPracticeReview: true,
		publicActivityAnswered: null,
		...overrides,
	};
}
