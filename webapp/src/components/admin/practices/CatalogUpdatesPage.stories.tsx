import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";

import { CatalogUpdatesPage } from "./CatalogUpdatesPage";
import { releaseBase, releaseProposal } from "./fixtures";

// The comparison of one update is `PracticeReleaseReview.stories.tsx`; this file pins the page
// around it: its name and way back in every state, and the list of waiting updates.

const proposal = releaseProposal();
const renamed = { ...releaseBase, name: "Short commits" };
const second = releaseProposal({
	practiceSlug: "small-commits",
	etag: "second-offer",
	base: renamed,
	current: renamed,
	offered: { ...renamed, name: "Small commits" },
	fields: [{ field: "NAME", changedLocally: false, offeredChanged: true, conflict: false }],
});

const meta = {
	component: CatalogUpdatesPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "acme",
		state: { status: "ready", proposals: [proposal, second] },
		pending: null,
		onAccept: fn(),
		onDecline: fn(),
	},
} satisfies Meta<typeof CatalogUpdatesPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Every state keeps the page's name, which its tab and the Practice setup button also carry. */
async function expectHeaderAndWayBack(canvas: StoryContext["canvas"]) {
	await expect(canvas.getByRole("heading", { name: "Catalog updates", level: 1 })).toBeVisible();
	await expect(canvas.getByRole("button", { name: "Back to practice setup" })).toHaveAttribute(
		"href",
		"/w/acme/admin/practices",
	);
}

export const Waiting: Story = {
	play: async ({ canvas, args }) => {
		await expectHeaderAndWayBack(canvas);
		const list = within(canvas.getByRole("navigation", { name: "Pending catalog updates" }));
		await expect(list.getByRole("button", { name: /Explain the change/u })).toHaveAttribute(
			"aria-current",
			"true",
		);
		await userEvent.click(list.getByRole("button", { name: /Small commits.*1 changed field/u }));
		await expect(list.getByRole("button", { name: /Small commits/u })).toHaveAttribute(
			"aria-current",
			"true",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Decline this update" }));
		await expect(args.onDecline).toHaveBeenCalledWith(second);
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", proposals: [] } },
	play: async ({ canvas }) => {
		await expectHeaderAndWayBack(canvas);
		await expect(canvas.getByText("No updates to review")).toBeVisible();
		await expect(canvas.queryByRole("navigation", { name: "Pending catalog updates" })).toBeNull();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expectHeaderAndWayBack(canvas);
		await expect(canvas.queryByRole("navigation", { name: "Pending catalog updates" })).toBeNull();
		await expect(canvas.queryByText("No updates to review")).toBeNull();
	},
};

const retry = fn();

export const LoadFailed: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 503, detail: "The catalog is not reachable right now." },
			onRetry: retry,
		},
	},
	play: async ({ canvas }) => {
		await expectHeaderAndWayBack(canvas);
		const alert = within(canvas.getByRole("alert"));
		await expect(alert.getByText("We could not load catalog updates")).toBeVisible();
		await userEvent.click(alert.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalledOnce();
	},
};
