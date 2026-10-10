import type { Meta, StoryObj } from "@storybook/react";
import { HttpResponse, http } from "msw";
import { expect, fn, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import { WorkspaceSettingsPage, type WorkspaceSettingsPageProps } from "./WorkspaceSettingsPage";

const membershipRead = [
	http.get("*/workspaces/:workspaceSlug/members/me", () =>
		HttpResponse.json({ role: "OWNER", userLogin: "ada" }),
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
		hiddenContributors: [],
		restoring: undefined,
		pending: undefined,
	},
	onEnabledChange: fn(),
	onSearchEnginesChange: fn(),
	onShowAgain: fn(),
} satisfies WorkspaceSettingsPageProps["publicActivity"];

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
	},
} satisfies Meta<typeof WorkspaceSettingsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// Activity and Heph have no switch here: this card only says where each is decided.
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

export const PracticeReviewsOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		const capabilities = within(canvas.getByRole("region", { name: "Capabilities" }));
		await expect(capabilities.getByText(/^Off\./u)).toBeVisible();
	},
};
