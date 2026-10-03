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

/** The app's own sidebar is fixed at the top left, and the link is shown in the same corner. */
export const ShownAboveTheSidebar: Story = {
	render: () => (
		<>
			<SkipToContent />
			<div className="fixed inset-y-0 left-0 z-10 w-64 bg-muted">Sidebar</div>
			<main id="main-content" tabIndex={-1} className="p-6 pl-72">
				<h1>Main content</h1>
			</main>
		</>
	),
	play: async ({ canvas }) => {
		await userEvent.tab();
		const skipLink = canvas.getByRole("link", { name: "Skip to main content" });
		await expect(skipLink).toHaveFocus();
		const box = skipLink.getBoundingClientRect();
		await expect(box.top).toBeGreaterThanOrEqual(0);
		await expect(box.left).toBeGreaterThanOrEqual(0);
		await expect(
			document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2),
		).toBe(skipLink);
	},
};

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
