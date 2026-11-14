import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { measure } from "./check-story-states.ts";

const COMPONENTS = "webapp/src/components";

async function tree(files: Record<string, string>): Promise<string> {
	const root = await mkdtemp(path.join(tmpdir(), "story-states-"));
	for (const [file, source] of Object.entries(files)) {
		await mkdir(path.dirname(path.join(root, file)), { recursive: true });
		await writeFile(path.join(root, file), source);
	}
	return root;
}

const PANEL_STATE = `
export type PanelState =
	| { status: "loading" }
	| { status: "error"; error: unknown; onRetry: () => void }
	| { status: "empty" }
	| { status: "ready" };
`;
const PANEL = `
import type { PanelState } from "@/lib/panel-state";
export function Panel({ state }: { state: PanelState }) { return null; }
`;

const missingOf = async (files: Record<string, string>) => {
	const root = await tree({ "webapp/src/lib/panel-state.ts": PANEL_STATE, ...files });
	try {
		return Object.fromEntries((await measure(root)).missing);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
};

await test("a state read through an imported type needs a story for each state", async () => {
	assert.deepEqual(await missingOf({ [`${COMPONENTS}/Panel.tsx`]: PANEL }), {
		[`${COMPONENTS}/Panel.tsx`]: ["loading", "error", "empty"],
	});
});

await test("story names cover a state by their words, not by a substring", async () => {
	const component = { [`${COMPONENTS}/Panel.tsx`]: PANEL };
	const stories = (names: string[]) => ({
		[`${COMPONENTS}/Panel.stories.tsx`]: names
			.map((name) => `export const ${name} = {};`)
			.join("\n"),
	});
	assert.deepEqual(
		await missingOf({ ...component, ...stories(["Loading", "LoadFailed", "Empty"]) }),
		{},
	);
	assert.deepEqual(
		await missingOf({
			...component,
			...stories(["WhileThePracticesLoad", "Unavailable", "EmptyWithFilter"]),
		}),
		{},
	);
	// A failed load and a load of more rows are not the first load.
	assert.deepEqual(
		await missingOf({ ...component, ...stories(["LoadFailed", "LoadingMore", "Empty"]) }),
		{ [`${COMPONENTS}/Panel.tsx`]: ["loading"] },
	);
	assert.deepEqual(await missingOf({ ...component, ...stories(["Loading", "Empty"]) }), {
		[`${COMPONENTS}/Panel.tsx`]: ["error"],
	});
});

await test("a state that the props exclude needs no story", async () => {
	const missing = await missingOf({
		[`${COMPONENTS}/Region.tsx`]: `
import type { PanelState } from "@/lib/panel-state";
export type RegionState = Exclude<PanelState, { status: "error" | "empty" }>;
export function Region({ state }: { state: RegionState }) { return null; }
`,
		[`${COMPONENTS}/Region.stories.tsx`]: "export const Loading = {};",
	});
	assert.deepEqual(missing, {});
});

await test("a component with no loading, error or empty signal is not a data region", async () => {
	const missing = await missingOf({
		// A submit in flight is not a region that loads.
		[`${COMPONENTS}/Form.tsx`]:
			"export function Form({ isPending }: { isPending: boolean }) { return null; }",
		// A nested domain object does not add a signal; neither does a lowercase helper.
		[`${COMPONENTS}/Card.tsx`]: `
interface Row { error: string; status: "error" }
export function Card({ row }: { row: Row }) { return null; }
export function helper({ isLoading }: { isLoading: boolean }) { return null; }
`,
		// A vendored primitive is not ours to cover.
		[`${COMPONENTS}/ui/Spinner.tsx`]:
			"export function Spinner({ loading }: { loading: boolean }) { return null; }",
	});
	assert.deepEqual(missing, {});
});

await test("props named isLoading and error each need their own story", async () => {
	const missing = await missingOf({
		[`${COMPONENTS}/Table.tsx`]:
			"export const Table = ({ isLoading, error }: { isLoading?: boolean; error?: unknown }) => null;",
		[`${COMPONENTS}/Table.stories.tsx`]: "export const Loading = {};",
	});
	assert.deepEqual(missing, { [`${COMPONENTS}/Table.tsx`]: ["error"] });
});
