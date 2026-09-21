import type { Meta, StoryObj } from "@storybook/react";
import { expect, userEvent } from "storybook/test";

import { SkipToContent } from "./SkipToContent";

const meta = {
	component: SkipToContent,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
} satisfies Meta<typeof SkipToContent>;

export default meta;
type Story = StoryObj<typeof meta>;

export const KeyboardNavigation: Story = {
	render: () => (
		<>
			<SkipToContent />
			<main id="main-content" tabIndex={-1} className="p-6">
				<h1>Main content</h1>
				<button type="button">First action</button>
			</main>
		</>
	),
	play: async ({ canvas }) => {
		await userEvent.tab();
		const skipLink = canvas.getByRole("link", { name: "Skip to main content" });
		await expect(skipLink).toHaveFocus();
		await expect(skipLink).toBeVisible();

		await userEvent.keyboard("{Enter}");
		await expect(canvas.getByRole("main")).toHaveFocus();

		await userEvent.tab();
		await expect(canvas.getByRole("button", { name: "First action" })).toHaveFocus();
	},
};
