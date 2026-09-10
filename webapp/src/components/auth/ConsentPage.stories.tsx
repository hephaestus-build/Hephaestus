import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { Stateful } from "@/stories/stateful";
import { expectGenuinelyDisabled } from "@/test/controls";
import { expectNoPageOverflow } from "@/test/reflow";

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
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
} satisfies Meta<typeof ConsentPage>;
export default meta;
type Story = StoryObj<typeof meta>;

async function acceptTerms() {
	await userEvent.click(await screen.findByRole("checkbox", { name: /terms of use/i }));
}

async function answer(name: RegExp) {
	await userEvent.click(screen.getByRole("radio", { name }));
}

/** The wrapper drives the submission the caller supplied, so the spy still sees the real choice. */
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
		await expectGenuinelyDisabled(await screen.findByRole("button", { name: "Continue" }));
		await expect(screen.getByRole("radio", { name: /Yes, take part/ })).not.toBeChecked();
		await expect(screen.getByRole("radio", { name: /don't take part/ })).not.toBeChecked();
	},
};

export const TermsAcceptedOnly: Story = {
	play: async () => {
		await acceptTerms();
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Continue" }));
		await expect(screen.getByText("Answer the research question to continue.")).toBeVisible();
	},
};

export const TakingPart: Story = {
	play: async () => {
		await acceptTerms();
		await answer(/Yes, take part/);
		await userEvent.click(screen.getByRole("button", { name: "Continue" }));
		await expect(onSubmit).toHaveBeenCalledWith({
			noticeVersion: "2026-08-30",
			termsAccepted: true,
			participateInResearch: true,
		});
	},
};

export const DecliningResearch: Story = {
	play: async () => {
		await acceptTerms();
		await answer(/don't take part/);
		await userEvent.click(screen.getByRole("button", { name: "Continue" }));
		await expect(onSubmit).toHaveBeenCalledWith({
			noticeVersion: "2026-08-30",
			termsAccepted: true,
			participateInResearch: false,
		});
	},
};

export const Submitting: Story = {
	args: { state: { ...ready, submission: { status: "saving" } } },
	render: (args) => <AfterSubmission {...args} />,
	play: async () => {
		await acceptTerms();
		await answer(/Yes, take part/);
		await userEvent.click(screen.getByRole("button", { name: "Continue" }));
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Saving…" }));
		await expectGenuinelyDisabled(screen.getByRole("button", { name: "Sign out" }));
	},
};

export const SubmitFailed: Story = {
	args: { state: { ...ready, submission: { status: "error" } } },
	render: (args) => <AfterSubmission {...args} />,
	play: async () => {
		await acceptTerms();
		await answer(/don't take part/);
		await userEvent.click(screen.getByRole("button", { name: "Continue" }));
		await expect(screen.getByRole("alert")).toHaveTextContent(/weren't saved/i);
		await expect(screen.getByRole("button", { name: "Continue" })).toBeEnabled();
	},
};

export const Loading: Story = { args: { state: { status: "loading" } } };

export const FailedToLoad: Story = {
	args: { state: { status: "error", error: new Error("offline"), onRetry } },
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Retry" }));
		await expect(onRetry).toHaveBeenCalled();
		await expect(screen.queryByRole("checkbox")).toBeNull();
	},
};

export const CanSignOut: Story = {
	play: async ({ args }) => {
		await userEvent.click(await screen.findByRole("button", { name: "Sign out" }));
		await expect(args.onSignOut).toHaveBeenCalled();
	},
};

export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await acceptTerms();
		await answer(/Yes, take part/);
		await expectNoPageOverflow();
	},
};

export const Dark: Story = { globals: { theme: "dark" } };
