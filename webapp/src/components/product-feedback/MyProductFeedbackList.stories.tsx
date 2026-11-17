import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import {
	closedPublicIssue,
	draftingReport,
	filingReport,
	savedReport,
	heldReport,
	myReports,
	privateIssue,
	publicIssue,
	securityReport,
	stoppedReport,
	waitingForModelReport,
} from "./fixtures";
import { MyProductFeedbackList } from "./MyProductFeedbackList";

const meta = {
	component: MyProductFeedbackList,
	args: {
		state: { status: "ready", reports: myReports },
		pending: {},
		onKeepPrivate: fn(),
		onEdit: fn(async () => true),
		onMakePrivate: fn(),
		onAllowPublicChange: fn(),
	},
	tags: ["autodocs"],
} satisfies Meta<typeof MyProductFeedbackList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const Empty: Story = {
	args: { state: { status: "ready", reports: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No reports yet")).toBeVisible();
	},
};

export const LoadError: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your reports")).toBeVisible();
	},
};

/** A held report is drafted: it names the time it files and offers both reverses until then. */
export const Held: Story = {
	args: { state: { status: "ready", reports: [heldReport] } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("Public soon")).toBeVisible();
		await expect(canvas.getByText("Can become public")).toBeVisible();
		await expect(
			canvas.getByText(
				/becomes a public issue at about \d{1,2}:\d{2} [ap]m \(in about 4 minutes\) unless you keep it private or edit it/u,
			),
		).toBeVisible();
		await expect(canvas.getByText("Removed: 1 email address")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Keep private" }));
		await expect(args.onKeepPrivate).toHaveBeenCalledWith(heldReport);
	},
};

/**
 * A drafting report says since when and the time by which it goes on without a draft, so it never
 * reads as stuck. A public one also names the end of its hold and offers Keep private meanwhile.
 */
export const Drafting: Story = {
	args: { state: { status: "ready", reports: [draftingReport] } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("Drafting")).toBeVisible();
		await expect(canvas.getByText("Can become public")).toBeVisible();
		await expect(canvas.getByText(/An AI model is drafting the issue\. Started/u)).toBeVisible();
		await expect(canvas.getByText(/If the draft is not ready by about/u)).toHaveTextContent(
			/your report goes on as written and does not become public\.$/u,
		);
		await expect(
			canvas.getByText(
				/^Once drafted, it can become a public issue at about \d{1,2}:\d{2} [ap]m unless you keep it private\.$/u,
			),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Edit" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Keep private" }));
		await expect(args.onKeepPrivate).toHaveBeenCalledWith(draftingReport);
	},
};

/** The model is busy with other work: the report says so, and still names when it goes on. */
export const WaitingForModel: Story = {
	args: { state: { status: "ready", reports: [waitingForModelReport] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Drafting")).toBeVisible();
		await expect(canvas.getByText("Private")).toBeVisible();
		await expect(
			canvas.getByText(
				/The AI model is busy with other work, so the draft waits for it\. Started/u,
			),
		).toBeVisible();
		await expect(canvas.getByText(/If the draft is not ready by about/u)).toHaveTextContent(
			/your report goes on as written\.$/u,
		);
		await expect(canvas.queryByText(/public/u)).toBeNull();
	},
};

/** Filing is out of the person's hands: no change is offered while it reaches GitHub. */
export const Filing: Story = {
	args: { state: { status: "ready", reports: [filingReport] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Filing")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /private|Edit/u })).toBeNull();
		await expect(canvas.queryByRole("switch")).toBeNull();
	},
};

/** Make private asks first: copies made while the issue was public can remain. */
export const PublicIssue: Story = {
	args: { state: { status: "ready", reports: [publicIssue, closedPublicIssue] } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("link", { name: /public issue #1234/u })).toHaveAttribute(
			"href",
			publicIssue.issueUrl,
		);
		await expect(canvas.getAllByText("Public")).toHaveLength(2);
		await expect(canvas.getAllByText(/Filed as/u, { selector: "p" })).toHaveLength(2);
		await expect(canvas.getByText(/Closed as done/u)).toBeVisible();
		const [first] = canvas.getAllByRole("button", { name: "Make private" });
		await expect(first).toHaveAccessibleDescription(publicIssue.message);
		if (first !== undefined) {
			await userEvent.click(first);
		}
		const dialog = within(await screen.findByRole("alertdialog"));
		await expectSettledVisible(
			dialog.getByText(
				"Move this issue to the private repository? It disappears from public view. Copies made while it was public can remain.",
			),
		);
		await userEvent.click(dialog.getByRole("button", { name: "Make private" }));
		await expect(args.onMakePrivate).toHaveBeenCalledWith(publicIssue);
	},
};

export const MakingPrivate: Story = {
	args: {
		state: { status: "ready", reports: [publicIssue] },
		pending: { [publicIssue.id]: "make-private" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Making private…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

/**
 * A private issue says why in words, and Allow public lets the maintainers publish it. The page
 * explains the switch once, above the reports, with the way to the default.
 */
export const PrivateIssue: Story = {
	args: { state: { status: "ready", reports: [privateIssue, securityReport] } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getAllByText("Private")).toHaveLength(2);
		await expect(canvas.getAllByText(/Filed as a private issue/u)).toHaveLength(2);
		await expect(canvas.getByText("It is not marked public, so it stays private.")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "your settings" })).toHaveAttribute(
			"href",
			"/settings#product-feedback",
		);
		await expect(canvas.getByRole("switch", { name: "Allow public" })).toHaveAccessibleDescription(
			`The maintainers can then publish it as a public issue. ${privateIssue.message}`,
		);
		// A check kept the second one private, so it has no Allow public.
		await expect(canvas.getAllByRole("switch", { name: "Allow public" })).toHaveLength(1);
		await userEvent.click(canvas.getByRole("switch", { name: "Allow public" }));
		await expect(args.onAllowPublicChange).toHaveBeenCalledWith(privateIssue, true);
	},
};

/** Allowed public: the switch is on, and turning it off is the way back. */
export const PublicAllowed: Story = {
	args: {
		state: { status: "ready", reports: [{ ...privateIssue, allowPublic: true }] },
	},
	play: async ({ canvas, args }) => {
		const allow = canvas.getByRole("switch", { name: "Allow public" });
		await expect(allow).toBeChecked();
		// It stays private until the maintainers publish it, and the reason recorded at filing no longer holds.
		await expect(canvas.getByText("Private")).toBeVisible();
		await expect(
			canvas.getByText("It stays private unless a maintainer publishes it."),
		).toBeVisible();
		await expect(canvas.queryByText(/not marked public/u)).not.toBeInTheDocument();
		await userEvent.click(allow);
		await expect(args.onAllowPublicChange).toHaveBeenCalledWith(
			{ ...privateIssue, allowPublic: true },
			false,
		);
	},
};

export const NotOnGitHub: Story = {
	args: { state: { status: "ready", reports: [stoppedReport, savedReport] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Needs attention")).toBeVisible();
		await expect(
			canvas.getByText(
				"Your report is saved on this instance for its admins. They can move it to GitHub as a private issue.",
			),
		).toBeVisible();
		// Neither row has a change left to offer: the admins hold both.
		await expect(canvas.queryByRole("button", { name: /private|Edit/u })).toBeNull();
		await expect(canvas.queryByRole("switch")).toBeNull();
		await expect(canvas.queryByRole("link", { name: "your settings" })).toBeNull();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

/** A private issue copy is readable here without a private repository link. */
export const PrivateFiledCopy: Story = {
	args: {
		state: {
			status: "ready",
			reports: [
				{
					...privateIssue,
					message: "The summary repeats the same point. ".repeat(8),
					issueTitle: "Avoid repeated points in the review summary",
					issueText:
						"### User problem\n\nThe summary repeats the same point on a long pull request.",
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("The summary repeats the same point. ".repeat(8).trim()),
		).toBeVisible();
		await expect(canvas.queryByRole("link", { name: /issue #/u })).not.toBeInTheDocument();
		await userEvent.click(
			canvas.getByRole("button", {
				name: "The issue as filed: Avoid repeated points in the review summary",
			}),
		);
		await expectSettledVisible(
			await canvas.findByText("Avoid repeated points in the review summary"),
		);
		await expect(
			await canvas.findByText("The summary repeats the same point on a long pull request."),
		).toBeVisible();
	},
};
