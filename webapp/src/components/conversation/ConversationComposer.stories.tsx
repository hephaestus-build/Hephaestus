import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, screen } from "storybook/test";

import { RemovableToken } from "@/components/common/RemovableToken";
import { Button } from "@/components/ui/button";
import { expectSettledVisible } from "@/stories/overlay";

import { ConversationComposer, type ConversationComposerProps } from "./ConversationComposer";

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

/** The private conversation says, for as long as the composer shows, that only the person reads it. */
export const PrivateAudience: Story = {
	args: { audience: { kind: "PRIVATE" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Only you")).toBeVisible();
		await expect(
			canvas.getByRole("textbox", { name: "Message Hephaestus" }),
		).toHaveAccessibleDescription(/Only you/u);
	},
};

/** A team place thread names its team as the audience. */
export const TeamAudience: Story = {
	args: { label: "Message Team Wayfinding", audience: { kind: "TEAM", teamName: "Wayfinding" } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("textbox", { name: "Message Team Wayfinding" }),
		).toHaveAccessibleDescription(/Team Wayfinding/u);
	},
};

/** Moves one composer between audiences, as a route does when the person switches from a team to "Ask privately". */
function SwitchingComposer(args: ConversationComposerProps) {
	const [team, setTeam] = useState(true);
	return (
		<div className="flex flex-col gap-2">
			<Button variant="outline" size="sm" className="w-fit" onClick={() => setTeam(!team)}>
				{team ? "Ask privately" : "Back to the team"}
			</Button>
			<ConversationComposer
				{...args}
				label={team ? "Message Team Wayfinding" : "Ask privately"}
				audience={team ? { kind: "TEAM", teamName: "Wayfinding" } : { kind: "PRIVATE" }}
				draftKey={team ? "story:team:wayfinding:review:1" : "story:main"}
			/>
		</div>
	);
}

/**
 * A draft never moves to another audience (review of #5472). Text written to the team stays in the team's draft: the
 * private field opens empty, and switching back restores the team's text in its own place. Enter in the private field
 * sends only what was written there.
 */
export const DraftsStayWithTheirAudience: Story = {
	render: (args) => <SwitchingComposer {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.type(
			canvas.getByRole("textbox", { name: "Message Team Wayfinding" }),
			"Which need first?",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Ask privately" }));
		const own = canvas.getByRole("textbox", { name: "Ask privately" });
		await expect(own).toHaveValue("");
		await userEvent.type(own, "{Enter}");
		await expect(args.onSend).not.toHaveBeenCalled();
		await userEvent.type(own, "Am I reading this right?");
		await userEvent.click(canvas.getByRole("button", { name: "Back to the team" }));
		await expect(canvas.getByRole("textbox", { name: "Message Team Wayfinding" })).toHaveValue(
			"Which need first?",
		);
		await expect(args.onSend).not.toHaveBeenCalled();
	},
};

/** A long team name shortens inside the composer; the label never runs past its edge. */
export const LongTeamName: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	args: {
		label: "Message the team",
		audience: {
			kind: "TEAM",
			teamName: "Wayfinding for returning part-time students between both campuses",
		},
	},
	play: async ({ canvas }) => {
		// The outer group is the field's edge; the add-on row inside it is a group too.
		const [field] = canvas.getAllByRole("group");
		if (field === undefined) {
			throw new Error("The composer has no field group.");
		}
		// The name's own box, which `truncate` clips: it is the parent of the name.
		const shown = canvas.getByText(/Wayfinding for returning/u).parentElement ?? field;
		await expect(shown.getBoundingClientRect().right).toBeLessThanOrEqual(
			field.getBoundingClientRect().right,
		);
	},
};
