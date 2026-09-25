import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectTouchTarget } from "@/test/controls";

import { DetailPath } from "./DetailPath";

const meta = {
	component: DetailPath,
	args: {
		behind: [
			{ label: "Practice profile", depth: 0 },
			{ label: "All practice groups", depth: 1 },
			{ label: "Packaging work for review", depth: 2 },
		],
		current: "Practice",
		onClose: fn(),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof DetailPath>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Three levels over the page: every crumb behind closes down to its own depth. */
export const ThreeDeep: Story = {
	play: async ({ args, canvas }) => {
		const path = canvas.getByRole("list", { name: "Path" });
		await expect(path).toHaveTextContent(
			"Practice profileAll practice groupsPackaging work for reviewPractice",
		);
		await expect(canvas.getByText("Practice")).toHaveAttribute("aria-current", "location");
		// The crumb's pointer target reaches the 24 px minimum although the text is smaller.
		const crumb = canvas.getByRole("button", { name: "All practice groups" });
		await expectTouchTarget(crumb);
		await userEvent.click(crumb);
		await expect(args.onClose).toHaveBeenCalledWith(1);
		await userEvent.click(canvas.getByRole("button", { name: "Practice profile" }));
		await expect(args.onClose).toHaveBeenCalledWith(0);
	},
};

/** One level over the page: the page is the only crumb behind. */
export const OneOverThePage: Story = {
	args: {
		behind: [{ label: "Practice profile", depth: 0 }],
		current: "Group",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("listitem")).toHaveLength(2);
		await expect(canvas.getByRole("button", { name: "Practice profile" })).toBeVisible();
	},
};
