import type { Meta, StoryObj } from "@storybook/react-vite";
import { SaveIcon } from "lucide-react";
import { type ComponentProps, useState } from "react";
import { expect, fn, waitFor } from "storybook/test";

import { Button } from "@/components/ui/button";

/**
 * `pending` marks a button whose action is in flight. It acts at once and shows itself late: the
 * button refuses a second press at once and keeps the focus of the reader who pressed it, but it
 * looks the same until the action has taken a second. Only then does the spinner take the place of
 * the leading icon, because a spinner that appears and vanishes in under a second reads as a fault
 * (`webapp/DESIGN.md` § Loading and errors). The caller owns the label ("Saving…").
 */
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
 * The press that starts the action. At once, a second press does nothing and the focus stays on the
 * button, while the button looks as it did. The spinner follows only after the delay.
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
