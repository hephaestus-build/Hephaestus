import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import {
	ada,
	MANY_OPEN_PULL_REQUESTS,
	NOTHING_OPEN,
	OPEN_WORK,
} from "@/stories/activity-story-data";
import { withStandardPage } from "@/stories/decorators";

import { OpenWorkSections } from "./OpenWorkSections";

const meta = {
	component: OpenWorkSections,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: { status: "ready", openWork: OPEN_WORK },
		providerType: "GITHUB",
		perspective: "self",
		login: ada.login,
	},
} satisfies Meta<typeof OpenWorkSections>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "Your open pull requests 3 pull requests" }),
		).toBeVisible();
		// Someone else's work names its author; the person's own does not.
		await expect(canvas.getByText("by Bob Brenner")).toBeVisible();
		await expect(canvas.queryByText("by Ada Lovelace")).not.toBeInTheDocument();
	},
};

export const Member: Story = {
	args: { perspective: "member" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 3, name: "Review requests 1 pull request" }),
		).toBeVisible();
	},
};

export const GitLab: Story = {
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "Your open merge requests 3 merge requests" }),
		).toBeVisible();
	},
};

export const NothingOpen: Story = {
	args: { state: { status: "ready", openWork: NOTHING_OPEN } },
	play: async ({ canvas }) => {
		// An empty list counts nothing in its title.
		await expect(canvas.getByRole("heading", { name: "Waiting on you" })).toBeVisible();
	},
};

export const LongList: Story = {
	args: { state: { status: "ready", openWork: MANY_OPEN_PULL_REQUESTS } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getAllByRole("link", { name: /Follow-up/u })).toHaveLength(5);
		await userEvent.click(canvas.getByRole("button", { name: "Show three more" }));
		// The rest joins the same list, which the trigger says it controls.
		const less = canvas.getByRole("button", { name: "Show less" });
		const list = canvas.getByRole("list");
		await expect(less).toHaveAttribute("aria-expanded", "true");
		await expect(less).toHaveAttribute("aria-controls", list.id);
		await expect(within(list).getAllByRole("listitem")).toHaveLength(8);
		// The server cut the list short, so the count says so and the list says which ones these are.
		await expect(canvas.getByText("These are the eight most recently updated.")).toBeVisible();
		await userEvent.click(less);
		await expect(canvas.getAllByRole("link", { name: /Follow-up/u })).toHaveLength(5);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Loading open work")).toHaveLength(3);
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
};
