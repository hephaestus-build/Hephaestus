import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ProviderResourceRepair } from "@/api/types.gen";
import { ProviderResourceRepairs } from "./ProviderResourceRepairs";

const repair: ProviderResourceRepair = {
	id: "unresolved",
	identityHash: "a".repeat(64),
	kind: "OUTLINE",
	origin: "https://outline.example.test",
	resourceType: "SUBSCRIPTION",
	parentId: "",
	resourceId: "old-subscription",
	state: "AWAITING_CONFIRMATION",
	replacementInUse: false,
	createdAt: new Date("2026-11-13T00:00:00Z"),
	owners: [],
};

describe("provider removal confirmation", () => {
	it("requires an explicit provider check before a release", async () => {
		const user = userEvent.setup();
		const onRelease = vi.fn(async () => undefined);
		render(
			<ProviderResourceRepairs
				state={{ status: "ready", items: [{ repair, releaseAvailable: true }] }}
				onRelease={onRelease}
			/>,
		);
		await user.click(screen.getByRole("button", { name: "Confirm removal" }));
		const dialog = screen.getByRole("dialog");
		expect(
			within(dialog).getByRole<HTMLButtonElement>("button", { name: "Release block" }).disabled,
		).toBe(true);
		await user.click(within(dialog).getByRole("checkbox"));
		await user.click(within(dialog).getByRole("button", { name: "Release block" }));
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	});

	it("keeps the confirmation open when release is refused", async () => {
		const user = userEvent.setup();
		render(
			<ProviderResourceRepairs
				state={{ status: "ready", items: [{ repair, releaseAvailable: true }] }}
				onRelease={async () => {
					throw new Error("The identity changed.");
				}}
			/>,
		);
		await user.click(screen.getByRole("button", { name: "Confirm removal" }));
		await user.click(screen.getByRole("checkbox"));
		await user.click(screen.getByRole("button", { name: "Release block" }));
		await screen.findByText("We could not release this block");
		expect(screen.getByRole("dialog")).not.toBeNull();
	});

	it("removes an open action when the caller no longer has permission", async () => {
		const user = userEvent.setup();
		const onRelease = vi.fn(async () => undefined);
		const view = render(
			<ProviderResourceRepairs
				state={{ status: "ready", items: [{ repair, releaseAvailable: true }] }}
				onRelease={onRelease}
			/>,
		);
		await user.click(screen.getByRole("button", { name: "Confirm removal" }));
		view.rerender(
			<ProviderResourceRepairs
				state={{ status: "ready", items: [{ repair, releaseAvailable: false }] }}
				onRelease={onRelease}
			/>,
		);
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
		expect(screen.queryByRole("button", { name: "Confirm removal" })).toBeNull();
	});
});
