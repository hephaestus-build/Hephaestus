import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";

import { ConsentPage, type ConsentPageProps, type ConsentSubmission } from "./ConsentPage";

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
} satisfies ConsentPageProps["state"];
const meta = {
	component: ConsentPage,
	args: { state: ready, onSignOut: fn() },
	parameters: { layout: "fullscreen" },
} satisfies Meta<typeof ConsentPage>;
export default meta;
type Story = StoryObj<typeof meta>;

async function openResearch() {
	await userEvent.click(await screen.findByRole("checkbox", { name: /terms of use/i }));
	await userEvent.click(screen.getByRole("button", { name: "Continue to the research question" }));
}

function AfterSubmission(args: ConsentPageProps) {
	const { state } = args;
	if (state.status !== "ready") return <ConsentPage {...args} />;
	return (
		<Stateful<ConsentSubmission> initial={{ status: "idle" }}>
			{(submission, setSubmission) => (
				<ConsentPage
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
		await waitFor(() =>
			expect(screen.getByRole("heading", { name: "How Hephaestus uses your data" })).toHaveFocus(),
		);
		await expectGenuinelyDisabled(
			screen.getByRole("button", { name: "Continue to the research question" }),
		);
		await openResearch();
		await expect(onSubmit).not.toHaveBeenCalled();
		await expect(screen.getByRole("heading", { name: "Take part in the research?" })).toHaveFocus();
	},
};
export const ResearchInvitation: Story = {
	play: async () => {
		await openResearch();
		await userEvent.click(screen.getByRole("button", { name: "Yes, I'll take part" }));
		await expect(onSubmit).toHaveBeenCalledWith({
			noticeVersion: "2026-08-30",
			termsAccepted: true,
			participateInResearch: true,
		});
	},
};
export const ContinueWithoutResearch: Story = {
	play: async () => {
		await openResearch();
		await userEvent.click(screen.getByRole("button", { name: "Continue without research" }));
		await expect(onSubmit).toHaveBeenCalledWith({
			noticeVersion: "2026-08-30",
			termsAccepted: true,
			participateInResearch: false,
		});
	},
};
export const RevisitNotice: Story = {
	play: async () => {
		await openResearch();
		await userEvent.click(screen.getByRole("button", { name: "Back" }));
		await expect(screen.getByRole("checkbox", { name: /terms of use/i })).toBeChecked();
		await expect(onSubmit).not.toHaveBeenCalled();
	},
};
export const Submitting: Story = {
	args: { state: { ...ready, submission: { status: "saving", participateInResearch: true } } },
	render: (args) => <AfterSubmission {...args} />,
	play: async () => {
		await openResearch();
		await userEvent.click(screen.getByRole("button", { name: "Yes, I'll take part" }));
		await expect(onSubmit).toHaveBeenCalledWith(
			expect.objectContaining({ participateInResearch: true }),
		);
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Saving…" }));
		await expectGenuinelyDisabled(
			screen.getByRole("button", { name: "Continue without research" }),
		);
	},
};
export const SubmitFailed: Story = {
	args: { state: { ...ready, submission: { status: "error" } } },
	render: (args) => <AfterSubmission {...args} />,
	play: async () => {
		await openResearch();
		await userEvent.click(screen.getByRole("button", { name: "Continue without research" }));
		await expect(onSubmit).toHaveBeenCalledWith(
			expect.objectContaining({ participateInResearch: false }),
		);
		await expect(screen.getByRole("alert")).toHaveTextContent(/wasn't saved/i);
	},
};
export const Loading: Story = { args: { state: { status: "loading" } } };
export const FailedToLoad: Story = {
	args: { state: { status: "error", onRetry } },
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Try again" }));
		await expect(onRetry).toHaveBeenCalled();
	},
};
export const CanSignOut: Story = {
	play: async ({ args }) => {
		await userEvent.click(await screen.findByRole("button", { name: "Sign out" }));
		await expect(args.onSignOut).toHaveBeenCalled();
	},
};
export const NarrowNotice: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
export const NarrowResearchInvitation: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await openResearch();
		await expect(screen.getByRole("button", { name: "Continue without research" })).toBeEnabled();
	},
};
