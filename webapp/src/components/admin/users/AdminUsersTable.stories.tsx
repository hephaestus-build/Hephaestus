import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { AdminAccountView } from "@/api/types.gen";
import { expectTablePlaceholders } from "@/stories/loading-more";

import { AdminUsersTable } from "./AdminUsersTable";

const admin: AdminAccountView = {
	id: 1,
	displayName: "Ada Admin",
	primaryEmail: "ada@example.com",
	appRole: "APP_ADMIN",
	status: "ACTIVE",
};
const user: AdminAccountView = {
	id: 2,
	displayName: "Bob User",
	primaryEmail: "bob@example.com",
	appRole: "USER",
	status: "ACTIVE",
};
const blocked: AdminAccountView = {
	id: 3,
	displayName: "Carol Blocked",
	primaryEmail: "carol@example.com",
	appRole: "USER",
	status: "BLOCKED",
};
const unclaimed: AdminAccountView = {
	id: 4,
	displayName: "Dana Unclaimed",
	appRole: "USER",
	status: "UNCLAIMED",
};

/**
 * Super-admin users table (presentation-only). Rendered against fixtures so every state — loading,
 * error, empty, the self-admin lockout guard, and pagination — is reviewable in isolation.
 */
const meta = {
	component: AdminUsersTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		users: [admin, user, blocked, unclaimed],
		isLoading: false,
		hasSearch: false,
		totalLoaded: 4,
		currentUserId: 1,
		hasNextPage: false,
		isFetchingNextPage: false,
		onLoadMore: fn(),
		onChangeRole: fn(),
		onForceSignOut: fn(),
		onChangeStatus: fn(),
	},
} satisfies Meta<typeof AdminUsersTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await canvas.findByText("Ada Admin");
		// Roles and statuses in words, never the constants the wire carries.
		canvas.getByText("Instance admin");
		await expect(canvas.getAllByText("User")).toHaveLength(3);
		canvas.getByText("Blocked");
		canvas.getByText("Unclaimed");
		await expect(
			canvas.queryByText(/^(?:APP_ADMIN|USER|ACTIVE|BLOCKED|UNCLAIMED)$/u),
		).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "Actions for Ada Admin" }));
		// A disabled Base UI item has pointer-events:none, so the guard is asserted rather than clicked.
		const revokeSelf = await screen.findByRole("menuitem", {
			name: /cannot revoke your own instance admin role/iu,
		});
		await expect(revokeSelf).toHaveAttribute("data-disabled");
		// An admin never blocks themselves.
		await expect(screen.queryByRole("menuitem", { name: "Block account" })).not.toBeInTheDocument();
	},
};

export const BlockAnActiveAccount: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Actions for Bob User" }));
		await expect(
			screen.queryByRole("menuitem", { name: "Unblock account" }),
		).not.toBeInTheDocument();
		await userEvent.click(await screen.findByRole("menuitem", { name: "Block account" }));
		await expect(args.onChangeStatus).toHaveBeenCalledWith(user);
	},
};

export const UnblockABlockedAccount: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Actions for Carol Blocked" }));
		await expect(screen.queryByRole("menuitem", { name: "Block account" })).not.toBeInTheDocument();
		await userEvent.click(await screen.findByRole("menuitem", { name: "Unblock account" }));
		await expect(args.onChangeStatus).toHaveBeenCalledWith(blocked);
	},
};

/** Acting on another account fires the role-change callback. */
export const ChangeAnotherUsersRole: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Actions for Bob User" }));
		await userEvent.click(await screen.findByRole("menuitem", { name: "Grant instance admin" }));
		await expect(args.onChangeRole).toHaveBeenCalledWith(user);
	},
};

/**
 * Placeholder rows under the real header, one cell per column, in the busy table — not an empty
 * state and not a "0 users" count before the first page is in.
 */
export const Loading: Story = {
	args: { users: [], totalLoaded: 0, isLoading: true },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "Application users" });
		await expect(table).toHaveAttribute("aria-busy", "true");
		const headers = within(table).getAllByRole("columnheader");
		await expect(headers).toHaveLength(6);
		const [, ...bodyRows] = within(table).getAllByRole("row");
		await expect(bodyRows.length).toBeGreaterThan(0);
		for (const row of bodyRows) {
			await expect(within(row).getAllByRole("cell")).toHaveLength(headers.length);
		}
		await expect(canvas.queryByText("No users yet")).not.toBeInTheDocument();
		await expect(canvas.queryByText(/^Showing /u)).not.toBeInTheDocument();
	},
};

export const Empty: Story = {
	args: { users: [], totalLoaded: 0 },
	play: async ({ canvas }) => {
		canvas.getByText("No users yet");
	},
};

/** Empty because a search matched nothing — copy nudges adjusting the term. */
export const EmptySearch: Story = {
	args: { users: [], hasSearch: true, totalLoaded: 3 },
	play: async ({ canvas }) => {
		canvas.getByText(/try a different search/iu);
	},
};

/** The first read failed in an outage: it says so and offers Retry, with no table or count. */
export const ErrorState: Story = {
	args: {
		users: [],
		loadFailure: { error: { status: 503 }, onRetry: fn(), pending: false },
	},
	play: async ({ canvas }) => {
		canvas.getByText("We could not load users");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
		await expect(canvas.queryByText(/^Showing /u)).not.toBeInTheDocument();
	},
};

export const ErrorRetrying: Story = {
	args: {
		users: [],
		loadFailure: { error: { status: 503 }, onRetry: fn(), pending: true },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
	},
};

/** A refused read says so and offers no retry that would be refused again. */
export const Refused: Story = {
	args: {
		users: [],
		loadFailure: { error: { status: 403 }, onRetry: fn(), pending: false },
	},
	play: async ({ canvas }) => {
		canvas.getByText("You do not have access to users");
		await expect(canvas.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
		await expect(canvas.queryByText(/reload the page/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByRole("table")).not.toBeInTheDocument();
	},
};

/** More pages available — the "Load more" control is offered. */
export const HasMore: Story = {
	args: { hasNextPage: true },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Load more" }));
		await expect(args.onLoadMore).toHaveBeenCalled();
	},
};

/** The next page's placeholder rows continue the table in its own columns while it is busy. */
export const LoadingMore: Story = {
	args: { hasNextPage: true, isFetchingNextPage: true },
	play: async ({ canvas }) => {
		await expectTablePlaceholders(canvas.getByRole("table", { name: "Application users" }));
		await expect(canvas.getByRole("button", { name: "Load more" })).toBeDisabled();
	},
};

/** A search reads every page before it says nothing matched. */
export const Searching: Story = {
	args: { users: [], hasSearch: true, hasNextPage: true, isFetchingNextPage: true },
	play: async ({ canvas }) => {
		canvas.getByText("Searching all users…");
		await expect(canvas.queryByText("No users found")).not.toBeInTheDocument();
		// The one row says the search reads on; no placeholder rows stand under it.
		const table = canvas.getByRole("table", { name: "Application users" });
		await expect(table).toHaveAttribute("aria-busy", "true");
		await expect(table.querySelector("[data-loading-row]")).toBeNull();
	},
};

/**
 * The next page failed during a search: the loaded matches stay, the warning says the search is
 * incomplete, and its Retry is the only way to ask again.
 */
export const SearchIncomplete: Story = {
	args: {
		users: [admin],
		hasSearch: true,
		totalLoaded: 25,
		hasNextPage: true,
		loadMoreError: { status: 503 },
	},
	play: async ({ canvas }) => {
		canvas.getByText("Ada Admin");
		canvas.getByText("Showing 1 of 25 loaded users");
		canvas.getByText("Search results may be incomplete");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
	},
};

/** No loaded user matched and the rest did not load, so the empty state does not claim a result. */
export const SearchIncompleteEmpty: Story = {
	args: {
		users: [],
		hasSearch: true,
		totalLoaded: 25,
		hasNextPage: true,
		loadMoreError: { status: 503 },
	},
	play: async ({ canvas }) => {
		canvas.getByText("No matches among the loaded users");
		canvas.getByText(/search did not include them/iu);
		await expect(canvas.queryByText("No users found")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Searching all users…")).not.toBeInTheDocument();
		await expect(canvas.queryByText(/try a different search/iu)).not.toBeInTheDocument();
	},
};

/** A failed refresh keeps the loaded users and says they may be out of date. */
export const RefreshFailed: Story = {
	args: { refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() } },
	play: async ({ canvas }) => {
		canvas.getByText("These users may be out of date");
		canvas.getByText("Ada Admin");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

export const RefreshRetrying: Story = {
	args: { refreshFailure: { error: { status: 503 }, pending: true, onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
	},
};
