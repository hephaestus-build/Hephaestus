import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { expect } from "@playwright/test";
import { chromium, type Browser } from "playwright";
import { build, preview } from "vite";

import { appSourcePlugins } from "./vite.shared.ts";

const fixtureRoot = fileURLToPath(new URL("tools/code-editor-runtime", import.meta.url));
const sourceRoot = fileURLToPath(new URL("src", import.meta.url));
const headers = await readFile(new URL("docker/security-headers.conf", import.meta.url), "utf8");
// nginx fills the API origin at runtime; this check serves the app from one origin.
const policy = /Content-Security-Policy "(?<policy>[^"]+)"/u
	.exec(headers)
	?.groups?.policy?.replace(" $hephaestus_api_origin", "");
assert.ok(
	policy !== undefined && policy.length > 0,
	"The shipped content security policy is missing",
);
const output = await mkdtemp(path.join(tmpdir(), "hephaestus-editor-check-"));
try {
	await build({
		configFile: false,
		root: fixtureRoot,
		plugins: appSourcePlugins(),
		resolve: { alias: { "@": sourceRoot } },
		build: { outDir: output, emptyOutDir: true },
	});
	const server = await preview({
		configFile: false,
		root: fixtureRoot,
		build: { outDir: output },
		preview: { host: "127.0.0.1", port: 0, headers: { "Content-Security-Policy": policy } },
	});
	try {
		const url = server.resolvedUrls?.local[0];
		assert.ok(url !== undefined && url.length > 0, "The runtime check server has no local URL");
		const browser = await chromium.launch({ headless: true });
		try {
			for (const fault of [false, true]) {
				await checkEditor(browser, url, fault);
			}
		} finally {
			await browser.close();
		}
	} finally {
		await server.close();
	}
} finally {
	await rm(output, { recursive: true, force: true });
}
process.stdout.write(
	"Code editor runtime: CSP, workers, syntax-only markers, typing, read-only, draft-preserving retry and model ownership passed.\n",
);

async function checkEditor(browser: Browser, url: string, fault: boolean) {
	const { origin } = new URL(url);
	const context = await browser.newContext();
	try {
		await context.addInitScript(() => {
			document.addEventListener("securitypolicyviolation", (event) => {
				document.documentElement.dataset.codeEditorCspViolation = event.violatedDirective;
			});
		});
		const page = await context.newPage();
		const pageErrors: string[] = [];
		const consoleErrors: string[] = [];
		page.on("console", (message) => {
			if (
				message.type() === "error" &&
				!message.text().includes("Synthetic editor construction refusal")
			) {
				consoleErrors.push(message.text());
			}
		});
		page.on("pageerror", (error) => pageErrors.push(String(error)));
		const externalRequests: string[] = [];
		page.on("request", (request) => {
			if (!request.url().startsWith(origin) && !request.url().startsWith(`blob:${origin}`)) {
				externalRequests.push(request.url());
			}
		});
		await page.goto(url);
		await page.getByRole("textbox", { name: "Name", exact: true }).fill("Keep this edited name");
		if (fault) {
			await page.getByRole("button", { name: "Fail next editor construction" }).click();
		}
		await page.getByRole("button", { name: "Open editor" }).click();
		if (fault) {
			await expect(page.getByRole("alert")).toContainText("The editor could not load");
			await page.getByRole("button", { name: "Count models" }).click();
			await expect(page.getByLabel("Model count")).toHaveText("1");
			await expect(page.getByLabel("Other model")).toHaveText("present");
			await page.getByRole("button", { name: "Retry", exact: true }).click();
		}
		const editor = page.getByRole("textbox", { name: "Practice script", exact: true });
		await expect(editor).toBeVisible();
		await expect(page.getByRole("textbox", { name: "Name", exact: true })).toHaveValue(
			"Keep this edited name",
		);
		await expect(page.getByLabel("Script draft")).toHaveText("export const answer: string = 42;");
		await page.getByRole("button", { name: "Check TypeScript worker" }).click();
		await expect(page.getByLabel("TypeScript diagnostics")).toHaveText("1");
		await editor.press("ControlOrMeta+A");
		await editor.pressSequentially("export const answer = 42;");
		await expect(page.getByLabel("Script draft")).toHaveText("export const answer = 42;");
		await page.getByRole("checkbox", { name: "Read only" }).check();
		await editor.press("ControlOrMeta+A");
		await editor.pressSequentially("this must not replace the draft");
		await expect(page.getByLabel("Script draft")).toHaveText("export const answer = 42;");
		await page.getByRole("button", { name: "Reset controlled script" }).click();
		await expect(page.getByLabel("Script draft")).toHaveText(
			"export const reset: number = 'wrong';",
		);
		await page.getByRole("button", { name: "Check TypeScript worker" }).click();
		await expect(page.getByLabel("TypeScript diagnostics")).toHaveText("1");
		await page.getByRole("checkbox", { name: "Read only" }).uncheck();
		await editor.press("ControlOrMeta+A");
		await editor.pressSequentially("export const restored = true;");
		await expect(page.getByLabel("Script draft")).toHaveText("export const restored = true;");
		// Imports are not loaded beside the script, so a syntax error is marked and an unresolved import is not.
		const markers = async () => {
			await page.getByRole("button", { name: "Count markers" }).click();
			return page.getByLabel("Editor markers").textContent();
		};
		await page.getByRole("button", { name: "Use a syntax error" }).click();
		await expect.poll(markers).not.toMatch(/^0?$/u);
		await page.getByRole("button", { name: "Use an unresolved import" }).click();
		await expect.poll(markers).toBe("0");
		await page.getByRole("button", { name: "Count models" }).click();
		await expect(page.getByLabel("Model count")).toHaveText(fault ? "2" : "1");
		await page.getByRole("button", { name: "Hide editor" }).click();
		await page.getByRole("button", { name: "Count models" }).click();
		await expect(page.getByLabel("Model count")).toHaveText(fault ? "1" : "0");
		await expect(page.getByLabel("Other model")).toHaveText(fault ? "present" : "absent");
		await expect(page.locator("html")).not.toHaveAttribute("data-code-editor-csp-violation");
		assert.deepEqual(pageErrors, []);
		assert.deepEqual(consoleErrors, []);
		assert.deepEqual(externalRequests, []);
	} finally {
		await context.close();
	}
}
