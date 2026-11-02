import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import {
	closedPublicIssue,
	emailedReport,
	heldReport,
	myReports,
	privateIssue,
	publicIssue,
	securityReport,
	stoppedReport,
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
		await expect(canvas.getByText("No product feedback yet")).toBeVisible();
	},
};

export const LoadError: Story = {
	args: { state: { status: "error", error: new TypeError("Failed to fetch"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your product feedback")).toBeVisible();
	},
};

/** A held report counts down to its filing and offers both reverses. */
export const Held: Story = {
	args: { state: { status: "ready", reports: [heldReport] } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("Waiting")).toBeVisible();
		await expect(canvas.getByText(/becomes a public issue in about 4 minutes/u)).toBeVisible();
		await expect(canvas.getByText("Removed: 1 email address")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Keep private" }));
		await expect(args.onKeepPrivate).toHaveBeenCalledWith(heldReport);
	},
};

/** Make private asks first: copies made while the issue was public can remain. */
export const PublicIssue: Story = {
	args: { state: { status: "ready", reports: [publicIssue, closedPublicIssue] } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByRole("link", { name: /Public issue #1234/u })).toHaveAttribute(
			"href",
			publicIssue.issueUrl,
		);
		await expect(canvas.getByText(/Closed as done/u)).toBeVisible();
		const [first] = canvas.getAllByRole("button", { name: "Make private" });
		await expect(first).toHaveAccessibleDescription(publicIssue.excerpt);
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
		await expect(canvas.getByRole("button", { name: "Making private…" })).toBeDisabled();
	},
};

/** A private issue says why in words, and Allow public lets the maintainers publish it. */
export const PrivateIssue: Story = {
	args: { state: { status: "ready", reports: [privateIssue, securityReport] } },
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("You chose to keep it private.")).toBeVisible();
		// A check kept the second one private, so it has no Allow public.
		await expect(canvas.getAllByRole("switch", { name: "Allow public" })).toHaveLength(1);
		await userEvent.click(canvas.getByRole("switch", { name: "Allow public" }));
		await expect(args.onAllowPublicChange).toHaveBeenCalledWith(privateIssue, true);
	},
};

export const NotOnGitHub: Story = {
	args: { state: { status: "ready", reports: [stoppedReport, emailedReport] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not filed")).toBeVisible();
		await expect(canvas.getByText("The admins of this instance have it.")).toBeVisible();
		// Neither row has a change left to offer: the admins hold both.
		await expect(canvas.queryByRole("button", { name: /private|Edit/u })).toBeNull();
		await expect(canvas.queryByRole("switch")).toBeNull();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
