import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { SlackMonitoredChannel } from "@/api/types.gen";

import {
	RemoveChannelAlertDialog,
	type RemoveChannelAlertDialogProps,
} from "./RemoveChannelAlertDialog";

const base = {
	slackTeamId: "T0000000000",
	createdAt: new Date("2026-01-01T00:00:00Z"),
} satisfies Pick<SlackMonitoredChannel, "slackTeamId" | "createdAt">;

const active: SlackMonitoredChannel = {
	...base,
	id: 1,
	slackChannelId: "C02ACTIVE002",
	channelName: "team-standup",
	consentState: "ACTIVE",
	optedOutMemberCount: 0,
};

describe("RemoveChannelAlertDialog — resets on close", () => {
	it("clears a typed confirmation and reason after Cancel, ready for the next open", () => {
		const onOpenChange = vi.fn<RemoveChannelAlertDialogProps["onOpenChange"]>();
		render(
			<RemoveChannelAlertDialog channel={active} onOpenChange={onOpenChange} onConfirm={vi.fn()} />,
		);

		fireEvent.change(screen.getByLabelText(/to confirm/iu), {
			target: { value: active.slackChannelId },
		});
		fireEvent.change(screen.getByLabelText(/reason/iu), { target: { value: "testing" } });

		fireEvent.click(screen.getByRole("button", { name: /^cancel$/iu }));
		expect(onOpenChange).toHaveBeenCalledWith(false);

		// Same instance persists (no `key`-driven remount) — the fields reset synchronously.
		expect(screen.getByLabelText<HTMLInputElement>(/to confirm/iu).value).toBe("");
		expect(screen.getByLabelText<HTMLTextAreaElement>(/reason/iu).value).toBe("");
	});
});
