import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { DrawerBody } from "@/components/ui/drawer";
import { withPageBehind } from "@/stories/decorators";
import { InLevelStack } from "@/stories/level-stack";
import { expectSettledVisible, settledDrawerPanel } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";

import { WorkspacePracticeEditedInNote } from "./WorkspacePracticeEditedInNote";
import { WorkspacePracticeFormLevel } from "./WorkspacePracticeFormLevel";

// The form inside the level is `WorkspacePracticeForm.stories.tsx`; this file pins the header the
// level gives each kind of content.

const meta = {
	component: WorkspacePracticeFormLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	tags: ["autodocs"],
	args: {
		creating: false,
		path: { behind: [{ label: "Practice setup", depth: 0 }], onClose: fn() },
		children: null,
	},
	argTypes: { path: { control: false }, children: { control: false } },
	render: (args) => (
		<InLevelStack entry={{ kind: "practice-edit", id: "explain-the-change" }} path={args.path}>
			{(level) => <WorkspacePracticeFormLevel {...args} {...level} />}
		</InLevelStack>
	),
} satisfies Meta<typeof WorkspacePracticeFormLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Editing: Story = {
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText(/Update this practice’s review rules/u)).toBeVisible();
	},
};

export const Creating: Story = {
	args: { creating: true },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText(/Define a way of working/u)).toBeVisible();
	},
};

const control = {
	repositoryName: "acme/practices",
	confirming: false,
	pending: false,
	onConfirmingChange: fn(),
	onConfirm: fn(),
};

/**
 * The editor's address for a practice whose folder in the practice repository holds it. The level
 * has no editor, so the header names the practice and where it is edited, and the notice offers the
 * way back.
 */
export const EditedInRepository: Story = {
	args: {
		editedInRepositoryName: "Explain the change",
		editInHephaestus: { practiceName: "Explain the change", control },
		children: (
			<WorkspacePracticeEditedInNote
				repositoryName="acme/practices"
				sourceCommit={null}
				onEditInHephaestus={fn()}
			/>
		),
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("heading", { name: "Explain the change" })).toBeVisible();
		await expect(screen.getByText("Edited in the practice repository")).toBeVisible();
		await expect(screen.queryByText(/Update this practice’s review rules/u)).toBeNull();
		await expect(screen.queryByRole("alertdialog")).toBeNull();
	},
};

/**
 * What the play function gives once the state it waits on is reached: the server's answer to Edit in
 * Hephaestus with the reload after it, and the practice moving back to the repository later. A property
 * of an object, so TypeScript does not narrow it to `undefined` across the render callback.
 */
const answers: { editInHephaestus?: () => void; moveBackToRepository?: () => void } = {};

/** Forgets the answers of an earlier run, outside `play`, where an assignment would narrow them. */
function resetAnswers() {
	answers.editInHephaestus = undefined;
	answers.moveBackToRepository = undefined;
}

/**
 * Edit in Hephaestus keeps its dialog open, busy, until the practice has reloaded as editable. The
 * reload closes the dialog in the same render that swaps the notice for the form, and the dialog
 * hands focus to the title, so a keyboard or screen-reader user starts at the top of the form. The
 * route's success toast announces the change.
 */
export const EditInHephaestusFocusesTheEditor: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	render: (args) => (
		<Stateful initial={{ confirming: false, pending: false, editable: false }}>
			{(view, setView) => (
				<InLevelStack entry={{ kind: "practice-edit", id: "explain-the-change" }} path={args.path}>
					{(level) => (
						<WorkspacePracticeFormLevel
							{...args}
							{...level}
							editedInRepositoryName={view.editable ? undefined : "Explain the change"}
							editInHephaestus={{
								practiceName: "Explain the change",
								control: {
									...control,
									confirming: view.confirming,
									pending: view.pending,
									onConfirmingChange: (confirming) => setView({ ...view, confirming }),
									onConfirm: () => {
										setView({ ...view, pending: true });
										// As the route's hook does: the request ends in the render that shows the
										// practice reloaded as editable.
										answers.editInHephaestus = () =>
											setView({ confirming: false, pending: false, editable: true });
										answers.moveBackToRepository = () =>
											setView({ confirming: false, pending: false, editable: false });
									},
								},
							}}
						>
							{view.editable ? (
								<DrawerBody>
									<p>The practice editor</p>
								</DrawerBody>
							) : (
								<DrawerBody>
									<WorkspacePracticeEditedInNote
										repositoryName="acme/practices"
										sourceCommit={null}
										onEditInHephaestus={() => setView({ ...view, confirming: true })}
									/>
								</DrawerBody>
							)}
						</WorkspacePracticeFormLevel>
					)}
				</InLevelStack>
			)}
		</Stateful>
	),
	play: async () => {
		resetAnswers();
		await settledDrawerPanel();
		await userEvent.click(screen.getByRole("button", { name: "Edit in Hephaestus" }));
		const dialog = await screen.findByRole("alertdialog");
		await expectSettledVisible(dialog);
		await userEvent.click(within(dialog).getByRole("button", { name: "Edit in Hephaestus" }));

		// The request runs with the dialog open and busy.
		const busy = await within(dialog).findByRole("button", { name: "Making it editable…" });
		await expect(busy).toHaveAttribute("aria-disabled", "true");
		await expect(answers.editInHephaestus).toBeDefined();
		answers.editInHephaestus?.();

		const title = await screen.findByRole("heading", { name: "Edit practice" });
		await waitFor(async () => expect(title).toHaveFocus());
		await expect(screen.getByText("The practice editor")).toBeVisible();
		await expect(screen.queryByRole("alertdialog")).toBeNull();

		// The request is over: a practice that moves back to the repository later shows its notice,
		// and nothing reopens the confirmation.
		answers.moveBackToRepository?.();
		await expect(await screen.findByText("Edited in acme/practices")).toBeVisible();
		await expect(screen.queryByRole("alertdialog")).toBeNull();
	},
};
