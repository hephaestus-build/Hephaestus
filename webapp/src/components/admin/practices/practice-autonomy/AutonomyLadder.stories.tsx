import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { PRACTICE_AUTONOMY_ADDS } from "@/lib/practice-autonomy";
import { Stateful } from "@/stories/stateful";
import { expectNoPageOverflow } from "@/test/reflow";

import { AutonomyLadder } from "./AutonomyLadder";

const LAYOUT_SLACK_PX = 1;

/**
 * The complaint this component was rebuilt for was "the parts are not connecting", so the regression
 * test is adjacency rather than any class name: three rungs that read as one control have to share
 * edges, along whichever axis the group is laid out on at this width. A rung that rounds its own
 * corners or holds a gap fails this at one of the two widths, which is exactly how the old build broke.
 */
async function expectRungsConnected(rungs: HTMLElement[]) {
	await expect(rungs.length).toBeGreaterThan(1);
	const [firstBox, ...laterBoxes] = rungs.map((rung) => rung.getBoundingClientRect());
	if (!firstBox) {
		throw new Error("The ladder rendered no rungs.");
	}
	let previous = firstBox;
	for (const [offset, current] of laterBoxes.entries()) {
		const stacked =
			Math.abs(current.top - previous.bottom) <= LAYOUT_SLACK_PX &&
			Math.abs(current.left - previous.left) <= LAYOUT_SLACK_PX;
		const sideBySide =
			Math.abs(current.left - previous.right) <= LAYOUT_SLACK_PX &&
			Math.abs(current.top - previous.top) <= LAYOUT_SLACK_PX;
		await expect(
			stacked || sideBySide,
			`Rung ${offset + 2} does not share an edge with the one before it, so the ladder reads as separate fragments.`,
		).toBe(true);
		previous = current;
	}
}

function rungsOf(radios: HTMLElement[]): HTMLElement[] {
	return radios.map((radio) => {
		const rung = radio.closest("label");
		if (rung == null) {
			throw new Error("A rung's radio is not inside a label, so the whole row is not clickable.");
		}
		return rung;
	});
}

const meta = {
	component: AutonomyLadder,
	parameters: {
		layout: "padded",
		// The width this control is actually used at. The narrow branch is a story of its own below:
		// defaulting the whole file to 320px meant every reviewer, and every docs page, opened the
		// stacked layout and never saw the one the workspace settings screen renders.
		viewport: { defaultViewport: "desktop" },
	},
	args: {
		label: "How far reviews go without you",
		value: "AUTOMATIC",
		onChange: fn(),
	},
	decorators: [
		(Story) => (
			<div className="mx-auto w-full max-w-2xl">
				<Story />
			</div>
		),
	],
	tags: ["autodocs"],
	/** Held in state, so the ladder can be moved; `fn()` alone pins every rung to `args.value`. */
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<AutonomyLadder
					{...args}
					value={value}
					onChange={(next) => {
						args.onChange(next);
						setValue(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof AutonomyLadder>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Full: Story = {
	args: { variant: "full" },
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		// Radios, not pressed buttons: the rungs are three states of one setting, and a screen reader
		// should say "Send automatically, selected, 3 of 3" rather than "Off, not pressed" twice over.
		await expect(radios).toHaveLength(3);
		await expect(canvas.getByRole("radiogroup")).toHaveAccessibleName(
			"How far reviews go without you",
		);
		// The chosen rung is announced, not merely tinted (WCAG 2.2 SC 1.4.1), and it is announced under
		// the word the reader can see (SC 2.5.3) with the sentence beside it as its description.
		const automatic = canvas.getByRole("radio", { name: "Send automatically" });
		await expect(automatic).toBeChecked();
		await expect(automatic).toHaveAccessibleDescription(PRACTICE_AUTONOMY_ADDS.AUTOMATIC);
		await expect(canvas.getByRole("radio", { name: "Off" })).not.toBeChecked();

		await expectRungsConnected(rungsOf(radios));
	},
};

/**
 * The width the owner was shown by the old default. The ladder turns its axis and nothing else: one
 * border, one radius, the same lines between the rungs.
 */
export const Narrow: Story = {
	args: { variant: "full" },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		await expect(canvas.getByRole("radio", { name: "Send automatically" })).toBeChecked();
		await expectRungsConnected(rungsOf(radios));
		await expectNoPageOverflow();
	},
};

export const Off: Story = {
	args: { variant: "full", value: "OFF" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("radio", { name: "Off" })).toBeChecked();
	},
};

export const Compact: Story = {
	args: { variant: "compact", value: "HUMAN_APPROVAL" },
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		await expect(canvas.getByRole("radio", { name: "Review before sending" })).toBeChecked();
		// No sentences at this size, so nothing describes the rungs — the word has to be the whole name.
		await expect(
			canvas.getByRole("radio", { name: "Review before sending" }),
		).not.toHaveAccessibleDescription();
		await expectRungsConnected(rungsOf(radios));
	},
};

export const CompactNarrow: Story = {
	args: { variant: "compact", value: "HUMAN_APPROVAL" },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		await expectRungsConnected(rungsOf(canvas.getAllByRole("radio")));
		await expectNoPageOverflow();
	},
};

export const Inherited: Story = {
	args: { variant: "compact", value: "HUMAN_APPROVAL", muted: true },
};

export const Choosing: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: { variant: "full", value: "AUTOMATIC" },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Review before sending" }));
		await expect(args.onChange).toHaveBeenCalledWith("HUMAN_APPROVAL");
		await expect(canvas.getByRole("radio", { name: "Review before sending" })).toBeChecked();
	},
};

/** The whole rung is the target, not the 16px circle: the label wraps the control. */
export const ChoosingByTheWholeRung: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: { variant: "full", value: "OFF" },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByText(PRACTICE_AUTONOMY_ADDS.AUTOMATIC));
		await expect(args.onChange).toHaveBeenCalledWith("AUTOMATIC");
		await expect(canvas.getByRole("radio", { name: "Send automatically" })).toBeChecked();
	},
};

/** Arrow keys move along the ladder — the only way a keyboard reaches the rungs, since one tab stop covers the group. */
export const ChoosingByKeyboard: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: { variant: "full", value: "OFF" },
	play: async ({ args, canvas, userEvent }) => {
		const off = canvas.getByRole("radio", { name: "Off" });
		await userEvent.tab();
		await expect(off).toHaveFocus();
		await userEvent.keyboard("{ArrowDown}");
		await expect(args.onChange).toHaveBeenCalledWith("HUMAN_APPROVAL");
		const humanApproval = canvas.getByRole("radio", { name: "Review before sending" });
		await expect(humanApproval).toHaveFocus();
		await expect(humanApproval).toBeChecked();
	},
};

export const ReSelectingTheRungInForceWritesNothing: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: { variant: "full", value: "HUMAN_APPROVAL" },
	play: async ({ args, canvas, userEvent }) => {
		const humanApproval = canvas.getByRole("radio", { name: "Review before sending" });
		await expect(humanApproval).toBeChecked();
		await userEvent.click(humanApproval);
		await expect(args.onChange).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("radio", { name: "Send automatically" }));
		await expect(args.onChange).toHaveBeenCalledWith("AUTOMATIC");
	},
};

export const Disabled: Story = {
	args: { variant: "compact", value: "OFF", disabled: true },
	play: async ({ canvas }) => {
		for (const radio of canvas.getAllByRole("radio")) {
			await expect(radio).toHaveAttribute("aria-disabled", "true");
		}
	},
};
