import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectSettledVisible } from "@/test/overlay";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";

const context = {
	pagePath: "/w/engineering/practices",
	userAgent: "Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0",
};

const meta = {
	title: "Surveys/Product feedback dialog",
	component: ProductFeedbackDialog,
	args: {
		open: true,
		onOpenChange: fn(),
		kind: "FEEDBACK",
		onKindChange: fn(),
		context,
		isSubmitting: false,
		onSubmit: fn(() => Promise.resolve(true)),
	},
	render: (args) => (
		<Stateful initial={args.kind}>
			{(kind, setKind) => (
				<ProductFeedbackDialog
					{...args}
					kind={kind}
					onKindChange={(next) => {
						args.onKindChange(next);
						setKind(next);
					}}
				/>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** Feedback attaches nothing unless the sender ticks the box. */
export const SendsFeedbackWithoutContext: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(
			dialog.getByRole("checkbox", { name: "Include page and browser details" }),
		).not.toBeChecked();
		await userEvent.type(
			dialog.getByRole("textbox", { name: "Message" }),
			"The survey flow is clear.",
		);
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith({
			kind: "FEEDBACK",
			message: "The survey flow is clear.",
			pagePath: undefined,
			userAgent: undefined,
		});
	},
};

/** A bug report starts with the details ticked, and shows exactly what they are. */
export const BugReport: Story = {
	args: { kind: "BUG" },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(
			dialog.getByRole("checkbox", { name: "Include page and browser details" }),
		).toBeChecked();
		await expectSettledVisible(dialog.getByText(context.pagePath));
		await userEvent.type(dialog.getByRole("textbox", { name: "Message" }), "The list jumps.");
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith({
			kind: "BUG",
			message: "The list jumps.",
			pagePath: context.pagePath,
			userAgent: context.userAgent,
		});
	},
};

export const Sending: Story = {
	args: { isSubmitting: true },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("button", { name: "Sending…" }));
		await expect(dialog.getByRole("textbox", { name: "Message" })).toBeDisabled();
	},
};

export const Error: Story = {
	args: {
		error: "Couldn't send. Your draft is still here.",
		onSubmit: fn(() => Promise.resolve(false)),
	},
	play: async () => {
		await expectSettledVisible(within(await screen.findByRole("dialog")).getByRole("alert"));
	},
};

/** Without a page, the context choice is not offered at all. */
export const NoContext: Story = {
	args: { context: undefined },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.queryByRole("checkbox")).toBeNull();
	},
};
