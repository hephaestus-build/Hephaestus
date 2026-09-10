import type { GitHubAccessTarget } from "@/api/types.gen";
import { minutesBefore } from "@/components/common/story-clock";

export const githubTarget: GitHubAccessTarget = {
	id: 1,
	connectionId: 20,
	organization: "example-org",
	installationId: 500,
	organizationId: 1000,
	scopeId: 0,
	source: "DIRECTORY",
	status: "ACTIVE",
	paused: false,
	authorityHeld: true,
	authorized: true,
	configurationVersion: 2,
	draftGroupIds: ["engineering"],
	approvedGroupIds: ["engineering"],
	lastConfirmedAt: minutesBefore(2),
	members: [
		{
			githubUserId: 61,
			accountId: 5,
			displayName: "Morgan Lee",
			githubLogin: "morgan",
			enrolled: true,
			managed: true,
			manualException: false,
			revocationRequested: false,
			externalState: "PENDING",
			confirmedAt: minutesBefore(2),
		},
	],
	actions: [],
	preview: {
		capturedAt: minutesBefore(2),
		eligiblePeople: 2,
		awaitingIdentity: 1,
		unlinkedInvitations: 1,
		inventory: [{ githubUserId: 61, login: "morgan", state: "PENDING" }],
	},
};
