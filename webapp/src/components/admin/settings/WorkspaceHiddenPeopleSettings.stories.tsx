import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { WorkspaceHiddenPeopleSettings } from "./WorkspaceHiddenPeopleSettings";

const ADA = { id: 11, login: "ada", name: "Ada Lovelace", avatarUrl: "" };
const BOB = { id: 12, login: "bob", name: "Bob Brenner", avatarUrl: "" };

const meta = {
	component: WorkspaceHiddenPeopleSettings,
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "ase",
		state: { status: "ready", people: [ADA, BOB] },
		restoring: undefined,
		onShowAgain: fn(),
	},
} satisfies Meta<typeof WorkspaceHiddenPeopleSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A person an admin hid with no membership is shown again here. Members and teams are shown again
 * on their own pages, which the section names and links.
 */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const section = within(canvas.getByRole("region", { name: "Hidden people" }));
		await expect(section.getAllByRole("listitem")).toHaveLength(2);
		await expect(section.getByRole("link", { name: "Members" })).toHaveAttribute(
			"href",
			"/w/ase/admin/members",
		);
		await expect(section.getByRole("link", { name: "Team setup" })).toHaveAttribute(
			"href",
			"/w/ase/admin/teams",
		);
		const [ada] = section.getAllByRole("button", { name: "Show again" });
		await userEvent.click(ada ?? section.getByRole("list"));
		await expect(args.onShowAgain).toHaveBeenCalledWith(11);
	},
};

/** The section stays where the hide dialog sends an admin, and says that nobody is in it. */
export const NobodyHidden: Story = {
	args: { state: { status: "ready", people: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Hidden people" })).toBeVisible();
		await expect(canvas.getByText("No person without a membership is hidden.")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Show again" })).toBeNull();
	},
};

export const ShowingAgain: Story = {
	args: { state: { status: "ready", people: [ADA] }, restoring: 11 },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Show again" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

/** While the hidden people are read, nothing claims nobody is hidden. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Hidden people" })).toBeVisible();
		await expect(canvas.queryByText("No person without a membership is hidden.")).toBeNull();
		await expect(canvas.queryByRole("alert")).toBeNull();
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load hidden people")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /Retry/u }));
		await expect(
			args.state.status === "error" ? args.state.onRetry : undefined,
		).toHaveBeenCalledOnce();
	},
};

/** A refused read shows nobody and no retry that would be refused again. */
export const Refused: Story = {
	args: { state: { status: "error", error: { status: 403 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You do not have access to hidden people")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /Retry/u })).toBeNull();
		await expect(canvas.queryByRole("button", { name: "Show again" })).toBeNull();
	},
};

/** A failed refresh keeps the people last read, and their Show again, with a notice and Retry. */
export const RefreshFailed: Story = {
	args: {
		state: {
			status: "ready",
			people: [ADA],
			refreshFailure: { error: { status: 503 }, onRetry: fn(), pending: false },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hidden people may be out of date")).toBeVisible();
		await expect(canvas.getByRole("button", { name: /Retry/u })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: "Show again" })).toBeEnabled();
	},
};

export const Retrying: Story = {
	args: {
		state: {
			status: "ready",
			people: [ADA],
			refreshFailure: { error: { status: 503 }, onRetry: fn(), pending: true },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
		await expect(canvas.getByText("Ada Lovelace")).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
