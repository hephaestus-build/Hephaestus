import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { ADA, ALAN, EMPTY_TEAM, GRACE, WAYFINDING } from "./fixtures";
import { TeamProjectsPage } from "./TeamProjectsPage";

const meta = {
	component: TeamProjectsPage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: {
			status: "ready",
			teams: [WAYFINDING, EMPTY_TEAM],
			workspaceMembers: [ADA, ALAN, GRACE],
		},
		onCreate: fn(),
		onAddMember: fn(),
		onRemoveMember: fn(),
		onRemoveTeam: fn(),
	},
	decorators: [
		(Story) => (
			<div className="max-w-2xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof TeamProjectsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Each team with its members and counts, never its content, and the line that a removal acts at once. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("A member you remove loses access to the team at once."),
		).toBeVisible();
		const wayfinding = canvas.getByRole("article", { name: "Team Wayfinding" });
		await expect(wayfinding).toHaveTextContent("2 members · 2 reviews");
		await expect(canvas.getByText("No members yet. Add one below.")).toBeVisible();
	},
};

/** Removing a member asks first: the route opens the confirmation. */
export const RemoveMember: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Remove Alan Turing from Team Wayfinding" }),
		);
		await expect(args.onRemoveMember).toHaveBeenCalledWith(WAYFINDING, ALAN);
	},
};

/** A team draws only from current workspace members who are not in it yet, and only Add to team adds one. */
export const AddMember: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(
			canvas.getByRole("combobox", { name: "Choose a member to add to Team Wayfinding" }),
		);
		// The list opens in a portal, outside the canvas.
		const grace = await screen.findByRole("option", { name: "Grace Hopper (ghopper)" });
		await expect(screen.queryByRole("option", { name: "Alan Turing" })).toBeNull();
		await userEvent.click(grace);
		await expect(args.onAddMember).not.toHaveBeenCalled();
		const wayfinding = within(canvas.getByRole("article", { name: "Team Wayfinding" }));
		await userEvent.click(wayfinding.getByRole("button", { name: "Add to team" }));
		await expect(args.onAddMember).toHaveBeenCalledWith(WAYFINDING, GRACE.accountId);
	},
};

/** Creating: the button says so at once. */
export const Creating: Story = {
	args: { creating: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Creating…" })).toBeVisible();
	},
};

export const CreateTeamProject: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.type(canvas.getByRole("textbox", { name: "Team project name" }), "Library");
		await userEvent.click(canvas.getByRole("button", { name: "Create team project" }));
		await expect(args.onCreate).toHaveBeenCalledWith("Library");
	},
};

export const Loading: Story = {
	args: { state: { status: "loading", rows: 2 } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("article")).toBeNull();
	},
};

export const Error: Story = {
	args: { state: { status: "error", error: new globalThis.Error("Network error") } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Team projects could not load")).toBeVisible();
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", teams: [], workspaceMembers: [ADA] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "No team projects yet" })).toBeVisible();
	},
};
