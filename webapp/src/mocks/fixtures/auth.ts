// Realistic fixtures for the native-auth endpoints (ADR 0017), shared by the MSW
// handlers and available for per-story overrides. Shapes mirror the generated
// `src/api/types.gen.ts` views so stories render against the real component code
// paths. Date fields are typed through `Wire<…>` because that is what a response
// carries: ISO strings, never revived `Date` objects.

import type {
	AdminAccountView,
	CurrentUserView,
	ExportStatus,
	IdentityProviderView,
	IdentityView,
	SessionView,
} from "@/api/types.gen";
import type { Wire } from "@/lib/dates";

export const currentUser: CurrentUserView = {
	id: 42,
	username: "octocat",
	displayName: "The Octocat",
	primaryEmail: "octocat@example.com",
	avatarUrl: "https://avatars.githubusercontent.com/u/583231?v=4",
	profileUrl: "https://github.com/octocat",
	appRole: "APP_ADMIN",
	roles: ["ROLE_USER", "ROLE_ADMIN"],
	status: "ACTIVE",
	// Uppercase to match the server, which serializes GitProviderType.name() (GITHUB/GITLAB).
	identityProvider: "GITHUB",
	gitProviderId: "583231",
	hasGitLabIdentity: true,
	linkedProviders: [
		{ type: "GITHUB", serverUrl: "https://github.com" },
		{ type: "GITLAB", serverUrl: "https://gitlab.lrz.de" },
	],
	impersonating: false,
};

export const identityProviders: IdentityProviderView[] = [
	{
		registrationId: "github",
		displayName: "GitHub",
		providerType: "GITHUB",
		baseUrl: "https://github.com",
	},
	{
		registrationId: "gitlab",
		displayName: "GitLab",
		providerType: "GITLAB",
		baseUrl: "https://gitlab.lrz.de",
	},
];

export const linkedIdentities: Wire<IdentityView>[] = [
	{
		id: 1,
		providerType: "GITHUB",
		username: "octocat",
		serverUrl: "https://github.com",
		displayName: "The Octocat",
		subject: "583231",
		avatarUrl: "https://avatars.githubusercontent.com/u/583231?v=4",
		lastLoginAt: "2026-05-20T10:00:00Z",
	},
	{
		id: 2,
		providerType: "GITLAB",
		username: "octocat-lrz",
		serverUrl: "https://gitlab.lrz.de",
		displayName: "Octo (LRZ)",
		subject: "991122",
		lastLoginAt: "2026-04-02T08:30:00Z",
	},
];

export const sessions: Wire<SessionView>[] = [
	{
		jti: "sess-current-001",
		current: true,
		userAgent: "Chrome 124 on macOS",
		ip: "192.0.2.10",
		issuedAt: "2026-05-29T09:00:00Z",
		expiresAt: "2026-06-29T09:00:00Z",
	},
	{
		jti: "sess-other-002",
		current: false,
		userAgent: "Firefox 126 on Ubuntu",
		ip: "198.51.100.23",
		issuedAt: "2026-05-25T14:12:00Z",
		expiresAt: "2026-06-25T14:12:00Z",
	},
	{
		jti: "sess-other-003",
		current: false,
		userAgent: "Mobile Safari on iOS 18",
		ip: "203.0.113.77",
		issuedAt: "2026-05-21T07:45:00Z",
		expiresAt: "2026-06-21T07:45:00Z",
	},
];

export const adminUsers: AdminAccountView[] = [
	{
		id: 42,
		displayName: "The Octocat",
		primaryEmail: "octocat@example.com",
		appRole: "APP_ADMIN",
		status: "ACTIVE",
	},
	{
		id: 7,
		displayName: "Ada Lovelace",
		primaryEmail: "ada@example.com",
		appRole: "APP_USER",
		status: "ACTIVE",
	},
	{
		id: 99,
		displayName: "Suspended Sam",
		primaryEmail: "sam@example.com",
		appRole: "APP_USER",
		status: "SUSPENDED",
	},
];

// Sequential export-status snapshots: PENDING on first poll, READY afterwards so a
// story / test exercising the DangerZone export flow sees the state transition.
export const exportPending: Wire<ExportStatus> = {
	id: 9001,
	status: "PENDING",
	requestedAt: "2026-05-29T10:00:00Z",
};

export const exportReady: Wire<ExportStatus> = {
	id: 9001,
	status: "READY",
	requestedAt: "2026-05-29T10:00:00Z",
	completedAt: "2026-05-29T10:00:05Z",
	expiresAt: "2026-06-05T10:00:05Z",
};
