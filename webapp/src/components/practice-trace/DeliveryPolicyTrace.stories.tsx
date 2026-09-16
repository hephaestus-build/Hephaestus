import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";
import type { DeliveryPolicyTrace as DeliveryPolicyTraceData } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";
import { DeliveryPolicyTrace } from "./DeliveryPolicyTrace";
import { deniedDeliveryPolicyEvaluation } from "./fixtures";

const allowedEvaluation: DeliveryPolicyTraceData = {
	...deniedDeliveryPolicyEvaluation,
	evaluatedRevision: deniedDeliveryPolicyEvaluation.admittedRevision,
	allowed: true,
	decisiveReason: undefined,
	checks: [
		{ check: "INSTANCE_SILENT_MODE", status: "PASSED" },
		{ check: "WORKSPACE_ENABLED", status: "PASSED" },
		{ check: "ROLLOUT_REVISION", status: "PASSED" },
		{ check: "WORKSPACE_DELIVERY", status: "PASSED" },
		{ check: "CURRENT_COVERAGE", status: "PASSED" },
		{ check: "PRACTICE_AUTHORITY", status: "PASSED" },
		{ check: "RECIPIENT_CONSENT", status: "PASSED" },
		{ check: "ARTIFACT_ELIGIBILITY", status: "PASSED" },
	],
};

const openScopeEvaluation: DeliveryPolicyTraceData = {
	...deniedDeliveryPolicyEvaluation,
	surface: "IN_APP",
	stage: "AUTOMATIC",
	evaluatedRevision: undefined,
	decisiveReason: "OUTSIDE_CURRENT_COVERAGE",
	facts: {
		...deniedDeliveryPolicyEvaluation.facts,
		repositoryMode: "ALL_MONITORED",
		personMode: "ALL_ELIGIBLE",
		subject: "UNLINKED",
	},
	checks: [
		{ check: "INSTANCE_SILENT_MODE", status: "PASSED" },
		{ check: "WORKSPACE_ENABLED", status: "PASSED" },
		{ check: "ROLLOUT_REVISION", status: "PASSED" },
		{ check: "WORKSPACE_DELIVERY", status: "PASSED" },
		{ check: "CURRENT_COVERAGE", status: "DENIED" },
		{ check: "PRACTICE_AUTHORITY", status: "NOT_REACHED" },
		{ check: "RECIPIENT_CONSENT", status: "NOT_REACHED" },
		{ check: "ARTIFACT_ELIGIBILITY", status: "NOT_REACHED" },
	],
};

const conversationEvaluation: DeliveryPolicyTraceData = {
	...deniedDeliveryPolicyEvaluation,
	surface: "CONVERSATION",
	stage: "COMPOSITION",
	evaluatedRevision: undefined,
	decisiveReason: "CONVERSATION_EXPIRED",
	facts: {
		artifactKind: "chat.conversation_thread",
		deliveryStatus: "ACTIVE",
		triggerMode: "AUTO",
	},
	checks: [
		{ check: "INSTANCE_SILENT_MODE", status: "PASSED" },
		{ check: "WORKSPACE_ENABLED", status: "PASSED" },
		{ check: "ROLLOUT_REVISION", status: "PASSED" },
		{ check: "WORKSPACE_DELIVERY", status: "PASSED" },
		{ check: "CURRENT_COVERAGE", status: "NOT_APPLICABLE" },
		{ check: "PRACTICE_AUTHORITY", status: "PASSED" },
		{ check: "RECIPIENT_CONSENT", status: "PASSED" },
		{ check: "ARTIFACT_ELIGIBILITY", status: "DENIED" },
	],
};

async function openEvaluation(canvas: StoryContext["canvas"], name: RegExp) {
	await userEvent.click(canvas.getByRole("button", { name }));
}

const meta = {
	component: DeliveryPolicyTrace,
	tags: ["autodocs"],
	args: { evaluations: [deniedDeliveryPolicyEvaluation] },
} satisfies Meta<typeof DeliveryPolicyTrace>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Denied: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Why this was not sent" })).toBeVisible();
		await openEvaluation(canvas, /In-context feedback · Final delivery/u);
		await expect(await canvas.findByText("In-context feedback · Final delivery")).toBeVisible();
		await expect(
			canvas.getByText("Policy revision 4, evaluated as revision 5 · Resolver 1"),
		).toBeVisible();
		await expect(canvas.getByText("Stopped")).toBeVisible();
		await expect(canvas.getByText("The developer has opted out of AI feedback.")).toBeVisible();
		await expect(canvas.getByText("Denied")).toBeVisible();
		await expect(canvas.getByText("Not reached")).toBeVisible();
	},
};

export const Allowed: Story = {
	args: { evaluations: [allowedEvaluation] },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Delivery checks" })).toBeVisible();
		await openEvaluation(canvas, /In-context feedback · Final delivery/u);
		await expect(await canvas.findByText("In-context feedback · Final delivery")).toBeVisible();
		await expect(canvas.getByText("Allowed")).toBeVisible();
		await expect(
			canvas.queryByText("The developer has opted out of AI feedback."),
		).not.toBeInTheDocument();
	},
};

export const NoEvaluations: Story = {
	args: { evaluations: [] },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("heading", { name: "Why this was not sent" }),
		).not.toBeInTheDocument();
	},
};

export const BroadCoverage: Story = {
	args: { evaluations: [openScopeEvaluation] },
	play: async ({ canvas }) => {
		await openEvaluation(canvas, /In-app feedback · Automatic authorization/u);
		const scope = await canvas.findByText(/^Scope:/u);
		await expect(scope).toHaveTextContent("Repositories: all monitored");
		await expect(scope).toHaveTextContent("People: all eligible");
		await expect(scope).toHaveTextContent("Subject: author is not a workspace member");
		await expect(scope.textContent).not.toMatch(/_/u);
		await expect(canvas.getByText(/^In-app feedback · Automatic authorization/u)).toBeVisible();
	},
};

export const NoCoverageRecorded: Story = {
	args: { evaluations: [conversationEvaluation] },
	play: async ({ canvas }) => {
		await openEvaluation(canvas, /Conversation feedback · Composition/u);
		await expect(await canvas.findByText(/^Scope:/u)).toHaveTextContent(
			"Scope: no repository · Repositories: not applicable · People: not applicable · Subject: not applicable",
		);
		await expect(canvas.getByText(/^Conversation feedback · Composition/u)).toBeVisible();
	},
};

export const Reflow: Story = {
	args: {
		evaluations: [
			{
				...openScopeEvaluation,
				facts: {
					...openScopeEvaluation.facts,
					baseBranch:
						"release202609boundedpracticereviewrolloutwithoutanybreakopportunitiesxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
				},
			},
		],
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async ({ canvas }) => {
		await openEvaluation(canvas, /In-app feedback · Automatic authorization/u);
		await expect(await canvas.findByText("Current review coverage")).toBeVisible();
		await expectNoPageOverflow();
	},
};
