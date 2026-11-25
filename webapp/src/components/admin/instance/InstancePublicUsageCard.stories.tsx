import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen } from "storybook/test";

import { Stateful } from "@/stories/stateful";

import {
	InstancePublicUsageCard,
	type InstancePublicUsageCardProps,
} from "./InstancePublicUsageCard";

const meta = {
	component: InstancePublicUsageCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: {
			status: "ready",
			allowed: false,
			pending: false,
			withdrawals: [],
			withdrawalsUnreadable: false,
		},
		onAllowedChange: fn(),
	},
} satisfies Meta<typeof InstancePublicUsageCard>;

export default meta;
type Story = StoryObj<typeof meta>;

const SWITCH = "Publish Hephaestus in numbers";

/** The switch keeps its own state here, so the story shows what an admin sees after each answer. */
function StatefulCard(args: InstancePublicUsageCardProps) {
	return (
		<Stateful initial={args.state.status === "ready" && args.state.allowed}>
			{(allowed, setAllowed) => (
				<InstancePublicUsageCard
					{...args}
					state={args.state.status === "ready" ? { ...args.state, allowed } : args.state}
					onAllowedChange={(next) => {
						args.onAllowedChange(next);
						setAllowed(next);
					}}
				/>
			)}
		</Stateful>
	);
}

/** Turning the page on asks first, because anyone can then read the released half-years. */
export const Off: Story = {
	render: (args) => <StatefulCard {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		await expect(
			canvas.getByText("Off. Within 5 minutes, a new load of the page shows no figures."),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(await screen.findByRole("alertdialog")).toHaveTextContent("keep a copy");
		await expect(args.onAllowedChange).not.toHaveBeenCalled();
		await userEvent.click(screen.getByRole("button", { name: "Publish figures" }));
		await expect(args.onAllowedChange).toHaveBeenCalledWith(true);
		await expect(canvas.getByRole("switch", { name: SWITCH })).toBeChecked();
	},
};

/** Keeping the page off at the question changes nothing. */
export const KeepItOff: Story = {
	render: (args) => <StatefulCard {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await userEvent.click(await screen.findByRole("button", { name: "Keep it off" }));
		await expect(args.onAllowedChange).not.toHaveBeenCalled();
		await expect(canvas.getByRole("switch", { name: SWITCH })).not.toBeChecked();
	},
};

/**
 * While a change is saved, the switch keeps its place and focus but takes no second change, by pointer or by keyboard.
 */
export const Saving: Story = {
	args: {
		state: {
			status: "ready",
			allowed: true,
			pending: true,
			withdrawals: [],
			withdrawalsUnreadable: false,
		},
	},
	play: async ({ args, canvas, userEvent }) => {
		const control = canvas.getByRole("switch", { name: SWITCH });
		await userEvent.click(control);
		await userEvent.keyboard(" ");
		await expect(control).toHaveFocus();
		await expect(control).toHaveAttribute("aria-readonly", "true");
		await expect(control).toBeChecked();
		await expect(args.onAllowedChange).not.toHaveBeenCalled();
	},
};

/** Turning the page off takes effect at once, with no question. */
export const TurnOff: Story = {
	args: {
		state: {
			status: "ready",
			allowed: true,
			pending: false,
			withdrawals: [],
			withdrawalsUnreadable: false,
		},
	},
	render: (args) => <StatefulCard {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("switch", { name: SWITCH }));
		await expect(args.onAllowedChange).toHaveBeenCalledWith(false);
		await expect(canvas.getByRole("switch", { name: SWITCH })).not.toBeChecked();
	},
};

/** On, with a withdrawn half-year and a withdrawn figure, which stay hidden for good. */
export const OnWithWithdrawals: Story = {
	args: {
		state: {
			status: "ready",
			allowed: true,
			pending: false,
			withdrawals: [{ period: "2026-04" }, { period: "2025-10", figure: "REVIEWED_PEOPLE" }],
			withdrawalsUnreadable: false,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("switch", { name: SWITCH })).toBeChecked();
		await expect(canvas.getByText("2026-04: the whole half-year")).toBeVisible();
		await expect(canvas.getByText("2025-10: Developers whose work was reviewed")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("switch")).toBeNull();
	},
};

export const Failed: Story = {
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /Retry/u })).toBeVisible();
	},
};

/** A failed refresh keeps the last setting, says so, and offers Retry. */
export const RefreshFailed: Story = {
	args: {
		state: {
			status: "ready",
			allowed: true,
			pending: false,
			withdrawals: [],
			withdrawalsUnreadable: false,
			refreshFailure: { error: { status: 503 }, onRetry: fn(), pending: false },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This setting may be out of date")).toBeVisible();
		await expect(canvas.getByRole("switch", { name: SWITCH })).toBeChecked();
	},
};

/** A stored withdrawal that cannot be read hides every figure, and the card says the list may be incomplete. */
export const WithdrawalsUnreadable: Story = {
	args: {
		state: {
			status: "ready",
			allowed: true,
			pending: false,
			withdrawals: [{ period: "2026-04" }],
			withdrawalsUnreadable: true,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("The page hides every figure")).toBeVisible();
		await expect(canvas.getByText(/may be incomplete/u)).toBeVisible();
	},
};
