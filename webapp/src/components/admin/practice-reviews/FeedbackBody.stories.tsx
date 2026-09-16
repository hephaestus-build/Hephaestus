import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, waitFor, within } from "storybook/test";

import { FeedbackBody } from "./FeedbackBody";

const body =
	"## What worked\n\nThe controller stays focused on HTTP concerns.\n\n[Read the guide](https://example.com/guide).";

const meta = {
	component: FeedbackBody,
	parameters: { layout: "padded", chromatic: { viewports: [320, 768] } },
	tags: ["autodocs"],
	args: {
		feedback: { body, channel: "IN_CONTEXT", deliveryState: "DELIVERED" },
	},
} satisfies Meta<typeof FeedbackBody>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The ordinary case wears no badge: text that reached the developer needs no marking, and the page
 * this card sits on says so once already. The header row is then the view switch alone, sitting on
 * the same left edge as the note below it.
 */
export const Delivered: Story = {
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { level: 4, name: "What worked" });
		await expect(canvas.queryByText("Delivered")).not.toBeInTheDocument();
		canvas.getByRole("tablist", { name: "How to show the feedback" });
	},
};

/**
 * Switching views, and the wiring a screen reader needs to follow it: the two views are named, the
 * one being shown says so in `aria-selected` rather than only in colour, and the body announces the
 * view it belongs to.
 */
export const SwitchToSource: Story = {
	play: async ({ canvas, userEvent }) => {
		const views = within(canvas.getByRole("tablist", { name: "How to show the feedback" }));
		const rendered = views.getByRole("tab", { name: "Rendered" });
		const source = views.getByRole("tab", { name: "Source" });
		await expect(rendered).toHaveAttribute("aria-selected", "true");
		await expect(source).toHaveAttribute("aria-selected", "false");
		// The body is the tab's panel, not a sibling div that happens to sit under it.
		await expect(canvas.getByRole("tabpanel", { name: "Rendered" })).toHaveAttribute(
			"id",
			rendered.getAttribute("aria-controls"),
		);

		await userEvent.click(source);
		await expect(canvas.getByRole("tabpanel", { name: "Source" }).textContent).toContain(
			"## What worked",
		);
		await expect(source).toHaveAttribute("aria-selected", "true");
		await expect(rendered).toHaveAttribute("aria-selected", "false");
		// The view left behind goes: one panel, and no heading from the Markdown it was showing.
		// Awaited because the primitive keeps the closing panel until its transition finishes.
		await waitFor(async () => {
			await expect(canvas.queryByRole("heading", { level: 4 })).not.toBeInTheDocument();
			await expect(canvas.getAllByRole("tabpanel")).toHaveLength(1);
		});

		// The arrow keys walk the views and Enter opens one, which is what a tab list promises a
		// keyboard user; switching back is never a state in which the body shows nothing.
		await userEvent.keyboard("{ArrowLeft}{Enter}");
		canvas.getByRole("heading", { level: 4, name: "What worked" });
		await expect(rendered).toHaveAttribute("aria-selected", "true");
	},
};

export const Withheld: Story = {
	args: {
		feedback: {
			body,
			channel: "IN_CONTEXT",
			deliveryState: "SUPPRESSED",
			suppressionReason: "ARTIFACT_MERGED",
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Withheld");
	},
};

export const FailedToDeliver: Story = {
	args: { feedback: { body, channel: "IN_CONTEXT", deliveryState: "FAILED" } },
};

/** `PREPARED` only ever exists on the conversation lane, so the badge can name the lane it waits on. */
export const PreparedForConversation: Story = {
	args: { feedback: { body, channel: "IN_CHAT", deliveryState: "PREPARED" } },
	play: async ({ canvas }) => {
		// The lane, not the exact wording: the `PREPARED` label lives in `delivery-outcome-defs`.
		canvas.getByText(/for conversation/u);
	},
};

export const ReplacedByNewer: Story = {
	args: { feedback: { body, channel: "IN_CONTEXT", deliveryState: "SUPERSEDED" } },
};

export const NoComposedText: Story = {
	args: {
		feedback: {
			channel: "IN_CONTEXT",
			deliveryState: "SUPPRESSED",
			suppressionReason: "REACTED_DISPUTED",
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("No feedback text was composed for this record.");
	},
};

/**
 * A composed body is model output that quotes a developer's own text, so it is rendered as untrusted
 * Markdown: images are dropped entirely and only `http(s)` links stay links.
 */
export const UntrustedMarkdown: Story = {
	args: {
		feedback: {
			body: [
				"![tracking pixel](https://attacker.example/pixel.png)",
				"[unsafe link](javascript:alert(1))",
				"<img src=x onerror=alert(1)>",
				"[safe link](https://example.com/docs)",
			].join("\n\n"),
			channel: "IN_CONTEXT",
			deliveryState: "SUPPRESSED",
			suppressionReason: "VOLUME_CAPPED",
		},
	},
	play: async ({ canvas, canvasElement }) => {
		await expect(canvasElement.querySelector("img")).toBeNull();
		canvas.getByText("unsafe link");
		await expect(canvas.queryByRole("link", { name: "unsafe link" })).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "safe link" })).toHaveAttribute(
			"href",
			"https://example.com/docs",
		);
	},
};
