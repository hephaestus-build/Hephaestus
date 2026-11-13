import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { DocLink } from "./DocLink";

const meta = {
	component: DocLink,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { doc: "upgradeGuide", children: "Read the upgrade guide" },
	render: (args) => (
		<p className="max-w-md text-sm text-muted-foreground">
			A new release is available. Back up the database first. <DocLink {...args} />.
		</p>
	),
} satisfies Meta<typeof DocLink>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A page of the docs, in the sentence that needs it. It opens in a new tab and says so with the
 * icon and to a screen reader, so a half-finished form stays where the reader left it.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: "Read the upgrade guide (opens in a new tab)" });
		// Storybook is not a release build, so it reads the docs of the next release.
		await expect(link).toHaveAttribute("href", "https://docs.hephaestus.build/next/admin/upgrade");
		await expect(link).toHaveAttribute("target", "_blank");
		await expect(link).toHaveAttribute("rel", "noopener noreferrer");
	},
};

/** A target with a heading opens the page at that section. */
export const Section: Story = {
	args: { doc: "practiceScriptTraceLine", children: "Why scripts stop early" },
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: "Why scripts stop early (opens in a new tab)" });
		await expect(link).toHaveAttribute(
			"href",
			expect.stringMatching(/\/admin\/practice-review-operations#the-trace-line$/u),
		);
	},
};

/** In dark mode the underline, not the colour, marks the link in muted text (WCAG 2.2 SC 1.4.1). */
export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: "Read the upgrade guide (opens in a new tab)" });
		await expect(getComputedStyle(link).textDecorationLine).toBe("underline");
	},
};

/** Long link text wraps with its sentence at 320px. */
export const LongText: Story = {
	args: {
		doc: "practiceScriptGuide",
		children: "Write a practice script that decides in code before the review model reads the work",
	},
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link")).toHaveAttribute(
			"href",
			expect.stringMatching(/\/admin\/practice-scripts$/u),
		);
	},
};
