import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, within } from "storybook/test";

import { Toggle } from "@/components/ui/toggle";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

/**
 * A toggle carries four states at once, and each one owns a different channel so that no two of them
 * can look alike: **hover** paints the background, **selection** sets the border colour and the type
 * weight, **focus-visible** draws the ring, **disabled** drops the opacity.
 *
 * Selection stays off the background on purpose. `--muted`, `--accent` and `--secondary` are one and
 * the same colour in this theme, so any pressed background is also the hover background — which is
 * how a chosen filter came to look exactly like the filter the pointer happened to be resting on, on
 * every segmented picker in the app at once.
 *
 * The weight is not decoration. WCAG 2.2 SC 1.4.1 does not accept a hue as the only visual sign of
 * which item is chosen, and a border colour is still a hue, so the selected segment is also the bold
 * one.
 *
 * @see https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html
 */
const meta = {
	component: Toggle,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { children: "Set by hand", variant: "outline" },
} satisfies Meta<typeof Toggle>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One toggle, driven by the controls panel. */
export const Default: Story = {};

/** The same toggle holding the chosen value. */
export const Pressed: Story = {
	args: { defaultPressed: true },
};

/**
 * The hover specimen wears `bg-muted text-foreground` — the two utilities the base recipe applies on
 * `:hover` — because a play function drives synthetic pointer events, which never place a real
 * pointer over an element and so never match `:hover`. Comparing the pressed toggle against it is
 * still the regression this file exists for: it fails the moment selection borrows that fill back.
 */
function StateBoard() {
	return (
		<div className="flex flex-col gap-6">
			<div className="flex flex-wrap items-center gap-3">
				<Toggle variant="outline" defaultPressed>
					Selected
				</Toggle>
				<Toggle variant="outline">Not selected</Toggle>
				<Toggle variant="outline" className="bg-muted text-foreground">
					Hovered
				</Toggle>
				<Toggle variant="outline" disabled>
					Disabled
				</Toggle>
				<Toggle variant="outline" disabled defaultPressed>
					Disabled and selected
				</Toggle>
			</div>
			<ToggleGroup
				role="toolbar"
				variant="outline"
				size="sm"
				aria-label="Filter practices"
				defaultValue={["all"]}
			>
				<ToggleGroupItem value="all">All</ToggleGroupItem>
				<ToggleGroupItem value="overrides">Set by hand</ToggleGroupItem>
				<ToggleGroupItem value="paused" disabled>
					Paused
				</ToggleGroupItem>
			</ToggleGroup>
		</div>
	);
}

async function expectOneChannelPerState(canvas: StoryContext["canvas"]) {
	const toggleNamed = (name: string) => canvas.getByRole("button", { name });

	const selected = getComputedStyle(toggleNamed("Selected"));
	const plain = getComputedStyle(toggleNamed("Not selected"));
	const hovered = getComputedStyle(toggleNamed("Hovered"));
	const off = toggleNamed("Disabled");
	const groupOff = toggleNamed("Paused");

	await expect(selected.borderTopColor).not.toBe(plain.borderTopColor);
	await expect(Number(selected.fontWeight)).toBeGreaterThan(Number(plain.fontWeight));

	await expect(selected.backgroundColor).toBe(plain.backgroundColor);
	await expect(selected.backgroundColor).not.toBe(hovered.backgroundColor);
	await expect(hovered.borderTopColor).toBe(plain.borderTopColor);
	await expect(hovered.fontWeight).toBe(plain.fontWeight);

	// The focus ring is drawn as a box-shadow, so a selected toggle that shadows exactly like an
	// unselected one leaves that channel to focus alone.
	await expect(selected.boxShadow).toBe(plain.boxShadow);

	await expect(getComputedStyle(off).opacity).toBe("0.5");
	await expect(getComputedStyle(off).borderTopColor).toBe(plain.borderTopColor);
	// Base UI removes the native `disabled` from a group item to keep it focusable and marks it
	// `aria-disabled` instead, so the recipe has to answer to both spellings.
	await expect(groupOff).toHaveAttribute("aria-disabled", "true");
	await expect(getComputedStyle(groupOff).opacity).toBe("0.5");
}

async function expectSegmentsJoinOnce(canvas: StoryContext["canvas"]) {
	const toolbar = canvas.getByRole("toolbar", { name: "Filter practices" });
	const [first, second, third] = within(toolbar).getAllByRole("button");
	if (!first || !second || !third) {
		throw new Error("The segmented run needs three segments");
	}

	// One pixel of overlap per seam, so two neighbouring one-pixel borders read as one line rather
	// than as a two-pixel rule, and the selected segment keeps a border on all four sides.
	await expect(second.getBoundingClientRect().left).toBeCloseTo(
		first.getBoundingClientRect().right - 1,
		0,
	);
	await expect(third.getBoundingClientRect().left).toBeCloseTo(
		second.getBoundingClientRect().right - 1,
		0,
	);

	// The ends of the run are round and its middle is square, which is what makes three buttons look
	// like one control.
	await expect(getComputedStyle(first).borderTopLeftRadius).not.toBe("0px");
	await expect(getComputedStyle(first).borderTopRightRadius).toBe("0px");
	await expect(getComputedStyle(second).borderTopLeftRadius).toBe("0px");
	await expect(getComputedStyle(third).borderTopRightRadius).not.toBe("0px");
}

/** Every state side by side, so a treatment that collides with another one is visible at a glance. */
export const States: Story = {
	// A board of fixed states, not one toggle: there is no single set of args for the panel to drive,
	// so it says so rather than offering controls that edit nothing.
	parameters: { controls: { disable: true } },
	render: () => <StateBoard />,
	play: async ({ canvas }) => {
		await expectOneChannelPerState(canvas);
		await expectSegmentsJoinOnce(canvas);
	},
};

/**
 * The same board on the dark palette, where `--primary` inverts from near-black to white. The class
 * sits on a wrapper rather than on the theme global so that the Docs page can show both palettes at
 * once instead of repainting itself.
 */
export const StatesOnDark: Story = {
	parameters: { controls: { disable: true } },
	render: () => (
		<div className="dark rounded-lg border border-border bg-background p-4 text-foreground">
			<StateBoard />
		</div>
	),
	play: async ({ canvas }) => {
		await expectOneChannelPerState(canvas);
		await expectSegmentsJoinOnce(canvas);
	},
};
