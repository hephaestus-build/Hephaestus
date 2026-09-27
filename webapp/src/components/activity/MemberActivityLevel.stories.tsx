import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { ada, OPEN_WORK, SUMMARY, TIMELINE } from "@/stories/activity-story-data";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";

import { memberLevel } from "./activity-search";
import { MemberActivityLevel } from "./MemberActivityLevel";

// The level has no page of its own, so every story mounts a real drawer over a real page.
const meta = {
	component: MemberActivityLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Workspace activity", depth: 0 }], onClose: fn() },
		login: ada.login,
		user: ada,
		providerType: "GITHUB",
		range: "7d",
		openWork: { status: "ready", openWork: OPEN_WORK },
		summary: { status: "ready", summary: SUMMARY },
		timeline: {
			status: "ready",
			items: TIMELINE,
			hasMore: false,
			isLoadingMore: false,
			onLoadMore: fn(),
		},
	},
	argTypes: { path: { control: false } },
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={[memberLevel(ada.login)]}>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					size="detailWide"
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(_entry, level) => (
						<MemberActivityLevel
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
} satisfies Meta<typeof MemberActivityLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { name: "Ada Lovelace" })).toBeVisible();
		await expect(panel.getByRole("link", { name: /ada on GitHub/u })).toHaveAttribute(
			"href",
			"https://github.com/ada",
		);
		// A summary row stacks the category over this member, which makes it this member's.
		await expect(panel.getByRole("link", { name: "Reviews" })).toHaveAttribute(
			"href",
			expect.stringContaining("activity%3Areviews"),
		);
	},
};

export const BeforeTheMemberListArrives: Story = {
	args: { user: undefined },
	play: async () => {
		// The login stands in for the name until the member list names them.
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { name: "ada" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: {
		openWork: { status: "loading" },
		summary: { status: "loading" },
		timeline: { status: "loading" },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("link", { name: "Reviews" })).not.toBeInTheDocument();
	},
};
