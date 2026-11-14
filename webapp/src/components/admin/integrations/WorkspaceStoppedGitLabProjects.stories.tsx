import type { Meta, StoryContext, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectDismissed, expectSettledVisible } from "@/stories/overlay";
import { expectDialogFitsViewport, expectNoPageOverflow } from "@/stories/reflow";
import { pending } from "@/test/async";

import { WorkspaceStoppedGitLabProjects } from "./WorkspaceStoppedGitLabProjects";

const LONG_PATH = "university/teaching/2026-winter/software-engineering/team-07/backend-service";

const meta = {
	component: WorkspaceStoppedGitLabProjects,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		projects: [
			{
				repositoryItemId: "4101",
				nameWithOwner: "acme/platform/api",
				stopReceipt: "6f1c2a3b-4d5e-4f60-8a71-92b3c4d5e6f7",
			},
			{
				repositoryItemId: "4102",
				nameWithOwner: "acme/platform/web",
				stopReceipt: "0b7e9c11-2a44-4d3f-9e65-1c2d3e4f5a6b",
			},
		],
		isLoading: false,
		error: null,
		onRemove: fn(async () => undefined),
	},
} satisfies Meta<typeof WorkspaceStoppedGitLabProjects>;

export default meta;
type Story = StoryObj<typeof meta>;

async function openRemove(canvas: StoryContext["canvas"], nameWithOwner: string) {
	await userEvent.click(
		canvas.getByRole("button", { name: `Remove synced work from ${nameWithOwner}` }),
	);
	return screen.findByRole("alertdialog", { name: `Remove the synced work of ${nameWithOwner}?` });
}

export const Default: Story = {
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list", { name: "Stopped projects" });
		await expect(within(list).getAllByRole("listitem")).toHaveLength(2);
		canvas.getByText(/add it under Manage repositories with its current path/iu);
	},
};

/** The confirmation names the project, keeps the GitLab project and the history, and changes nothing on Cancel. */
export const ConfirmRemoval: Story = {
	play: async ({ canvas }) => {
		const dialog = await openRemove(canvas, "acme/platform/api");
		const description = within(dialog).getByText(/project on GitLab is not affected/iu);
		await expect(description).toHaveTextContent(
			/removes the work it synced from this project from this workspace/iu,
		);
		await expect(description).toHaveTextContent(
			/practice reviews, feedback and conversations about that work keep their usual retention/iu,
		);
		await expect(description).toHaveTextContent(/to sync it again, add it with its current path/iu);
		await expect(description).not.toHaveTextContent(/permanently|all data/iu);
		await expect(within(dialog).getByRole("button", { name: "Remove synced work" })).toBeEnabled();

		await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
		await expectDismissed("alertdialog");
		canvas.getByText("acme/platform/api");
	},
};

/** An accepted removal closes its confirmation; the refreshed list then drops the row. */
export const RemoveAccepted: Story = {
	play: async ({ canvas }) => {
		const dialog = await openRemove(canvas, "acme/platform/web");
		await userEvent.click(within(dialog).getByRole("button", { name: "Remove synced work" }));
		await expectDismissed("alertdialog");
	},
};

/** A pending removal holds its confirmation: neither Cancel nor Escape can hide the answer. */
export const RemovePending: Story = {
	// Never answers, so the confirmation stays pending for the whole play.
	args: { onRemove: fn(async (): Promise<void> => pending()) },
	play: async ({ canvas }) => {
		const dialog = await openRemove(canvas, "acme/platform/api");
		await userEvent.click(within(dialog).getByRole("button", { name: "Remove synced work" }));

		await expect(within(dialog).getByRole("button", { name: "Removing…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
		await userEvent.keyboard("{Escape}");
		await expectSettledVisible(
			await screen.findByRole("alertdialog", {
				name: "Remove the synced work of acme/platform/api?",
			}),
		);
	},
};

/** A refused removal stays open with its reason, and only a new press sends it again. */
export const RemoveRefused: Story = {
	args: {
		onRemove: fn(async () => {
			throw Object.assign(new Error("request failed"), {
				status: 409,
				detail:
					"This project was added or stopped again after the list was loaded. Reload and try again.",
			});
		}),
	},
	play: async ({ canvas }) => {
		const dialog = await openRemove(canvas, "acme/platform/api");
		await userEvent.click(within(dialog).getByRole("button", { name: "Remove synced work" }));

		await within(dialog).findByText("The synced work was not removed");
		within(dialog).getByText(/added or stopped again after the list was loaded/iu);
		await expect(within(dialog).getByRole("button", { name: "Remove synced work" })).toBeEnabled();
		await expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeEnabled();
	},
};

export const Loading: Story = {
	args: { projects: [], isLoading: true },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("button", { name: /remove synced work/iu }),
		).not.toBeInTheDocument();
		await expect(canvas.queryByText("No stopped projects")).not.toBeInTheDocument();
	},
};

export const Empty: Story = {
	args: { projects: [] },
	play: async ({ canvas }) => {
		canvas.getByText("No stopped projects");
		await expect(
			canvas.queryByRole("button", { name: /remove synced work/iu }),
		).not.toBeInTheDocument();
	},
};

export const LoadError: Story = {
	args: {
		projects: [],
		error: Object.assign(new Error("request failed"), {
			status: 503,
			detail: "GitLab is unavailable.",
		}),
		onRetry: fn(),
	},
	play: async ({ canvas }) => {
		canvas.getByText("We could not load the stopped projects");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.queryByText("No stopped projects")).not.toBeInTheDocument();
	},
};

/** A refused read shows no projects and no Retry. */
export const ReadRefused: Story = {
	args: {
		projects: [],
		error: Object.assign(new Error("request failed"), {
			status: 403,
			detail: "You cannot manage this workspace’s source control.",
		}),
		onRetry: fn(),
	},
	play: async ({ canvas }) => {
		canvas.getByText("You do not have access to the stopped projects");
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("button", { name: /remove synced work/iu }),
		).not.toBeInTheDocument();
	},
};

/** A failed refresh keeps the projects last read, and their Remove, beside the failure. */
export const RefreshFailed: Story = {
	args: {
		refreshFailure: {
			error: Object.assign(new Error("request failed"), {
				status: 503,
				detail: "GitLab is unavailable.",
			}),
			pending: false,
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("The stopped projects may be out of date");
		await expect(
			canvas.getByRole("button", { name: "Remove synced work from acme/platform/api" }),
		).toBeEnabled();
		await expect(
			canvas.queryByText("We could not load the stopped projects"),
		).not.toBeInTheDocument();
	},
};

/**
 * A long nested namespace wraps on a phone, in the row and in its confirmation. The path keeps the
 * width of the row and Remove moves below it, rather than squeezing the path beside the button.
 */
export const LongNamespaceOnPhone: Story = {
	args: {
		projects: [
			{
				repositoryItemId: "4103",
				nameWithOwner: LONG_PATH,
				stopReceipt: "a3c5e7f9-1b2d-4f6a-8c0e-2d4f6a8c0e1b",
			},
		],
	},
	globals: { viewport: { value: "reflow", isRotated: false } },
	parameters: { chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(window.innerWidth).toBe(320);
		const row = canvas.getByRole("listitem").getBoundingClientRect();
		const name = canvas.getByText(LONG_PATH).getBoundingClientRect();
		const remove = canvas
			.getByRole("button", { name: `Remove synced work from ${LONG_PATH}` })
			.getBoundingClientRect();
		await expect(name.width, "The path is squeezed beside Remove").toBeGreaterThan(row.width / 2);
		await expect(
			remove.top,
			"Remove overlaps the path instead of sitting below it",
		).toBeGreaterThanOrEqual(name.bottom);
		await expectNoPageOverflow();
		await openRemove(canvas, LONG_PATH);
		await expectDialogFitsViewport();
	},
};
