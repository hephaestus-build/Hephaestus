import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";

import { ConsentDialog, type ConsentDialogProps, type ConsentSubmission } from "./ConsentDialog";

const notice = {
	completed: false,
	noticeVersion: "2026-08-30",
	participateInResearch: false,
	noticeText: [
		"Hephaestus is operated by the Technical University of Munich (TUM), Research Group for Applied Education Technologies (AET).",
		"Hephaestus analyzes your GitHub, GitLab, Slack and Outline activity against your team's engineering practices to provide practice feedback. Platform operation uses the public-task basis described in the privacy notice; accepting the terms is not consent to research.",
		"Terms of use: use Hephaestus lawfully and only for workspaces and data you are authorized to access. Feedback is advisory, may be inaccurate, and must not be used as the sole basis for grading, employment, or access decisions.",
		"You may access, rectify or erase your data, restrict or object to processing, complain to a supervisory authority, and withdraw any research consent at any time without affecting your use of Hephaestus. Read the full privacy notice for recipients, retention periods and contact details.",
		"Separately, you may choose to let AET use your Hephaestus usage and feedback data for academic research and invite you to occasional surveys. This is optional, starts only if you opt in, and declining has no effect on the service.",
	].join("\n\n"),
};
const onSubmit = fn();
const onRetry = fn();
const ready = {
	status: "ready",
	notice,
	submission: { status: "idle" },
	onSubmit,
} satisfies ConsentDialogProps["state"];
const meta = {
	component: ConsentDialog,
	args: { state: ready, onSignOut: fn() },
	parameters: { layout: "fullscreen" },
} satisfies Meta<typeof ConsentDialog>;
export default meta;
type Story = StoryObj<typeof meta>;

async function openResearch() {
	const dialog = within(await screen.findByRole("dialog"));
	await userEvent.click(dialog.getByRole("checkbox", { name: /terms of use/i }));
	await userEvent.click(dialog.getByRole("button", { name: "Continue" }));
	return dialog;
}

function AfterSubmission(args: ConsentDialogProps) {
	const { state } = args;
	if (state.status !== "ready") return <ConsentDialog {...args} />;
	return (
		<Stateful<ConsentSubmission> initial={{ status: "idle" }}>
			{(submission, setSubmission) => (
				<ConsentDialog
					{...args}
					state={{
						...state,
						submission,
						onSubmit: (choice) => {
							state.onSubmit(choice);
							setSubmission(state.submission);
						},
					}}
				/>
			)}
		</Stateful>
	);
}

export const Default: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await waitFor(() =>
			expect(dialog.getByRole("heading", { name: "How Hephaestus uses your data" })).toHaveFocus(),
		);
		await expectGenuinelyDisabled(dialog.getByRole("button", { name: "Continue" }));
		await openResearch();
		await expect(onSubmit).not.toHaveBeenCalled();
		await expect(
			dialog.getByRole("heading", { name: "Help shape better feedback for developers" }),
		).toHaveFocus();
	},
};
export const ResearchInvitation: Story = {
	play: async () => {
		const dialog = await openResearch();
		await userEvent.click(dialog.getByRole("button", { name: "Yes, I'll take part" }));
		await expect(onSubmit).toHaveBeenCalledWith({
			noticeVersion: "2026-08-30",
			termsAccepted: true,
			participateInResearch: true,
		});
	},
};
export const ContinueWithoutResearch: Story = {
	play: async () => {
		const dialog = await openResearch();
		await userEvent.click(dialog.getByRole("button", { name: "Continue without research" }));
		await expect(onSubmit).toHaveBeenCalledWith({
			noticeVersion: "2026-08-30",
			termsAccepted: true,
			participateInResearch: false,
		});
	},
};
export const RevisitNotice: Story = {
	play: async () => {
		const dialog = await openResearch();
		await userEvent.click(dialog.getByRole("button", { name: "Back to your data" }));
		await expect(dialog.getByRole("checkbox", { name: /terms of use/i })).toBeChecked();
		await expect(onSubmit).not.toHaveBeenCalled();
	},
};
export const Submitting: Story = {
	args: { state: { ...ready, submission: { status: "saving", participateInResearch: true } } },
	render: (args) => <AfterSubmission {...args} />,
	play: async () => {
		const dialog = await openResearch();
		await userEvent.click(dialog.getByRole("button", { name: "Yes, I'll take part" }));
		await expect(onSubmit).toHaveBeenCalledWith(
			expect.objectContaining({ participateInResearch: true }),
		);
		await expectGenuinelyDisabled(dialog.getByRole("button", { name: "Saving…" }));
		await expectGenuinelyDisabled(
			dialog.getByRole("button", { name: "Continue without research" }),
		);
	},
};
export const SubmitFailed: Story = {
	args: { state: { ...ready, submission: { status: "error" } } },
	render: (args) => <AfterSubmission {...args} />,
	play: async () => {
		const dialog = await openResearch();
		await userEvent.click(dialog.getByRole("button", { name: "Continue without research" }));
		await expect(onSubmit).toHaveBeenCalledWith(
			expect.objectContaining({ participateInResearch: false }),
		);
		await expect(dialog.getByRole("alert")).toHaveTextContent(/wasn't saved/i);
	},
};
export const Loading: Story = { args: { state: { status: "loading" } } };
export const FailedToLoad: Story = {
	args: { state: { status: "error", onRetry } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: "Try again" }));
		await expect(onRetry).toHaveBeenCalled();
	},
};
export const CanSignOut: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.click(dialog.getByRole("button", { name: /sign out/i }));
		await expect(args.onSignOut).toHaveBeenCalled();
	},
};
export const NarrowNotice: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
export const NarrowResearchInvitation: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const dialog = await openResearch();
		await expect(dialog.getByRole("button", { name: "Continue without research" })).toBeEnabled();
	},
};
