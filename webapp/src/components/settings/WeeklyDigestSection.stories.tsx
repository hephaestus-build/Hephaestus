import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, waitFor, within } from "storybook/test";

import { TIME_ZONES } from "@/lib/dates";

import { WeeklyDigestSection } from "./WeeklyDigestSection";

const onToggle = fn();
const onChangeTimeZone = fn();

const ready = {
	status: "ready",
	enabled: false,
	available: true,
	timeZone: "Europe/Berlin",
	zones: TIME_ZONES,
	isPending: false,
	onToggle,
	onChangeTimeZone,
} as const;

const meta = {
	component: WeeklyDigestSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { state: ready },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof WeeklyDigestSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Off until the developer turns it on. */
export const Default: Story = {
	play: async ({ canvas, userEvent }) => {
		const toggle = canvas.getByRole("switch", { name: "Weekly digest" });
		await expect(toggle).not.toBeChecked();
		await userEvent.click(toggle);
		await expect(onToggle).toHaveBeenCalledWith(true, expect.anything());
	},
};

/** The time zone decides when the digest arrives; the developer can change it. */
export const ChangeTimeZone: Story = {
	args: { state: { ...ready, enabled: true } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Time zone" }));
		const body = within(canvas.getByRole("combobox", { name: "Time zone" }).ownerDocument.body);
		await userEvent.click(await body.findByRole("option", { name: "Asia/Tokyo" }));
		await expect(onChangeTimeZone).toHaveBeenCalledWith("Asia/Tokyo");
		// Picking a zone closes the list. Base UI drops `aria-controls` as the close starts, so for
		// the exit animation axe no longer sees the list as the combobox's popup; it checks the open
		// list in `TimeZoneListOpen`.
		await waitFor(async () => expect(body.queryByRole("listbox")).toBeNull());
	},
};

/**
 * The open list holds every zone and scrolls. Focus stays in the search while Home and End move
 * through the zones and scroll the one they reach into view, so a keyboard reaches all of them. The
 * story ends with the list open, so axe checks it.
 */
export const TimeZoneListOpen: Story = {
	args: { state: { ...ready, enabled: true } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Time zone" }));
		const body = within(canvas.getByRole("combobox", { name: "Time zone" }).ownerDocument.body);
		const list = await body.findByRole("listbox", { name: "Time zones" });
		const search = body.getByRole("combobox", { name: "Search time zones" });
		await expect(list.scrollHeight).toBeGreaterThan(list.clientHeight);

		await userEvent.keyboard("{End}");
		const last = body.getByRole("option", { name: TIME_ZONES.at(-1) });
		await expect(search).toHaveFocus();
		await expect(search).toHaveAttribute("aria-activedescendant", last.id);
		await expect(list.scrollTop).toBeGreaterThan(0);

		await userEvent.keyboard("{Home}");
		await expect(search).toHaveAttribute(
			"aria-activedescendant",
			body.getByRole("option", { name: "UTC" }).id,
		);
		await expect(list.scrollTop).toBe(0);
	},
};

/** UTC is a choice like any zone, so a developer who chose another can go back to it. */
export const ChooseUtc: Story = {
	args: { state: { ...ready, enabled: true } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Time zone" }));
		const body = within(canvas.getByRole("combobox", { name: "Time zone" }).ownerDocument.body);
		await userEvent.type(await body.findByRole("combobox", { name: "Search time zones" }), "UTC");
		await userEvent.click(await body.findByRole("option", { name: "UTC" }));
		await expect(onChangeTimeZone).toHaveBeenCalledWith("UTC");
	},
};

/** Heph answers in none of the developer's workspaces: a digest that is on can still be turned off. */
export const UnavailableButOn: Story = {
	args: { state: { ...ready, enabled: true, available: false } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/no weekly digest is written\. You can still turn it off\.$/u),
		).toBeVisible();
		await expect(canvas.getByRole("switch", { name: "Weekly digest" })).toBeEnabled();
	},
};

/** Turned off where Heph answers nowhere: the switch cannot turn on a digest that would never be written. */
export const TurnedOffWhereUnavailable: Story = {
	args: { state: { ...ready, available: false } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/no weekly digest is written\.$/u)).toBeVisible();
		await expect(canvas.queryByText(/You can still turn it off/u)).toBeNull();
		await expect(canvas.getByRole("switch", { name: "Weekly digest" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const ErrorState: Story = {
	name: "Error",
	args: { state: { status: "error", error: new Error("Network down"), onRetry: fn() } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your weekly digest choice")).toBeVisible();
	},
};
