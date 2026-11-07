import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { chromium } from "playwright";
import { build } from "vite-plus";

const ROOT = path.resolve(import.meta.dirname, "..");
const REPORT = path.join(ROOT, "tmp/extension-provider-slots.json");

type Provider = "GITHUB" | "GITLAB";

export interface SlotCase {
	provider: Provider;
	layout: string;
	url: string;
}

// Public, fixed work pages keep the probe independent of sign-in and new repository activity.
export const SLOT_CASES: readonly SlotCase[] = [
	{
		provider: "GITHUB",
		layout: "github-pull-request",
		url: "https://github.com/cli/cli/pull/14625",
	},
	{ provider: "GITHUB", layout: "github-diff", url: "https://github.com/cli/cli/pull/14625/files" },
	{ provider: "GITHUB", layout: "github-issue", url: "https://github.com/cli/cli/issues/11404" },
	{
		provider: "GITLAB",
		layout: "gitlab-merge-request",
		url: "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/252562",
	},
	{
		provider: "GITLAB",
		layout: "gitlab-diff",
		url: "https://gitlab.com/gitlab-org/gitlab/-/merge_requests/252562/diffs",
	},
	{
		provider: "GITLAB",
		layout: "gitlab-work-item",
		url: "https://gitlab.com/gitlab-org/gitlab/-/issues/233836",
	},
];

declare global {
	interface Window {
		HephaestusProviderSlots: {
			findReportSlot: (root: ParentNode, provider: Provider) => { layout: string } | undefined;
		};
	}
}

/** Bundle the actual adapter so the canary cannot keep passing on a second copy of its selectors. */
export async function providerSlotBundle(): Promise<string> {
	const result = await build({
		configFile: false,
		logLevel: "error",
		resolve: { alias: { "~": path.join(ROOT, "extension/src") } },
		build: {
			write: false,
			lib: {
				entry: path.join(ROOT, "extension/src/content/anchors.ts"),
				name: "HephaestusProviderSlots",
				formats: ["iife"],
			},
		},
	});
	const outputs = (Array.isArray(result) ? result : [result]).flatMap((item) => {
		if (!("output" in item)) {
			throw new Error("The provider slot probe did not produce a bundle");
		}
		return item.output;
	});
	const chunks = outputs.filter((item) => item.type === "chunk");
	const [chunk] = chunks;
	if (chunks.length !== 1 || chunk === undefined) {
		throw new Error("The provider slot probe must be a single script");
	}
	return chunk.code;
}

/** This function runs in the provider page, after the bundled adapter has loaded. */
function slotMatches({ provider, layout }: Pick<SlotCase, "provider" | "layout">): boolean {
	return window.HephaestusProviderSlots.findReportSlot(document, provider)?.layout === layout;
}

export async function checkProviderSlots(cases: readonly SlotCase[] = SLOT_CASES) {
	const script = await providerSlotBundle();
	const browser = await chromium.launch();
	const results: { url: string; layout: string; status: "passed" | "failed"; detail?: string }[] =
		[];
	try {
		const context = await browser.newContext();
		await context.addInitScript({
			content: `${script}\nwindow.HephaestusProviderSlots = HephaestusProviderSlots;`,
		});
		for (const entry of cases) {
			const page = await context.newPage();
			try {
				const response = await page.goto(entry.url, {
					waitUntil: "domcontentloaded",
					timeout: 30_000,
				});
				if (response?.ok() !== true) {
					throw new Error(`Page unavailable: HTTP ${response?.status() ?? "no response"}`);
				}
				await page.waitForFunction(slotMatches, entry, { timeout: 30_000 });
				results.push({ url: entry.url, layout: entry.layout, status: "passed" });
				console.log(`PASS ${entry.layout}: ${entry.url}`);
			} catch (error) {
				const detail = error instanceof Error ? error.message : String(error);
				results.push({ url: entry.url, layout: entry.layout, status: "failed", detail });
				console.error(`FAIL ${entry.layout}: ${detail}`);
			} finally {
				await page.close();
			}
		}
	} finally {
		await browser.close();
	}
	return results;
}

if (import.meta.main) {
	const results = await checkProviderSlots();
	await mkdir(path.dirname(REPORT), { recursive: true });
	await writeFile(
		REPORT,
		`${JSON.stringify({ checkedAt: new Date().toISOString(), results }, null, 2)}\n`,
	);
	if (results.some((result) => result.status === "failed")) {
		process.exitCode = 1;
	}
}
