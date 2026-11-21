import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";
import { expectNoOverflowingElement } from "@/stories/reflow";
import { expectGenuinelyDisabled } from "@/test/controls";

import { WorkspacePracticeRepositoryCard } from "./WorkspacePracticeRepositoryCard";

const repository = {
	repositoryItemId: "7",
	nameWithOwner: "acme/practices",
	defaultBranch: "main",
	directory: ".hephaestus/practices",
};

const setting = {
	repositories: {
		status: "ready" as const,
		options: [
			{ itemId: "7", name: "acme/practices" },
			{ itemId: "8", name: "acme/handbook" },
		],
	},
	onOpen: fn(),
	onCancel: fn(),
	onSave: fn(),
	onUnset: fn(),
};

const meta = {
	component: WorkspacePracticeRepositoryCard,
	tags: ["autodocs"],
	args: { workspaceSlug: "acme", state: { status: "set", repository }, setting },
	argTypes: { state: { control: false }, setting: { control: false } },
} satisfies Meta<typeof WorkspacePracticeRepositoryCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Anyone who can merge into the default branch changes what reviews judge, so the card says so. */
export const RepositorySet: Story = {
	play: async ({ args, canvas }) => {
		const card = canvas.getByRole("region", { name: "Practice repository" });
		await expect(card).toHaveTextContent(/Repository\s*acme\/practices/u);
		await expect(card).toHaveTextContent(/Default branch\s*main/u);
		await expect(card).toHaveTextContent(/Directory\s*\.hephaestus\/practices/u);
		await expect(card).toHaveTextContent(
			"Anyone who can merge into main changes what reviews judge. Protect that branch: require a review before a merge.",
		);
		await userEvent.click(canvas.getByRole("button", { name: "Change" }));
		await expect(args.setting.onOpen).toHaveBeenCalledWith("change");
		await userEvent.click(canvas.getByRole("button", { name: "Unset" }));
		await expect(args.setting.onOpen).toHaveBeenCalledWith("unset");
	},
};

/** A repository that the workspace stopped monitoring has no name, and a new one no branch yet. */
export const NoLongerMonitored: Story = {
	args: {
		state: {
			status: "set",
			repository: { repositoryItemId: "7", directory: ".hephaestus/practices" },
		},
	},
	play: async ({ canvas }) => {
		const card = canvas.getByRole("region", { name: "Practice repository" });
		await expect(card).toHaveTextContent(/a repository that the workspace no longer monitors/u);
		await expect(card).toHaveTextContent(/Default branch\s*Not known yet/u);
		await expect(card).toHaveTextContent(/Anyone who can merge into its default branch/u);
	},
};

/** A workspace that never uses it sees one quiet row. */
export const NotSet: Story = {
	args: { state: { status: "empty" } },
	play: async ({ args, canvas }) => {
		const card = canvas.getByRole("region", { name: "Practice repository" });
		await expect(card).toHaveTextContent(/Practice repository\s*Not set/u);
		await expect(
			within(card).getByRole("link", { name: /Keep practices in a repository/u }),
		).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Unset" })).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Set practice repository" }));
		await expect(args.setting.onOpen).toHaveBeenCalledWith("set");
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Practice repository" })).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

export const FailedToLoad: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 503, title: "Service Unavailable" },
			onRetry: fn(),
		},
	},
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("We could not load the practice repository")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		const { state } = args;
		await expect(state.status === "error" && state.onRetry).toHaveBeenCalledTimes(1);
	},
};

/** Setting one sends the chosen repository and, only when the reader names one, the directory. */
export const Setting: Story = {
	args: {
		state: { status: "empty" },
		setting: { ...setting, request: { action: "set", phase: "confirming" } },
	},
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog", { name: "Set practice repository" });
		await expectSettledVisible(dialog);
		await expect(within(dialog).getByText("Protect its default branch")).toBeVisible();
		// No setting is replaced, so nothing moves back to Hephaestus.
		await expect(within(dialog).queryByText(/move back/u)).toBeNull();

		await userEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));
		await expect(within(dialog).getByText("Choose a repository.")).toBeVisible();
		await expect(
			within(dialog).getByRole("combobox", { name: "Repository" }),
		).toHaveAccessibleDescription("Choose a repository.");
		await expect(args.setting.onSave).not.toHaveBeenCalled();

		await userEvent.click(within(dialog).getByRole("combobox", { name: "Repository" }));
		await userEvent.click(await screen.findByRole("option", { name: "acme/handbook" }));
		await userEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));
		await expect(args.setting.onSave).toHaveBeenCalledTimes(1);
		await expect(args.setting.onSave).toHaveBeenCalledWith({ repositoryItemId: "8" });
	},
};

export const SettingADirectory: Story = {
	args: Setting.args,
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog", { name: "Set practice repository" });
		await expectSettledVisible(dialog);
		const directory = within(dialog).getByRole("textbox", { name: "Directory" });
		await expect(directory).toHaveAttribute("placeholder", ".hephaestus/practices");
		await userEvent.click(within(dialog).getByRole("combobox", { name: "Repository" }));
		await userEvent.click(await screen.findByRole("option", { name: "acme/practices" }));
		await userEvent.type(directory, "  practices  ");
		await userEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));
		await expect(args.setting.onSave).toHaveBeenCalledWith({
			repositoryItemId: "7",
			directory: "practices",
		});
	},
};

/** Changing starts from the saved setting and says what a change does to practices edited there. */
export const Changing: Story = {
	args: { setting: { ...setting, request: { action: "change", phase: "confirming" } } },
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog", { name: "Change practice repository" });
		await expectSettledVisible(dialog);
		await expect(within(dialog).getByRole("combobox", { name: "Repository" })).toHaveTextContent(
			"acme/practices",
		);
		await expect(within(dialog).getByRole("textbox", { name: "Directory" })).toHaveValue(
			".hephaestus/practices",
		);
		await expect(dialog).toHaveTextContent(
			/Every practice edited there becomes editable in Hephaestus again/u,
		);
		// The saved setting is not a change, and the unavailable button says so to a keyboard reader.
		const save = within(dialog).getByRole("button", { name: "Change practice repository" });
		await expect(save).toHaveAttribute("aria-disabled", "true");
		await expect(save).toHaveAccessibleDescription("Choose another repository or directory first.");
		await userEvent.click(save);
		await expect(args.setting.onSave).not.toHaveBeenCalled();

		await userEvent.click(within(dialog).getByRole("combobox", { name: "Repository" }));
		await userEvent.click(await screen.findByRole("option", { name: "acme/handbook" }));
		await userEvent.click(save);
		await expect(args.setting.onSave).toHaveBeenCalledWith({
			repositoryItemId: "8",
			directory: ".hephaestus/practices",
		});
	},
};

/** The server's rule for a directory is checked before sending, and marks the field. */
export const DirectoryNotValid: Story = {
	args: Setting.args,
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog", { name: "Set practice repository" });
		await expectSettledVisible(dialog);
		await userEvent.click(within(dialog).getByRole("combobox", { name: "Repository" }));
		await userEvent.click(await screen.findByRole("option", { name: "acme/practices" }));
		const directory = within(dialog).getByRole("textbox", { name: "Directory" });
		await userEvent.type(directory, ".hephaestus/practices/");
		await userEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));
		await expect(args.setting.onSave).not.toHaveBeenCalled();
		await expect(directory).toHaveAttribute("aria-invalid", "true");
		await expect(directory).toHaveAccessibleDescription(/with no slash at either end/u);

		await userEvent.clear(directory);
		await userEvent.type(directory, "practices/../secrets");
		await userEvent.click(within(dialog).getByRole("button", { name: "Set practice repository" }));
		await expect(args.setting.onSave).not.toHaveBeenCalled();
		await expect(directory).toHaveAttribute("aria-invalid", "true");
	},
};

/** A 400 about the directory shows at that field, in the server's words, not as a general error. */
export const DirectoryRefused: Story = {
	args: {
		state: { status: "empty" },
		setting: {
			...setting,
			request: {
				action: "set",
				phase: "refused",
				reason: "Some fields need changes. Check your entries and try again.",
				directoryError:
					"The directory is a path relative to the repository root, without ., .. or .git segments.",
			},
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(dialog);
		const directory = within(dialog).getByRole("textbox", { name: "Directory" });
		await expect(directory).toHaveAttribute("aria-invalid", "true");
		await expect(directory).toHaveAccessibleDescription(
			/The directory is a path relative to the repository root/u,
		);
		await expect(within(dialog).getAllByRole("alert")).toHaveLength(1);
		await expect(within(dialog).queryByText(/Some fields need changes/u)).toBeNull();
		// Editing the directory clears the server's mark: it was about the value it refused.
		await userEvent.type(directory, "practices");
		await expect(directory).toHaveAttribute("aria-invalid", "false");
	},
};

/** The request runs with the dialog open, so nothing hides the outcome. */
export const Saving: Story = {
	args: { setting: { ...setting, request: { action: "change", phase: "pending" } } },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(dialog);
		await expect(within(dialog).getByRole("button", { name: "Saving…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
		await expect(within(dialog).getByRole("textbox", { name: "Directory" })).toBeDisabled();
	},
};

/** A refusal, such as a repository the workspace does not monitor, stays in the dialog. */
export const SaveRefused: Story = {
	args: {
		state: { status: "empty" },
		setting: {
			...setting,
			request: {
				action: "set",
				phase: "refused",
				reason: "The workspace does not monitor this repository.",
			},
		},
	},
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(dialog);
		// The notes on what a change does are notes, not alerts; only the refusal is announced.
		await expect(within(dialog).getAllByRole("note")).toHaveLength(1);
		const alert = within(dialog).getByRole("alert");
		await expect(alert).toHaveTextContent("We could not save the practice repository");
		await expect(alert).toHaveTextContent("The workspace does not monitor this repository.");
		await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
		await expect(args.setting.onCancel).toHaveBeenCalledTimes(1);
	},
};

export const RepositoriesLoading: Story = {
	args: {
		state: { status: "empty" },
		setting: {
			...setting,
			repositories: { status: "loading" },
			request: { action: "set", phase: "confirming" },
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(dialog);
		const choice = within(dialog).getByRole("combobox", { name: "Repository" });
		await expect(choice).toHaveTextContent("Loading repositories…");
		await expectGenuinelyDisabled(choice);
	},
};

export const RepositoriesFailedToLoad: Story = {
	args: {
		state: { status: "empty" },
		setting: {
			...setting,
			repositories: { status: "error", error: { status: 503 }, onRetry: fn() },
			request: { action: "set", phase: "confirming" },
		},
	},
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(dialog);
		await expect(within(dialog).getByText("We could not load repositories")).toBeVisible();
		// With no control to name, the field's title is not a label pointing at one.
		await expect(within(dialog).getByText("Repository").tagName).not.toBe("LABEL");
		await userEvent.click(within(dialog).getByRole("button", { name: "Retry" }));
		const { repositories } = args.setting;
		await expect(repositories.status === "error" && repositories.onRetry).toHaveBeenCalledTimes(1);
	},
};

export const NoMonitoredRepositories: Story = {
	args: {
		state: { status: "empty" },
		setting: {
			...setting,
			repositories: { status: "ready", options: [] },
			request: { action: "set", phase: "confirming" },
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(dialog);
		await expect(within(dialog).getByText(/monitors no repositories yet/u)).toBeVisible();
		await expect(within(dialog).getByRole("link", { name: "Integrations" })).toHaveAttribute(
			"href",
			"/w/acme/admin/integrations",
		);
	},
};

/** Unsetting deletes nothing, and the dialog says where the practices edited there go. */
export const Unsetting: Story = {
	args: { setting: { ...setting, request: { action: "unset", phase: "confirming" } } },
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog", {
			name: "Unset the practice repository?",
		});
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveTextContent(
			/Every practice edited in acme\/practices becomes editable in Hephaestus again/u,
		);
		await expect(dialog).toHaveTextContent(/Nothing is deleted/u);
		await userEvent.click(within(dialog).getByRole("button", { name: "Unset" }));
		await expect(args.setting.onUnset).toHaveBeenCalledTimes(1);
		await expect(args.setting.onCancel).not.toHaveBeenCalled();
	},
};

export const UnsetPending: Story = {
	args: { setting: { ...setting, request: { action: "unset", phase: "pending" } } },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await expect(within(dialog).getByRole("button", { name: "Unsetting…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
	},
};

export const UnsetRefused: Story = {
	args: {
		setting: {
			...setting,
			request: {
				action: "unset",
				phase: "refused",
				reason: "The practice repository changed meanwhile.",
			},
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		const alert = within(dialog).getByRole("alert");
		await expect(alert).toHaveTextContent("We could not unset the practice repository");
		await expect(alert).toHaveTextContent("The practice repository changed meanwhile.");
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
