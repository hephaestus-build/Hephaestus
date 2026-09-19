import type { Meta, StoryObj } from "@storybook/react";
import { type ReactNode, useState } from "react";
import { fn, screen, userEvent, within } from "storybook/test";

import { expectControlOnScreen, expectDialogFitsViewport } from "@/stories/reflow";

import { ConfirmDialog, type ConfirmDialogProps } from "./ConfirmDialog";

interface Row {
	id: number;
	displayName: string;
}

const row: Row = { id: 7, displayName: "GPT-5" };

function ConfirmHarness(props: ConfirmDialogProps<Row>) {
	const [subject, setSubject] = useState<Row | null>(props.subject);
	const [deleted, setDeleted] = useState<Row | null>(null);
	return (
		<>
			<ConfirmDialog
				{...props}
				subject={subject}
				onConfirm={(confirmed) => {
					props.onConfirm(confirmed);
					setDeleted(confirmed);
				}}
				onClose={() => {
					props.onClose();
					setSubject(null);
				}}
			/>
			{deleted != null && <p>Deleted “{deleted.displayName}”</p>}
		</>
	);
}

const meta = {
	component: ConfirmDialog as (props: ConfirmDialogProps<Row>) => ReactNode,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		subject: row,
		title: (subject: Row) => `Delete “${subject.displayName}”?`,
		description:
			"A model still bound to a workspace's agent can't be deleted. This cannot be undone.",
		confirmLabel: "Delete",
		onConfirm: fn(),
		onClose: fn(),
	},
} satisfies Meta<ConfirmDialogProps<Row>>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const CustomVerbs: Story = {
	args: {
		title: (subject: Row) => `Turn off “${subject.displayName}”?`,
		description: (subject: Row) =>
			`This immediately stops requests through every model on ${subject.displayName}.`,
		confirmLabel: "Turn off connection",
		cancelLabel: "Keep active",
	},
};

export const LongName: Story = {
	args: {
		subject: { id: 8, displayName: "gpt-5-turbo-preview-2026-07-01-eu-central-fallback" },
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectDialogFitsViewport();
		await expectControlOnScreen(screen.getByRole("button", { name: /^cancel$/iu }));
		await expectControlOnScreen(screen.getByRole("button", { name: /^delete$/iu }));
	},
};

export const Confirming: Story = {
	render: (args) => <ConfirmHarness {...args} />,
	play: async ({ canvas }) => {
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Delete" }));

		canvas.getByText("Deleted “GPT-5”");
	},
};
