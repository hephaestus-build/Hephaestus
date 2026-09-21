import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { BulletList } from "./BulletList";

const meta = {
	title: "Shared/Bullet list",
	component: BulletList,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		className: "max-w-md text-sm",
		children: (
			<>
				<li>Scope the change to one concern has new feedback.</li>
				<li>Describe what changed and why resolved on 9 September.</li>
				<li>Keep the diff reviewable in one sitting moved to Needs attention.</li>
			</>
		),
	},
} satisfies Meta<typeof BulletList>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A list with the grey disc marker; the items are the caller's. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list");
		await expect(list).toHaveClass("list-disc", "marker:text-muted-foreground");
		await expect(canvas.getAllByRole("listitem")).toHaveLength(3);
	},
};
