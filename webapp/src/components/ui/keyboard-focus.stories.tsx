import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import type { ReactNode } from "react";
import { expect } from "storybook/test";

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

/**
 * The specimens do not transition, so a play reads settled colours: a transition reports its start
 * value at first, which is the rest value, and would hide a ring that should not be there.
 */
function Specimen({ children }: { children: ReactNode }) {
	return <div className="max-w-sm **:transition-none">{children}</div>;
}

const card = (control: HTMLElement) => {
	const label = control.closest('[data-slot="field-label"]');
	if (label === null) {
		throw new Error("The control is not inside a choice card.");
	}
	return label;
};

async function expectCardTakesTheRing(
	{ userEvent }: Pick<StoryContext, "userEvent">,
	control: HTMLElement,
) {
	const cardEdge = getComputedStyle(card(control)).borderTopColor;
	const controlEdge = getComputedStyle(control).borderTopColor;

	await userEvent.tab();
	await expect(control).toHaveFocus();
	await expect(getComputedStyle(card(control)).borderTopColor).not.toBe(cardEdge);
	// One ring for one focus: the card's. The control keeps its rest edge and paints no ring.
	await expect(getComputedStyle(control).borderTopColor).toBe(controlEdge);
	await expect(paintedLayers(getComputedStyle(control).boxShadow)).toEqual([]);
}

export const CheckboxCardTakesTheFocusRing: Story = {
	render: () => (
		<Specimen>
			<FieldGroup>
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
		</Specimen>
	),
	play: async ({ canvas, userEvent }) => {
		await expectCardTakesTheRing({ userEvent }, canvas.getByRole("checkbox"));
	},
};

export const SwitchCardTakesTheFocusRing: Story = {
	render: () => (
		<Specimen>
			<FieldGroup>
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
		</Specimen>
	),
	play: async ({ canvas, userEvent }) => {
		await expectCardTakesTheRing({ userEvent }, canvas.getByRole("switch"));
	},
};

export const RadioCardTakesTheFocusRing: Story = {
	render: () => (
		<Specimen>
			<RadioGroup aria-label="Review depth" defaultValue="quick">
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
		</Specimen>
	),
	play: async ({ canvas, userEvent }) => {
		await expectCardTakesTheRing({ userEvent }, canvas.getByRole("radio", { name: /Quick/u }));
	},
};

/** An invalid control keeps its error edge and ring on focus; the card adds the focus ring around it. */
export const InvalidCardKeepsTheErrorRing: Story = {
	render: () => (
		<Specimen>
			<FieldGroup>
				<FieldLabel htmlFor="focus-terms">
					<Field orientation="horizontal" data-invalid>
						<Checkbox id="focus-terms" aria-invalid />
						<FieldContent>
							<FieldTitle>Accept the terms</FieldTitle>
							<FieldDescription>Required to continue.</FieldDescription>
						</FieldContent>
					</Field>
				</FieldLabel>
			</FieldGroup>
		</Specimen>
	),
	play: async ({ canvas, userEvent }) => {
		const control = canvas.getByRole("checkbox");
		const cardEdge = getComputedStyle(card(control)).borderTopColor;
		const rest = getComputedStyle(control);
		const errorEdge = rest.borderTopColor;
		const errorRing = rest.boxShadow;

		await userEvent.tab();
		await expect(control).toHaveFocus();
		await expect(getComputedStyle(card(control)).borderTopColor).not.toBe(cardEdge);
		await expect(getComputedStyle(control).borderTopColor).toBe(errorEdge);
		await expect(getComputedStyle(control).boxShadow).toBe(errorRing);
	},
};
