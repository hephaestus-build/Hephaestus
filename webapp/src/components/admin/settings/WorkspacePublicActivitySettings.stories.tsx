import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { expectUnavailable } from "@/test/controls";

import { WorkspacePublicActivitySettings } from "./WorkspacePublicActivitySettings";

const meta = {
	component: WorkspacePublicActivitySettings,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceName: "Hephaestus",
		providerType: "GITHUB",
		address: "https://hephaestus.build/w/hephaestus",
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
	},
} satisfies Meta<typeof WorkspacePublicActivitySettings>;

export default meta;
type Story = StoryObj<typeof meta>;

const PAGE = "Publish the page";

/** The confirmation, once it has arrived: a dialog fades in, and what is inside it reads as transparent until then. */
async function confirmation() {
	const dialog = await screen.findByRole("alertdialog");
	await expectSettledVisible(dialog);
	return within(dialog);
}

const live = {
	status: "ready",
	enabled: true,
	allowSearchEngines: false,
	live: true,
	hiddenPeople: 3,
	pending: undefined,
} as const;

/** Turning the page on asks first, and says what becomes public. */
export const Off: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.queryByRole("switch", { name: "Allow search engines" })).toBeNull();
		await userEvent.click(canvas.getByRole("switch", { name: PAGE }));
		const dialog = await confirmation();
		await expect(
			dialog.getByRole("heading", { name: "Make the activity of Hephaestus public?" }),
		).toBeVisible();
		await expect(dialog.getByText(/pull requests each person opened and reviewed/u)).toBeVisible();
		await expect(args.onEnabledChange).not.toHaveBeenCalled();
		await userEvent.click(dialog.getByRole("button", { name: "Make public" }));
		await expect(args.onEnabledChange).toHaveBeenCalledWith(true);
	},
};

export const ConfirmationCancelled: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: PAGE }));
		const dialog = await confirmation();
		await userEvent.click(dialog.getByRole("button", { name: "Cancel" }));
		await expect(args.onEnabledChange).not.toHaveBeenCalled();
		await expect(canvas.getByRole("switch", { name: PAGE })).not.toBeChecked();
	},
};

/** The page is on and public: its address, the search engines' choice, and how many people are left out. */
export const On: Story = {
	args: { state: live },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("switch", { name: PAGE })).toBeChecked();
		await expect(
			canvas.getByText("On. Anyone can see it at https://hephaestus.build/w/hephaestus."),
		).toBeVisible();
		const hidden = canvas.getByText("Hidden people").closest("li");
		await expect(hidden).toHaveTextContent("3");
		await expect(canvas.getByRole("switch", { name: "Allow search engines" })).not.toBeChecked();
		await userEvent.click(canvas.getByRole("switch", { name: "Allow search engines" }));
		await expect(args.onSearchEnginesChange).toHaveBeenCalledWith(true);
		// Turning the page off is a change of mind, not a decision to confirm.
		await userEvent.click(canvas.getByRole("switch", { name: PAGE }));
		await expect(args.onEnabledChange).toHaveBeenCalledWith(false);
		await expect(screen.queryByRole("alertdialog")).toBeNull();
	},
};

export const SearchEnginesAllowed: Story = {
	args: { state: { ...live, allowSearchEngines: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("switch", { name: "Allow search engines" })).toBeChecked();
	},
};

/** The workspace turned it on, but the instance does not allow public pages, so nothing is public. */
export const InstanceDoesNotAllow: Story = {
	args: { state: { ...live, live: false } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/An instance administrator has not allowed/u)).toBeVisible();
	},
};

/** Where the count did not load, the row is left out rather than guessed. */
export const HiddenCountUnavailable: Story = {
	args: { state: { ...live, hiddenPeople: undefined } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Hidden people")).toBeNull();
	},
};

export const NobodyHidden: Story = {
	args: { state: { ...live, hiddenPeople: 0 } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hidden people").closest("li")).toHaveTextContent("0");
	},
};

/** While a change saves, its switch waits and says so. */
export const Saving: Story = {
	args: { state: { ...live, pending: "enabled" } },
	play: async ({ canvas }) => {
		await expectUnavailable(canvas.getByRole("switch", { name: PAGE }));
		await expect(canvas.getByRole("switch", { name: "Allow search engines" })).toBeEnabled();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("switch")).toBeNull();
	},
};

export const Failed: Story = {
	args: {
		state: { status: "error", error: new Error("Network down"), onRetry: fn() },
	},
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(
			args.state.status === "error" ? args.state.onRetry : undefined,
		).toHaveBeenCalledOnce();
	},
};

/** A GitLab workspace reads merge requests and projects. */
export const GitLab: Story = {
	args: { providerType: "GITLAB" },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: PAGE }));
		const dialog = await confirmation();
		await expect(dialog.getByText(/the public projects of this workspace/u)).toBeVisible();
		await expect(dialog.getByText(/merge requests each person opened/u)).toBeVisible();
	},
};

export const Dark: Story = {
	args: { state: live },
	globals: { theme: "dark" },
};

export const Reflow: Story = {
	args: { state: live },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
