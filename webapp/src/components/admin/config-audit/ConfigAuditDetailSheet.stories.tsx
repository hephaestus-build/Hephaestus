import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import type { ConfigAuditEntryView } from "@/api/types.gen";
import { expectSettledVisible } from "@/test/overlay";

import { ConfigAuditDetailSheet } from "./ConfigAuditDetailSheet";

const baseEntry: ConfigAuditEntryView = {
	id: 91,
	elevatedViaInstanceAdmin: false,
	occurredAt: new Date("2026-07-24T09:14:32Z"),
	action: "UPDATED",
	entityType: "AGENT_BINDING",
	entityId: "PRACTICE_REVIEW",
	actorKind: "USER",
	actorAccountId: 7,
	actor: { id: 7, displayName: "Ada Lovelace", email: "ada@example.com" },
	workspaceId: 3,
	changedKeys: ["timeoutSeconds", "maxConcurrentJobs"],
	oldValue: JSON.stringify({ timeoutSeconds: 600, maxConcurrentJobs: 1, allowInternet: false }),
	newValue: JSON.stringify({ timeoutSeconds: 900, maxConcurrentJobs: 3, allowInternet: false }),
};

const meta = {
	component: ConfigAuditDetailSheet,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: {
		entry: baseEntry,
		open: true,
		onOpenChange: fn(),
		resolveWorkspaceName: (id: number) => (id === 3 ? "Acme" : undefined),
	},
} satisfies Meta<typeof ConfigAuditDetailSheet>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async () => {
		const body = within(document.body);
		await expectSettledVisible(await body.findByText("Acme (#3)"));
		await expectSettledVisible(await body.findByText(/timeoutSeconds/));
	},
};

export const SystemActor: Story = {
	args: {
		entry: { ...baseEntry, actorKind: "SYSTEM", actor: undefined, actorAccountId: undefined },
	},
};

export const Impersonated: Story = {
	args: {
		entry: {
			...baseEntry,
			actorKind: "IMPERSONATED",
			actingAccountId: 1,
			actingActor: { id: 1, displayName: "Grace Hopper", email: "grace@example.com" },
		},
	},
};

export const Created: Story = {
	args: {
		entry: {
			...baseEntry,
			action: "CREATED",
			oldValue: undefined,
			changedKeys: undefined,
			newValue: JSON.stringify({ timeoutSeconds: 600, maxConcurrentJobs: 1 }),
		},
	},
};

export const Deleted: Story = {
	args: {
		entry: { ...baseEntry, action: "DELETED", newValue: undefined, changedKeys: undefined },
	},
};

export const ElevatedAccess: Story = {
	args: { entry: { ...baseEntry, elevatedViaInstanceAdmin: true } },
	play: async () => {
		const body = within(document.body);
		await expectSettledVisible(await body.findByText("Access"));
		await expectSettledVisible(await body.findByText(/not a member of/i));
	},
};

export const NoWorkspace: Story = {
	args: { entry: { ...baseEntry, workspaceId: undefined } },
};

export const RawSnapshotsRevealed: Story = {
	play: async () => {
		const body = within(document.body);
		const trigger = await body.findByRole("button", { name: "Show raw snapshots" });
		await expect(trigger).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(trigger);
		await expect(trigger).toHaveAttribute("aria-expanded", "true");
	},
};

export const Closed: Story = {
	args: { open: false },
};
