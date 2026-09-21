import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { useState } from "react";
import { expect, it } from "vitest";

import type { OutlineCollectionCandidate } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";

import { AddCollectionDialog } from "./AddCollectionDialog";

function Harness({ onRegister }: { onRegister: () => Promise<void> }) {
	const [open, setOpen] = useState(true);
	return (
		<AddCollectionDialog
			workspaceSlug="acme"
			open={open}
			onOpenChange={setOpen}
			onRegister={onRegister}
		/>
	);
}

async function beginRegistration() {
	const user = userEvent.setup();
	server.use(
		http.get("*/workspaces/acme/outline/collections/candidates", () =>
			HttpResponse.json([
				{
					collectionId: "product",
					name: "Product",
					alreadyMirrored: false,
				} satisfies OutlineCollectionCandidate,
			]),
		),
	);
	const registration = deferred();
	const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	const { unmount } = render(
		<QueryClientProvider client={client}>
			<Harness onRegister={async () => registration.promise} />
		</QueryClientProvider>,
	);
	await user.click(await screen.findByRole("option", { name: /Product/u }));
	await user.click(screen.getByRole("button", { name: "Add 1 collection" }));
	await screen.findByRole("button", { name: "Adding…" });
	return {
		registration,
		user,
		cleanup: () => {
			unmount();
			client.clear();
		},
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
