import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { IdentityProviderView, IdentityView } from "@/api/types.gen";

import { LinkedAccountsSection } from "./LinkedAccountsSection";

const github: IdentityView = {
	id: 1,
	providerType: "GITHUB",
	subject: "12345",
	username: "octocat",
	displayName: "The Octocat",
	lastLoginAt: new Date("2026-05-20T10:00:00Z"),
};

const gitlab: IdentityView = {
	id: 2,
	providerType: "GITLAB",
	subject: "67890",
	username: "tux",
	displayName: "Tux",
	lastLoginAt: new Date("2026-04-02T09:30:00Z"),
};

const outline: IdentityView = {
	id: 3,
	providerType: "OUTLINE",
	subject: "o-1",
	username: "octocat",
	displayName: "Octo Docs",
};

const slack: IdentityView = {
	id: 4,
	providerType: "SLACK",
	subject: "U-1",
	displayName: "Octo in Slack",
};

const githubProvider: IdentityProviderView = {
	registrationId: "github",
	displayName: "GitHub",
	providerType: "GITHUB",
};
const gitlabProvider: IdentityProviderView = {
	registrationId: "gitlab",
	displayName: "GitLab",
	providerType: "GITLAB",
	baseUrl: "https://gitlab.com",
};
const lrzGitlabProvider: IdentityProviderView = {
	registrationId: "gitlab-lrz",
	displayName: "LRZ GitLab",
	providerType: "GITLAB",
	baseUrl: "https://gitlab.lrz.de",
};

// The deployments each identity above is linked to, as the current user reports them. GitHub's
// source origin is its API host; the account is matched to GitHub by type alone.
const githubLinked = { type: "GITHUB", serverUrl: "https://api.github.com" };
const gitlabLinked = { type: "GITLAB", serverUrl: "https://gitlab.com" };
const outlineLinked = { type: "OUTLINE", serverUrl: "https://outline.acme.test" };
// An instance can run SEVERAL Outline deployments (unique on type + base URL), so Outline is a LIST
// of providers, never a single `find(...)` match — each unconnected one gets its own CTA.
const outlineProvider: IdentityProviderView = {
	registrationId: "outline-acme",
	displayName: "ACME Outline",
	providerType: "OUTLINE",
	baseUrl: "https://outline.acme.test",
};
const secondOutlineProvider: IdentityProviderView = {
	registrationId: "outline-research",
	displayName: "Research Outline",
	providerType: "OUTLINE",
	baseUrl: "https://docs.research.test",
};

/**
 * Connected-accounts settings section (ADR 0017 native auth). Presentation-only: identities,
 * linkable providers, and the link/unlink callbacks are passed in. Shown here against static
 * fixtures so every state — including the last-identity lockout guard and an in-flight
 * disconnect — is reviewable in isolation.
 */
const meta = {
	component: LinkedAccountsSection,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	argTypes: {
		onLink: { description: "Start the sign-in/link flow for a provider" },
		onUnlink: { description: "Disconnect a linked identity by id" },
		unlinkingId: {
			control: "number",
			description: "Identity currently being disconnected (shows a spinner, blocks repeat clicks)",
		},
		isLoading: { control: "boolean" },
		isError: { control: "boolean" },
	},
	args: {
		onLink: fn(),
		onUnlink: fn(),
		onRetry: fn(),
		identities: [github, gitlab],
		providers: [githubProvider, gitlabProvider],
		linkedProviders: [githubLinked, gitlabLinked],
	},
} satisfies Meta<typeof LinkedAccountsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Two connected providers — either can be disconnected (a confirmation dialog gates it). */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		const triggers = await canvas.findAllByRole("button", { name: /^disconnect /iu });
		await expect(triggers).toHaveLength(2);
		const [firstTrigger] = triggers;
		if (!firstTrigger) {
			throw new Error("No linked account rendered a disconnect control");
		}
		await expect(firstTrigger).toBeEnabled();

		// Drive the confirm flow: open the dialog, then confirm. The dialog renders in a portal,
		// so query the whole document for its destructive "Disconnect" action.
		await userEvent.click(firstTrigger);
		const action = await screen.findByRole("button", { name: "Disconnect" });
		await userEvent.click(action);
		await expect(args.onUnlink).toHaveBeenCalledWith(1);
	},
};

/** Several identities, including a link-only Outline one — labels are human, never raw enums. */
export const MultipleIdentities: Story = {
	args: {
		identities: [github, gitlab, outline],
		providers: [githubProvider, gitlabProvider, outlineProvider],
		linkedProviders: [githubLinked, gitlabLinked, outlineLinked],
	},
	play: async ({ canvas }) => {
		canvas.getByText("Outline");
		// Two sign-in accounts: each of the three, the link-only one too, can be disconnected.
		await expect(canvas.getAllByRole("button", { name: /^Disconnect /u })).toHaveLength(3);
		await expect(canvas.queryByText(/only sign-in method/iu)).toBeNull();
		await expect(canvas.queryByText("OUTLINE")).not.toBeInTheDocument();
		await expect(canvas.queryByText("GITHUB")).not.toBeInTheDocument();
		// Outline is already linked → no Outline connect CTA is offered.
		await expect(canvas.queryByRole("button", { name: /^Connect ACME Outline$/u })).toBeNull();
	},
};

/** Only one sign-in identity — disconnect is unavailable so the account can never lock itself out. */
export const SingleIdentity: Story = {
	args: { identities: [github], providers: [githubProvider], linkedProviders: [githubLinked] },
	play: async ({ args, canvas }) => {
		// The lockout reason is always-visible text (not a disabled button), so SR/keyboard users
		// can read why disconnect is unavailable. No other sign-in account is offered to connect.
		canvas.getByText(
			"This is your only sign-in method. To remove it, delete your account in Danger zone.",
		);
		await expect(canvas.queryByRole("button", { name: /^disconnect /iu })).toBeNull();
		await expect(args.onUnlink).not.toHaveBeenCalled();
	},
};

/**
 * GitHub is the only way in, beside a link-only Slack and Outline. The link-only accounts are no
 * sign-in method, so they do not let GitHub go; each of them can still be disconnected.
 */
export const OnlySignInBesideLinkOnly: Story = {
	args: {
		identities: [github, slack, outline],
		providers: [githubProvider],
		linkedProviders: [githubLinked, outlineLinked],
	},
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText(/only sign-in method/iu)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Disconnect The Octocat" })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Disconnect Octo Docs" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Disconnect Octo in Slack" }));
		await userEvent.click(await screen.findByRole("button", { name: "Disconnect" }));
		await expect(args.onUnlink).toHaveBeenCalledWith(4);
	},
};

/** One sign-in account, and another one to connect: that is the way to let the first one go. */
export const WithLinkableProvider: Story = {
	args: { identities: [github], linkedProviders: [githubLinked] },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: /connect gitlab/iu });
		await expect(
			canvas.getByText(
				"This is your only sign-in method. To remove it, first connect another sign-in account below.",
			),
		).toBeVisible();
	},
};

/** An offered provider without a registration cannot unlock the last sign-in account. */
export const UnavailableSignInProvider: Story = {
	args: {
		identities: [github],
		linkedProviders: [githubLinked],
		providers: [githubProvider, { ...gitlabProvider, registrationId: "" }],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /connect gitlab/iu })).toBeDisabled();
		await expect(
			canvas.getByText(
				"This is your only sign-in method. To remove it, delete your account in Danger zone.",
			),
		).toBeVisible();
	},
};

/**
 * Outline connect CTA. Outline is configured but unlinked, so a dedicated row offers it — and with
 * TWO Outline instances configured, BOTH are offered, each named by its display name.
 */
export const OutlineConnectCTA: Story = {
	args: {
		identities: [github, gitlab],
		providers: [githubProvider, gitlabProvider, outlineProvider, secondOutlineProvider],
	},
	play: async ({ args, canvas }) => {
		// Every unconnected Outline instance gets its own CTA, named by its display name.
		canvas.getByText("Connect ACME Outline");
		canvas.getByText("Connect Research Outline");

		await userEvent.click(canvas.getByRole("button", { name: "Connect Research Outline" }));
		await expect(args.onLink).toHaveBeenCalledWith("outline-research");
	},
};

/**
 * Linked to GitLab.com, with an LRZ GitLab also configured: the account is linked to one GitLab
 * deployment, not to every one, so LRZ stays offered and GitLab.com does not.
 */
export const AnotherGitLabInstance: Story = {
	args: { providers: [githubProvider, gitlabProvider, lrzGitlabProvider] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Connect LRZ GitLab" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Connect GitLab" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Connect GitHub" })).toBeNull();
	},
};

/** One of two Outline deployments is connected: only the other one is offered. */
export const OneOutlineOfTwoConnected: Story = {
	args: {
		identities: [github, gitlab, outline],
		providers: [githubProvider, gitlabProvider, outlineProvider, secondOutlineProvider],
		linkedProviders: [githubLinked, gitlabLinked, outlineLinked],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Connect Research Outline")).toBeVisible();
		await expect(canvas.queryByText("Connect ACME Outline")).toBeNull();
	},
};

const schoolLogin: IdentityView = {
	id: 5,
	providerType: "OIDC",
	subject: "3f6c2a1e-9b1d-4f0e-a2c4-5d6e7f8a9b0c",
	// The server names an OIDC identity by its login provider: no mirror names it, and its subject is an opaque key.
	displayName: "School Login",
};
// Two realms on one host are two issuers, so the server sends each issuer with its path.
const schoolLoginProvider: IdentityProviderView = {
	registrationId: "school",
	displayName: "School Login",
	providerType: "OIDC",
	baseUrl: "https://login.school.test/realms/students",
};
const staffLoginProvider: IdentityProviderView = {
	registrationId: "school-staff",
	displayName: "Staff Login",
	providerType: "OIDC",
	baseUrl: "https://login.school.test/realms/staff",
};
const schoolLoginLinked = { type: "OIDC", serverUrl: "https://login.school.test/realms/students" };

/**
 * Connected through single sign-on and GitHub. It is a sign-in method, so GitHub can go; the other
 * realm on the same host is another issuer and stays offered.
 */
export const SingleSignOnBesideGitHub: Story = {
	args: {
		identities: [github, schoolLogin],
		providers: [githubProvider, schoolLoginProvider, staffLoginProvider],
		linkedProviders: [githubLinked, schoolLoginLinked],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Disconnect The Octocat" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Disconnect School Login" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Connect Staff Login" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Connect School Login" })).toBeNull();
	},
};

/** An OIDC identity whose provider was removed still never shows its subject: the row names the provider type. */
export const SingleSignOnWithoutItsProvider: Story = {
	args: {
		identities: [github, { id: 5, providerType: "OIDC", subject: schoolLogin.subject }],
		providers: [githubProvider],
		linkedProviders: [githubLinked, schoolLoginLinked],
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(schoolLogin.subject)).toBeNull();
		await expect(canvas.getByRole("button", { name: "Disconnect Single sign-on" })).toBeVisible();
	},
};

/** Single sign-on is the only way in, so it cannot be disconnected. */
export const OnlySingleSignOn: Story = {
	args: {
		identities: [schoolLogin],
		providers: [schoolLoginProvider],
		linkedProviders: [schoolLoginLinked],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/only sign-in method/iu)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Disconnect School Login" })).toBeNull();
	},
};

/** A disconnect is in flight — that row shows a spinner and the button is disabled. */
export const Disconnecting: Story = {
	args: { unlinkingId: 1 },
};

/** No connected accounts yet — Empty state, with providers still offered to connect. */
export const Empty: Story = {
	args: { identities: [], linkedProviders: [] },
	play: async ({ canvas }) => {
		canvas.getByText(/no connected accounts yet/iu);
		canvas.getByRole("button", { name: /connect github/iu });
	},
};

/** The rows hold their place while the accounts load, and nothing claims there are none. */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Loading connected accounts…" })).toBeVisible();
		await expect(canvas.queryByText(/no connected accounts yet/iu)).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

/** Failed to load — the shared error alert, with a retry. */
export const ErrorState: Story = {
	args: {
		isError: true,
		error: { detail: "The identity service is unavailable." },
		identities: [],
		providers: [],
		linkedProviders: [],
	},
	play: async ({ args, canvas }) => {
		canvas.getByText(/we could not load your connected accounts/iu);
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};
