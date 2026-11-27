import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { LATER_REVIEW_THREAD, REVIEW_THREAD } from "./fixtures";
import { TeamThreadList } from "./TeamThreadList";

const meta = {
	component: TeamThreadList,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", threads: [REVIEW_THREAD, LATER_REVIEW_THREAD] },
		selectedId: REVIEW_THREAD.id,
		threadLink: (thread) => <a href={`#${thread.id}`} />,
	},
	decorators: [
		(Story) => (
			<div className="max-w-xs">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof TeamThreadList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One entry for each review, most recent activity first, with the open one marked. There are no topic threads. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const items = canvas.getAllByRole("listitem");
		await expect(items[0]).toHaveTextContent(/Journey map, version 2/u);
		await expect(items[1]).toHaveAttribute("aria-current", "true");
		await expect(canvas.getByText("Review of version 1")).toBeVisible();
	},
};

/** A review whose version was withdrawn says so in the list. */
export const VersionWithdrawn: Story = {
	args: {
		state: {
			status: "ready",
			threads: [
				{
					...REVIEW_THREAD,
					review: { itemTitle: "Journey map", versionSequence: 1, versionState: "WITHDRAWN" },
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Version 1 was withdrawn")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading", rows: 3 } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

export const Error: Story = {
	args: { state: { status: "error", error: new globalThis.Error("Network error") } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reviews could not load")).toBeVisible();
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", threads: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "No reviews yet" })).toBeVisible();
	},
};
