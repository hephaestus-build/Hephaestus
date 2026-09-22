import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";
import { expectGenuinelyDisabled } from "@/test/controls";

import { AiChoiceSection } from "./AiChoiceSection";

const IN_HOUSE = /^In-house only /u;
const NOT_KEPT = /^Provider, nothing kept /u;
const ANY = /^Provider, kept for safety checks /u;
const NO_AI = /^No AI /u;

const meta = {
	component: AiChoiceSection,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		choice: undefined,
		onSave: fn(),
	},
} satisfies Meta<typeof AiChoiceSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The card a radio sits in, which is what the grid lays out. */
function cardOf(radio: HTMLElement) {
	const card = radio.closest("label");
	if (!card) {
		throw new Error("Expected the radio inside its card");
	}
	return card.getBoundingClientRect();
}

export const Unanswered: Story = {
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		await expect(radios).toHaveLength(4);
		for (const name of [IN_HOUSE, NOT_KEPT, ANY, NO_AI]) {
			await expect(canvas.getByRole("radio", { name })).not.toBeChecked();
		}
		const save = canvas.getByRole("button", { name: "Save" });
		await expectGenuinelyDisabled(save);
		await expect(save).toHaveAccessibleDescription(
			"Applies in all your workspaces. Change it any time.",
		);
	},
};

export const Answered: Story = {
	args: { choice: "NOT_KEPT_ONLY" },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("radio", { name: NOT_KEPT })).toBeChecked();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save" }));
		await userEvent.click(canvas.getByRole("radio", { name: NO_AI }));
		await expect(canvas.getByRole("radio", { name: NO_AI })).toBeChecked();
		await expect(canvas.getByRole("button", { name: "Save" })).toBeEnabled();
		await userEvent.click(canvas.getByRole("button", { name: "Save" }));
		await expect(args.onSave).toHaveBeenCalledWith("NO_AI");
	},
};

export const Saving: Story = {
	args: { choice: "NOT_KEPT_ONLY", isSaving: true },
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Saving…" }));
		await expect(canvas.getByRole("radio", { name: NO_AI })).toBeDisabled();
	},
};

export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryAllByRole("radio")).toHaveLength(0);
		await expect(canvas.getByText("Loading…")).toBeVisible();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Save" }));
	},
};

export const LoadFailed: Story = {
	args: {
		isError: true,
		error: new globalThis.Error("Connection unavailable"),
		onRetry: fn(),
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.queryAllByRole("radio")).toHaveLength(0);
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const Narrow: Story = {
	args: { choice: "IN_HOUSE_ONLY" },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		// The viewport parameter is applied by the viewport addon and ignored where it is not; at
		// 1440px the overflow assertion below would pass without proving anything.
		await expect(window.innerWidth).toBe(320);
		await expectNoPageOverflow();
		// One column: every card starts below the one before it.
		const cards = canvas.getAllByRole("radio").map(cardOf);
		for (let index = 1; index < cards.length; index += 1) {
			const above = cards[index - 1];
			const card = cards[index];
			if (!above || !card) {
				throw new Error("Expected four cards");
			}
			await expect(card.top).toBeGreaterThanOrEqual(above.bottom);
			await expect(card.left).toBe(above.left);
		}
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	args: { choice: "ANY_DECLARED" },
};
