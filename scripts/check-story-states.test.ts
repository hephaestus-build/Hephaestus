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

await test("a story covers the state its args set, whatever its name", async () => {
	const component = { [`${COMPONENTS}/Panel.tsx`]: PANEL };
	const stories = (body: string) => ({ [`${COMPONENTS}/Panel.stories.tsx`]: body });
	assert.deepEqual(
		await missingOf({
			...component,
			...stories(`
export const WhileThePracticesLoad = { args: { state: { status: "loading" } } };
export const Unavailable = { args: { state: { status: "error", error: new Error("x") } } };
export const NothingYet = { args: { state: { status: "empty" } } };
`),
		}),
		{},
	);
	// A name alone covers nothing: the story must set the state.
	assert.deepEqual(
		await missingOf({
			...component,
			...stories(`
export const Loading = { args: { state: { status: "ready" } } };
export const LoadFailed = {};
export const Empty = { args: { state: { status: "empty" } } };
`),
		}),
		{ [`${COMPONENTS}/Panel.tsx`]: ["loading", "error"] },
	);
});

await test("a story's spreads, module constants and rendered props count", async () => {
	const missing = await missingOf({
		[`${COMPONENTS}/Panel.tsx`]: PANEL,
		[`${COMPONENTS}/Panel.stories.tsx`]: `
const LOADING_ARGS = { state: { status: "loading" } };
const Failed = { args: { state: { status: "error" } } };
export const Busy = { args: LOADING_ARGS };
export const FailedDark = { ...Failed, globals: { theme: "dark" } };
export const Empty = { render: () => <Panel state={{ status: "empty" }} /> };
`,
	});
	assert.deepEqual(missing, {});
});

await test("a state that the props exclude or do not extract needs no story", async () => {
	const missing = await missingOf({
		[`${COMPONENTS}/Region.tsx`]: `
import type { PanelState } from "@/lib/panel-state";
export type RegionState = Exclude<PanelState, { status: "error" | "empty" }>;
export function Region({ state }: { state: RegionState }) { return null; }
`,
		[`${COMPONENTS}/Region.stories.tsx`]:
			'export const Pending = { args: { state: { status: "loading" } } };',
		[`${COMPONENTS}/Picked.tsx`]: `
import type { PanelState } from "@/lib/panel-state";
export function Picked({ state }: { state: Extract<PanelState, { status: "error" | "ready" }> }) { return null; }
`,
		[`${COMPONENTS}/Picked.stories.tsx`]:
			'export const Refused = { args: { state: { status: "error" } } };',
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
		[`${COMPONENTS}/Table.stories.tsx`]: `
const meta = { args: { isLoading: false, error: undefined } };
export default meta;
export const Busy = { render: () => <Table isLoading /> };
export const Settled = { args: { error: null } };
`,
	});
	// `false`, `null` and `undefined` set no state.
	assert.deepEqual(missing, { [`${COMPONENTS}/Table.tsx`]: ["error"] });
});

await test("a prop that ends in Error is an error state, and a story that sets it covers it", async () => {
	const dialog = {
		[`${COMPONENTS}/AmountDialog.tsx`]: `
export interface AmountDialogProps { isPending: boolean; serverError?: string | null; }
export function AmountDialog(props: AmountDialogProps) { return null; }
`,
	};
	assert.deepEqual(await missingOf(dialog), { [`${COMPONENTS}/AmountDialog.tsx`]: ["error"] });
	assert.deepEqual(
		await missingOf({
			...dialog,
			[`${COMPONENTS}/AmountDialog.stories.tsx`]:
				'export const ServerRejection = { args: { serverError: "Too large." } };',
		}),
		{},
	);
});
