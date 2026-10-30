import type { Meta, StoryObj } from "@storybook/react";
import { HttpResponse, http } from "msw";
import { expect, fn, within } from "storybook/test";

import { ownMembership } from "@/mocks/fixtures/workspaces";
import { withStandardPage } from "@/stories/decorators";

import { WorkspaceSettingsPage, type WorkspaceSettingsPageProps } from "./WorkspaceSettingsPage";

const membershipRead = [
	http.get("*/workspaces/:workspaceSlug/members/me", () =>
		HttpResponse.json(ownMembership("OWNER")),
	),
];

const publicActivity = {
	workspaceName: "AET",
	providerType: "GITHUB",
	address: "https://hephaestus.build/w/ase",
	state: {
		status: "ready",
		enabled: false,
		allowSearchEngines: false,
		live: false,
		hiddenPeople: undefined,
		pending: undefined,
	},
	onEnabledChange: fn(),
	onSearchEnginesChange: fn(),
} satisfies WorkspaceSettingsPageProps["publicActivity"];

const hiddenPeople = {
	state: {
		status: "ready",
		people: [{ id: 11, login: "ada", name: "Ada Lovelace", avatarUrl: "" }],
	},
	restoring: undefined,
	onShowAgain: fn(),
} satisfies WorkspaceSettingsPageProps["hiddenPeople"];

const meta = {
	component: WorkspaceSettingsPage,
	parameters: {
		// One MSW worker answers a whole Docs page, so each story gets its own frame until MSW goes.
		docs: { story: { inline: false, height: "600px" } },
		layout: "fullscreen",
		msw: { handlers: membershipRead },
		chromatic: { viewports: [320, 1440] },
	},
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "ase",
		practicesEnabled: true,
		publicActivity,
		hiddenPeople,
	},
} satisfies Meta<typeof WorkspaceSettingsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// Activity and Hephaestus have no switch here: this card only says where each is decided.
		const capabilities = within(canvas.getByRole("region", { name: "Capabilities" }));
		await expect(capabilities.queryByRole("switch")).not.toBeInTheDocument();
		await expect(canvas.getByRole("switch", { name: "Publish the page" })).not.toBeChecked();
		await expect(canvas.getByRole("link", { name: /AI models/u })).toHaveAttribute(
			"href",
			"/w/ase/admin/models",
		);
		await canvas.findByRole("button", { name: /^delete workspace$/iu });
	},
};

/**
 * The hide dialog sends an admin to Hidden people here, and the Activity capability links to it, so
 * the way back from hiding someone has one place and one name on this page.
 */
export const HiddenPeople: Story = {
	play: async ({ canvas }) => {
		const capabilities = within(canvas.getByRole("region", { name: "Capabilities" }));
		await expect(capabilities.getByRole("link", { name: "Hidden people" })).toHaveAttribute(
			"href",
			"/w/ase/admin/settings#hidden-people-heading",
		);
		const hidden = within(canvas.getByRole("region", { name: "Hidden people" }));
		await expect(hidden.getByText("Ada Lovelace")).toBeVisible();
		await expect(hidden.getByRole("button", { name: "Show again" })).toBeEnabled();
	},
};

export const PracticeReviewsOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		const capabilities = within(canvas.getByRole("region", { name: "Capabilities" }));
		await expect(capabilities.getByText(/^Off\./u)).toBeVisible();
	},
};
