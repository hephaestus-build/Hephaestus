import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";

const meta = {
	component: ProductFeedbackDialog,
	args: {
		open: true,
		onOpenChange: fn(),
		reportsLink: <a href="/product-feedback">Your reports</a>,
		pagePath: "/w/$workspaceSlug/practice-profile",
		sharing: { status: "off" },
		isSubmitting: false,
		onSubmit: fn(async () => true),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackDialog>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const SendsFeedbackWithoutContext: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(
			dialog.getByRole("textbox", { name: "Your feedback" }),
			"The list jumps when I open it.",
		);
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(dialog.getByRole("textbox", { name: "Your feedback" })).toHaveValue("");
	},
};

export const EmptySubmission: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(dialog.getByText("Write your feedback before sending.")).toBeVisible();
		await expect(dialog.getByRole("textbox", { name: "Your feedback" })).toHaveFocus();
	},
};

export const NearTheLimit: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		const field = dialog.getByRole("textbox", { name: "Your feedback" });
		await expect(dialog.queryByText(/characters left$/u)).toBeNull();
		await userEvent.click(field);
		await userEvent.paste("x".repeat(4600));
		await expectSettledVisible(dialog.getByText("400 characters left"));
	},
};

export const Sending: Story = {
	args: { isSubmitting: true },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getByRole("button", { name: "Sending…" })).toBeDisabled();
		await expect(dialog.getByRole("textbox", { name: "Your feedback" })).toBeDisabled();
	},
};

export const Error: Story = {
	args: {
		error:
			"We could not confirm receipt. Your draft is still here. Check Your reports before trying again.",
		onSubmit: fn(async () => false),
	},
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getByRole("alert")).toBeVisible();
		await expect(dialog.getByRole("link", { name: "Your reports" })).toBeVisible();
	},
};

export const NoPage: Story = { args: { pagePath: undefined } };

/** The first report: Send works at once, and nothing is ticked for the person. */
export const FirstReport: Story = {
	args: { sharing: { status: "public", remembered: false } },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(
			dialog.getByRole("textbox", { name: "Your feedback" }),
			"Let me pin a practice.",
		);
		await expect(
			dialog.getByRole("checkbox", { name: "Post as a public GitHub issue" }),
		).not.toBeChecked();
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ share: false }));
	},
};

export const RememberedPublic: Story = {
	args: { sharing: { status: "public", remembered: true } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(
			dialog.getByRole("checkbox", { name: "Post as a public GitHub issue" }),
		).toBeChecked();
	},
};
export const PrivateRepository: Story = { args: { sharing: { status: "private" } } };
export const SharingLoading: Story = { args: { sharing: { status: "loading" } } };

/** A failed lookup never blocks a report: it stays private, and the toast says so. */
export const SharingError: Story = {
	args: { sharing: { status: "error" } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getByRole("button", { name: "Send" })).toBeEnabled();
		await expect(dialog.queryByRole("checkbox")).toBeNull();
	},
};

export const Narrow: Story = {
	args: { sharing: { status: "public", remembered: false } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await screen.findByRole("checkbox", { name: "Post as a public GitHub issue" });
		await expectNoPageOverflow();
	},
};
