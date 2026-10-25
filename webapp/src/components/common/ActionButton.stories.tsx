import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { ActionButton } from "./ActionButton";

const meta = {
	component: ActionButton,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { children: "See this group across the workspace", onClick: fn() },
} satisfies Meta<typeof ActionButton>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The background `bg-primary` paints, read off a probe so the story never spells a color. */
function primaryBackground(host: HTMLElement): string {
	const probe = document.createElement("span");
	probe.className = "bg-primary";
	host.append(probe);
	const color = getComputedStyle(probe).backgroundColor;
	probe.remove();
	return color;
}

/**
 * White with a thin border at rest. Held down by a key, as by a pointer, it takes the dark primary
 * fill, and it lets go of it on release; most browsers apply `:active` to Enter not at all.
 */
export const Pressed: Story = {
	play: async ({ canvas, canvasElement }) => {
		const button = canvas.getByRole("button", { name: "See this group across the workspace" });
		const primary = primaryBackground(canvasElement);
		// The fill eases in and out (`transition-all`), so each reading waits for it to land.
		const fillIs = async (filled: boolean) => {
			await waitFor(async () => {
				const fill = getComputedStyle(button).backgroundColor;
				await (filled ? expect(fill).toBe(primary) : expect(fill).not.toBe(primary));
			});
		};
		await fillIs(false);

		await userEvent.pointer({ keys: "[MouseLeft>]", target: button });
		await fillIs(true);
		await userEvent.pointer({ keys: "[/MouseLeft]", target: button });
		await userEvent.unhover(button);
		button.blur();
		await fillIs(false);

		button.focus();
		await userEvent.keyboard("{Enter>}");
		await fillIs(true);
		await userEvent.keyboard("{/Enter}");
		button.blur();
		await fillIs(false);
	},
};

export const Disabled: Story = {
	args: { disabled: true },
};
