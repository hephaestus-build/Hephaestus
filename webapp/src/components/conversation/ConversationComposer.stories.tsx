import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import { RemovableToken } from "@/components/common/RemovableToken";
import { expectSettledVisible } from "@/stories/overlay";

import { ConversationComposer } from "./ConversationComposer";

const meta = {
	component: ConversationComposer,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { label: "Message Hephaestus", placeholder: "Message Hephaestus", onSend: fn() },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ConversationComposer>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The height of one line of text in `field`, with the field's own padding. */
function oneLineHeight(field: HTMLElement): number {
	const style = getComputedStyle(field);
	return (
		Number.parseFloat(style.lineHeight) +
		Number.parseFloat(style.paddingTop) +
		Number.parseFloat(style.paddingBottom)
	);
}

/**
 * Enter sends; Shift+Enter starts a new line; an empty message does not send. Empty, the field is
 * one line tall; it grows with each line written, and is one line again once the message is sent.
 * On a touch screen, whose keyboard has no Shift, Return starts a new line and Send sends.
 */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const field = canvas.getByRole("textbox", { name: "Message Hephaestus" });
		const empty = field.getBoundingClientRect().height;
		await expect(empty).toBeLessThanOrEqual(oneLineHeight(field) + 1);
		await expect(canvas.getByRole("button", { name: "Send message" })).toBeDisabled();
		await userEvent.type(field, "First line{Shift>}{Enter}{/Shift}second line");
		await expect(field.getBoundingClientRect().height).toBeGreaterThan(empty);
		await expect(args.onSend).not.toHaveBeenCalled();
		await userEvent.keyboard("{Enter}");
		await expect(args.onSend).toHaveBeenCalledWith("First line\nsecond line");
		await expect(field).toHaveValue("");
		await expect(field.getBoundingClientRect().height).toBe(empty);
	},
};

/**
 * Hephaestus is answering. Send stays: a message written now waits as a follow-up. Stop sits beside it
 * rather than replacing it, so stopping and asking are never the same press. It is the one Stop
 * while Hephaestus answers, and an icon, so a pointer that rests on it reads its name.
 */
export const Busy: Story = {
	args: { stop: { label: "Stop answer", onStop: fn() } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Message Hephaestus" }),
			"And one more{Enter}",
		);
		await expect(args.onSend).toHaveBeenCalledWith("And one more");
		const stop = canvas.getByRole("button", { name: "Stop answer" });
		await userEvent.hover(stop);
		await expectSettledVisible(
			await screen.findByText("Stop answer", { selector: "[data-slot=tooltip-content]" }),
		);
		await userEvent.click(stop);
		await expect(args.stop?.onStop).toHaveBeenCalledOnce();
	},
};

const REMOVE_PAGE = "Do not send this page with your message";

function pageContext(label: string) {
	return <RemovableToken label={label} removeLabel={REMOVE_PAGE} onRemove={fn()} />;
}

/** In the Hephaestus panel: the page the developer asks from travels with the message until they remove it. */
export const WithContext: Story = {
	args: { context: pageContext("From Practice profile") },
};

/** The composer as the Hephaestus panel draws it in a 319px phone window: 287px inside the panel's padding. */
function PhonePanel(Story: Parameters<Decorator>[0]) {
	return (
		<div className="w-[287px]">
			<Story />
		</div>
	);
}

function overlaps(a: DOMRect, b: DOMRect): boolean {
	return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
}

/**
 * Removing the page, Stop and Send each keep a target of at least 24 by 24 pixels (WCAG 2.5.8),
 * inside the composer and clear of each other, and the page's name ends before its remove control.
 */
function keepsControlsApart(page: string): Story["play"] {
	return async ({ canvas }) => {
		const frame = canvas
			.getByRole("textbox", { name: "Message Hephaestus" })
			.parentElement?.getBoundingClientRect();
		if (frame === undefined) {
			throw new Error("Expected the field inside the composer's frame");
		}
		const [remove, ...rest] = [REMOVE_PAGE, "Stop answer", "Send message"].map((name) =>
			canvas.getByRole("button", { name }).getBoundingClientRect(),
		);
		if (remove === undefined) {
			throw new Error("Expected the page's remove control");
		}
		const controls = [remove, ...rest];
		for (const [index, control] of controls.entries()) {
			await expect(Math.min(control.width, control.height)).toBeGreaterThanOrEqual(24);
			await expect(control.left).toBeGreaterThanOrEqual(frame.left);
			await expect(control.right).toBeLessThanOrEqual(frame.right);
			for (const other of controls.slice(index + 1)) {
				await expect(overlaps(control, other)).toBe(false);
			}
		}
		await expect(canvas.getByText(page).getBoundingClientRect().right).toBeLessThanOrEqual(
			remove.left,
		);
	};
}

const BUSY = { label: "Stop answer", onStop: fn() };

/**
 * A phone's panel while Hephaestus answers, asked from Workspace activity. The page, Stop and Send do not
 * fit on one row, so Stop and Send wrap below the page rather than over its remove control.
 */
export const PhoneBusyWithPage: Story = {
	args: { stop: BUSY, context: pageContext("From Workspace activity") },
	decorators: [PhonePanel],
	play: keepsControlsApart("From Workspace activity"),
};

/** A page name longer than the row shortens, and its remove control stays whole and reachable. */
export const PhoneBusyWithLongPage: Story = {
	args: {
		stop: BUSY,
		context: pageContext("From Practice review settings of the payments workspace"),
	},
	decorators: [PhonePanel],
	play: keepsControlsApart("From Practice review settings of the payments workspace"),
};
