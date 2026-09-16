import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen } from "storybook/test";

import { ModelAccessScopeChoice } from "./ModelAccessScopeChoice";

const meta = {
	component: ModelAccessScopeChoice,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		idPrefix: "story-access",
		label: "Initial workspace access",
		value: "ALL",
		onChange: fn(),
	},
	decorators: [
		(Story) => (
			<div className="max-w-md">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ModelAccessScopeChoice>;

export default meta;
type Story = StoryObj<typeof meta>;

const TRANSPARENT = "rgba(0, 0, 0, 0)";

function cardOf(name: RegExp): HTMLElement {
	const card = screen.getByRole("radio", { name }).closest("label");
	if (!card) {
		throw new Error(`No card wraps the ${name} radio`);
	}
	return card;
}

/**
 * The tint is a `has-data-checked:` rule on `FieldLabel`, so it only pays out if the checked radio
 * really is a descendant of that label — hence computed style rather than a class name.
 */
export const AllWorkspaces: Story = {
	play: async () => {
		const chosen = cardOf(/^All workspaces/iu);
		const other = cardOf(/^Selected workspaces/iu);

		await expect(screen.getByRole("radio", { name: /^All workspaces/iu })).toHaveAttribute(
			"data-checked",
		);
		await expect(getComputedStyle(chosen).backgroundColor).not.toBe(TRANSPARENT);
		await expect(getComputedStyle(other).backgroundColor).toBe(TRANSPARENT);
		await expect(getComputedStyle(chosen).borderTopColor).not.toBe(
			getComputedStyle(other).borderTopColor,
		);
	},
};

export const SelectedWorkspaces: Story = {
	args: { value: "SELECTED" },
	play: async () => {
		const chosen = cardOf(/^Selected workspaces/iu);
		const other = cardOf(/^All workspaces/iu);

		await expect(getComputedStyle(chosen).backgroundColor).not.toBe(TRANSPARENT);
		await expect(getComputedStyle(other).backgroundColor).toBe(TRANSPARENT);
	},
};
