import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { expect, it } from "vitest";

import type { OutlineCollectionCandidate } from "@/api/types.gen";
import { deferred } from "@/test/async";

import { AddCollectionDialog } from "./AddCollectionDialog";

const product = {
	collectionId: "product",
	name: "Product",
	alreadyMirrored: false,
} satisfies OutlineCollectionCandidate;

function Harness({ onRegister }: { onRegister: () => Promise<void> }) {
	const [open, setOpen] = useState(true);
	return (
		<AddCollectionDialog
			candidates={{ items: [product], isLoading: false, onRetry: () => undefined }}
			open={open}
			onOpenChange={setOpen}
			onRegister={onRegister}
		/>
	);
}

async function beginRegistration() {
	const user = userEvent.setup();
	const registration = deferred();
	const { unmount } = render(<Harness onRegister={async () => registration.promise} />);
	await user.click(await screen.findByRole("option", { name: /Product/u }));
	await user.click(screen.getByRole("button", { name: "Add 1 collection" }));
	await screen.findByRole("button", { name: "Adding…" });
	return {
		registration,
		user,
		cleanup: unmount,
	};
}

it("keeps the pending batch open after Escape and closes when registration completes", async () => {
	const { registration, user, cleanup } = await beginRegistration();
	try {
		await user.keyboard("{Escape}");
		expect(screen.getByRole("dialog").textContent).toContain("Adding 1 of 1");
		expect(screen.queryByRole("button", { name: "Close" })).toBeNull();
		expect(screen.getByRole("button", { name: "Cancel" }).hasAttribute("disabled")).toBe(true);
		registration.resolve();
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	} finally {
		registration.resolve();
		cleanup();
	}
});

it("allows dismissal again after registration fails", async () => {
	const { registration, user, cleanup } = await beginRegistration();
	try {
		registration.reject({ detail: "Registration failed" });
		await screen.findByText("Registration failed");
		expect(screen.getByRole("button", { name: "Close" }).hasAttribute("disabled")).toBe(false);
		await user.keyboard("{Escape}");
		await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
	} finally {
		cleanup();
	}
});
