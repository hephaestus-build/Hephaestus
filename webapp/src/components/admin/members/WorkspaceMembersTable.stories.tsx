import type { Meta, StoryContext, StoryObj } from "@storybook/react";
import { expect, fn, screen, within } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import {
	memberAccount,
	membership,
	mockMemberships,
	mockTeams,
	multiProviderMember,
	ownerMember,
	unclaimedMember,
} from "./fixtures";

import { workspaceMemberRows } from "./workspace-members";
import { WorkspaceMembersTable } from "./WorkspaceMembersTable";

const members = workspaceMemberRows(mockMemberships);

type Scope = StoryContext["canvas"];

function rowOf(canvas: Scope, name: string): HTMLElement {
	const row = canvas.getByText(name, { selector: "div" }).closest("tr");
	if (row === null) {
		throw new Error(`No row for ${name}`);
	}
	return row;
}

const meta = {
	component: WorkspaceMembersTable,
	parameters: {
		layout: "fullscreen",
	},
	tags: ["autodocs"],
	args: {
		members,
		rowCount: members.length,
		teams: mockTeams,
		onToggleHidden: fn(),
		onRoleChange: fn(),
		onRemove: fn(),
		onAddBack: fn(),
		view: { q: "", team: "all", sort: "name", desc: false, page: 0, size: 10 },
		onViewChange: fn(),
	},
} satisfies Meta<typeof WorkspaceMembersTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// The sort lives in the URL, so a column is always sorted — and which one has to be legible
		// to a screen reader, not only to whoever can see the arrow.
		await expect(canvas.getByRole("columnheader", { name: "Name" })).toHaveAttribute(
			"aria-sort",
			"ascending",
		);
		await expect(canvas.getByRole("columnheader", { name: "Username" })).toHaveAttribute(
			"aria-sort",
			"none",
		);
	},
};

export const MultiProviderAccount: Story = {
	args: { members: workspaceMemberRows([multiProviderMember]), rowCount: 1 },
	play: async ({ canvas }) => {
		const row = within(rowOf(canvas, "Alice Johnson"));
		await expect(row.getByText("alice")).toBeVisible();
		await expect(row.getByText("ajohnson")).toBeVisible();
		await expect(row.getByText("(GitLab)")).toBeVisible();
		await expect(canvas.getByText("Showing 1 of 1 member")).toBeVisible();
	},
};

export const UnclaimedAccount: Story = {
	args: { members: workspaceMemberRows([unclaimedMember]), rowCount: 1 },
	play: async ({ canvas }) => {
		const row = within(rowOf(canvas, "Bob Smith"));
		await expect(row.getByText("Unclaimed")).toBeVisible();
		await expect(row.queryByText("Active")).not.toBeInTheDocument();
	},
};

const erinWhoLeft = membership(
	memberAccount("21", {
		displayName: "Erin Former",
		identities: [{ provider: "GITHUB", login: "erin", displayName: null, avatarUrl: null }],
	}),
	{ leftAt: new Date("2026-06-30T12:00:00Z") },
);

/** A removed member keeps a row that says when they left, and the Actions menu adds them back. */
export const MemberWhoLeft: Story = {
	args: { rowCount: 1, members: workspaceMemberRows([erinWhoLeft]) },
	play: async ({ args, canvas, userEvent }) => {
		const row = within(rowOf(canvas, "Erin Former"));
		await expect(row.getByText(/^Left /u)).toBeVisible();
		const actions = row.getByRole("button", { name: "Actions for Erin Former" });
		await userEvent.click(actions);
		const addBack = await screen.findByRole("menuitem", { name: "Add back to workspace" });
		await expect(screen.queryByRole("menuitem", { name: "Remove from workspace" })).toBeNull();
		await userEvent.click(addBack);
		await expect(args.onAddBack).toHaveBeenCalledWith(
			expect.objectContaining({ name: "Erin Former" }),
			actions,
		);
	},
};

/** A workspace that does not publish public activity has no answers to show, so it has no column. */
export const NoPublicActivityPage: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("columnheader", { name: "Public activity" })).toBeNull();
	},
};

function grace(publicActivityAnswered: boolean) {
	return workspaceMemberRows([
		membership(
			memberAccount("31", {
				displayName: "Grace Hopper",
				identities: [{ provider: "GITHUB", login: "grace", displayName: null, avatarUrl: null }],
			}),
			{ publicActivityAnswered },
		),
	]);
}

/** Where the workspace publishes, an admin sees who has not answered yet, and may follow up. */
export const PublicActivityNotAnswered: Story = {
	args: { members: grace(false), rowCount: 1 },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: "Public activity" })).toBeVisible();
		const badge = within(rowOf(canvas, "Grace Hopper")).getByRole("button", {
			name: "Not answered yet: Grace Hopper",
		});
		await expect(badge).toHaveTextContent("Not answered yet");
		await expect(badge).toHaveAccessibleDescription(
			"They have not answered this workspace’s question.",
		);
	},
};

/** A member who answered draws nothing: the column does not show which way anyone chose. */
export const PublicActivityAnswered: Story = {
	args: { members: grace(true), rowCount: 1 },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("columnheader", { name: "Public activity" })).toBeVisible();
		const row = within(rowOf(canvas, "Grace Hopper"));
		await expect(row.queryByRole("button", { name: /^Not answered yet/u })).toBeNull();
		await expect(row.queryByText(/Shown|Hidden/u)).toBeNull();
	},
};

export const RoleChangePending: Story = {
	args: { pendingRoleAccountId: unclaimedMember.account.id },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "Role of Bob Smith" })).toBeDisabled();
		await expect(canvas.getByRole("combobox", { name: "Role of Alice Johnson" })).toBeEnabled();
	},
};

/** Remove asks first: the table hands the member and the menu, where focus returns, to the caller. */
export const RemoveMember: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const actions = canvas.getByRole("button", { name: "Actions for Bob Smith" });
		await userEvent.click(actions);
		const remove = await screen.findByRole("menuitem", { name: "Remove from workspace" });
		await expect(screen.queryByRole("menuitem", { name: "Add back to workspace" })).toBeNull();
		await userEvent.click(remove);
		await expect(args.onRemove).toHaveBeenCalledWith(
			expect.objectContaining({ name: "Bob Smith" }),
			actions,
		);
	},
};

/**
 * Only the members whose removal is in flight wait. Their menu stays reachable, so focus can return
 * to it, but it offers nothing to fire twice, and their role waits too.
 */
export const RemovalPending: Story = {
	args: { removingAccountIds: new Set([unclaimedMember.account.id]) },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByRole("combobox", { name: "Role of Bob Smith" })).toBeDisabled();
		await expect(canvas.getByRole("combobox", { name: "Role of Alice Johnson" })).toBeEnabled();
		const actions = canvas.getByRole("button", { name: "Actions for Bob Smith" });
		await expect(actions).toHaveAttribute("aria-busy", "true");
		await userEvent.click(actions);
		await expect(
			await screen.findByRole("menuitem", { name: "Remove from workspace" }),
		).toHaveAttribute("aria-disabled", "true");
	},
};

/** Leaving would lock the reader out of this page, so their own row offers no removal. */
export const OwnRowOffersNoRemoval: Story = {
	args: { readerAccountId: multiProviderMember.account.id },
	play: async ({ canvas }) => {
		const own = within(rowOf(canvas, "Alice Johnson"));
		await expect(own.queryByRole("button", { name: /^Actions for/u })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Actions for Bob Smith" })).toBeEnabled();
	},
};

/** An admin manages admins and members only: an owner's row is read-only, and Owner is not offered. */
export const AdminCannotAssignOwner: Story = {
	args: { assignableRoles: ["ADMIN", "MEMBER"] },
	play: async ({ args, canvas, userEvent }) => {
		const owner = within(rowOf(canvas, "Charlie Brown"));
		await expect(owner.queryByRole("combobox")).not.toBeInTheDocument();
		// Only an owner removes an owner, as only an owner changes one's role.
		await expect(owner.queryByRole("button", { name: /^Actions for/u })).toBeNull();
		await expect(owner.getByText("Owner")).toBeVisible();

		await userEvent.click(canvas.getByRole("combobox", { name: "Role of Alice Johnson" }));
		const member = await screen.findByRole("option", { name: "Member" });
		await expect(screen.queryByRole("option", { name: "Owner" })).not.toBeInTheDocument();
		await userEvent.click(member);
		await expect(args.onRoleChange).toHaveBeenCalledWith(
			expect.objectContaining({ name: "Alice Johnson" }),
			"MEMBER",
			canvas.getByRole("combobox", { name: "Role of Alice Johnson" }),
		);
	},
};

export const RoleFromTheProvider: Story = {
	args: {
		members: workspaceMemberRows([unclaimedMember]),
		rowCount: 1,
		providerLabel: "GitHub",
		onFollowProvider: fn(),
	},
	play: async ({ canvas }) => {
		const row = within(rowOf(canvas, "Bob Smith"));
		await expect(row.getByText("From GitHub")).toBeVisible();
		await expect(row.queryByRole("button", { name: /^Use GitHub role/u })).toBeNull();
	},
};

export const RoleSetInHephaestus: Story = {
	args: {
		members: workspaceMemberRows([multiProviderMember]),
		rowCount: 1,
		providerLabel: "GitHub",
		onFollowProvider: fn(),
	},
	play: async ({ args, canvas, userEvent }) => {
		const row = within(rowOf(canvas, "Alice Johnson"));
		await expect(row.getByText("Set in Hephaestus")).toBeVisible();
		await userEvent.click(row.getByRole("button", { name: "Use GitHub role for Alice Johnson" }));
		await expect(args.onFollowProvider).toHaveBeenCalledWith(
			expect.objectContaining({ name: "Alice Johnson" }),
		);
	},
};

export const OwnerRoleSetInHephaestus: Story = {
	args: {
		members: workspaceMemberRows([ownerMember]),
		rowCount: 1,
		providerLabel: "GitLab",
		onFollowProvider: fn(),
	},
	play: async ({ canvas }) => {
		const row = within(rowOf(canvas, "Charlie Brown"));
		await expect(row.getByText("Set in Hephaestus")).toBeVisible();
		// An owner's role is always set by a person, so it never follows the provider.
		await expect(row.queryByRole("button", { name: /^Use GitLab role/u })).toBeNull();
	},
};

export const FollowProviderPending: Story = {
	args: {
		members: workspaceMemberRows([multiProviderMember]),
		rowCount: 1,
		providerLabel: "GitHub",
		onFollowProvider: fn(),
		pendingFollowAccountId: multiProviderMember.account.id,
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Use GitHub role for Alice Johnson" }),
		).toBeDisabled();
	},
};

export const NoProviderSetsRoles: Story = {
	args: { providerLabel: undefined, onFollowProvider: fn() },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Set in Hephaestus")).toBeNull();
		await expect(canvas.queryByText(/^From /u)).toBeNull();
	},
};

export const FilteredByTeam: Story = {
	args: {
		members: workspaceMemberRows([multiProviderMember]),
		rowCount: 1,
		view: { q: "", team: "3", sort: "name", desc: false, page: 0, size: 10 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Alice Johnson")).toBeVisible();
		await expect(canvas.queryByText("Charlie Brown")).not.toBeInTheDocument();
	},
};

export const AtTwoHundredPercentZoom: Story = {
	parameters: { viewport: { defaultViewport: "zoom200" } },
	play: expectNoPageOverflow,
};

export const Loading: Story = {
	args: {
		isLoading: true,
		members: [],
		rowCount: 0,
	},
};

/** A new search is on its way: the rows already read stay readable, and only the table says it is busy. */
export const Updating: Story = {
	args: {
		updating: true,
		view: { q: "bob", team: "all", sort: "name", desc: false, page: 0, size: 10 },
	},
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "Workspace members" });
		await expect(table).toHaveAttribute("aria-busy", "true");
		await expect(within(rowOf(canvas, "Bob Smith")).getByText("Unclaimed")).toBeVisible();
		await expect(canvas.queryByText(/^Showing/u)).toBeNull();
		await canvas.findByText("Updating members…", {}, { timeout: 3000 });
		await expect(canvas.getByRole("status")).toHaveTextContent("Updating members…");
	},
};

export const Empty: Story = {
	args: {
		members: [],
		rowCount: 0,
		isLoading: false,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This workspace has no members yet.")).toBeVisible();
	},
};

export const EmptyPage: Story = {
	args: {
		members: [],
		rowCount: 2,
		view: { q: "", team: "all", sort: "name", desc: false, page: 9, size: 10 },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No members on this page")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Go to first page" })).toBeVisible();
	},
};

export const ReadOnly: Story = {
	args: {
		onRoleChange: undefined,
		onToggleHidden: undefined,
		onRemove: undefined,
		onAddBack: undefined,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("combobox", { name: /^Role of/u })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: /^Actions for/u })).toBeNull();
		await expect(canvas.getByText("Owner")).toBeVisible();
	},
};

export const ManyMembers: Story = {
	args: {
		rowCount: 50,
		members: workspaceMemberRows(
			Array.from({ length: 10 }, (_, i) =>
				membership(
					memberAccount(String(i + 100), {
						displayName: `User ${String(i + 1).padStart(2, "0")}`,
						identities: [
							{ provider: "GITHUB", login: `user${i + 1}`, displayName: null, avatarUrl: null },
						],
					}),
				),
			),
		),
	},
};
