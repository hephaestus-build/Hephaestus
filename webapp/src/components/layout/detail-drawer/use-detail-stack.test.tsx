import { act, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { describe, expect, it } from "vitest";

import { renderWithRouter } from "@/test/router-harness";

import { focusSwitchHistory, isFocusSwitchStep } from "@/components/practice-focus/practice-focus";

import { stackInSearch } from "./detail-stack";
import {
	type DetailStackControls,
	type DetailStackOptions,
	useDetailStack,
} from "./use-detail-stack";

const GROUP = { kind: "group", id: "a" };
const PRACTICE = { kind: "practice", id: "p" };

function Probe({
	options,
	onControls,
}: {
	options?: DetailStackOptions;
	onControls: (controls: DetailStackControls) => void;
}) {
	const controls = useDetailStack(options);
	useEffect(() => onControls(controls));
	return null;
}

async function setUp(options?: DetailStackOptions) {
	let latest: DetailStackControls | undefined;
	const { router } = await renderWithRouter(
		<Probe
			options={options}
			onControls={(controls) => {
				latest = controls;
			}}
		/>,
		"/",
	);
	const controls = (): DetailStackControls => {
		if (latest === undefined) {
			throw new Error("The probe has rendered");
		}
		return latest;
	};
	const stack = () => {
		const search: unknown = router.state.location.search;
		return stackInSearch(
			typeof search === "object" && search !== null && "detail" in search
				? search.detail
				: undefined,
		);
	};
	const index = () => router.history.location.state.__TSR_index;
	return { router, controls, stack, index };
}

/** The switch's own step, as `usePracticeFocusSwitch` writes it over the page it left. */
async function switchedTo(options: DetailStackOptions) {
	const setup = await setUp(options);
	const before = setup.index();
	await act(async () =>
		setup.router.navigate({
			to: ".",
			search: { detail: ["group:a"] },
			...focusSwitchHistory({}),
		}),
	);
	return { ...setup, before, step: setup.index() };
}

describe("useDetailStack", () => {
	it("closes pushed levels by going back, so Back afterwards opens none of them again", async () => {
		const { controls, stack, index } = await setUp();
		const start = index();
		await act(async () => controls().push([GROUP, PRACTICE]));
		expect(stack()).toStrictEqual(["group:a", "practice:p"]);

		act(() => controls().close(0));
		await waitFor(() => expect(stack()).toStrictEqual([]));
		// Back to the entry the page started on, not a new entry over the two pushed ones.
		expect(index()).toBe(start);
	});

	it("closes against the stack in the address, not the one of the render that made it", async () => {
		const { router, controls, stack } = await setUp();
		await act(async () => controls().push([GROUP, PRACTICE]));
		const stale = controls();
		// The reader dismisses the practice while a request of that level is still running.
		act(() => router.history.back());
		await waitFor(() => expect(stack()).toStrictEqual(["group:a"]));

		// The request ends and closes its level: the group the reader is still in stays open.
		await act(async () => {
			stale.close(1);
			// oxlint-disable-next-line no-restricted-globals, promise/avoid-new -- proves that the late close leaves the open level alone
			await new Promise<void>((resolve) => setTimeout(resolve, 50));
		});
		expect(stack()).toStrictEqual(["group:a"]);
	});

	it("does not count a level already open as a push", async () => {
		const { router, controls, stack } = await setUp();
		await act(async () => controls().push([GROUP]));
		await act(async () => controls().push([GROUP, PRACTICE]));
		expect(stack()).toStrictEqual(["group:a", "practice:p"]);
		expect(router.state.location.state).toMatchObject({ detailPush: true, detailPushes: 2 });
	});

	describe("after a switch from the other page opened a group", () => {
		it("closes a level over it and the group by going back, then rewriting the step", async () => {
			const { router, controls, stack, index, before, step } = await switchedTo({
				replaceOnClose: isFocusSwitchStep,
			});
			await act(async () => controls().push([PRACTICE]));
			expect(stack()).toStrictEqual(["group:a", "practice:p"]);

			// A press outside closes both levels, one of them pushed by this visit.
			act(() => controls().close(0));
			await waitFor(() => expect(stack()).toStrictEqual([]));
			expect(index()).toBe(step);

			// Back leaves for the page the switch came from instead of opening the levels again.
			act(() => router.history.back());
			await waitFor(() => expect(index()).toBe(before));
			expect(stack()).toStrictEqual([]);
		});

		it("still closes the group alone by rewriting the step", async () => {
			const { controls, stack, index, step } = await switchedTo({
				replaceOnClose: isFocusSwitchStep,
			});
			act(() => controls().close(0));
			await waitFor(() => expect(stack()).toStrictEqual([]));
			expect(index()).toBe(step);
		});

		it("writes nothing when a blocker keeps the reader on the level", async () => {
			const { router, controls, stack, index } = await switchedTo({
				replaceOnClose: isFocusSwitchStep,
			});
			await act(async () => controls().push([PRACTICE]));
			const before = { stack: stack(), index: index() };
			// As the browser history does on a refused back step: it stays on the entry and still
			// tells the router, which resolves the same location again.
			router.history.go = () => router.history.notify({ type: "GO", index: -1 });

			await act(async () => {
				controls().close(0);
				// oxlint-disable-next-line no-restricted-globals, promise/avoid-new -- proves that the close does not change the stack
				await new Promise<void>((resolve) => setTimeout(resolve, 50));
			});
			expect(stack()).toStrictEqual(before.stack);
			expect(index()).toBe(before.index);
		});

		it("adds an entry for the rest where the page does not rewrite the step", async () => {
			const { controls, stack, index, step } = await switchedTo({});
			await act(async () => controls().push([PRACTICE]));
			act(() => controls().close(0));
			await waitFor(() => expect(stack()).toStrictEqual([]));
			// Back over the pushed level first, so only the group's close is a new entry.
			expect(index()).toBe(step + 1);
		});
	});
});
