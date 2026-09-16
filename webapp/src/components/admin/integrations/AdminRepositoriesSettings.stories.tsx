import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/test/overlay";

import { AdminRepositoriesSettings } from "./AdminRepositoriesSettings";

const meta = {
	component: AdminRepositoriesSettings,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		repositories: [
			{ nameWithOwner: "octocat/Hello-World" },
			{ nameWithOwner: "microsoft/vscode" },
			{ nameWithOwner: "facebook/react" },
		],
		providerLabel: "GitHub",
		isLoading: false,
		error: null,
		addRepositoryError: null,
		isAddingRepository: false,
		isRemovingRepository: false,
		onAddRepository: fn(),
		onRemoveRepository: fn(),
	},
} satisfies Meta<typeof AdminRepositoriesSettings>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		const input = canvas.getByLabelText("Add a repository");
		const addButton = canvas.getByRole("button", { name: /^add$/iu });

		await expect(addButton).toBeDisabled();
		await userEvent.type(input, "not-a-repo");
		await expect(addButton).toBeDisabled();

		await userEvent.clear(input);
		await userEvent.type(input, "owner/name");
		await expect(addButton).toBeEnabled();
		await userEvent.click(addButton);
		await expect(args.onAddRepository).toHaveBeenCalledWith("owner/name");
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
		await userEvent.click(canvas.getByRole("button", { name: /remove octocat\/Hello-World/iu }));

		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/stop monitoring octocat\/Hello-World/iu);
		within(dialog).getByText(/permanently erases everything Hephaestus has mirrored/iu);
		within(dialog).getByText(/repository on GitHub itself is not affected/iu);
		within(dialog).getByText(/monitoring it again later/iu);
		await expect(within(dialog).getByRole("button", { name: /stop monitoring/iu })).toBeEnabled();
	},
};

export const RemoveInProgress: Story = {
	args: {
		isRemovingRepository: true,
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /remove microsoft\/vscode/iu }));

		const dialog = await screen.findByRole("alertdialog");
		const confirm = within(dialog).getByRole("button", { name: /stopping/iu });
		await expect(confirm).toBeDisabled();
		await expect(within(dialog).getByRole("button", { name: /cancel/iu })).toBeDisabled();
	},
};

export const RemoveHoldsDialogOpen: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /remove facebook\/react/iu }));

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
		canvas.getByText(/couldn't load the monitored repositories/iu);
		canvas.getByText(/repositories service is unavailable/iu);
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
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

export const AddingRepository: Story = {
	args: {
		isAddingRepository: true,
	},
};
