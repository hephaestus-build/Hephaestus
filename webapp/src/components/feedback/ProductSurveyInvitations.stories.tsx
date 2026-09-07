import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { expectSettledVisible } from "@/test/overlay";

import { productSurvey } from "./product-survey-fixtures";
import { ProductSurveyInvitations } from "./ProductSurveyInvitations";

const meta = {
	title: "Surveys/Product survey invitations",
	component: ProductSurveyInvitations,
	args: {
		surveys: [productSurvey],
		isLoading: false,
		loadError: false,
		isPending: false,
		onRetry: fn(),
		onSelect: fn(),
		onSubmit: fn(() => Promise.resolve(true)),
		onDismiss: fn(() => Promise.resolve(true)),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof ProductSurveyInvitations>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
export const Empty: Story = {
	args: { surveys: [] },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Product surveys" }));
		await expectSettledVisible(
			within(await screen.findByRole("dialog")).getByText(/all caught up/),
		);
	},
};
export const Loading: Story = {
	args: { surveys: [], isLoading: true },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Product surveys" }));
		await expectSettledVisible(
			within(await screen.findByRole("dialog")).getByLabelText("Loading surveys"),
		);
	},
};
export const LoadError: Story = {
	args: { surveys: [], loadError: true },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Product surveys" }));
		await userEvent.click(
			within(await screen.findByRole("dialog")).getByRole("button", {
				name: "Try again",
			}),
		);
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};
export const SubmissionError: Story = {
	args: {
		error: "Couldn't send. Your draft is still here.",
		onSubmit: fn(() => Promise.resolve(false)),
	},
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Product surveys (1 available)" }));
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Take survey" }));
		await expect(args.onSelect).toHaveBeenCalledOnce();
		await userEvent.click(dialog.getByRole("radio", { name: "4" }));
		await userEvent.click(dialog.getByRole("button", { name: "Submit response" }));
		await expect(args.onSubmit).toHaveBeenCalledWith(productSurvey.id, { useful: "4" });
		await expectSettledVisible(dialog.getByRole("alert"));
		await expect(dialog.getByRole("radio", { name: "4" })).toBeChecked();
	},
};
export const Mobile: Story = {
	globals: { viewport: { value: "reflow" } },
	parameters: { chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Product surveys (1 available)" }));
		await expectSettledVisible(
			within(await screen.findByRole("dialog")).getByRole("button", { name: "Take survey" }),
		);
	},
};

export const NoAutomaticInterruption: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Product surveys (1 available)" }),
		).toBeEnabled();
		await expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
	},
};

export const CloseAndResumeDraft: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Product surveys (1 available)" }));
		let dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Take survey" }));
		await userEvent.type(
			dialog.getByRole("textbox", { name: /What should improve/ }),
			"Clearer feedback",
		);
		await expect(args.onSelect).toHaveBeenCalledOnce();
		await userEvent.keyboard("{Escape}");
		await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
		await userEvent.click(canvas.getByRole("button", { name: "Product surveys (1 available)" }));
		dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getByRole("textbox", { name: /What should improve/ })).toHaveValue(
			"Clearer feedback",
		);
		await expect(args.onDismiss).not.toHaveBeenCalled();
	},
};
