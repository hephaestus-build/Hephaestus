import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { Button } from "@/components/ui/button";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { ada, OVERVIEW, readyOverview, WORK_LOG } from "@/stories/activity-story-data";
import { withPageBehind, withProvider } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { personLevel } from "./activity-search";
import { PersonActivityLevel } from "./PersonActivityLevel";

// The level has no page of its own, so every story mounts a real drawer over a real page.
const meta = {
	component: PersonActivityLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Workspace activity", depth: 0 }], onClose: fn() },
		login: ada.login,
		user: ada,
		providerType: "GITHUB",
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
	},
	argTypes: { path: { control: false } },
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={[personLevel(ada.login)]}>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					size="detail"
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(_entry, level) => (
						<PersonActivityLevel
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
} satisfies Meta<typeof PersonActivityLevel>;

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
		// The same parts as the Activity page, after what needs you.
		const sections = panel
			.getAllByRole("heading", { level: 3 })
			.map((heading) => heading.textContent);
		await expect(sections).toStrictEqual(["Last 90 days", "Repositories", "Timeline"]);
		// A tile stacks its category over this person, which makes it this person's.
		await expect(panel.getByRole("link", { name: /^Reviews/u })).toHaveAttribute(
			"href",
			expect.stringContaining("detail=activity:reviews"),
		);
	},
};

export const BeforeThePeopleArrive: Story = {
	args: { user: undefined },
	play: async () => {
		// The login stands in for the name until the people list names them.
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
		overview: { status: "loading" },
		workLog: { status: "loading" },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.queryByRole("link", { name: /^Reviews/u })).not.toBeInTheDocument();
	},
};

/** Nobody by this login contributed in the period, which the level says instead of zeros. */
export const Absent: Story = {
	args: { user: undefined, absent: true },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByText("No activity in this range")).toBeVisible();
		await expect(panel.queryByRole("heading", { name: "Timeline" })).not.toBeInTheDocument();
	},
};

const retry = fn();

export const Failed: Story = {
	args: {
		overview: { status: "error", error: new Error("Network down"), onRetry: retry },
		workLog: { status: "error", error: new Error("Network down"), onRetry: retry },
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getAllByRole("alert")).toHaveLength(2);
	},
};

/** A workspace admin sees how the account counts, in the level's footer. */
export const WithAutomationAction: Story = {
	args: {
		automationAction: (
			<Button variant="outline" size="sm">
				Treat as automation
			</Button>
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("button", { name: "Treat as automation" })).toBeVisible();
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
