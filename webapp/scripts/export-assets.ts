import { spawn } from "node:child_process";
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { setTimeout } from "node:timers/promises";

import { type Browser, chromium, type Page } from "playwright";

const webappDirectory = resolve(import.meta.dirname, "..");
const sourceDirectory = resolve(webappDirectory, "brand");
const publicDirectory = resolve(webappDirectory, "public");
const docsImageDirectory = resolve(webappDirectory, "../docs/static/img");
const docsBrandDirectory = resolve(docsImageDirectory, "brand");
const readmeImageDirectory = resolve(webappDirectory, "../docs/images/readme");
const proxyComposePath = resolve(webappDirectory, "../docker/compose.proxy.yaml");
const markSvg = await readFile(resolve(sourceDirectory, "hephaestus-mark.svg"), "utf8");
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
const storybookPort = 6106;
const storybookUrl = `http://127.0.0.1:${storybookPort}`;

await mkdir(resolve(publicDirectory, "brand"), { recursive: true });
await rm(docsBrandDirectory, { recursive: true, force: true });
await mkdir(docsBrandDirectory, { recursive: true });
await rm(readmeImageDirectory, { recursive: true, force: true });
await mkdir(readmeImageDirectory, { recursive: true });

for (const [source, targets] of [
	["hephaestus-mark.svg", [resolve(publicDirectory, "brand/hephaestus-mark.svg")]],
] as const) {
	for (const target of targets) {
		await copyFile(resolve(sourceDirectory, source), target);
	}
}

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
await writeFile(proxyComposePath, nextProxyCompose);

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

const browser = await chromium.launch();
try {
	const page = await browser.newPage();

	const captureMark = async (
		path: string,
		size: number,
		opaque = false,
		keepInsideSafeZone = false,
		svg = markSvg,
	): Promise<void> => {
		await page.setViewportSize({ width: size, height: size });
		await page.setContent(
			`<style>*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;background:${opaque ? SIGNAL_BLUE : "transparent"};display:grid;place-items:center}svg{display:block;width:${keepInsideSafeZone ? "80%" : "100%"};height:${keepInsideSafeZone ? "80%" : "100%"}}</style>${svg}`,
		);
		await page.screenshot({ path, omitBackground: !opaque });
	};
	const captureApplicationMark = (path: string, size: number): Promise<void> =>
		captureMark(path, size, true, false, applicationMarkSvg);

	await captureMark(resolve(publicDirectory, "favicon.png"), 64);
	await captureMark(resolve(publicDirectory, "apple-touch-icon.png"), 180, true);
	await captureMark(resolve(publicDirectory, "icon-192.png"), 192, true);
	await captureMark(resolve(publicDirectory, "icon-512.png"), 512, true);
	await captureMark(resolve(publicDirectory, "icon-maskable-512.png"), 512, true, true);
	await captureApplicationMark(resolve(docsBrandDirectory, "slack-app-icon-512.png"), 512);
	await captureApplicationMark(resolve(docsBrandDirectory, "external-app-icon-1024.png"), 1024);
	await captureMark(resolve(docsImageDirectory, "favicon.png"), 64);

	const captureLockup = async (path: string, dark: boolean): Promise<void> => {
		await page.setViewportSize({ width: 1240, height: 256 });
		await page.setContent(
			`<style>${FONT_FACE}html,body{margin:0;width:100%;height:100%;font-family:Inter,sans-serif;background:transparent;color:${dark ? "#F8FAFC" : "#17191F"}}main{height:100%;display:flex;align-items:center;gap:56px}.mark{width:256px;height:256px}.name{font-size:144px;font-weight:650;letter-spacing:-4px}.heph{color:${dark ? DARK_ACCENT : SIGNAL_BLUE}}</style><main><div class="mark">${markSvg}</div><div class="name"><span class="heph">Heph</span>aestus</div></main>`,
		);
		await page.evaluate(() => document.fonts.ready);
		await page.screenshot({ path, omitBackground: true });
	};

	await captureLockup(resolve(docsBrandDirectory, "hephaestus-lockup-light.png"), false);
	await captureLockup(resolve(docsBrandDirectory, "hephaestus-lockup-dark.png"), true);

	const captureSocialCard = async (width: number, height: number, path: string): Promise<void> => {
		await page.setViewportSize({ width, height });
		await page.setContent(
			`<style>${FONT_FACE}*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;font-family:Inter,sans-serif;background:#fbfcff;color:#17191f}.card{position:relative;isolation:isolate;height:100%;padding:58px 68px;overflow:hidden;background-image:radial-gradient(#315fdc18 1.2px,transparent 1.2px);background-size:22px 22px}.card::before{content:"";position:absolute;z-index:-1;width:520px;height:520px;right:-170px;bottom:-290px;border-radius:50%;background:#315fdc;opacity:.13;filter:blur(72px)}.brand{display:flex;align-items:center;gap:16px}.mark{width:64px;height:64px;flex:none;filter:drop-shadow(0 10px 18px rgb(49 95 220/.18))}.name{font-size:38px;font-weight:720;letter-spacing:-1.5px}.heph{color:${SIGNAL_BLUE}}.layout{display:grid;grid-template-columns:minmax(0,1fr) 350px;align-items:center;gap:64px;height:430px}.eyebrow{display:inline-flex;border:1px solid #b9cbff;border-radius:999px;padding:8px 14px;font-size:16px;font-weight:600;color:#315fdc;background:#f3f6ff}.headline{margin:20px 0 18px;font-size:58px;font-weight:760;letter-spacing:-3px;line-height:1.02}.tagline{max-width:650px;font-size:23px;line-height:1.42;color:#596174}.cards{display:grid;gap:24px}.feedback{border:1px solid #cad7ff;border-radius:18px;padding:20px 22px;background:#fff;box-shadow:0 18px 44px rgb(32 46 80/.12)}.feedback:first-child{transform:rotate(-2deg)}.feedback:last-child{transform:rotate(2deg)}.practice{font-size:14px;font-weight:650;color:#315fdc}.finding{margin-top:12px;font-size:20px;font-weight:700;line-height:1.25}.detail{margin-top:7px;font-size:15px;line-height:1.35;color:#697386}</style><main class="card"><div class="brand"><div class="mark">${markSvg}</div><div class="name"><span class="heph">Heph</span>aestus</div></div><div class="layout"><section><div class="eyebrow">Open-source AI mentoring for software teams</div><h1 class="headline">Learn from the work<br>you're already doing</h1><div class="tagline">Practice feedback on the work itself — and a mentor to help you act on it.</div></section><aside class="cards"><div class="feedback"><div class="practice">Define a checkable outcome</div><div class="finding">No acceptance criteria.</div><div class="detail">Which outcome tells everyone the work is done?</div></div><div class="feedback"><div class="practice">Leave actionable review comments</div><div class="finding">Name the doubt.</div><div class="detail">Say what evidence would settle it.</div></div></aside></div></main>`,
		);
		await page.evaluate(() => document.fonts.ready);
		await page.screenshot({ path });
	};

	const socialCard = resolve(docsImageDirectory, "hephaestus-social-card.png");
	await captureSocialCard(1200, 630, socialCard);
	await copyFile(socialCard, resolve(publicDirectory, "hephaestus-social-card.png"));
	await captureSocialCard(
		1280,
		640,
		resolve(docsBrandDirectory, "github-repository-social-preview-1280x640.png"),
	);

	await exportReadmeImages(browser);
} finally {
	await browser.close();
}

async function waitForStorybook(storyId: string): Promise<void> {
	for (let attempt = 0; attempt < 120; attempt += 1) {
		const response = await fetch(`${storybookUrl}/index.json`).catch(() => {});
		if (response?.ok === true) {
			const index: unknown = await response.json();
			if (
				index === null ||
				typeof index !== "object" ||
				!("entries" in index) ||
				index.entries === null ||
				typeof index.entries !== "object" ||
				!(storyId in index.entries)
			) {
				throw new Error(`Story ${storyId} is not in the Storybook index. Was it renamed?`);
			}
			return;
		}
		await setTimeout(500);
	}
	throw new Error("Storybook did not start within 60 seconds.");
}

async function captureReadmeImage(
	page: Page,
	capture: ReadmeCapture,
	theme: "light" | "dark",
): Promise<void> {
	const globals = encodeURIComponent(`theme:${theme}`);
	await page.goto(
		`${storybookUrl}/iframe.html?id=${capture.storyId}&viewMode=story&globals=${globals}`,
	);
	await page.waitForLoadState("networkidle");
	await page.evaluate(() => document.fonts.ready);
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
		path: resolve(readmeImageDirectory, `${capture.name}-${theme}.png`),
	});
}

async function exportReadmeImages(activeBrowser: Browser): Promise<void> {
	const storybook = spawn(
		"pnpm",
		["run", "storybook:dev", "--port", String(storybookPort), "--ci", "--host", "127.0.0.1"],
		{ cwd: webappDirectory, stdio: "ignore" },
	);

	try {
		for (const capture of readmeCaptures) {
			await waitForStorybook(capture.storyId);
		}
		for (const capture of readmeCaptures) {
			for (const theme of ["light", "dark"] as const) {
				const page = await activeBrowser.newPage({
					viewport: { width: capture.viewportWidth, height: 800 },
					deviceScaleFactor: 2,
					colorScheme: theme,
					reducedMotion: "reduce",
				});
				try {
					await captureReadmeImage(page, capture, theme);
				} finally {
					await page.close();
				}
			}
		}
	} finally {
		storybook.kill("SIGTERM");
	}
}
