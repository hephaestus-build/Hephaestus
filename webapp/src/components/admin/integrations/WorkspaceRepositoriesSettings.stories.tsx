import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { WorkspaceRepositoriesSettings } from "./WorkspaceRepositoriesSettings";

const meta = {
	component: WorkspaceRepositoriesSettings,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		repositories: [
			{ nameWithOwner: "octocat/Hello-World" },
			{ nameWithOwner: "microsoft/vscode" },
			{ nameWithOwner: "facebook/react" },
		],
		provider: "GITHUB",
		providerLabel: "GitHub",
		isLoading: false,
		error: null,
		addRepositoryError: null,
		isAddingRepository: false,
		isRemovingRepository: false,
		onAddRepository: fn(async () => true),
		onRemoveRepository: fn(),
	},
} satisfies Meta<typeof WorkspaceRepositoriesSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		const input = canvas.getByLabelText("Add a repository");
		const addButton = canvas.getByRole("button", { name: /^add repository$/iu });

		await expect(addButton).toBeDisabled();
		await userEvent.type(input, "not-a-repo");
		await expect(addButton).toBeDisabled();

		await userEvent.clear(input);
		await userEvent.type(input, "owner/name");
		await expect(addButton).toBeEnabled();
		await userEvent.click(addButton);
		await expect(args.onAddRepository).toHaveBeenCalledWith("owner/name");
		await expect(input).toHaveValue("");
	},
};

export const ManyRepositories: Story = {
	args: {
		repositories: Array.from({ length: 40 }, (_, index) => ({
			nameWithOwner: `ls1intum/repository-number-${index + 1}`,
		})),
	},
	play: async ({ canvas, canvasElement }) => {
		const viewport = canvasElement.querySelector<HTMLElement>('[data-slot="scroll-area-viewport"]');
		await expect(viewport).not.toBeNull();
		if (!viewport) {
			return;
		}

		await expect(viewport.clientHeight).toBeLessThanOrEqual(320);
		await expect(viewport.scrollHeight).toBeGreaterThan(viewport.clientHeight);

		await expect(canvas.getByLabelText("Add a repository")).toBeVisible();
	},
};

export const RemoveConfirm: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: /stop monitoring octocat\/Hello-World/iu }),
		);

		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/stop monitoring octocat\/Hello-World/iu);
		const description = within(dialog).getByText(/repository on GitHub is not affected/iu);
		await expect(description).toHaveTextContent(
			/if no other workspace on this instance still monitors it, Hephaestus also erases/iu,
		);
		await expect(description).toHaveTextContent(/practice reviews and feedback can remain/iu);
		await expect(description).toHaveTextContent(/to sync it again, add it below/iu);
		await expect(description).not.toHaveTextContent(/permanently|stays stopped/iu);
		await expect(within(dialog).getByRole("button", { name: /stop monitoring/iu })).toBeEnabled();
	},
};

export const RemoveConfirmGitLab: Story = {
	args: {
		repositories: [{ nameWithOwner: "acme/platform/api" }],
		provider: "GITLAB",
		providerLabel: "GitLab",
	},
	play: async ({ canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Stop monitoring acme/platform/api" }),
		);

		const dialog = await screen.findByRole("alertdialog");
		const description = within(dialog).getByText(/project on GitLab is not affected/iu);
		await expect(description).toHaveTextContent(
			/keeps the work Hephaestus already synced from it, under Stopped projects/iu,
		);
		await expect(description).toHaveTextContent(/add it below with its current path/iu);
		// A GitLab Stop keeps the work, so it neither promises nor threatens to erase it.
		await expect(description).not.toHaveTextContent(/erases|if no other workspace/iu);
		await expect(within(dialog).getByRole("button", { name: "Stop monitoring" })).toBeEnabled();
	},
};

export const RemoveConfirmUnknownProvider: Story = {
	args: { provider: undefined, providerLabel: undefined },
	play: async ({ canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Stop monitoring octocat/Hello-World" }),
		);

		const dialog = await screen.findByRole("alertdialog");
		const description = within(dialog).getByText(
			/repository on GitHub or GitLab is not affected/iu,
		);
		await expect(description).not.toHaveTextContent(/permanently|stays stopped/iu);
	},
};

export const RemoveInProgress: Story = {
	args: {
		isRemovingRepository: true,
	},
	play: async ({ canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: /stop monitoring microsoft\/vscode/iu }),
		);

		const dialog = await screen.findByRole("alertdialog");
		const confirm = within(dialog).getByRole("button", { name: /stopping/iu });
		await expect(confirm).toHaveAttribute("aria-disabled", "true");
		await expect(within(dialog).getByRole("button", { name: /cancel/iu })).toBeDisabled();
	},
};

export const RemoveHoldsDialogOpen: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: /stop monitoring facebook\/react/iu }),
		);

		const dialog = await screen.findByRole("alertdialog");
		await userEvent.click(within(dialog).getByRole("button", { name: /stop monitoring/iu }));

		await expect(args.onRemoveRepository).toHaveBeenCalledWith("facebook/react");
		await expectSettledVisible(await screen.findByRole("alertdialog"));
	},
};

export const Loading: Story = {
	args: {
		repositories: [],
		isLoading: true,
	},
};

export const Empty: Story = {
	args: {
		repositories: [],
	},
	play: async ({ canvas }) => {
		canvas.getByText(/no repositories monitored yet/iu);
	},
};

export const LoadError: Story = {
	args: {
		repositories: [],
		isLoading: false,
		error: Object.assign(new Error("request failed"), {
			status: 503,
			detail: "The repositories service is unavailable.",
		}),
		onRetry: fn(),
	},
	play: async ({ args, canvas }) => {
		canvas.getByText(/could not load the monitored repositories/iu);
		canvas.getByText(/repositories service is unavailable/iu);
		await expect(canvas.queryByLabelText("Add a repository")).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
	},
};

/** A failed refresh keeps the repositories last read, and their Stop, beside the failure. */
export const RefreshFailed: Story = {
	args: {
		repositories: [{ nameWithOwner: "acme/platform/api" }],
		provider: "GITLAB",
		providerLabel: "GitLab",
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
		canvas.getByText("The monitored repositories may be out of date");
		canvas.getByText(/GitLab is unavailable/u);
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(
			canvas.getByRole("button", { name: "Stop monitoring acme/platform/api" }),
		).toBeEnabled();
		await expect(canvas.getByLabelText("Add a repository")).toBeVisible();
		await expect(
			canvas.queryByText(/could not load the monitored repositories/iu),
		).not.toBeInTheDocument();
	},
};

export const RefreshRetrying: Story = {
	args: {
		...RefreshFailed.args,
		refreshFailure: {
			error: Object.assign(new Error("request failed"), {
				status: 503,
				detail: "GitLab is unavailable.",
			}),
			pending: true,
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		canvas.getByText("acme/platform/api");
	},
};

/** A refused read shows no repositories, and no Add until the list reads again. */
export const ListRefused: Story = {
	args: {
		repositories: [],
		provider: "GITLAB",
		providerLabel: "GitLab",
		error: Object.assign(new Error("request failed"), {
			status: 403,
			detail: "You cannot manage this workspace’s source control.",
		}),
		onRetry: fn(),
	},
	play: async ({ canvas }) => {
		canvas.getByText("You do not have access to the monitored repositories");
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
		await expect(canvas.queryByLabelText("Add a repository")).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("button", { name: /stop monitoring/iu }),
		).not.toBeInTheDocument();
	},
};

export const AddValidationError: Story = {
	args: {
		addRepositoryError: Object.assign(new Error("request failed"), {
			status: 404,
			detail: "Repository owner/name was not found, or the token cannot see it.",
		}),
	},
	play: async ({ canvas }) => {
		canvas.getByText(/was not found, or the token cannot see it/iu);
		await expect(
			canvas.queryByText(/an error occurred while adding the repository/iu),
		).not.toBeInTheDocument();
	},
};

/** A failed add keeps the entered path, so the admin can retry without typing it again. */
export const AddFailedKeepsPath: Story = {
	args: {
		provider: "GITLAB",
		providerLabel: "GitLab",
		onAddRepository: fn(async () => false),
		addRepositoryError: Object.assign(new Error("request failed"), {
			status: 503,
			detail: "GitLab is unavailable.",
		}),
	},
	play: async ({ canvas }) => {
		const input = canvas.getByLabelText("Add a repository");
		await userEvent.type(input, "acme/platform/api");
		await userEvent.click(canvas.getByRole("button", { name: /^add repository$/iu }));

		await expect(input).toHaveValue("acme/platform/api");
		await expect(canvas.getByRole("button", { name: /^add repository$/iu })).toBeEnabled();
		canvas.getByText("GitLab is unavailable.");
	},
};

export const AddingRepository: Story = {
	args: {
		isAddingRepository: true,
	},
};
