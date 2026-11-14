import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { settledDrawerPanel } from "@/stories/overlay";

import { resultLevel } from "./review-levels";
import { ReviewLevelHeader } from "./ReviewLevelHeader";

/**
 * A Practice reviews level's header. The level's kind names the last crumb, and the title is the
 * record's own name.
 */
const meta = {
	component: ReviewLevelHeader,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		path: { behind: [{ label: "Practice reviews", depth: 0 }], onClose: fn() },
		kind: "result",
		title: "Controllers stay thin",
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<InLevelStack entry={resultLevel("result-1")} path={args.path}>
			{(level) => <ReviewLevelHeader {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof ReviewLevelHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async () => {
		const panel = within(await settledDrawerPanel());
		await expect(panel.getByRole("heading", { level: 2 })).toHaveTextContent(
			"Controllers stay thin",
		);
		await expect(panel.getByRole("list", { name: "Path" })).toHaveTextContent(
			"Practice reviewsObservation",
		);
	},
};

/** While the record loads, the kind names the drawer, so it is never nameless. */
export const Loading: Story = {
	args: { loading: true, title: undefined },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(within(panel).getByRole("heading", { level: 2 })).toHaveTextContent(
			"Loading observation",
		);
		await expect(panel.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
	},
};
