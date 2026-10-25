import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, waitFor } from "storybook/test";

import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldGroup,
	FieldLabel,
	FieldTitle,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Switch } from "@/components/ui/switch";
import { paintedLayers } from "@/stories/focus";

/**
 * A choice card is a `FieldLabel` around a `Field` and its control. When the control has keyboard
 * focus, the card takes the ring on its edge and the small control drops its own, so the whole
 * choice reads as focused.
 */
const meta = {
	parameters: { layout: "padded", controls: { disable: true } },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

async function expectCardTakesTheRing(
	{ userEvent }: Pick<StoryContext, "userEvent">,
	control: HTMLElement,
) {
	const card = control.closest('[data-slot="field-label"]');
	if (card === null) {
		throw new Error("The control is not inside a choice card.");
	}
	const restEdge = getComputedStyle(card).borderTopColor;
	await expect(paintedLayers(getComputedStyle(card).boxShadow)).toHaveLength(0);

	await userEvent.tab();
	await expect(control).toHaveFocus();
	// The switch transitions every property, so its ring would read 0px while it is still growing.
	await waitFor(() => {
		void expect(control.getAnimations().filter((a) => a.playState === "running")).toEqual([]);
	});
	await expect(getComputedStyle(card).borderTopColor).not.toBe(restEdge);
	await expect(paintedLayers(getComputedStyle(card).boxShadow)).toHaveLength(1);
	// One ring for one focus: the card's, not a second one on the control inside it.
	await expect(paintedLayers(getComputedStyle(control).boxShadow)).toHaveLength(0);
}

export const CheckboxCardTakesTheFocusRing: Story = {
	render: () => (
		<FieldGroup className="max-w-sm">
			<FieldLabel htmlFor="focus-weekly-digest">
				<Field orientation="horizontal">
					<Checkbox id="focus-weekly-digest" />
					<FieldContent>
						<FieldTitle>Weekly digest</FieldTitle>
						<FieldDescription>A summary of your practice feedback every Monday.</FieldDescription>
					</FieldContent>
				</Field>
			</FieldLabel>
		</FieldGroup>
	),
	play: async ({ canvas, userEvent }) => {
		await expectCardTakesTheRing({ userEvent }, canvas.getByRole("checkbox"));
	},
};

export const SwitchCardTakesTheFocusRing: Story = {
	render: () => (
		<FieldGroup className="max-w-sm">
			<FieldLabel htmlFor="focus-mentions">
				<Field orientation="horizontal">
					<FieldContent>
						<FieldTitle>Mentions</FieldTitle>
						<FieldDescription>Tell me when someone mentions me.</FieldDescription>
					</FieldContent>
					<Switch id="focus-mentions" />
				</Field>
			</FieldLabel>
		</FieldGroup>
	),
	play: async ({ canvas, userEvent }) => {
		await expectCardTakesTheRing({ userEvent }, canvas.getByRole("switch"));
	},
};

export const RadioCardTakesTheFocusRing: Story = {
	render: () => (
		<RadioGroup aria-label="Review depth" defaultValue="quick" className="max-w-sm">
			<FieldLabel htmlFor="focus-depth-quick">
				<Field orientation="horizontal">
					<RadioGroupItem id="focus-depth-quick" value="quick" />
					<FieldContent>
						<FieldTitle>Quick</FieldTitle>
						<FieldDescription>Checks the changed lines only.</FieldDescription>
					</FieldContent>
				</Field>
			</FieldLabel>
			<FieldLabel htmlFor="focus-depth-thorough">
				<Field orientation="horizontal">
					<RadioGroupItem id="focus-depth-thorough" value="thorough" />
					<FieldContent>
						<FieldTitle>Thorough</FieldTitle>
						<FieldDescription>Reads the files around the change too.</FieldDescription>
					</FieldContent>
				</Field>
			</FieldLabel>
		</RadioGroup>
	),
	play: async ({ canvas, userEvent }) => {
		await expectCardTakesTheRing({ userEvent }, canvas.getByRole("radio", { name: /Quick/u }));
	},
};
