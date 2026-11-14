import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, screen, waitFor } from "storybook/test";

import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { expectSettledVisible } from "@/stories/overlay";

/** The spinner is decoration, hidden from assistive technology, so no query by role finds it. */
const SPINNER = '[data-slot="spinner"]';

const ITEMS = [
	{ value: "propose", label: "Propose" },
	{ value: "deliver", label: "Deliver" },
];

function ModeSelect({ pending }: { pending?: boolean }) {
	return (
		<Select items={ITEMS} defaultValue="propose">
			<SelectTrigger aria-label="Feedback mode" pending={pending}>
				<SelectValue />
			</SelectTrigger>
			<SelectContent aria-label="Feedback mode">
				<SelectItem value="propose">Propose</SelectItem>
				<SelectItem value="deliver">Deliver</SelectItem>
			</SelectContent>
		</Select>
	);
}

/** `pending` on the trigger shows progress after a second and does not block a new choice. */
const meta = {
	component: ModeSelect,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof ModeSelect>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Pending: Story = {
	args: { pending: true },
	play: async ({ canvas, userEvent }) => {
		const trigger = canvas.getByRole("combobox", { name: "Feedback mode" });
		await waitFor(async () => expect(trigger.querySelector(SPINNER)).toBeVisible(), {
			timeout: 3000,
		});
		await userEvent.click(trigger);
		await expectSettledVisible(await screen.findByRole("option", { name: "Deliver" }));
	},
};
