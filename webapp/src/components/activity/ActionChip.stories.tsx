import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen } from "storybook/test";

import { withProvider, withStandardPage } from "@/stories/decorators";

import { ActionChips } from "./ActionChip";

/**
 * The leaderboard's badge language, kept: the provider's icon in its state colour and a neutral
 * number. `count` is the display of a total — a tile, a table cell, a legend — and `work` the
 * display of one pull request's row, where a merge or an approval reads by its label.
 */
const meta = {
	component: ActionChips,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		display: "count",
		actions: [
			{ kind: "PULL_REQUEST_MERGED", count: 3 },
			{ kind: "REVIEW_APPROVED", count: 11 },
			{ kind: "REVIEW_CHANGES_REQUESTED", count: 3 },
			{ kind: "REVIEW_COMMENTED", count: 4 },
			{ kind: "COMMENTED", count: 60 },
			{ kind: "CODE_COMMENTED", count: 82 },
			{ kind: "ISSUE_OPENED", count: 1 },
			{ kind: "ISSUE_CLOSED", count: 0 },
		],
	},
} satisfies Meta<typeof ActionChips>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		// A zero is not drawn; every chip that is says what it counts in words.
		await expect(canvas.queryByText(/issues? closed/u)).not.toBeInTheDocument();
		await expect(canvas.getByRole("img", { name: "82 comments on code" })).toBeVisible();
		await expect(canvas.getByRole("img", { name: "1 issue opened" })).toBeVisible();
		await userEvent.hover(canvas.getByText("11"));
		const tooltip = await screen.findByText("11 approvals", {
			selector: "[data-slot=tooltip-content]",
		});
		await expect(tooltip).toHaveAttribute("data-open");
	},
};

/** Where there is room — a tile, a chart's legend — each count says what it counts. */
export const Labelled: Story = {
	args: { display: "labelled" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("on code")).toBeVisible();
		await expect(canvas.getByText("changes requested")).toBeVisible();
		await expect(canvas.getByText("commented")).toBeVisible();
	},
};

/** One pull request's row: a lifecycle event by its label, a comment by its count. */
export const OnOneWork: Story = {
	args: {
		display: "work",
		actions: [
			{ kind: "PULL_REQUEST_MERGED", count: 1 },
			{ kind: "REVIEW_APPROVED", count: 2 },
			{ kind: "CODE_COMMENTED", count: 40 },
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Merged", { selector: "[aria-hidden=true]" })).toBeVisible();
		await expect(canvas.getByText("×2")).toBeVisible();
		await expect(canvas.getByRole("img", { name: "Approved 2 times" })).toBeVisible();
		await expect(canvas.getByRole("img", { name: "40 comments on code" })).toBeVisible();
	},
};

/** GitLab's own icons and Pajamas colours, and its words: a merge request, not a pull request. */
export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "3 merge requests merged" })).toBeVisible();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const DarkGitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	globals: { theme: "dark" },
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
