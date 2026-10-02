import { type ChildProcess, spawn } from "node:child_process";
import { once } from "node:events";
import { rmSync } from "node:fs";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

import { type Browser, chromium, type Page } from "playwright";

const { values } = parseArgs({ options: { only: { type: "string" } } });
if (values.only !== undefined && values.only !== "extension") {
	throw new Error("Usage: export:assets [--only extension]");
}
const extensionOnly = values.only === "extension";

const webappDirectory = path.resolve(import.meta.dirname, "..");
const repositoryDirectory = path.resolve(webappDirectory, "..");
const sourceDirectory = path.resolve(webappDirectory, "brand");
const extensionIconDirectory = path.resolve(webappDirectory, "../extension/public/icon");
const publicDirectory = path.resolve(webappDirectory, "public");
const docsImageDirectory = path.resolve(webappDirectory, "../docs/static/img");
const docsBrandDirectory = path.resolve(docsImageDirectory, "brand");
const readmeImageDirectory = path.resolve(webappDirectory, "../docs/images/readme");
const proxyComposePath = path.resolve(webappDirectory, "../docker/compose.proxy.yaml");
const markSvg = await readFile(path.resolve(sourceDirectory, "hephaestus-mark.svg"), "utf8");
const applicationMarkSvg = markSvg.replace(
	'transform="translate(24 20) scale(3.3333)"',
	'transform="translate(14.8 12) scale(4.1)"',
);
if (applicationMarkSvg === markSvg) {
	throw new Error("The application icon crop could not be applied.");
}
const interFont = await readFile(
	new URL(import.meta.resolve("@fontsource-variable/inter/files/inter-latin-wght-normal.woff2")),
);
const FONT_FACE = `@font-face{font-family:Inter;font-style:normal;font-weight:100 900;src:url(data:font/woff2;base64,${interFont.toString("base64")}) format('woff2')}`;
const SIGNAL_BLUE = "#315FDC";
const DARK_ACCENT = "#8EAEFF";
const STORYBOOK_STARTUP_MS = 120_000;

interface ReadmeCapture {
	name: string;
	storyId: string;
	selector: string;
	viewportWidth: number;
	expectedWidth: number;
}

const readmeCaptures: ReadmeCapture[] = [
	{
		name: "landing-hero",
		storyId: "site-landing-landingherosection--readme-export",
		selector: '[data-readme-export="landing-hero"]',
		viewportWidth: 1440,
		expectedWidth: 1280,
	},
	{
		name: "feedback-scene",
		storyId: "site-landing-landingherosection--scene-export",
		selector: '[data-readme-export="feedback-scene"]',
		viewportWidth: 1024,
		expectedWidth: 896,
	},
];

/**
 * Every output renders into a private staging directory first, and reaches the working tree only once
 * all of them rendered: a failed run leaves the committed assets exactly as they were.
 */
const stagingDirectory = await mkdtemp(path.join(tmpdir(), "hephaestus-assets-"));
// `exit` handlers run however the run ends — done, thrown, or stopped by a signal once the signal is
// turned into an exit — so the staging directory and this run's Storybook never outlive it.
process.on("exit", () => {
	rmSync(stagingDirectory, { recursive: true, force: true });
});
for (const [signal, code] of [
	["SIGINT", 130],
	["SIGTERM", 143],
] as const) {
	process.once(signal, () => process.exit(code));
}
/** Final path → where this run rendered it. */
const outputs = new Map<string, string>();

/** Where to render `target` now; it is copied to `target` when the whole export succeeded. */
async function output(target: string): Promise<string> {
	const staged = path.join(stagingDirectory, path.relative(repositoryDirectory, target));
	await mkdir(path.dirname(staged), { recursive: true });
	outputs.set(target, staged);
	return staged;
}

await render();
// Generated directories are replaced whole, so a file this run no longer produces does not linger.
if (!extensionOnly) {
	await rm(docsBrandDirectory, { recursive: true, force: true });
	await rm(readmeImageDirectory, { recursive: true, force: true });
}
for (const [target, staged] of outputs) {
	await mkdir(path.dirname(target), { recursive: true });
	await copyFile(staged, target);
}

async function render(): Promise<void> {
	if (!extensionOnly) {
		await copyFile(
			path.resolve(sourceDirectory, "hephaestus-mark.svg"),
			await output(path.resolve(publicDirectory, "brand/hephaestus-mark.svg")),
		);

		const proxyCompose = await readFile(proxyComposePath, "utf8");
		const proxyMark = markSvg
			.replace(
				'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" role="img" aria-labelledby="title"><title id="title">Hephaestus</title>',
				'<svg class="brand" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" role="img" aria-label="Hephaestus">',
			)
			.trim();
		const nextProxyCompose = proxyCompose.replace(
			/<svg class="brand" xmlns="http:\/\/www\.w3\.org\/2000\/svg".*<\/svg>/u,
			proxyMark,
		);
		if (nextProxyCompose === proxyCompose && !proxyCompose.includes(proxyMark)) {
			throw new Error("The maintenance-page brand mark could not be updated.");
		}
		await writeFile(await output(proxyComposePath), nextProxyCompose);
	}

	const browser = await chromium.launch();
	try {
		const page = await browser.newPage();

		await exportExtensionAssets(page);
		if (!extensionOnly) {
			const captureMark = async (
				target: string,
				size: number,
				opaque = false,
				keepInsideSafeZone = false,
				svg = markSvg,
			): Promise<void> => {
				await page.setViewportSize({ width: size, height: size });
				await page.setContent(
					`<style>*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;background:${opaque ? SIGNAL_BLUE : "transparent"};display:grid;place-items:center}svg{display:block;width:${keepInsideSafeZone ? "80%" : "100%"};height:${keepInsideSafeZone ? "80%" : "100%"}}</style>${svg}`,
				);
				await page.screenshot({ path: await output(target), omitBackground: !opaque });
			};
			const captureApplicationMark = async (target: string, size: number): Promise<void> =>
				captureMark(target, size, true, false, applicationMarkSvg);

			await captureMark(path.resolve(publicDirectory, "favicon.png"), 64);
			await captureMark(path.resolve(publicDirectory, "apple-touch-icon.png"), 180, true);
			await captureMark(path.resolve(publicDirectory, "icon-192.png"), 192, true);
			await captureMark(path.resolve(publicDirectory, "icon-512.png"), 512, true);
			await captureMark(path.resolve(publicDirectory, "icon-maskable-512.png"), 512, true, true);
			await captureApplicationMark(path.resolve(docsBrandDirectory, "slack-app-icon-512.png"), 512);
			await captureApplicationMark(
				path.resolve(docsBrandDirectory, "external-app-icon-1024.png"),
				1024,
			);
			await captureMark(path.resolve(docsImageDirectory, "favicon.png"), 64);

			const captureLockup = async (target: string, dark: boolean): Promise<void> => {
				await page.setViewportSize({ width: 1240, height: 256 });
				await page.setContent(
					`<style>${FONT_FACE}html,body{margin:0;width:100%;height:100%;font-family:Inter,sans-serif;background:transparent;color:${dark ? "#F8FAFC" : "#17191F"}}main{height:100%;display:flex;align-items:center;gap:56px}.mark{width:256px;height:256px}.name{font-size:144px;font-weight:650;letter-spacing:-4px}.heph{color:${dark ? DARK_ACCENT : SIGNAL_BLUE}}</style><main><div class="mark">${markSvg}</div><div class="name"><span class="heph">Heph</span>aestus</div></main>`,
				);
				await page.evaluate(async () => document.fonts.ready);
				await page.screenshot({ path: await output(target), omitBackground: true });
			};

			await captureLockup(path.resolve(docsBrandDirectory, "hephaestus-lockup-light.png"), false);
			await captureLockup(path.resolve(docsBrandDirectory, "hephaestus-lockup-dark.png"), true);

			const captureSocialCard = async (
				width: number,
				height: number,
				target: string,
			): Promise<void> => {
				await page.setViewportSize({ width, height });
				await page.setContent(
					`<style>${FONT_FACE}*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;font-family:Inter,sans-serif;background:#fbfcff;color:#17191f}.card{position:relative;isolation:isolate;height:100%;padding:58px 68px;overflow:hidden;background-image:radial-gradient(#315fdc18 1.2px,transparent 1.2px);background-size:22px 22px}.card::before{content:"";position:absolute;z-index:-1;width:520px;height:520px;right:-170px;bottom:-290px;border-radius:50%;background:#315fdc;opacity:.13;filter:blur(72px)}.brand{display:flex;align-items:center;gap:16px}.mark{width:64px;height:64px;flex:none;filter:drop-shadow(0 10px 18px rgb(49 95 220/.18))}.name{font-size:38px;font-weight:720;letter-spacing:-1.5px}.heph{color:${SIGNAL_BLUE}}.layout{display:grid;grid-template-columns:minmax(0,1fr) 350px;align-items:center;gap:64px;height:430px}.eyebrow{display:inline-flex;border:1px solid #b9cbff;border-radius:999px;padding:8px 14px;font-size:16px;font-weight:600;color:#315fdc;background:#f3f6ff}.headline{margin:20px 0 18px;font-size:58px;font-weight:760;letter-spacing:-3px;line-height:1.02}.tagline{max-width:650px;font-size:23px;line-height:1.42;color:#596174}.cards{display:grid;gap:24px}.feedback{border:1px solid #cad7ff;border-radius:18px;padding:20px 22px;background:#fff;box-shadow:0 18px 44px rgb(32 46 80/.12)}.feedback:first-child{transform:rotate(-2deg)}.feedback:last-child{transform:rotate(2deg)}.practice{font-size:14px;font-weight:650;color:#315fdc}.finding{margin-top:12px;font-size:20px;font-weight:700;line-height:1.25}.detail{margin-top:7px;font-size:15px;line-height:1.35;color:#697386}</style><main class="card"><div class="brand"><div class="mark">${markSvg}</div><div class="name"><span class="heph">Heph</span>aestus</div></div><div class="layout"><section><div class="eyebrow">Open-source AI mentoring for software teams</div><h1 class="headline">Learn from the work<br>you're already doing</h1><div class="tagline">Practice feedback on the work itself — and a mentor to help you act on it.</div></section><aside class="cards"><div class="feedback"><div class="practice">Define a checkable outcome</div><div class="finding">No acceptance criteria.</div><div class="detail">Which outcome tells everyone the work is done?</div></div><div class="feedback"><div class="practice">Leave actionable review comments</div><div class="finding">Name the doubt.</div><div class="detail">Say what evidence would settle it.</div></div></aside></div></main>`,
				);
				await page.evaluate(async () => document.fonts.ready);
				await page.screenshot({ path: await output(target) });
			};

			const socialCard = path.resolve(docsImageDirectory, "hephaestus-social-card.png");
			await captureSocialCard(1200, 630, socialCard);
			await copyFile(
				await output(socialCard),
				await output(path.resolve(publicDirectory, "hephaestus-social-card.png")),
			);
			await captureSocialCard(
				1280,
				640,
				path.resolve(docsBrandDirectory, "github-repository-social-preview-1280x640.png"),
			);

			await exportReadmeImages(browser);
		}
	} finally {
		await browser.close();
	}
}

/** Chrome uses optical toolbar sizes and a padded, circular store icon from the same brand source. */
async function exportExtensionAssets(page: Page): Promise<void> {
	for (const [size, artworkSize] of [
		[16, 16],
		[32, 32],
		[48, 42],
		[128, 112],
	] as const) {
		// Chrome's store guidance gives circular artwork a 112px diameter on a 128px canvas.
		// Toolbar icons need the tighter existing application crop to preserve Heph's face.
		const svg = size <= 32 ? applicationMarkSvg : markSvg;
		await page.setViewportSize({ width: size, height: size });
		await page.setContent(
			`<style>html,body{margin:0;width:100%;height:100%;background:transparent;display:grid;place-items:center}svg{display:block;width:${artworkSize}px;height:${artworkSize}px}</style>${svg}`,
		);
		await page.screenshot({
			path: await output(path.resolve(extensionIconDirectory, `${size}.png`)),
			omitBackground: true,
		});
	}
	await page.setViewportSize({ width: 440, height: 280 });
	await page.setContent(
		`<style>${FONT_FACE}*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;font-family:Inter,sans-serif;background:#234fc4;color:#fff}.card{position:relative;isolation:isolate;height:100%;padding:28px;overflow:hidden;background:linear-gradient(125deg,#315fdc 0%,#244dc0 60%,#193792 100%)}.card::after{content:"";position:absolute;z-index:-1;width:320px;height:320px;right:-170px;top:30px;border:1px solid #ffffff20;border-radius:50%;box-shadow:0 0 0 38px #ffffff05,0 0 0 78px #ffffff04}.brand{display:flex;align-items:center;gap:11px;font-size:20px;font-weight:650;letter-spacing:-.5px}.mark{width:36px;height:36px;flex:none;box-shadow:0 0 0 1px #ffffff40;border-radius:50%}.mark svg{display:block;width:100%;height:100%}h1{margin:27px 0 25px;font-size:32px;line-height:1.13;letter-spacing:-1px;font-weight:700}.sites{display:flex;gap:8px}.site{padding:6px 11px;border:1px solid #ffffff30;border-radius:6px;background:#ffffff0a;font-size:13px;font-weight:550;line-height:18px}</style><main class="card"><div class="brand"><div class="mark">${markSvg}</div><span>Hephaestus</span></div><h1>Practice reviews,<br>in context.</h1><div class="sites"><span class="site">GitHub</span><span class="site">GitLab</span></div></main>`,
	);
	await page.evaluate(async () => document.fonts.ready);
	await page.screenshot({
		path: await output(path.resolve(docsBrandDirectory, "chrome-extension-promo-440x280.png")),
	});
}

/** A loopback port no other process holds right now, for this run's own Storybook. */
async function freePort(): Promise<number> {
	const probe = createServer().listen(0, "127.0.0.1");
	await once(probe, "listening");
	const address = probe.address();
	probe.close();
	await once(probe, "close");
	if (address === null || typeof address === "string") {
		throw new Error("Could not allocate a port for Storybook.");
	}
	return address.port;
}

/** This run's own Storybook, and the last lines it printed, for a failure to show. */
interface Storybook {
	url: string;
	process: ChildProcess;
	output: string[];
	/** Set when the process could not be started at all. */
	failure?: Error;
}

/**
 * Starts the Storybook CLI itself — not a package-manager wrapper — on a port allocated for this run.
 * `--exact-port` makes it exit rather than move to another port, so what answers there is this
 * process, never another worktree's Storybook.
 */
async function startStorybook(): Promise<Storybook> {
	const port = await freePort();
	const cli = fileURLToPath(import.meta.resolve("storybook/internal/bin/dispatcher"));
	const child = spawn(
		process.execPath,
		[
			cli,
			"dev",
			"--port",
			String(port),
			"--exact-port",
			"--host",
			"127.0.0.1",
			"--ci",
			"--no-open",
		],
		{ cwd: webappDirectory, stdio: ["ignore", "pipe", "pipe"] },
	);
	const storybook: Storybook = { url: `http://127.0.0.1:${port}`, process: child, output: [] };
	const keep = (chunk: Buffer): void => {
		storybook.output.push(
			...chunk
				.toString()
				.split(/\r?\n/u)
				.filter((line) => line.trim() !== ""),
		);
		storybook.output.splice(0, Math.max(0, storybook.output.length - 40));
	};
	child.stdout.on("data", keep);
	child.stderr.on("data", keep);
	child.on("error", (error) => {
		storybook.failure = error;
	});
	// Leaving without the graceful stop below: Storybook ignores SIGTERM while it is still starting, and
	// nothing of it is worth waiting for.
	process.on("exit", () => {
		if (running(child)) {
			child.kill("SIGKILL");
		}
	});
	return storybook;
}

function running(child: ChildProcess): boolean {
	return child.exitCode === null && child.signalCode === null;
}

/** Stops this run's Storybook and waits for it to be gone; nothing else is signalled. */
async function stopStorybook(child: ChildProcess): Promise<void> {
	if (!running(child) || child.pid === undefined) {
		return;
	}
	const exited = once(child, "exit");
	child.kill();
	const stopped = await Promise.race([exited.then(() => true), setTimeout(10_000, false)]);
	if (!stopped && running(child)) {
		child.kill("SIGKILL");
		await exited;
	}
}

/** Waits until this run's Storybook serves an index holding every story the export captures. */
async function waitForStorybook(storybook: Storybook, storyIds: readonly string[]): Promise<void> {
	const deadline = Date.now() + STORYBOOK_STARTUP_MS;
	while (Date.now() < deadline) {
		if (storybook.failure !== undefined) {
			throw storybook.failure;
		}
		if (!running(storybook.process)) {
			throw new Error(
				`Storybook exited (${storybook.process.exitCode ?? storybook.process.signalCode}) before it was ready.`,
			);
		}
		const response = await fetch(`${storybook.url}/index.json`, {
			signal: AbortSignal.timeout(5000),
		}).catch(() => undefined);
		if (response?.ok === true) {
			const index: unknown = await response.json();
			const entries =
				index !== null && typeof index === "object" && "entries" in index
					? index.entries
					: undefined;
			for (const storyId of storyIds) {
				if (entries === null || typeof entries !== "object" || !(storyId in entries)) {
					throw new Error(`Story ${storyId} is not in the Storybook index. Was it renamed?`);
				}
			}
			return;
		}
		await setTimeout(500);
	}
	throw new Error(`Storybook did not start within ${STORYBOOK_STARTUP_MS / 1000} seconds.`);
}

async function captureReadmeImage(
	page: Page,
	storybookUrl: string,
	capture: ReadmeCapture,
	theme: "light" | "dark",
): Promise<void> {
	const globals = encodeURIComponent(`theme:${theme}`);
	await page.goto(
		`${storybookUrl}/iframe.html?id=${capture.storyId}&viewMode=story&globals=${globals}`,
	);
	await page.waitForLoadState("networkidle");
	await page.evaluate(async () => document.fonts.ready);
	await page.addStyleTag({
		content:
			"*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}" +
			"[data-readme-actions]{display:none!important}",
	});
	const surface = page.locator(capture.selector);
	await surface.waitFor({ state: "visible" });
	const bounds = await surface.boundingBox();
	if (!bounds || Math.round(bounds.width) !== capture.expectedWidth) {
		throw new Error(
			`${capture.name} export width was ${bounds?.width ?? "missing"}px; expected ${capture.expectedWidth}px.`,
		);
	}
	await surface.screenshot({
		path: await output(path.resolve(readmeImageDirectory, `${capture.name}-${theme}.png`)),
	});
}

async function exportReadmeImages(activeBrowser: Browser): Promise<void> {
	const storybook = await startStorybook();
	try {
		await waitForStorybook(
			storybook,
			readmeCaptures.map((capture) => capture.storyId),
		);
		for (const capture of readmeCaptures) {
			for (const theme of ["light", "dark"] as const) {
				const page = await activeBrowser.newPage({
					viewport: { width: capture.viewportWidth, height: 800 },
					deviceScaleFactor: 2,
					colorScheme: theme,
					reducedMotion: "reduce",
				});
				try {
					await captureReadmeImage(page, storybook.url, capture, theme);
				} finally {
					await page.close();
				}
			}
		}
	} catch (error) {
		throw new Error(
			`README export failed. Storybook's last output:\n${storybook.output.join("\n")}`,
			{
				cause: error,
			},
		);
	} finally {
		await stopStorybook(storybook.process);
	}
}
