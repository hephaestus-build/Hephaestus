import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { PageError } from "./PageError";

/**
 * A page that cannot be shown, in place of its content. Retry appears only on a failed load that
 * running the page again can fix: a missing page or a refused one would fail the same way again.
 */
const onRetry = fn();

const meta = {
	component: PageError,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { cause: "failed", onRetry },
} satisfies Meta<typeof PageError>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "We could not load this page" }),
		).toBeVisible();
		await expect(
			canvas.getByText("Try again. If it keeps failing, contact your instance operator."),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(onRetry).toHaveBeenCalledOnce();
		await expect(canvas.getByRole("link", { name: "Go to home page" })).toHaveAttribute(
			"href",
			"/",
		);
	},
};

/** A failure that a retry cannot fix, such as a refused request, sends the reader on instead. */
export const FailedForGood: Story = {
	args: { cause: "failed", onRetry: undefined },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "We could not load this page" }),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Go to home page" })).toHaveAttribute(
			"href",
			"/",
		);
	},
};

export const NotFound: Story = {
	args: { cause: "not-found" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "We could not find this page" }),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Go to home page" })).toHaveAttribute(
			"href",
			"/",
		);
	},
};

export const NoAccess: Story = {
	args: { cause: "no-access" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { level: 1, name: "You do not have access to this page" }),
		).toBeVisible();
		await expect(
			canvas.getByText("Ask a workspace admin or instance admin for access."),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

/** Retry and the way home wrap rather than push the page sideways. */
export const OnAPhone: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
