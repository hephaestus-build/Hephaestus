import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { FEEDBACK_MODE_DESCRIPTIONS } from "@/lib/practice-feedback-mode";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { FeedbackModeLadder } from "./FeedbackModeLadder";

const LAYOUT_SLACK_PX = 1;

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
	component: FeedbackModeLadder,
	parameters: {
		layout: "padded",
		viewport: { defaultViewport: "desktop" },
	},
	args: {
		label: "Workspace feedback mode",
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
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<FeedbackModeLadder
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
} satisfies Meta<typeof FeedbackModeLadder>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Full: Story = {
	args: { variant: "full" },
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		await expect(radios).toHaveLength(3);
		await expect(canvas.getByRole("radiogroup")).toHaveAccessibleName("Workspace feedback mode");
		const automatic = canvas.getByRole("radio", { name: "Automatic" });
		await expect(automatic).toBeChecked();
		await expect(automatic).toHaveAccessibleDescription(FEEDBACK_MODE_DESCRIPTIONS.AUTOMATIC);
		await expect(canvas.getByRole("radio", { name: "Off" })).not.toBeChecked();

		await expectRungsConnected(rungsOf(radios));
	},
};

export const FullInDarkMode: Story = {
	args: { variant: "full" },
	globals: { theme: "dark" },
};

export const Narrow: Story = {
	args: { variant: "full" },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		await expect(canvas.getByRole("radio", { name: "Automatic" })).toBeChecked();
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
	args: { variant: "compact", value: "APPROVE_FIRST" },
	play: async ({ canvas }) => {
		const radios = canvas.getAllByRole("radio");
		await expect(canvas.getByRole("radio", { name: "Approve first" })).toBeChecked();
		await expect(
			canvas.getByRole("radio", { name: "Approve first" }),
		).not.toHaveAccessibleDescription();
		await expectRungsConnected(rungsOf(radios));
	},
};

export const CompactNarrow: Story = {
	args: { variant: "compact", value: "APPROVE_FIRST" },
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
	args: { variant: "compact", value: "APPROVE_FIRST", muted: true },
};

export const Choosing: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: { variant: "full", value: "AUTOMATIC" },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Approve first" }));
		await expect(args.onChange).toHaveBeenCalledWith("APPROVE_FIRST");
		await expect(canvas.getByRole("radio", { name: "Approve first" })).toBeChecked();
	},
};

export const ChoosingByTheWholeRung: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: { variant: "full", value: "OFF" },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByText(FEEDBACK_MODE_DESCRIPTIONS.AUTOMATIC));
		await expect(args.onChange).toHaveBeenCalledWith("AUTOMATIC");
		await expect(canvas.getByRole("radio", { name: "Automatic" })).toBeChecked();
	},
};

export const ChoosingByKeyboard: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: { variant: "full", value: "OFF" },
	play: async ({ args, canvas, userEvent }) => {
		const off = canvas.getByRole("radio", { name: "Off" });
		await userEvent.tab();
		await expect(off).toHaveFocus();
		await userEvent.keyboard("{ArrowDown}");
		await expect(args.onChange).toHaveBeenCalledWith("APPROVE_FIRST");
		const approveFirst = canvas.getByRole("radio", { name: "Approve first" });
		await expect(approveFirst).toHaveFocus();
		await expect(approveFirst).toBeChecked();
	},
};

/** A rung is no choice card, so it has no edge to carry focus, and the radio keeps its own ring. */
export const FocusedRadioKeepsItsRing: Story = {
	args: { variant: "full", value: "OFF" },
	play: async ({ canvas, userEvent }) => {
		const off = canvas.getByRole("radio", { name: "Off" });
		const restEdge = getComputedStyle(off).borderTopColor;
		await userEvent.tab();
		await expect(off).toHaveFocus();
		await expect(getComputedStyle(off).borderTopColor).not.toBe(restEdge);
	},
};

export const ReSelectingTheRungInForceWritesNothing: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	args: { variant: "full", value: "APPROVE_FIRST" },
	play: async ({ args, canvas, userEvent }) => {
		const approveFirst = canvas.getByRole("radio", { name: "Approve first" });
		await expect(approveFirst).toBeChecked();
		await userEvent.click(approveFirst);
		await expect(args.onChange).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("radio", { name: "Automatic" }));
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
