import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import {
	readyOverview,
	WORKSPACE_OVERVIEW,
	WORKSPACE_WORK_LOG,
} from "@/stories/activity-story-data";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { categoryLevel } from "./activity-search";
import { ActivityCategoryLevel } from "./ActivityCategoryLevel";

const onCopy = fn(async () => {
	/* the copy is the route's */
});

const reviews = WORKSPACE_WORK_LOG.flatMap((item) => {
	const actions = item.actions.filter((action) => action.kind.startsWith("REVIEW_"));
	return actions.length > 0 ? [{ ...item, actions }] : [];
});

// The level has no page of its own, so every story mounts a real drawer over a real page.
const meta = {
	component: ActivityCategoryLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Workspace activity", depth: 0 }], onClose: fn() },
		category: "reviews",
		range: "30d",
		description: "Last 30 days · Platform / Payments",
		providerType: "GITHUB",
		overview: readyOverview(WORKSPACE_OVERVIEW),
		workLog: {
			status: "ready",
			stale: false,
			items: reviews,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
			onCopy,
		},
		subject: { people: "several" },
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
	play: async ({ userEvent }) => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2, name: "Reviews" })).toBeVisible();
		await expect(panel.getByText("Last 30 days · Platform / Payments")).toBeVisible();
		await expect(panel.getByRole("figure")).toHaveAccessibleName(/reviews; busiest day/u);
		await userEvent.click(panel.getByRole("button", { name: "Copy as Markdown" }));
		await expect(onCopy).toHaveBeenCalledOnce();
	},
};

export const Empty: Story = {
	args: {
		workLog: {
			status: "ready",
			stale: false,
			items: [],
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
			onCopy,
		},
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("No activity in this range")).toBeVisible();
		await expect(panel.queryByRole("button", { name: "Copy as Markdown" })).not.toBeInTheDocument();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const Loading: Story = {
	args: { overview: { status: "loading" }, workLog: { status: "loading" } },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("figure")).not.toBeInTheDocument();
		await expect(panel.queryByRole("button", { name: "Copy as Markdown" })).not.toBeInTheDocument();
	},
};
