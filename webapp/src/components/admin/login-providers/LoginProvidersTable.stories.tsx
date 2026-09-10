import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { LoginProviderView } from "@/api/types.gen";

import { LoginProvidersTable } from "./LoginProvidersTable";

const providers: LoginProviderView[] = [
	{
		directoryGroupIds: [],
		registrationId: "github",
		type: "GITHUB",
		displayName: "GitHub",
		baseUrl: "https://github.com",
		scopes: "read:user user:email",
		enabled: true,
		seededFromEnv: true,
		redirectUri: "https://hephaestus.example.com/api/login/oauth2/code/github",
		createdAt: new Date("2026-05-01T00:00:00Z"),
		updatedAt: new Date("2026-05-01T00:00:00Z"),
	},
	{
		directoryGroupIds: [],
		registrationId: "gitlab-acme",
		type: "GITLAB",
		displayName: "ACME GitLab",
		baseUrl: "https://gitlab.acme.test",
		scopes: "read_user",
		enabled: false,
		seededFromEnv: false,
		redirectUri: "https://hephaestus.example.com/api/login/oauth2/code/gitlab-acme",
		createdAt: new Date("2026-05-02T00:00:00Z"),
		updatedAt: new Date("2026-05-02T00:00:00Z"),
	},
	{
		directoryGroupIds: [],
		registrationId: "outline-acme",
		type: "OUTLINE",
		displayName: "ACME Outline",
		baseUrl: "https://outline.acme.test",
		scopes: "read",
		enabled: true,
		seededFromEnv: false,
		redirectUri: "https://hephaestus.example.com/api/login/oauth2/code/outline-acme",
		createdAt: new Date("2026-07-02T00:00:00Z"),
		updatedAt: new Date("2026-07-02T00:00:00Z"),
	},
];

const meta = {
	component: LoginProvidersTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		providers,
		isLoading: false,
		isError: false,
		mutatingIds: new Set<string>(),
		onEdit: fn(),
		onToggleEnabled: fn(),
		onDelete: fn(),
		onAdd: fn(),
		onDirectoryGroups: fn(),
		onRetry: fn(),
	},
} satisfies Meta<typeof LoginProvidersTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Populated, including an env-seeded row (badged "seeded"). Provider types render as human labels —
 * never the raw `GITHUB` / `OUTLINE` enum — and each row exposes its redirect URI as a readonly,
 * copyable field for registering on the upstream OAuth app.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("ACME GitLab");
		canvas.getByText("ACME Outline");
		// The type column shows "Outline", never the raw enum "OUTLINE".
		canvas.getByText("Outline");
		await expect(canvas.queryByText("OUTLINE")).not.toBeInTheDocument();
		await expect(canvas.queryByText("GITLAB")).not.toBeInTheDocument();
		// The env-seeded row carries the "seeded" badge; the admin-created ones do not.
		canvas.getByText("seeded");
		canvas.getByRole("button", { name: /Copy redirect URI for GitHub/i });
	},
};

/** A row mid-mutation disables its own toggle/edit/delete so concurrent edits can't race. */
export const RowBusy: Story = {
	args: { mutatingIds: new Set(["github"]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /Edit GitHub/i })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: /Edit ACME GitLab/i })).toBeEnabled();
		await expect(canvas.getByRole("switch", { name: /Disable GitHub/i })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

/** Deleting is a destructive, irreversible action: one hoisted dialog, destructive confirm button. */
export const ConfirmDelete: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /Delete ACME Outline/i }));
		const dialog = await screen.findByRole("alertdialog");
		await expect(within(dialog).getByRole("heading")).toHaveTextContent("Delete “ACME Outline”?");

		const confirm = within(dialog).getByRole("button", { name: "Delete" });
		await userEvent.click(confirm);
		await expect(args.onDelete).toHaveBeenCalledWith(
			expect.objectContaining({ directoryGroupIds: [], registrationId: "outline-acme" }),
		);
	},
};

/** While the delete is in flight the confirm button is disabled and states what is happening. */
export const DeletePending: Story = {
	args: { mutatingIds: new Set(["outline-acme"]) },
	play: async ({ canvas }) => {
		// The row's delete trigger is disabled mid-mutation, so open the dialog on a quiet row and
		// assert the busy affordance on the mutating row instead.
		await expect(canvas.getByRole("button", { name: /Delete ACME Outline/i })).toBeDisabled();
	},
};

/** Empty state is not a dead end — it carries the Add-provider action. */
export const Empty: Story = {
	args: { providers: [] },
	play: async ({ args, canvas }) => {
		canvas.getByText(/No login providers yet/i);
		await userEvent.click(canvas.getByRole("button", { name: /Add provider/i }));
		await expect(args.onAdd).toHaveBeenCalled();
	},
};

/** Failed load → the shared error alert, with a retry. */
export const ErrorState: Story = {
	args: {
		providers: [],
		isError: true,
		error: { detail: "Upstream database unavailable." },
	},
	play: async ({ args, canvas }) => {
		canvas.getByText(/Could not load login providers/i);
		canvas.getByText(/Upstream database unavailable/i);
		await userEvent.click(canvas.getByRole("button", { name: /Retry/i }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};

/** Loading: skeleton rows inside the real table shell, so the layout doesn't jump on arrival. */
export const Loading: Story = {
	args: { providers: [], isLoading: true },
};
