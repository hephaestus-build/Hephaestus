import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { ada, OPEN_WORK, OVERVIEW, readyOverview, WORK_LOG } from "@/stories/activity-story-data";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
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
		range: "30d",
		openWork: { status: "ready", openWork: OPEN_WORK, login: ada.login },
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
		// The same parts as the Activity page, named from the outside.
		const sections = panel
			.getAllByRole("heading", { level: 3 })
			.map((heading) => heading.textContent);
		await expect(sections).toStrictEqual([
			"Open work",
			"Assigned issues",
			"Last 30 days",
			"Timeline",
		]);
		// A tile stacks its category over this member, which makes it this member's.
		await expect(panel.getByRole("link", { name: /^Reviews/u })).toHaveAttribute(
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

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};

export const Loading: Story = {
	args: {
		openWork: { status: "loading" },
		overview: { status: "loading" },
		workLog: { status: "loading" },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("link", { name: /^Reviews/u })).not.toBeInTheDocument();
	},
};
