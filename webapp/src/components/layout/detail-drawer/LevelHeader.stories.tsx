import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { GroupPill } from "@/components/practice-vocabulary/GroupPill";
import { StandingBadge, TrendNote } from "@/components/practice-vocabulary/StandingBadge";
import { StandingSummaryBox } from "@/components/practice-vocabulary/StandingSummaryBox";
import { DrawerBody } from "@/components/ui/drawer";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { expectNoOverflowingElement, expectNoPanelOverflow } from "@/stories/reflow";
import { precedes } from "@/test/dom";

import { LevelHeader } from "./LevelHeader";

const groupPill = (
	<GroupPill
		size="lg"
		slug="review-ready-work"
		name="Review-ready work"
		icon="eye"
		color="blue"
		srLabel
	/>
);

/**
 * The header every detail level wears: the path, then the name with an optional mark leading it,
 * then the standing, then one line of provenance, all in one column so it holds at 320px.
 */
const meta = {
	component: LevelHeader,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Practice setup", depth: 0 }], onClose: fn() },
		current: "Practice",
		title: "Describe what changed and why",
	},
	argTypes: {
		path: { control: false },
		mark: { control: false },
		chips: { control: false },
		description: { control: false },
		aside: { control: false },
	},
	render: (args) => (
		<InLevelStack entry={{ kind: "practice", id: "describe-what-and-why" }} path={args.path}>
			{(level) => (
				<>
					<LevelHeader {...args} {...level} nested={args.nested ?? level.nested} />
					<DrawerBody>
						<p className="text-sm text-muted-foreground">The level’s body.</p>
					</DrawerBody>
				</>
			)}
		</InLevelStack>
	),
} satisfies Meta<typeof LevelHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Plain: Story = {
	play: async ({ args }) => {
		const panel = within(await settledDrawerPanel());
		// The title names the drawer, and the path's last crumb says what kind of level this is.
		await expectSettledVisible(
			panel.getByRole("heading", { level: 2, name: "Describe what changed and why" }),
		);
		const crumbs = within(panel.getByRole("list", { name: "Path" })).getAllByRole("listitem");
		await expect(crumbs.map((crumb) => crumb.textContent)).toEqual(["Practice setup", "Practice"]);
		await expect(screen.getByRole("dialog")).toHaveAccessibleName("Describe what changed and why");
		// A crumb behind closes the stack down to its depth.
		await userEvent.click(panel.getByRole("button", { name: "Practice setup" }));
		await expect(args.path.onClose).toHaveBeenCalledWith(0);
	},
};

export const WithMark: Story = {
	args: { mark: groupPill },
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const title = panel.getByRole("heading", { level: 2 });
		// The mark leads the title on its row rather than sitting in a column of its own.
		const mark = panel.getByTitle("Review-ready work");
		await expect(precedes(mark, title)).toBe(true);
		await expect(
			Math.abs(mark.getBoundingClientRect().top - title.getBoundingClientRect().top),
		).toBeLessThan(title.getBoundingClientRect().height);
	},
};

/** Text-like status goes below the title, inside its column (`webapp/AGENTS.md` § Panel regions). */
export const WithChipsAndDescription: Story = {
	args: {
		mark: groupPill,
		chips: (
			<>
				<StandingBadge standing="DEVELOPING" scope="practice" />
				<TrendNote direction="IMPROVING" scope="practice" />
			</>
		),
		description: "Pull request",
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		const title = panel.getByRole("heading", { level: 2 });
		const badge = panel.getByText("Needs attention");
		const description = panel.getByText("Pull request");
		await expect(precedes(title, badge)).toBe(true);
		await expect(precedes(badge, description)).toBe(true);
		await expect(badge.getBoundingClientRect().top).toBeGreaterThanOrEqual(
			title.getBoundingClientRect().bottom,
		);
		await expect(screen.getByRole("dialog")).toHaveAccessibleDescription("Pull request");
	},
};

/** A picture summary sits beside the title from `sm`. */
export const WithAside: Story = {
	args: {
		current: "Group",
		title: "Review-ready work",
		mark: groupPill,
		aside: (
			<StandingSummaryBox
				label="5 practices in this group"
				counts={{ STRENGTH: 3, DEVELOPING: 1, NOT_OBSERVED: 1 }}
			/>
		),
	},
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expectSettledVisible(panel.getByText("5 practices in this group"));
	},
};

export const Loading: Story = {
	args: { loading: true, mark: undefined },
	play: async () => {
		const panel = await settledDrawerPanel();
		// The drawer is still named while its record loads: by what is loading, not by a blank.
		await expect(within(panel).getByRole("heading", { level: 2 })).toHaveTextContent(
			"Loading practice",
		);
		await expect(within(panel).queryByText("Describe what changed and why")).toBeNull();
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
	},
};

export const Nested: Story = {
	args: {
		nested: true,
		path: {
			behind: [
				{ label: "Practice reviews", depth: 0 },
				{ label: "Review", depth: 1 },
			],
			onClose: fn(),
		},
		current: "Observation",
		title: "Controllers stay thin",
	},
	play: async () => {
		// Below the top, dismissing returns to the level behind, so the control says Back.
		const panel = within(await settledDrawerPanel());
		await expectSettledVisible(panel.getByRole("button", { name: "Back" }));
		await expect(panel.getByRole("list", { name: "Path" })).toHaveTextContent(
			/Practice reviews.*Review.*Observation/u,
		);
	},
};

export const NarrowViewport: Story = {
	args: {
		mark: groupPill,
		title: "Keep every controller method thin enough to read without scrolling",
		chips: (
			<>
				<StandingBadge standing="DEVELOPING" scope="practice" />
				<TrendNote direction="IMPROVING" scope="practice" />
			</>
		),
		description: "Pull request",
		aside: (
			<StandingSummaryBox
				label="5 practices in this group"
				counts={{ STRENGTH: 3, DEVELOPING: 1, NOT_OBSERVED: 1 }}
			/>
		),
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expectNoPanelOverflow(panel);
		await expectNoOverflowingElement(panel);
		// Below `sm` the aside wraps under the title rather than squeezing it into a third column.
		const title = within(panel).getByRole("heading", { level: 2 });
		const aside = within(panel).getByText("5 practices in this group");
		await expect(aside.getBoundingClientRect().top).toBeGreaterThan(
			title.getBoundingClientRect().bottom,
		);
	},
};
