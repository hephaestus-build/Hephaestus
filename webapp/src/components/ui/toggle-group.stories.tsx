import type { Meta, StoryObj } from "@storybook/react-vite";
import { CodeIcon, TextIcon } from "lucide-react";
import { expect } from "storybook/test";

import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

/**
 * A regression suite for one ARIA invariant, not a design-system entry for the primitive.
 *
 * ARIA 1.2 lists `aria-orientation` as used in `scrollbar`, `select`, `separator`, `slider`,
 * `tablist` and `toolbar`. `group` is not one of them, so a `role="group"` carrying it fails axe's
 * `aria-allowed-attr`. Both Base UI and Radix have shipped that combination before
 * (radix-ui/primitives#964), and the composite behaviour these groups rely on is the same code that
 * once emitted it — so the absence is asserted here rather than assumed to stay true.
 *
 * The visual axis stays readable from `data-orientation`, which is what the styles key off.
 *
 * @see https://www.w3.org/TR/wai-aria-1.2/#group
 */
const meta = {
	component: ToggleGroup,
	parameters: { layout: "padded", chromatic: { disableSnapshot: true } },
	tags: ["autodocs"],
} satisfies Meta<typeof ToggleGroup>;

export default meta;
type Story = StoryObj<typeof meta>;

function ViewSwitch({ orientation }: { orientation?: "horizontal" | "vertical" }) {
	return (
		<ToggleGroup
			orientation={orientation}
			variant="outline"
			size="sm"
			defaultValue={["rendered"]}
			aria-label="How to show the feedback"
		>
			<ToggleGroupItem value="rendered">
				<TextIcon aria-hidden />
				Rendered
			</ToggleGroupItem>
			<ToggleGroupItem value="source">
				<CodeIcon aria-hidden />
				Source
			</ToggleGroupItem>
		</ToggleGroup>
	);
}

/**
 * The default axis. The group keeps `role="group"` and carries no `aria-orientation`, while the
 * visual axis stays readable from `data-orientation` for styling.
 */
export const HorizontalHasNoAriaOrientation: Story = {
	// The point of these three is a whole configured group, so `args` never reaches the primitive and
	// the panel would edit nothing. Disabled rather than left to look live.
	parameters: { controls: { disable: true } },
	render: () => <ViewSwitch />,
	play: async ({ canvas }) => {
		const group = canvas.getByRole("group", {
			name: "How to show the feedback",
		});
		await expect(group).not.toHaveAttribute("aria-orientation");
		await expect(group).toHaveAttribute("data-orientation", "horizontal");
	},
};

/**
 * The axis a caller actually changes. `orientation` reaches the primitive — so `CompositeRoot`
 * moves focus with Up/Down rather than Left/Right — without the attribute coming back.
 */
export const VerticalHasNoAriaOrientation: Story = {
	parameters: { controls: { disable: true } },
	render: () => <ViewSwitch orientation="vertical" />,
	play: async ({ canvas }) => {
		const group = canvas.getByRole("group", {
			name: "How to show the feedback",
		});
		await expect(group).not.toHaveAttribute("aria-orientation");
		await expect(group).toHaveAttribute("data-orientation", "vertical");
	},
};

/**
 * A caller who wants the attribute — on a role that ARIA allows it on — can still set it. The
 * suppression sits before the prop spread, so it is a default and not a lock.
 */
export const ExplicitAriaOrientationSurvives: Story = {
	parameters: { controls: { disable: true } },
	render: () => (
		<ToggleGroup
			role="toolbar"
			aria-orientation="horizontal"
			variant="outline"
			size="sm"
			defaultValue={["rendered"]}
			aria-label="How to show the feedback"
		>
			<ToggleGroupItem value="rendered">Rendered</ToggleGroupItem>
			<ToggleGroupItem value="source">Source</ToggleGroupItem>
		</ToggleGroup>
	),
	play: async ({ canvas }) => {
		const group = canvas.getByRole("toolbar", {
			name: "How to show the feedback",
		});
		await expect(group).toHaveAttribute("aria-orientation", "horizontal");
	},
};
