import type { AccountRef, TeamInfo, WorkspaceMembership } from "@/api/types.gen";

function team(id: number, name: string, logins: string[]): TeamInfo {
	return {
		id,
		name,
		slug: `team-${id}`,
		hidden: false,
		membershipCount: logins.length,
		repoPermissionCount: 0,
		repositories: [],
		labels: [],
		members: logins.map((login, index) => ({
			id: id * 100 + index,
			login,
			name: login,
			avatarUrl: "",
			htmlUrl: `https://github.com/${login}`,
		})),
	};
}

export function memberAccount(id: string, overrides: Partial<AccountRef> = {}): AccountRef {
	return {
		id,
		status: "ACTIVE",
		displayName: null,
		avatarUrl: null,
		identities: [],
		...overrides,
	};
}

export function membership(
	account: AccountRef,
	overrides: Partial<Omit<WorkspaceMembership, "account">> = {},
): WorkspaceMembership {
	return {
		account,
		role: "MEMBER",
		joinedAt: new Date("2026-03-02T09:00:00Z"),
		leftAt: null,
		hidden: false,
		reviewSelected: true,
		eligibleForPracticeReview: true,
		publicActivityAnswered: null,
		...overrides,
	};
}

export const multiProviderMember = membership(
	memberAccount("11", {
		displayName: "Alice Johnson",
		identities: [
			{ provider: "GITHUB", login: "alice", displayName: "Alice Johnson", avatarUrl: null },
			{ provider: "GITLAB", login: "ajohnson", displayName: "Alice J.", avatarUrl: null },
		],
	}),
	{ role: "ADMIN" },
);

export const unclaimedMember = membership(
	memberAccount("12", {
		status: "UNCLAIMED",
		identities: [{ provider: "GITHUB", login: "bob", displayName: "Bob Smith", avatarUrl: null }],
	}),
);

export const ownerMember = membership(
	memberAccount("13", {
		displayName: "Charlie Brown",
		identities: [
			{ provider: "GITHUB", login: "charlie", displayName: "Charlie Brown", avatarUrl: null },
		],
	}),
	{ role: "OWNER", joinedAt: new Date("2025-11-20T09:00:00Z") },
);

export const hiddenMember = membership(
	memberAccount("14", {
		displayName: "Diana Prince",
		identities: [{ provider: "GITHUB", login: "diana", displayName: null, avatarUrl: null }],
	}),
	{ hidden: true, reviewSelected: false, eligibleForPracticeReview: false },
);

export const mockMemberships: WorkspaceMembership[] = [
	multiProviderMember,
	unclaimedMember,
	ownerMember,
	hiddenMember,
];

export const mockTeams: TeamInfo[] = [
	team(1, "Frontend Team", ["alice", "diana"]),
	team(2, "Backend Team", ["bob", "charlie"]),
	team(3, "DevOps Team", ["ajohnson"]),
];
