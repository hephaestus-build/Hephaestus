import type { Meta, StoryObj } from "@storybook/react-vite";
import { SaveIcon } from "lucide-react";
import { type ComponentProps, useState } from "react";
import { expect, fn, waitFor } from "storybook/test";

import { Button } from "@/components/ui/button";

/** `pending` and its timing are described on `Button`; `webapp/DESIGN.md` § Loading and errors owns the rule. */
const meta = {
	component: Button,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { children: "Save", onClick: fn() },
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The spinner is decoration, hidden from assistive technology, so no query by role finds it. */
const SPINNER = '[data-slot="spinner"]';

/** One button, driven by the controls panel. */
export const Default: Story = {};

/** An action that has taken longer than a second: the spinner stands in for the leading icon. */
export const Pending: Story = {
	args: {
		pending: true,
		children: (
			<>
				<SaveIcon aria-hidden />
				Saving…
			</>
		),
	},
	play: async ({ canvas }) => {
		const button = canvas.getByRole("button", { name: "Saving…" });
		await expect(button).toHaveAttribute("aria-disabled", "true");
		await waitFor(async () => expect(button.querySelector(SPINNER)).toBeVisible(), {
			timeout: 3000,
		});
		await expect(button.querySelector("svg.lucide-save")).not.toBeVisible();
	},
};

function SaveOnPress({ onClick }: Pick<ComponentProps<typeof Button>, "onClick">) {
	const [saving, setSaving] = useState(false);
	return (
		<Button
			pending={saving}
			onClick={(event) => {
				onClick?.(event);
				setSaving(true);
			}}
		>
			<SaveIcon aria-hidden />
			{saving ? "Saving…" : "Save"}
		</Button>
	);
}

/**
 * The press that starts the action. At once, the label says "Saving…", a second press does nothing
 * and the focus stays on the button. The spinner follows only after the delay.
 */
export const PendingAfterPress: Story = {
	render: ({ onClick }) => <SaveOnPress onClick={onClick} />,
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Save" }));
		const button = canvas.getByRole("button", { name: "Saving…" });
		await expect(button).toHaveAttribute("aria-disabled", "true");
		// Not the native attribute, which would drop the focus of the reader who pressed it.
		await expect(button).not.toHaveAttribute("disabled");
		await expect(button).toHaveFocus();
		await expect(button.querySelector(SPINNER)).toBeNull();
		await expect(button.querySelector("svg.lucide-save")).toBeVisible();

		await userEvent.click(button);
		await userEvent.keyboard("{Enter}");
		await expect(args.onClick).toHaveBeenCalledOnce();

		await waitFor(async () => expect(button.querySelector(SPINNER)).toBeVisible(), {
			timeout: 3000,
		});
	},
};
