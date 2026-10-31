import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { PageError } from "./PageError";

/**
 * A page that cannot be shown, in place of its content. Retry appears only on a failed load that
 * running the page again can fix: a missing page or a refused one would fail the same way again.
 */
const meta = {
	component: PageError,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { cause: "failed", onRetry: fn() },
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
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.getByRole("link", { name: "Go to the home page" })).toHaveAttribute(
			"href",
			"/",
		);
	},
};

const REFERENCE = "4bf92f3577b34da6a3ce929d0e0e4736";

/** Answers each of the page's clipboard writes with `written`, and returns the spy that records them. */
function clipboardAnswering(written: () => Promise<void>) {
	const writeText = fn(written);
	Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
	return writeText;
}

/**
 * A failed request names its reference, the server's id for it. The reader reads out the first
 * eight characters, or copies the whole reference for the operator.
 */
export const WithReference: Story = {
	args: { cause: "failed", onRetry: fn(), reference: REFERENCE },
	play: async ({ canvas, userEvent }) => {
		const writeText = clipboardAnswering(async () => undefined);
		await expect(canvas.getByText("4bf92f35")).toBeVisible();

		await userEvent.click(canvas.getByRole("button", { name: "Copy reference" }));

		await expect(writeText).toHaveBeenCalledWith(REFERENCE);
		await expect(await within(document.body).findByText("Reference copied")).toBeVisible();
	},
};

/** A refused clipboard write tells the reader, who can still read the reference out. */
export const ReferenceNotCopied: Story = {
	args: { cause: "failed", reference: REFERENCE },
	play: async ({ canvas, userEvent }) => {
		clipboardAnswering(async () => {
			throw new DOMException("Denied", "NotAllowedError");
		});

		await userEvent.click(canvas.getByRole("button", { name: "Copy reference" }));

		await expect(
			await within(document.body).findByText("We could not copy that to the clipboard. Try again."),
		).toBeVisible();
		await expect(canvas.getByText("4bf92f35")).toBeVisible();
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
		await expect(canvas.getByRole("link", { name: "Go to the home page" })).toHaveAttribute(
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
		// The next step names the action this page offers, not "go back".
		await expect(
			canvas.getByText("It may have been deleted or moved. Go to the home page to continue."),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Go to the home page" })).toHaveAttribute(
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

/** Retry, the way home and the reference wrap rather than push the page sideways. */
export const OnAPhone: Story = {
	args: { cause: "failed", onRetry: fn(), reference: REFERENCE },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
