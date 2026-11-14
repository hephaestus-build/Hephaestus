import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";
import type { ProviderResourceRepair } from "@/api/types.gen";

import { hoursBefore } from "@/stories/story-clock";

import { ProviderResourceRepairs } from "./ProviderResourceRepairs";

const meta = {
	component: ProviderResourceRepairs,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: { status: "ready", items: [] },
		onRelease: fn(async () => undefined),
	},
} satisfies Meta<typeof ProviderResourceRepairs>;
export default meta;
type Story = StoryObj<typeof meta>;

const repair = {
	id: "9f79394d-f5ab-4bbf-a6c7-4fde97bb8180",
	identityHash: "a".repeat(64),
	kind: "OUTLINE",
	origin: "https://outline.example.test",
	resourceType: "SUBSCRIPTION",
	resourceId: "old-subscription",
	parentId: "",
	state: "AWAITING_CONFIRMATION",
	replacementInUse: false,
	createdAt: hoursBefore(2),
	owners: [],
} satisfies ProviderResourceRepair;

export const Empty: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No pending provider removals.")).toBeVisible();
	},
};
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText("Loading provider removals")).toBeVisible();
	},
};
export const Error: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 503, detail: "The server is unavailable." },
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load provider removals")).toBeVisible();
	},
};
export const WorkspaceAdministrator: Story = {
	args: { state: { status: "ready", items: [{ repair, release: "instance-admin" }] } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Confirm removal" })).not.toBeInTheDocument();
		await expect(
			canvas.getByText(
				"An instance admin can release the block after confirming removal is complete.",
			),
		).toBeVisible();
	},
};
export const InstanceAdministratorOnConnection: Story = {
	args: { state: { status: "ready", items: [{ repair, release: "available" }] } },
	play: async ({ canvas, canvasElement, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Confirm removal" }));
		const body = within(canvasElement.ownerDocument.body);
		await expect(body.getByRole("button", { name: "Release block" })).toBeDisabled();
		await userEvent.click(body.getByRole("checkbox"));
		await expect(body.getByRole("button", { name: "Release block" })).toBeEnabled();
	},
};
export const Registering: Story = {
	args: {
		state: {
			status: "ready",
			items: [{ repair: { ...repair, state: "REGISTERING" }, release: "registering" }],
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"Wait for the authorization or registration to finish before confirming its outcome.",
			),
		).toBeVisible();
	},
};
export const AuthorizationNeeded: Story = {
	args: {
		state: {
			status: "ready",
			items: [{ repair: { ...repair, state: "NEEDS_AUTHORIZATION" }, release: "instance-admin" }],
		},
	},
};
export const SlackBotUser: Story = {
	args: {
		state: {
			status: "ready",
			items: [
				{
					repair: {
						...repair,
						kind: "SLACK",
						resourceType: "BOT_USER",
						parentId: "T123",
						resourceId: "U456",
					},
					release: "instance-admin",
				},
			],
		},
	},
};
export const FreshReplacement: Story = {
	args: {
		state: {
			status: "ready",
			items: [{ repair: { ...repair, replacementInUse: true }, release: "instance-admin" }],
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("A connection is using a different resource while this removal stays open."),
		).toBeVisible();
	},
};
export const AwaitingLateRemoval: Story = {
	args: {
		state: {
			status: "ready",
			items: [{ repair: { ...repair, state: "CONFIRMATION_REQUIRED" }, release: "available" }],
		},
	},
};
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	args: {
		state: {
			status: "ready",
			items: [
				{
					repair: {
						...repair,
						origin: "https://documentation.engineering.example.test",
						resourceId: "41aa91ab-7e54-4cc0-a344-a60ee874312f",
					},
					release: "available",
				},
			],
		},
		totalPages: 2,
		page: 0,
		onPageChange: fn(),
	},
};

export const InstanceAdministratorOrphan: Story = {
	args: {
		state: {
			status: "ready",
			items: [
				{
					repair: {
						...repair,
						kind: "GITHUB",
						origin: "https://github.com",
						resourceType: "INSTALLATION",
						parentId: "123",
						resourceId: "4242",
						state: "CONFIRMATION_REQUIRED",
					},
					release: "available",
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("App installation")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Confirm removal" })).toBeEnabled();
	},
};

export const InstanceOverviewWithOwner: Story = {
	args: {
		state: {
			status: "ready",
			items: [
				{
					repair: { ...repair, owners: [{ workspaceSlug: "platform", connectionId: 7 }] },
					release: "at-connection",
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("link", { name: "Open connection administration" }),
		).toHaveAttribute("href", "/w/platform/admin/integrations/outline?repairConnection=7");
		await expect(
			canvas.getByText(
				"Confirm that removal is complete, then release the block in connection administration.",
			),
		).toBeVisible();
		await expect(canvas.queryByText(/An instance admin can release/u)).not.toBeInTheDocument();
	},
};

export const UncertainSlackAuthorization: Story = {
	args: {
		state: {
			status: "ready",
			items: [
				{
					repair: {
						...repair,
						kind: "SLACK",
						resourceType: "OAUTH_EXCHANGE",
						origin: "https://slack.com",
						parentId: "T-EXAMPLE",
						resourceId: "authorization-attempt",
						state: "CONFIRMATION_REQUIRED",
					},
					release: "available",
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Slack authorization")).toBeVisible();
	},
};
