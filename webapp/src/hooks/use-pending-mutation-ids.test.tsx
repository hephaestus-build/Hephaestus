import {
	QueryClient,
	QueryClientProvider,
	type UseMutationOptions,
	useMutation,
} from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { deferred, pending } from "@/test/async";

import { filedUnder, pathNumber, usePendingMutationIds } from "./use-pending-mutation-ids";

/** The shape every generated mutation takes: the path parameters the endpoint is keyed on. */
interface Vars {
	path: { id: number };
}

const KEY = ["thing"];

function generatedMutation(): UseMutationOptions<void, Error, Vars> {
	return {
		mutationKey: ["generated", "its", "own", "key"],
		mutationFn: async (_variables: Vars) => pending<undefined>(),
	};
}

function Harness({ releaseFast }: { releaseFast: Promise<void> }) {
	const slow = useMutation({
		mutationKey: [...KEY, "slow"],
		mutationFn: async (_variables: Vars) => pending<undefined>(),
	});
	const fast = useMutation({
		mutationKey: [...KEY, "fast"],
		mutationFn: async (_variables: Vars) => releaseFast,
	});
	const generated = useMutation({
		...filedUnder([...KEY, "generated"], generatedMutation()),
	});
	const foreign = useMutation({
		mutationKey: [...KEY, "foreign"],
		mutationFn: async (_variables: { body: { note: string } }) => pending<undefined>(),
	});
	const pendingIds = usePendingMutationIds(KEY, (variables) => pathNumber(variables, "id"));

	return (
		<>
			<button type="button" onClick={() => slow.mutate({ path: { id: 1 } })}>
				start slow
			</button>
			<button type="button" onClick={() => fast.mutate({ path: { id: 2 } })}>
				start fast
			</button>
			<button type="button" onClick={() => generated.mutate({ path: { id: 3 } })}>
				start generated
			</button>
			<button type="button" onClick={() => foreign.mutate({ body: { note: "hi" } })}>
				start foreign
			</button>
			<output>{[...pendingIds].sort((a, b) => a - b).join(",")}</output>
		</>
	);
}

function renderHarness() {
	const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
	const fast = deferred();
	render(
		<QueryClientProvider client={client}>
			<Harness releaseFast={fast.promise} />
		</QueryClientProvider>,
	);
	return () => {
		fast.resolve();
	};
}

const pendingIds = () => screen.getByRole("status").textContent;
const click = (name: string) => fireEvent.click(screen.getByRole("button", { name }));

describe("usePendingMutationIds", () => {
	it("keeps reporting a call that is still running after a sibling settles", async () => {
		const releaseFast = renderHarness();

		click("start slow");
		await waitFor(() => expect(pendingIds()).toBe("1"));

		click("start fast");
		await waitFor(() => expect(pendingIds()).toBe("1,2"));

		releaseFast();
		await waitFor(() => expect(pendingIds()).toBe("1"));
	});

	it("ignores a call filed under the same key whose variables carry no such id", async () => {
		renderHarness();

		click("start foreign");
		click("start slow");

		await waitFor(() => expect(pendingIds()).toBe("1"));
	});

	it("still finds a call whose generated helper brought a mutation key of its own", async () => {
		renderHarness();

		click("start generated");

		await waitFor(() => expect(pendingIds()).toBe("3"));
	});
});
