import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import {
	NO_FEEDBACK,
	READY,
	READY_ON_GITHUB,
	WEB_APP,
	WORK_FEEDBACK,
} from "~/components/report/fixtures";
import { RowPreview, type RowPreviewProps } from "~/components/report/RowPreview";
import { LIST_STRIP_HEIGHT } from "~/shared/frame-messages";
import { expectNoHorizontalOverflow } from "~/stories/reflow";

/**
 * A list row's preview as it sits in the row: under the row's own title and meta line, in the row's
 * own column, with no box of its own. The row around it is the story's stand-in for the provider's.
 */
const meta = {
	component: RowPreview,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen", provider: "github" },
	decorators: [
		(Story) => (
			<div className="max-w-3xl border-y border-border px-4 py-2">
				<p className="m-0 text-base font-semibold">Add admin API</p>
				<p className="m-0 text-xs text-muted-foreground">#1 · opened on Mar 22</p>
				<Story />
			</div>
		),
	],
	args: {
		state: READY_ON_GITHUB,
		feedback: { status: "ready", data: WORK_FEEDBACK },
		activity: undefined,
		webAppOrigin: WEB_APP,
		onRetry: fn(),
		onOpenSettings: fn(),
	},
} satisfies Meta<typeof RowPreview>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The strip itself, whose height the page may measure. */
function strip(canvasElement: HTMLElement): HTMLElement {
	const element = canvasElement.querySelector<HTMLElement>("[role=status]")?.parentElement;
	if (element === null || element === undefined) {
		throw new Error("No preview line");
	}
	return element;
}

/**
 * Pressed on a row, the preview shows at once: the sentence the work's page leads with and the way to
 * the first comments. Nothing to open, no request, no heading, no card — the row's title opens the
 * work.
 */
export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(
			/^Practice review: 3 comments for you · reviewed .+ ago$/u,
		);
		await expect(strip(canvasElement).getBoundingClientRect().height).toBe(LIST_STRIP_HEIGHT);
		const links = canvas.getAllByRole("link");
		await expect(links.map((link) => link.textContent)).toStrictEqual([
			"Summary",
			"LoginScreen.tsx:12–18",
		]);
		for (const link of links) {
			await expect(link).toHaveAttribute("target", "_top");
		}
		// The third comment is counted, not crammed in.
		await expect(canvas.getByText("+1")).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.queryByRole("heading")).toBeNull();
		await expect(getComputedStyle(strip(canvasElement)).borderTopWidth).toBe("0px");
	},
};

/**
 * Every state is the same one line, so a page that frames a preview itself and measures it learns
 * nothing.
 */
export const OneHeightInEveryState: Story = {
	render: (args) => {
		const states: Partial<RowPreviewProps>[] = [
			{ state: { status: "loading" } },
			{ state: { status: "failed", message: "Hephaestus could not be reached." } },
			{ state: { status: "signed-out", instanceHost: "heph.example.test" } },
			{ state: { status: "not-found", instanceHost: "heph.example.test", workLabel: "#1" } },
			{ feedback: { status: "ready", data: NO_FEEDBACK } },
			{ feedback: { status: "error", message: "Could not load." } },
			{ activity: "queued-or-running" },
			{ stale: { message: "Hephaestus could not be reached." } },
			{},
		];
		return (
			<div className="flex flex-col">
				{states.map((state, index) => (
					<div key={index} data-state>
						<RowPreview {...args} {...state} />
					</div>
				))}
			</div>
		);
	},
	play: async ({ canvasElement }) => {
		const heights = [...canvasElement.querySelectorAll<HTMLElement>("[data-state] > div")].map(
			(element) => element.getBoundingClientRect().height,
		);
		await expect(heights).toHaveLength(9);
		await expect(new Set(heights)).toStrictEqual(new Set([LIST_STRIP_HEIGHT]));
	},
};

/**
 * A refresh failed after an earlier answer: the line says it is not current, keeps what it knew, and
 * offers the retry — a running review from before is not passed off as running now.
 */
export const NotRefreshed: Story = {
	args: {
		activity: "queued-or-running",
		stale: { message: "Hephaestus could not be reached." },
	},
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(
			/^Practice review: Not refreshed: Hephaestus could not be reached\. Last answer: · Review queued or running/u,
		);
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const NoCommentsForYou: Story = {
	args: { feedback: { status: "ready", data: NO_FEEDBACK } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(
			/No recorded comments for you · reviewed/u,
		);
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

export const Loading: Story = { args: { state: { status: "loading" }, feedback: undefined } };

export const CommentsLoading: Story = { args: { feedback: { status: "loading" } } };

export const LoadFailed: Story = {
	args: { state: { status: "failed", message: "Hephaestus could not be reached." } },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const CommentsFailed: Story = {
	args: { feedback: { status: "error", message: "Hephaestus ran into a problem." } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(/Your feedback could not load/u);
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const SignedOut: Story = {
	args: { state: { status: "signed-out", instanceHost: "hephaestus.build" } },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Sign in" }));
		await expect(args.onOpenSettings).toHaveBeenCalledOnce();
	},
};

export const NotConnected: Story = {
	args: { state: { status: "not-configured" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Set up" })).toBeVisible();
	},
};

export const OneStepLeft: Story = {
	args: {
		state: {
			status: "consent-required",
			instanceHost: "heph.example.test",
			webAppUrl: `${WEB_APP}/`,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /Open Hephaestus/u })).toHaveAttribute(
			"target",
			"_blank",
		);
	},
};

export const NotAvailable: Story = {
	args: { state: { status: "not-found", instanceHost: "hephaestus.build", workLabel: "#1" } },
};

/** A list row cannot choose a workspace; it says where that is done. */
export const FollowedInTwoWorkspaces: Story = {
	args: {
		state: {
			status: "choose-workspace",
			instanceHost: "hephaestus.build",
			workLabel: "#1",
			candidates: [
				{ slug: "intro-course", displayName: "Intro Course 2026" },
				{ slug: "research", displayName: "Research group" },
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(/open it to choose one/u);
		await expect(canvas.queryByRole("radio")).toBeNull();
	},
};

/** A running review is said, and a list row still offers no request. */
export const QueuedOrRunning: Story = {
	args: { activity: "queued-or-running" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(/Review queued or running/u);
		await expect(canvas.queryByRole("button", { name: /Request/u })).toBeNull();
	},
};

/** On a narrow row the sentence is what fits; the comment links wait on the work's page. */
export const OnANarrowRow: Story = {
	parameters: { reflow: true },
	play: async ({ canvas, canvasElement }) => {
		await expectNoHorizontalOverflow(canvasElement);
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

export const OnGitLabDark: Story = {
	args: { state: READY },
	parameters: { provider: "gitlab" },
	globals: { theme: "dark" },
};
