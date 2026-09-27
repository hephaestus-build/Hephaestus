import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import {
	DESCRIPTIVE_PRACTICE,
	LONG_PAGE,
	NO_FEEDBACK,
	OWN_PAGE,
	READY,
	READY_ADMIN,
	READY_ON_GITHUB,
	WEB_APP,
	WORK_FEEDBACK,
} from "~/components/report/fixtures";
import { PageReport, type PageReportProps } from "~/components/report/PageReport";
import { REPORT_ROW_HEIGHT } from "~/shared/frame-messages";
import { expectNoHorizontalOverflow } from "~/stories/reflow";

const ADMIN_ON_GITHUB = {
	...READY_ADMIN,
	work: READY_ON_GITHUB.work,
	pageUrl: READY_ON_GITHUB.pageUrl,
};

/**
 * The practice review on a work's own page, as it sits in the provider's content column: the box the
 * page draws around the frame (`content/report.css`), then the frame's content in the provider's
 * neutrals (`parameters.provider`, GitHub unless a story says otherwise).
 */
const meta = {
	component: PageReport,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen", provider: "github" },
	decorators: [
		(Story) => (
			<div className="max-w-3xl overflow-hidden rounded-md border border-border">
				<Story />
			</div>
		),
	],
	args: {
		state: READY_ON_GITHUB,
		feedback: { status: "ready", data: WORK_FEEDBACK },
		observations: { status: "ready", data: OWN_PAGE },
		activity: undefined,
		webAppOrigin: WEB_APP,
		expanded: false,
		onToggle: fn(),
		onRetry: fn(),
		onRetryObservations: fn(),
		onOpenSettings: fn(),
		onChooseWorkspace: fn(),
		onAction: fn(),
	},
} satisfies Meta<typeof PageReport>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The collapsed line's own box, which the page sizes the frame to. */
function row(canvasElement: HTMLElement): HTMLElement {
	const element = canvasElement.querySelector<HTMLElement>("h2")?.parentElement;
	if (element === null || element === undefined) {
		throw new Error("No report line");
	}
	return element;
}

/**
 * A developer on their pull request: the line says how many comments Hephaestus posted for them and
 * when the work was reviewed, with the one action beside it. Everyone sees this first, admins too.
 */
export const Default: Story = {
	play: async ({ canvas, canvasElement, args }) => {
		const toggle = canvas.getByRole("button", { expanded: false });
		await expect(toggle).toHaveAccessibleName(
			/^Practice review 3 comments for you · reviewed .+ ago$/u,
		);
		await expect(row(canvasElement).getBoundingClientRect().height).toBe(REPORT_ROW_HEIGHT);
		// Primer's small default button, not the web app's.
		const request = canvas.getByRole("button", { name: "Request review…" });
		await expect(getComputedStyle(request).height).toBe("28px");
		await expect(getComputedStyle(request).borderTopLeftRadius).toBe("6px");
		await userEvent.click(request);
		await expect(args.onAction).toHaveBeenCalledWith({ kind: "request-review" });
		await userEvent.click(toggle);
		await expect(args.onToggle).toHaveBeenCalledOnce();
	},
};

/**
 * Every state's collapsed line is one height, and carries the Hephaestus mark, so the page, which
 * can measure the frame, learns nothing from it, and nothing here passes for the provider's verdict.
 */
export const OneHeightInEveryState: Story = {
	render: (args) => {
		const states: Partial<PageReportProps>[] = [
			{ state: { status: "loading" } },
			{ state: { status: "failed", message: "Hephaestus could not be reached." } },
			{ state: { status: "signed-out", instanceHost: "heph.example.test" } },
			{ state: { status: "not-found", instanceHost: "heph.example.test", workLabel: "!1" } },
			{ feedback: { status: "ready", data: NO_FEEDBACK } },
			{ feedback: { status: "error", message: "Could not load." } },
			{ activity: "queued-or-running" },
		];
		return (
			<div className="flex flex-col divide-y divide-border">
				{states.map((state, index) => (
					<div key={index} data-state>
						<PageReport {...args} {...state} />
					</div>
				))}
			</div>
		);
	},
	play: async ({ canvasElement }) => {
		const heights = [...canvasElement.querySelectorAll<HTMLElement>("[data-state] > div")].map(
			(element) => element.getBoundingClientRect().height,
		);
		await expect(heights).toHaveLength(7);
		await expect(new Set(heights)).toStrictEqual(new Set([REPORT_ROW_HEIGHT]));
		for (const status of canvasElement.querySelectorAll("[data-tone]")) {
			await expect(status.querySelector("img")).not.toBeNull();
		}
	},
};

/**
 * Opened, the report is flat, as a merge request report's second level: the way to each comment,
 * then what the review concluded about the reader's work, then when and where the rest is. Only the
 * line opens and closes; nothing inside opens further, and no heading repeats what the line says.
 */
export const Opened: Story = {
	args: { expanded: true },
	play: async ({ canvas }) => {
		// The line is the only disclosure.
		await expect(canvas.getAllByRole("button", { expanded: true })).toHaveLength(1);
		await expect(canvas.queryByRole("button", { expanded: false })).toBeNull();
		await expect(canvas.queryByRole("heading", { name: /Feedback for you/u })).toBeNull();
		const comments = canvas.getByRole("list", { name: "Comments for you" });
		const links = within(comments).getAllByRole("link");
		// Where each comment is, is the link to it; the provider's page shows it, never this report.
		await expect(links.map((link) => link.textContent)).toStrictEqual([
			"Summary comment",
			"src/login/LoginScreen.tsx:12–18",
		]);
		for (const link of links) {
			await expect(link).toHaveAttribute("target", "_top");
			await expect(link.getAttribute("href")).toMatch(
				/^https:\/\/github\.com\/HephaestusTest\/lifecycle-validation\/pull\/1#/u,
			);
		}
		await expect(within(comments).getByText(/no link recorded/u)).toBeVisible();
		await expect(within(comments).getAllByRole("listitem")[0]).toHaveTextContent(
			/^Summary commentDescriptive merge request, Small, focused changes · \d+ min\. ago$/u,
		);
		// Posted while the rest of its feedback failed: no practice is claimed for it.
		await expect(within(comments).getByText("Feedback")).toBeVisible();
		const observations = canvas.getByRole("region", { name: "Your observations" });
		await expect(within(observations).getAllByRole("listitem")).toHaveLength(4);
		await expect(within(observations).getByText("Negative outcome · Major")).toBeVisible();
		await expect(within(observations).getByText(/no longer current/u)).toBeVisible();
		// When, precisely, and at which commit; the rest is in Hephaestus.
		await expect(canvas.getByText(/^Reviewed .+ at 4f2a9c1\.$/u)).toBeVisible();
		await expect(canvas.getByRole("link", { name: /Open in Hephaestus/u })).toHaveAttribute(
			"href",
			READY.links.trace,
		);
		await expect(canvas.queryByRole("link", { name: /Review details/u })).toBeNull();
		await expect(canvas.getAllByRole("button", { name: "Request review…" })).toHaveLength(1);
	},
};

/** On the changes, a comment on lines comes before the summary, as the reader is reading lines. */
export const OnTheChanges: Story = {
	args: { expanded: true, state: { ...READY_ON_GITHUB, view: "changes" } },
	play: async ({ canvas }) => {
		const comments = within(canvas.getByRole("list", { name: "Comments for you" }));
		const items = comments.getAllByRole("listitem");
		await expect(items[0]).toHaveTextContent("src/login/LoginScreen.tsx:12–18");
		await expect(items.at(-1)).toHaveTextContent("Summary comment");
	},
};

/** No comment for the reader: the line says exactly that, and no empty list is drawn. */
export const NoFeedbackForYou: Story = {
	args: { expanded: true, feedback: { status: "ready", data: NO_FEEDBACK } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { expanded: true })).toHaveAccessibleName(
			/No recorded comments for you · reviewed/u,
		);
		await expect(canvas.queryByRole("list", { name: "Comments for you" })).toBeNull();
		await expect(canvas.queryByText(/clean|all good|no problems/iu)).toBeNull();
	},
};

export const NoReviewRecorded: Story = {
	args: {
		expanded: true,
		state: { ...READY_ON_GITHUB, trace: null },
		feedback: { status: "ready", data: NO_FEEDBACK },
		observations: { status: "ready", data: { ...OWN_PAGE, rows: [], total: 0 } },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { expanded: true })).toHaveAccessibleName(
			"Practice review No recorded comments for you · no review recorded",
		);
		await expect(canvas.getByText("No review recorded.")).toBeVisible();
		await expect(canvas.getByText("No observations about your work here.")).toBeVisible();
	},
};

/** Comments and no observation: the empty section would say nothing, so there is none. */
export const CommentsWithoutObservations: Story = {
	args: {
		expanded: true,
		observations: { status: "ready", data: { ...OWN_PAGE, rows: [], total: 0 } },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Comments for you" })).toBeVisible();
		await expect(canvas.queryByRole("region", { name: "Your observations" })).toBeNull();
	},
};

/** More feedback than one answer holds: the count is a floor, and the list says it is not all. */
export const OlderCommentsNotListed: Story = {
	args: { expanded: true, feedback: { status: "ready", data: { ...WORK_FEEDBACK, more: true } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { expanded: true })).toHaveAccessibleName(
			/^Practice review At least 3 comments for you/u,
		);
		await expect(canvas.getByText("Older comments are not listed here.")).toBeVisible();
	},
};

export const FeedbackLoading: Story = {
	args: { expanded: true, feedback: { status: "loading" }, observations: { status: "loading" } },
};

export const FeedbackFailed: Story = {
	args: {
		expanded: true,
		feedback: { status: "error", message: "Hephaestus ran into a problem." },
	},
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("button", { expanded: true })).toHaveAccessibleName(
			/Your feedback could not load/u,
		);
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const ObservationsFailed: Story = {
	args: {
		expanded: true,
		observations: { status: "error", message: "Your observations could not load." },
	},
	play: async ({ canvas, args }) => {
		const observations = canvas.getByRole("region", { name: "Your observations" });
		await userEvent.click(within(observations).getByRole("button", { name: "Try again" }));
		await expect(args.onRetryObservations).toHaveBeenCalledOnce();
		await expect(args.onRetry).not.toHaveBeenCalled();
	},
};

/** A long first page: the first rows, the rest on request, and what the page holds said plainly. */
export const ManyObservations: Story = {
	args: { expanded: true, observations: { status: "ready", data: LONG_PAGE } },
	play: async ({ canvas }) => {
		const observations = canvas.getByRole("region", { name: "Your observations" });
		await expect(within(observations).getAllByRole("listitem")).toHaveLength(5);
		await userEvent.click(within(observations).getByRole("button", { name: "Show 20 more" }));
		await expect(within(observations).getAllByRole("listitem")).toHaveLength(25);
		await expect(
			within(observations).getByText("The first 25 of 40, most severe first."),
		).toBeVisible();
	},
};

/** The trace cannot tell queued from running; the line says both, and offers no request. */
export const QueuedOrRunning: Story = {
	args: { activity: "queued-or-running", expanded: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { expanded: true })).toHaveAccessibleName(
			/^Practice review Review queued or running · 3 comments for you/u,
		);
		await expect(canvas.queryByRole("button", { name: "Request review…" })).toBeNull();
	},
};

/** A practice the review could not finish is named as incomplete, and nothing more is claimed. */
export const ReviewIncomplete: Story = {
	args: {
		expanded: true,
		state: {
			...READY_ON_GITHUB,
			trace:
				READY_ON_GITHUB.trace === null
					? null
					: {
							...READY_ON_GITHUB.trace,
							practices: [
								...READY_ON_GITHUB.trace.practices,
								{
									...DESCRIPTIVE_PRACTICE,
									practiceSlug: "tests-cover-the-change",
									practiceName: "Tests cover the change",
									outcome: "FAILED",
									decidedAt: undefined,
								},
							],
						},
		},
	},
	play: async ({ canvas }) => {
		// An unfinished review is said on the line itself, not only inside.
		await expect(canvas.getByRole("button", { expanded: true })).toHaveAccessibleName(
			/· 1 practice incomplete$/u,
		);
	},
};

/**
 * A workspace admin sees what a developer sees: their own comments and observations. Every
 * developer's records, delivery and runs are the web app's, one link away — not a panel in the page.
 */
export const WorkspaceAdmin: Story = {
	args: { state: ADMIN_ON_GITHUB, expanded: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /Review details/u })).toHaveAttribute(
			"href",
			ADMIN_ON_GITHUB.links.reviewDetails,
		);
		await expect(canvas.queryByRole("button", { name: /Manage/u })).toBeNull();
		await expect(canvas.getAllByRole("button", { expanded: true })).toHaveLength(1);
	},
};

export const Loading: Story = { args: { state: { status: "loading" }, expanded: true } };

export const LoadFailed: Story = {
	args: { state: { status: "failed", message: "Hephaestus could not be reached." } },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Try again" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

/** Opened, a failure explains itself once and keeps the line's one Try again. */
export const LoadFailedOpened: Story = {
	args: {
		state: { status: "failed", message: "Hephaestus could not be reached." },
		expanded: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByRole("button", { name: "Try again" })).toHaveLength(1);
		await expect(canvas.getByRole("alert")).toHaveTextContent("Hephaestus could not be reached.");
	},
};

export const SignedOut: Story = {
	args: { state: { status: "signed-out", instanceHost: "hephaestus.build" }, expanded: true },
	play: async ({ canvas, args }) => {
		// The line's action is the only one; the explanation below does not repeat it.
		await expect(canvas.getAllByRole("button", { name: "Sign in" })).toHaveLength(1);
		await userEvent.click(canvas.getByRole("button", { name: "Sign in" }));
		await expect(args.onOpenSettings).toHaveBeenCalledOnce();
	},
};

export const NotConnected: Story = {
	args: { state: { status: "not-configured" }, expanded: true },
};

export const NotAvailable: Story = {
	args: {
		state: { status: "not-found", instanceHost: "hephaestus.build", workLabel: "octo/app #1" },
		expanded: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { expanded: true })).toHaveAccessibleName(
			"Practice review Not available for this work",
		);
		await expect(canvas.queryByRole("button", { name: /Request/u })).toBeNull();
	},
};

export const ChooseWorkspace: Story = {
	args: {
		state: {
			status: "choose-workspace",
			instanceHost: "hephaestus.build",
			workLabel: "octo/app #1",
			candidates: [
				{ slug: "intro-course", displayName: "Intro Course 2026" },
				{ slug: "research", displayName: "Research group" },
			],
		},
		expanded: true,
	},
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("radio", { name: "Research group" }));
		await expect(args.onChooseWorkspace).toHaveBeenCalledWith("research");
	},
};

/**
 * At 320px (and so at 200% zoom on a 640px column) the report keeps its width for reading: no second
 * level indent, the request moved from the line to the report's end, and nothing scrolls sideways.
 */
export const OnANarrowWindow: Story = {
	args: { expanded: true },
	parameters: { reflow: true },
	play: async ({ canvas, canvasElement }) => {
		await expectNoHorizontalOverflow(canvasElement);
		const requests = canvas.getAllByRole("button", { name: "Request review…" });
		await expect(requests.filter((request) => request.checkVisibility())).toHaveLength(1);
		await expect(canvasElement.querySelector("[data-tone] img")).toBeVisible();
		const item = within(canvas.getByRole("list", { name: "Comments for you" })).getAllByRole(
			"listitem",
		)[0];
		await expect(item === undefined ? "" : getComputedStyle(item).paddingLeft).toBe("16px");
	},
};

/** Wide, the second level lines up with the line's text past its status. */
export const Indented: Story = {
	args: { expanded: true },
	play: async ({ canvas }) => {
		const item = within(canvas.getByRole("list", { name: "Comments for you" })).getAllByRole(
			"listitem",
		)[0];
		await expect(item === undefined ? "" : getComputedStyle(item).paddingLeft).toBe("48px");
	},
};

export const OnGitLabDark: Story = {
	args: { state: READY, expanded: true, feedback: { status: "ready", data: NO_FEEDBACK } },
	parameters: { provider: "gitlab" },
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		// GitLab's medium default button, not Primer's or the web app's.
		const request = canvas.getAllByRole("button", { name: "Request review…" })[0];
		await expect(request === undefined ? "" : getComputedStyle(request).height).toBe("32px");
		await expect(request === undefined ? "" : getComputedStyle(request).borderTopLeftRadius).toBe(
			"4px",
		);
	},
};

export const OnGitHubDimmed: Story = {
	args: { expanded: true },
	parameters: { palette: "dark_dimmed" },
	globals: { theme: "dark" },
};
