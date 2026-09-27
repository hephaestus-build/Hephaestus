import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { WORKSPACE_TIMELINE } from "@/stories/activity-story-data";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";

import { categoryLevel } from "./activity-search";
import { ActivityCategoryLevel } from "./ActivityCategoryLevel";

// The level has no page of its own, so every story mounts a real drawer over a real page.
const meta = {
	component: ActivityCategoryLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Workspace activity", depth: 0 }], onClose: fn() },
		title: "Reviews",
		description: "Reviews in Platform / Payments in the last 7 days.",
		state: {
			status: "ready",
			items: WORKSPACE_TIMELINE.filter((item) => item.kind.startsWith("REVIEW_")),
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
		},
		providerType: "GITHUB",
		people: "several",
		empty: {
			title: "Nothing in the last 7 days",
			description: "A longer range on the page looks further back.",
		},
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<Stateful initial={[categoryLevel("reviews")]}>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					size="detailWide"
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(_entry, level) => (
						<ActivityCategoryLevel
							{...args}
							nested={level.nested}
							path={{
								behind: args.path.behind,
								onClose: (depth) => setStack(stack.slice(0, depth)),
							}}
						/>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ActivityCategoryLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { name: "Reviews" })).toBeVisible();
		// A name in the list opens that member one level up the stack.
		await expect(panel.getByRole("link", { name: "Bob Brenner" })).toHaveAttribute(
			"href",
			expect.stringContaining("member%3Abob"),
		);
	},
};

export const Empty: Story = {
	args: {
		state: {
			status: "ready",
			items: [],
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
		},
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("link", { name: "Bob Brenner" })).not.toBeInTheDocument();
	},
};
