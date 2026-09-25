import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { ResponseButton, type ResponseTone } from "./ResponseButton";

const meta = {
	component: ResponseButton,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { tone: "positive", pressed: false, children: "Helpful" },
} satisfies Meta<typeof ResponseButton>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One response at rest: an outline button that says what it is not pressed. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Helpful" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
	},
};

/** What each tone is called where a reader meets it, so a row reads as the real choices. */
const TONE_LABELS: Record<ResponseTone, string> = {
	positive: "Helpful",
	negative: "Not helpful",
	neutral: "Not sure",
};

const TONES = ["positive", "negative", "neutral"] as const;

const groundOf = (button: HTMLElement) => getComputedStyle(button).backgroundColor;

/** The two buttons a tone renders in this story: the one at rest and the pressed one. */
function restAndPressed(buttons: HTMLElement[]): [HTMLElement, HTMLElement] {
	const [rest, pressed] = buttons;
	if (!rest || !pressed) {
		throw new Error("Each tone renders one response at rest and one pressed.");
	}
	return [rest, pressed];
}

/**
 * The responses themselves, at rest and pressed. Each tone owns a tint and wears no other, so the
 * agreeing and the disputing choice read as opposites and neither is mistaken for an untouched
 * outline. The grounds are read off the rendered buttons: the outline variant paints its own
 * `aria-pressed:bg-muted`, and only the computed colour says whose ground won.
 */
export const Responses: Story = {
	render: () => (
		<div className="flex flex-col gap-2 p-4">
			{[false, true].map((pressed) => (
				<div key={String(pressed)} className="flex flex-wrap gap-2">
					{TONES.map((tone) => (
						<ResponseButton key={tone} tone={tone} pressed={pressed}>
							{TONE_LABELS[tone]}
						</ResponseButton>
					))}
				</div>
			))}
		</div>
	),
	play: async ({ canvas }) => {
		const pressedGrounds = new Set<string>();
		for (const tone of TONES) {
			const [rest, pressed] = restAndPressed(
				canvas.getAllByRole("button", { name: TONE_LABELS[tone] }),
			);
			await expect(rest).toHaveAttribute("aria-pressed", "false");
			await expect(pressed).toHaveAttribute("aria-pressed", "true");
			// Pressed says so on the ground itself.
			await expect(groundOf(pressed)).not.toBe(groundOf(rest));
			pressedGrounds.add(groundOf(pressed));
		}
		// No tone borrows another's, the outline's muted one included: a pressed "Not helpful" never
		// reads as agreement.
		await expect(pressedGrounds.size).toBe(TONES.length);
	},
};

/**
 * The same responses in the dark theme, where the outline paints a ground and a border of its own:
 * each tint restates both there, so a pressed response still reads in its tone and never as the
 * outline's own pressed muted ground.
 */
export const ResponsesDark: Story = {
	...Responses,
	globals: { theme: "dark" },
};
