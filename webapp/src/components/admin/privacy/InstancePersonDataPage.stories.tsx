import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

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
		outline_document: 0,
	},
	scope: { accountId: 42, identities: [{ providerId: 1, subject: "314" }] },
	completed: {},
	externalDeliveries: [],
} satisfies PersonDataRequest;

const onErase = fn();
const ready = { status: "ready", request, onExport: fn(), onErase } as const;

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
	parameters: { layout: "padded" },
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
	play: async ({ canvas, userEvent: user }) => {
		await expect(canvas.getByRole("heading", { name: "No person selected" })).toBeVisible();
		const preview = canvas.getByRole("button", { name: "Preview data" });
		await expect(preview).toBeDisabled();
		await user.type(canvas.getByLabelText("Account ID"), "42");
		await expect(preview).toBeEnabled();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading preview").parentElement).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.getByRole("button", { name: "Preview data" })).toBeDisabled();
	},
};

export const LoadError: Story = {
	args: {
		state: {
			status: "error",
			error: {
				status: 409,
				detail: "The supplied identities belong to different accounts.",
			},
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
		await expect(canvas.getByText(/14 rows in 5 of 7 stores/u)).toBeVisible();
		await expect(canvas.getByText(/GITLAB · https:\/\/gitlab\.example\.com: user/u)).toBeVisible();
		await expect(canvas.getByRole("cell", { name: "slack_message" })).toBeVisible();
		await expect(canvas.queryByRole("cell", { name: "user_preferences" })).toBeNull();

		await userEvent.click(canvas.getByRole("button", { name: "2 stores hold no rows" }));
		await expect(canvas.getByText("user_preferences")).toBeVisible();

		await userEvent.click(canvas.getByRole("button", { name: "Erase data…" }));
		const dialog = within(await screen.findByRole("alertdialog"));
		await userEvent.type(dialog.getByLabelText(/to confirm/iu), "ERASE");
		await userEvent.click(dialog.getByRole("button", { name: "Erase data" }));
		await expect(onErase).toHaveBeenCalledWith(false);
	},
};

export const ProviderCopies: Story = {
	args: {
		state: {
			...ready,
			request: {
				...request,
				externalDeliveries: [
					{
						workspaceId: 7,
						locator: "https://gitlab.example.com/acme/api/-/merge_requests/12#note_345",
					},
					{ workspaceId: 7, locator: "slack:T12345/C123/1700000000.000100" },
				],
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Feedback is still posted on providers")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /merge_requests\/12/u })).toHaveAttribute(
			"href",
			"https://gitlab.example.com/acme/api/-/merge_requests/12#note_345",
		);
		await expect(canvas.getByText("slack:T12345/C123/1700000000.000100")).toBeVisible();
	},
};

export const ActionRejected: Story = {
	args: {
		state: {
			...ready,
			actionError: { status: 409, detail: "The preview scope changed." },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("The preview scope changed.");
		await expect(canvas.getByRole("cell", { name: "observation" })).toBeVisible();
	},
};

export const Downloading: Story = {
	args: { state: { ...ready, pendingAction: "export" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Downloading…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Erase data…" })).toBeDisabled();
		await expect(canvas.getByLabelText("Account ID")).toBeDisabled();
		await expect(canvas.getByRole("cell", { name: "observation" })).toBeVisible();
	},
};

export const StartingErasure: Story = {
	args: { state: { ...ready, pendingAction: "erase" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Starting erasure…" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Download JSON export" })).toBeDisabled();
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
		await expect(canvas.getByRole("columnheader", { name: "Erased" })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /erase/iu })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Preview data" })).toBeDisabled();
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
		await expect(canvas.getByText(/STORE_ERASURE_FAILED/u)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Resume erasure…" })).toBeEnabled();
		await expect(canvas.queryByRole("button", { name: "Download JSON export" })).toBeNull();
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
					outline_document: 0,
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Erasure complete")).toBeVisible();
		await expect(canvas.queryByRole("heading", { name: "Identities" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: /export|erase/iu })).toBeNull();
	},
};

export const Expired: Story = {
	args: { state: { ...ready, request: { ...request, state: "EXPIRED", scope: undefined } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Preview again to export or erase/u)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /export|erase/iu })).toBeNull();
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
		await expect(canvas.getByLabelText("Provider user ID")).toBeRequired();
	},
};

export const Narrow: Story = {
	args: { state: ready },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
};
