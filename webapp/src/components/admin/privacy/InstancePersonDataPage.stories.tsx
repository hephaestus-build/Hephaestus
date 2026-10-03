import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";
import type { PersonDataRequest } from "@/api/types.gen";
import { Stateful } from "@/stories/stateful";
import { daysAfter } from "@/stories/story-clock";
import { InstancePersonDataPage } from "./InstancePersonDataPage";

const request = {
	id: "11111111-1111-1111-1111-111111111111",
	state: "PREVIEW",
	expiresAt: daysAfter(2),
	counts: {
		observation: 4,
		feedback: 2,
		chat_thread: 1,
		slack_message: 6,
		user: 1,
		user_preferences: 0,
	},
	scope: { accountId: 42, identities: [{ providerId: 1, subject: "314", teamId: undefined }] },
	completed: {},
	externalDeliveries: [],
} satisfies PersonDataRequest;
const ready = { status: "ready", request, onExport: fn(), onErase: fn(), onRefresh: fn() } as const;
const meta = {
	component: InstancePersonDataPage,
	render: (args) => (
		<Stateful initial={args.selection}>
			{(selection, setSelection) => (
				<InstancePersonDataPage
					{...args}
					selection={selection}
					onChange={(next) => {
						setSelection(next);
						args.onChange(next);
					}}
				/>
			)}
		</Stateful>
	),
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: {
		providers: [
			{ id: 1, type: "GITLAB", serverUrl: "https://gitlab.example.com" },
			{ id: 2, type: "SLACK", serverUrl: "https://slack.com" },
		],
		selection: { accountId: "", identities: [] },
		onChange: fn(),
		onPreview: fn(),
		state: { status: "empty" },
	},
} satisfies Meta<typeof InstancePersonDataPage>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/No person selected/u)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Preview data" })).toBeDisabled();
	},
};
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText("Loading personal-data scope")).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};
export const Error: Story = {
	args: {
		state: {
			status: "error",
			message:
				"The supplied identities belong to different accounts. Correct the exact identity scope.",
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("different accounts");
	},
};
export const Preview: Story = {
	args: { state: ready },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Download JSON export" })).toBeEnabled();
		await expect(canvas.getByRole("button", { name: "Erase this person's data" })).toBeDisabled();
	},
};
export const ProviderCopies: Story = {
	args: {
		state: {
			...ready,
			request: {
				...request,
				externalDeliveries: [{ workspaceId: 7, locator: "github:issue-comment:12345" }],
			},
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("Remove provider copies first");
		const erase = canvas.getByRole("button", { name: "Erase this person's data" });
		await userEvent.type(canvas.getByLabelText("Type ERASE to confirm permanent erasure"), "ERASE");
		await expect(erase).toBeDisabled();
		await userEvent.click(
			canvas.getByRole("checkbox", {
				name: "I have checked and removed external feedback copies.",
			}),
		);
		await expect(erase).toBeEnabled();
		await userEvent.click(
			canvas.getByRole("checkbox", {
				name: "I have checked and removed external feedback copies.",
			}),
		);
		await expect(erase).toBeDisabled();
	},
};
export const Running: Story = {
	args: {
		state: {
			...ready,
			request: { ...request, state: "ERASING", completed: { feedback: 2, observation: 4 } },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Erasure in progress")).toBeVisible();
	},
};
export const Failed: Story = {
	args: {
		state: {
			...ready,
			request: {
				...request,
				state: "FAILED",
				completed: { feedback: 2 },
				failureCode: "STORE_ERASURE_FAILED",
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Resume erasure" })).toBeDisabled();
	},
};
export const Complete: Story = {
	args: {
		state: {
			...ready,
			request: {
				...request,
				state: "COMPLETE",
				scope: undefined,
				completed: {
					observation: 4,
					feedback: 2,
					chat_thread: 1,
					slack_message: 6,
					user: 1,
					user_preferences: 0,
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Erasure complete")).toBeVisible();
		await expect(
			canvas.queryByRole("button", { name: "Download JSON export" }),
		).not.toBeInTheDocument();
	},
};
export const SlackIdentity: Story = {
	args: {
		selection: {
			accountId: "",
			identities: [{ inputId: "slack", providerId: "2", subject: "U12345", teamId: "T12345" }],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText("Slack workspace ID")).toHaveValue("T12345");
	},
};

export const Expired: Story = {
	args: { state: { ...ready, request: { ...request, state: "EXPIRED", scope: undefined } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Preview expired")).toBeVisible();
	},
};
export const Narrow: Story = {
	args: { state: ready },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};

export const Downloading: Story = {
	args: { state: { ...ready, pendingAction: "export" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Personal-data preview")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Downloading…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Erase this person's data" })).toBeDisabled();
		await expect(canvas.getByLabelText("Account ID (optional)")).toBeDisabled();
		await expect(canvas.getByLabelText("Type ERASE to confirm permanent erasure")).toBeDisabled();
	},
};
export const StartingErasure: Story = {
	args: { state: { ...ready, pendingAction: "erase" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Personal-data preview")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Starting erasure…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Download JSON export" })).toBeDisabled();
		await expect(canvas.getByRole("checkbox")).toHaveAttribute("aria-disabled", "true");
	},
};
