import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { IdentityProviderView, IdentityView } from "@/api/types.gen";

import { LinkedAccountsSection } from "./LinkedAccountsSection";

const github: IdentityView = {
	id: 1,
	providerType: "GITHUB",
	serverUrl: "https://github.com",
	subject: "12345",
	username: "octocat",
	displayName: "The Octocat",
	lastLoginAt: new Date("2026-05-20T10:00:00Z"),
};

const gitlab: IdentityView = {
	id: 2,
	providerType: "GITLAB",
	serverUrl: "https://gitlab.com",
	subject: "67890",
	username: "tux",
	displayName: "Tux",
	lastLoginAt: new Date("2026-04-02T09:30:00Z"),
};

const outline: IdentityView = {
	id: 3,
	providerType: "OUTLINE",
	serverUrl: "https://outline.acme.test",
	subject: "o-1",
	username: "octocat",
	displayName: "Octo Docs",
};

const githubProvider: IdentityProviderView = {
	registrationId: "github",
	displayName: "GitHub",
	providerType: "GITHUB",
	baseUrl: "https://github.com",
};
const gitlabProvider: IdentityProviderView = {
	registrationId: "gitlab",
	displayName: "GitLab",
	providerType: "GITLAB",
	baseUrl: "https://gitlab.com",
};
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
	},
} satisfies Meta<typeof LinkedAccountsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Two connected providers — either can be disconnected (a confirmation dialog gates it). */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		const triggers = await canvas.findAllByRole("button", { name: /^disconnect /i });
		await expect(triggers).toHaveLength(2);
		const [firstTrigger] = triggers;
		if (!firstTrigger) throw new Error("No linked account rendered a disconnect control");
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
	},
	play: async ({ canvas }) => {
		canvas.getByText("Outline");
		await expect(canvas.queryByText("OUTLINE")).not.toBeInTheDocument();
		await expect(canvas.queryByText("GITHUB")).not.toBeInTheDocument();
		// Outline is already linked → no Outline connect CTA is offered.
		await expect(canvas.queryByRole("button", { name: /^Connect ACME Outline$/ })).toBeNull();
	},
};

/** Only one identity — disconnect is unavailable so the account can never lock itself out. */
export const SingleIdentity: Story = {
	args: { identities: [github], providers: [githubProvider] },
	play: async ({ args, canvas }) => {
		// The lockout reason is always-visible text (not a disabled button), so SR/keyboard users
		// can read why disconnect is unavailable.
		canvas.getByText(/only sign-in method/i);
		await expect(canvas.queryByRole("button", { name: /^disconnect /i })).toBeNull();
		await expect(args.onUnlink).not.toHaveBeenCalled();
	},
};

/** One identity plus another provider available to connect. */
export const WithLinkableProvider: Story = {
	args: { identities: [github] },
	play: async ({ canvas }) => {
		canvas.getByRole("button", { name: /connect gitlab/i });
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
		canvas.getByText("ACME Outline is not connected");
		canvas.getByText("Research Outline is not connected");

		await userEvent.click(canvas.getByRole("button", { name: "Connect Research Outline" }));
		await expect(args.onLink).toHaveBeenCalledWith("outline-research");
	},
};

/** A disconnect is in flight — that row shows a spinner and the button is disabled. */
export const Disconnecting: Story = {
	args: { unlinkingId: 1 },
};

/** No connected accounts yet — Empty state, with providers still offered to connect. */
export const Empty: Story = {
	args: { identities: [] },
	play: async ({ canvas }) => {
		canvas.getByText(/no connected accounts yet/i);
		canvas.getByRole("button", { name: /connect github/i });
	},
};

/** Loading the connected accounts. */
export const Loading: Story = {
	args: { isLoading: true },
};

/** Failed to load — the shared error alert, with a retry. */
export const ErrorState: Story = {
	args: {
		isError: true,
		error: { detail: "The identity service is unavailable." },
		identities: [],
		providers: [],
	},
	play: async ({ args, canvas }) => {
		canvas.getByText(/could not load connected accounts/i);
		await userEvent.click(canvas.getByRole("button", { name: /retry/i }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};

export const OrganizationalRealms: Story = {
	args: {
		identities: [
			{
				id: 4,
				providerType: "OIDC",
				serverUrl: "https://identity.example.com/realms/team",
				subject: "member-123",
				displayName: "Team member",
			},
		],
		providers: [
			{
				registrationId: "team",
				providerType: "OIDC",
				baseUrl: "https://identity.example.com/realms/team",
				displayName: "Team account",
			},
			{
				registrationId: "research",
				providerType: "OIDC",
				baseUrl: "https://identity.example.com/realms/research",
				displayName: "Research account",
			},
		],
	},
	play: async ({ args, canvas }) => {
		canvas.getByText("Organization account");
		canvas.getByText("https://identity.example.com/realms/team");
		await expect(canvas.queryByRole("button", { name: "Connect Team account" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Connect Research account" }));
		await expect(args.onLink).toHaveBeenCalledWith("research");
		await expect(canvas.queryByRole("button", { name: /^disconnect /i })).toBeNull();
	},
};

export const SignInAndContentIdentity: Story = {
	args: { identities: [github, outline], providers: [githubProvider, outlineProvider] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Disconnect The Octocat" })).toBeNull();
		canvas.getByRole("button", { name: "Disconnect Octo Docs" });
		canvas.getByText(/only sign-in method/i);
	},
};
