import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";

import { WorkspaceMoveToRepositoryDialog } from "./WorkspaceMoveToRepositoryDialog";

const control = {
	repository: {
		repositoryItemId: "7",
		nameWithOwner: "acme/practices",
		defaultBranch: "main",
		directory: ".hephaestus/practices",
	},
	request: { phase: "confirming" as const },
	onOpen: fn(),
	onCancel: fn(),
	onConfirm: fn(),
};

const meta = {
	component: WorkspaceMoveToRepositoryDialog,
	tags: ["autodocs"],
	args: {
		practiceName: "Explain the change",
		practiceSlug: "explain-the-change",
		control,
		finalFocus: true,
	},
	argTypes: { control: { control: false }, finalFocus: { control: false } },
} satisfies Meta<typeof WorkspaceMoveToRepositoryDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/** It names the folder it reads and says that the practice is read-only here afterwards. */
export const Confirm: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog", {
			name: "Move “Explain the change” to the practice repository?",
		});
		await expectSettledVisible(dialog);
		await expect(
			within(dialog).getByText(".hephaestus/practices/explain-the-change/"),
		).toBeVisible();
		await expect(dialog).toHaveTextContent(/in acme\/practices at the head of main/u);
		await expect(dialog).toHaveTextContent(/The practice is then read-only here/u);
		await userEvent.click(within(dialog).getByRole("button", { name: "Move to repository" }));
		await expect(args.control.onConfirm).toHaveBeenCalledTimes(1);
		// Confirming does not close the dialog: it stays until the practice has reloaded.
		await expect(args.control.onCancel).not.toHaveBeenCalled();
	},
};

export const Cancel: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
		await expect(args.control.onCancel).toHaveBeenCalledTimes(1);
		await expect(args.control.onConfirm).not.toHaveBeenCalled();
	},
};

/** The request and the reload of the practice run with the dialog open, so nothing hides the outcome. */
export const Moving: Story = {
	args: { control: { ...control, request: { phase: "pending" } } },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await expect(
			within(dialog).getByRole("button", { name: "Moving it to the repository…" }),
		).toHaveAttribute("aria-disabled", "true");
		await expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled();
	},
};

/** A conflict, such as a default branch with no folder for the practice, in the server's words. */
export const RefusedConflict: Story = {
	args: {
		control: {
			...control,
			request: {
				phase: "refused",
				reason: "The default branch has no folder for this practice.",
				problems: [],
			},
		},
	},
	play: async ({ args }) => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		const alert = within(dialog).getByRole("alert");
		await expect(alert).toHaveTextContent("We could not move it to the repository");
		await expect(alert).toHaveTextContent("The default branch has no folder for this practice.");
		await expect(within(alert).queryByRole("list")).toBeNull();
		await userEvent.click(within(dialog).getByRole("button", { name: "Move to repository" }));
		await expect(args.control.onConfirm).toHaveBeenCalledTimes(1);
	},
};

/** An invalid folder lists each problem with its file, and its line and column where it has them. */
export const RefusedWithProblems: Story = {
	args: {
		control: {
			...control,
			request: {
				phase: "refused",
				reason: "The folder is not a valid practice.",
				problems: [
					{ file: "practice.md", line: 3, column: 7, message: "Name a kind of work." },
					{ file: "practice.md", line: 9, message: "Remove the key `owner`." },
					{ file: "notes.txt", message: "Remove this file." },
					{ file: "", message: "The folder is larger than 256 KiB." },
				],
			},
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		const alert = within(dialog).getByRole("alert");
		await expect(alert).toHaveTextContent("The folder is not a valid practice.");
		const problems = within(alert).getAllByRole("listitem");
		await expect(problems.map((problem) => problem.textContent)).toStrictEqual([
			"practice.md, line 3, column 7: Name a kind of work.",
			"practice.md, line 9: Remove the key `owner`.",
			"notes.txt: Remove this file.",
			"The folder is larger than 256 KiB.",
		]);
		await expect(within(alert).getByRole("list", { name: "Problems in the folder" })).toBeVisible();
	},
};

export const UnknownRepository: Story = {
	args: { control: { ...control, repository: undefined } },
	play: async () => {
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveTextContent(
			/reads its folder in the practice repository at the head of the default branch/u,
		);
	},
};
