import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	closedPublicIssue,
	draftingReport,
	heldReport,
	privateIssue,
	publicIssue,
	savedReport,
} from "./fixtures";
import { ReportStatusLine } from "./ReportStatusLine";

const meta = {
	component: ReportStatusLine,
	args: { report: heldReport },
	tags: ["autodocs"],
} satisfies Meta<typeof ReportStatusLine>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** Before it is filed, the line names only the one time the report has: when it was sent. */
export const Sent: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^Sent/u)).toHaveTextContent(/^Sent .+ ago$/u);
	},
};

export const Drafting: Story = {
	args: { report: draftingReport },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^Sent/u)).toHaveTextContent(/^Sent .+ ago$/u);
	},
};

export const SavedForAdmins: Story = {
	args: { report: savedReport },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^Sent/u)).toHaveTextContent(/^Sent .+ ago$/u);
	},
};

/** A public issue is a link the reader can open. */
export const Filed: Story = {
	args: { report: publicIssue },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /public issue #1234/u })).toHaveAttribute(
			"href",
			publicIssue.issueUrl,
		);
	},
};

/** A private issue the reporter cannot open is named, not linked. */
export const FiledPrivate: Story = {
	args: { report: privateIssue },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Filed as a private issue$/u)).toBeVisible();
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

export const Closed: Story = {
	args: { report: closedPublicIssue },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Closed as done$/u)).toBeVisible();
	},
};
