import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";

const context = {
	pagePath: "/w/$workspaceSlug/practice-profile",
	userAgent: "Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0",
};

const meta = {
	component: ProductFeedbackDialog,
	args: {
		open: true,
		onOpenChange: fn(),
		onRetrySharing: fn(),
		reportsLink: <a href="/product-feedback">Your reports</a>,
		context,
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
		await userEvent.click(dialog.getByRole("button", { name: "Send feedback" }));
		await expect(dialog.getByRole("textbox", { name: "Your feedback" })).toHaveValue("");
	},
};

export const OptionalPageDetails: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByText("Add page details (optional)"));
		const attach = dialog.getByRole("checkbox", { name: "Include page and browser details" });
		await expect(attach).not.toBeChecked();
		await expect(dialog.getByText(context.pagePath)).toBeVisible();
		await expect(dialog.getByText(context.userAgent)).toBeVisible();
		await userEvent.click(attach);
		await expect(dialog.getByText("Page details included")).toBeVisible();
	},
};

export const EmptySubmission: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Send feedback" }));
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

export const NoContext: Story = { args: { context: undefined } };
export const FirstTimeChoice: Story = {
	args: { sharing: { status: "ask" } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(
			dialog.getByRole("textbox", { name: "Your feedback" }),
			"Let me pin a practice.",
		);
		const keep = await dialog.findByRole("radio", { name: "Keep private" });
		await expect(keep).not.toBeChecked();
		await expect(dialog.getByRole("radio", { name: "Share publicly" })).not.toBeChecked();
		await userEvent.click(dialog.getByRole("button", { name: "Send feedback" }));
		await expect(dialog.getByText("Choose whether your report can become public.")).toBeVisible();
		await expect(keep).toHaveFocus();
		await userEvent.click(keep);
		await expect(dialog.queryByText("Choose whether your report can become public.")).toBeNull();
	},
};

export const RememberedPublic: Story = {
	args: { sharing: { status: "remembered", allowPublic: true } },
};
export const RememberedPrivate: Story = {
	args: { sharing: { status: "remembered", allowPublic: false } },
};
export const PrivateRepository: Story = { args: { sharing: { status: "private" } } };
export const SharingLoading: Story = { args: { sharing: { status: "loading" } } };
export const SharingError: Story = {
	args: { sharing: { status: "error" } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getByRole("button", { name: "Retry" })).toBeVisible();
		await expect(dialog.getByRole("button", { name: "Send feedback" })).toBeDisabled();
	},
};

export const Narrow: Story = {
	args: { sharing: { status: "ask" } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await screen.findByRole("radio", { name: "Share publicly" });
		await expectNoPageOverflow();
	},
};
