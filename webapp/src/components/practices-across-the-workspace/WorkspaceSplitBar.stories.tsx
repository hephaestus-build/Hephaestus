import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	threeWay,
	TOTAL_ONLY,
	WITHHELD,
} from "@/stories/practices-across-the-workspace-story-data";

import { WorkspaceSplitBar } from "./WorkspaceSplitBar";

/**
 * One bar for a practice group and for a practice. The text alternative names the reference group
 * and every count, and each part carries its count and icon, so the colours carry nothing a screen
 * reader or a color-blind reader misses. The bar is `role="img"` rather than a Base UI `Meter`: a
 * meter announces one value against a range, and a split is four counts of one whole. It is CSS
 * rather than Recharts through `ui/chart.tsx`: one stacked row needs no axis, scale or tooltip, and
 * each part's count must sit under its own piece at every width.
 */
const meta = {
	component: WorkspaceSplitBar,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<div className="max-w-md">
				<Story />
			</div>
		),
	],
	args: {
		split: threeWay([6, 7, 7]),
		yourStanding: "MIXED",
	},
} satisfies Meta<typeof WorkspaceSplitBar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers with a current standing in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. The You marker is on Mixed feedback.",
		);
		await expect(canvas.getByText("You")).toBeVisible();
		await expect(canvas.getByText("28 developers")).toBeVisible();
	},
};

/** The parts and the You marker carry color meaning, so dark mode is asserted, not assumed. */
export const Dark: Story = { globals: { theme: "dark" } };

/** A part would hold too few: one neutral bar with the total, and no marker. */
export const TotalOnly: Story = {
	args: { split: TOTAL_ONLY, yourStanding: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers with a current standing in this workspace. The split is held back so no one can be singled out.",
		);
		await expect(canvas.getByText("Split held back")).toBeVisible();
		await expect(canvas.getByText("28 developers")).toBeVisible();
		await expect(canvas.queryByText("You")).toBeNull();
	},
};

/** The total would hold too few as well: an empty track and its reason. */
export const Withheld: Story = {
	args: { split: WITHHELD, yourStanding: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(canvas.getByText("Held back so no one can be singled out.")).toBeVisible();
		await expect(canvas.queryByText("You")).toBeNull();
	},
};

/** A reader with nothing to report is counted in none yet: the marker sits over that part. */
export const ReaderInNoneYet: Story = {
	args: { yourStanding: "NO_OPPORTUNITY" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You")).toBeVisible();
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			/, 8 none yet\. The You marker is on none yet\.$/u,
		);
	},
};
