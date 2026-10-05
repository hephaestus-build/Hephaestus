import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, waitFor, within } from "storybook/test";

import { FeedbackBody } from "./FeedbackBody";
import { POSTED_SUMMARY_COMMENT } from "./fixtures";

const body =
	"## What worked\n\nThe controller stays focused on HTTP concerns.\n\n[Read the guide](https://example.com/guide).";

/** Streamdown draws bold as a span it marks, not as a `strong` element. */
const STRONG = '[data-streamdown="strong"]';

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

/**
 * `PREPARED` only ever exists on the conversation lane, so the badge can name the lane it waits on. The
 * lane's notes are private: absent text is explained as withheld, never as missing, and a body a caller
 * passes anyway is not shown.
 */
export const PreparedForConversation: Story = {
	args: { feedback: { body, channel: "IN_CHAT", deliveryState: "PREPARED" } },
	play: async ({ canvas }) => {
		// The lane, not the exact wording: the `PREPARED` label lives in `delivery-outcome-defs`.
		canvas.getByText(/for conversation/u);
		canvas.getByText(/withheld from operators/u);
		await expect(canvas.queryByText("What worked")).toBeNull();
		await expect(canvas.queryByText(/No feedback text was composed/u)).toBeNull();
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

/**
 * The stored body of a summary comment is the comment as posted, HTML and all. It renders as GitHub
 * shows it: the marker is hidden, and the footer is small print under the rule. The Source view
 * still shows every character that was posted.
 */
export const PostedComment: Story = {
	args: {
		feedback: { body: POSTED_SUMMARY_COMMENT, channel: "IN_CONTEXT", deliveryState: "DELIVERED" },
	},
	play: async ({ canvas, userEvent }) => {
		const rendered = canvas.getByRole("tabpanel", { name: "Rendered" });
		await expect(rendered.textContent).not.toMatch(/<!--|<sub>|&middot;/u);
		await expect(
			within(rendered).getByRole("link", { name: "Why you see this and how to stop it" }),
		).toHaveAttribute("href", "https://hephaestus.example/settings#practice-feedback");
		await expect(rendered.querySelectorAll("small")).toHaveLength(2);

		await userEvent.click(canvas.getByRole("tab", { name: "Source" }));
		await expect(canvas.getByRole("tabpanel", { name: "Source" }).textContent).toContain(
			"<!-- hephaestus:practice-review:774b9e9b-2c40-4d6b-9e76-7094dda3a7a2 -->",
		);
	},
};

/**
 * The composer's Markdown at full range: a heading, bold, inline code, a list, a link, a line
 * break inside a paragraph, and a collapsed `<details>` block from GitHub's HTML subset.
 */
export const ComposerMarkdown: Story = {
	args: {
		feedback: {
			body: [
				"### What to tighten",
				"",
				"The lookup in `CacheService.find` collapses **two different failures** into one 404:",
				"",
				"- a cache miss, which the caller can retry",
				"- a permission failure, which it cannot",
				"",
				"Split them before this merges.  ",
				"The [error-handling guide](https://example.com/guide) shows the shape.",
				"",
				"<details><summary>Lines checked</summary>",
				"",
				"`server/src/main/java/CacheService.java`, lines 40–52",
				"",
				"</details>",
			].join("\n"),
			channel: "IN_CONTEXT",
			deliveryState: "DELIVERED",
		},
	},
	play: async ({ canvas }) => {
		canvas.getByRole("heading", { level: 4, name: "What to tighten" });
		await expect(canvas.getByText("CacheService.find", { selector: "code" })).toBeVisible();
		await expect(canvas.getByText("two different failures", { selector: STRONG })).toBeVisible();
		await expect(within(canvas.getByRole("list")).getAllByRole("listitem")).toHaveLength(2);
		await expect(canvas.getByRole("link", { name: "error-handling guide" })).toHaveAttribute(
			"href",
			"https://example.com/guide",
		);
		await expect(canvas.getByText("Lines checked", { selector: "summary" })).toBeVisible();
		await expect(canvas.queryByText(/\*\*|<details>/u)).toBeNull();
	},
};
