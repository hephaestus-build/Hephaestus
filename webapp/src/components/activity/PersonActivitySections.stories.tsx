import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { ada, OVERVIEW, readyOverview, WORK_LOG } from "@/stories/activity-story-data";
import { withProvider, withStandardPage } from "@/stories/decorators";

import { PersonActivitySections } from "./PersonActivitySections";

const meta = {
	component: PersonActivitySections,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		level: 2,
		period: { kind: "preset", preset: "90d" },
		overview: readyOverview(OVERVIEW),
		workLog: {
			status: "ready",
			stale: false,
			items: WORK_LOG,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
			onCopy: fn(async () => {
				/* the copy is the route's */
			}),
		},
		providerType: "GITHUB",
		subject: { people: "one", login: ada.login },
	},
} satisfies Meta<typeof PersonActivitySections>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const headings = canvas
			.getAllByRole("heading", { level: 2 })
			.map((heading) => heading.textContent);
		await expect(headings).toStrictEqual(["Last 90 days", "Repositories", "Timeline"]);
	},
};

/** The repositories wait for the counts; the period's title and the timeline do not. */
export const Loading: Story = {
	args: { overview: { status: "loading" }, workLog: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("heading", { name: "Repositories" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("heading", { name: "Timeline" })).toBeVisible();
	},
};

const onRetry = fn();

export const Failed: Story = {
	args: {
		overview: { status: "error", error: new Error("Network down"), onRetry },
		workLog: { status: "error", error: new Error("Network down"), onRetry },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("alert")).toHaveLength(2);
	},
};

export const GitLab: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
};

export const GitLabDark: Story = {
	decorators: [withProvider("GITLAB")],
	args: { providerType: "GITLAB" },
	globals: { theme: "dark" },
};

export const Dark: Story = { globals: { theme: "dark" } };
