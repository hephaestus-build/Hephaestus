import { render, waitFor } from "@testing-library/react";
import { assert, describe, expect, it, vi } from "vitest";

vi.mock("@/environment", () => ({
	default: { legal: { profile: "" } },
}));

import {
	isSafeLegalHref,
	isSafeLegalImageSrc,
	LEGAL_PAGE_TITLES,
	type resolveLegalContent,
} from "@/lib/legal";

import { LegalPage } from "./LegalPage";

declare global {
	interface Window {
		/** Set by the `<script>` in the hostile fixture, if the sanitiser ever lets one run. */
		__xss_executed__?: true;
		/** Set from inside the fixture's `<iframe srcdoc>`, if the frame ever renders. */
		__xss_frame__?: 1;
		/** Set by the fixture image's `onerror`, if an event handler ever survives. */
		__xss_onerror__?: 1;
	}
}

// Operator-supplied Markdown is untrusted. These fixtures inject every
// attack primitive we promised to strip: raw HTML, javascript: hrefs,
// data: images, and event handlers. If any reach the DOM, the guard has
// regressed.
const HOSTILE_MARKDOWN = `# Hostile

<script>window.__xss_executed__ = true;</script>

<iframe srcdoc="<script>parent.__xss_frame__=1</script>"></iframe>

<img src="x" onerror="window.__xss_onerror__=1">

[click me](javascript:alert(1))

[autolink](<javascript:alert(3)>)

[ref-style][ref]

[ref]: javascript:alert(2)

[spoof title](https://tum.de "evil-tooltip")

[ok link](https://tum.de)

![bad](data:image/png;base64,AAA)

![ok](https://tum.de/logo.png "spoof-img-title")
`;

function makeResolver(markdown: string): typeof resolveLegalContent {
	return async () => ({
		markdown,
		source: "profile",
		profile: "tumaet",
	});
}

describe("LegalPage — XSS guardrail", () => {
	it("strips raw HTML, javascript: hrefs, and data: images from operator markdown", async () => {
		const { container } = render(
			<LegalPage
				page="imprint"
				title={LEGAL_PAGE_TITLES.imprint}
				resolver={makeResolver(HOSTILE_MARKDOWN)}
			/>,
		);

		const article = await waitFor(() => {
			const el = container.querySelector("article");
			assert(el, "article not rendered yet");
			return el;
		});

		expect(article.querySelector("script")).toBeNull();
		expect(article.querySelector("iframe")).toBeNull();

		for (const el of article.querySelectorAll("*")) {
			for (const attr of el.getAttributeNames()) {
				expect(attr.toLowerCase().startsWith("on")).toBe(false);
			}
		}

		const anchors = Array.from(article.querySelectorAll("a"));
		expect(anchors.length).toBeGreaterThan(0);
		// The raw attribute, with no `?? ""` stand-in: a link with no href at all has to fail too.
		for (const anchor of anchors) {
			const href = anchor.getAttribute("href");
			expect({ href, safe: isSafeLegalHref(href) }).toStrictEqual({ href, safe: true });
		}

		const images = Array.from(article.querySelectorAll("img"));
		for (const image of images) {
			const src = image.getAttribute("src");
			expect({ src, safe: isSafeLegalImageSrc(src) }).toStrictEqual({ src, safe: true });
		}

		// Markdown titles (`[x](u "title")`, `![x](u "title")`) are a UI-spoof
		// primitive from an untrusted author. SafeAnchor/SafeImage drop them.
		for (const el of article.querySelectorAll("a, img")) {
			expect(el.hasAttribute("title")).toBe(false);
		}

		expect(window.__xss_executed__).toBeUndefined();
		expect(window.__xss_frame__).toBeUndefined();
		expect(window.__xss_onerror__).toBeUndefined();
	});

	it("external links get noopener/noreferrer + target=_blank", async () => {
		const { container } = render(
			<LegalPage
				page="imprint"
				title={LEGAL_PAGE_TITLES.imprint}
				resolver={makeResolver("[ext](https://tum.de) · [int](/privacy)")}
			/>,
		);
		const article = await waitFor(() => {
			const el = container.querySelector("article");
			assert(el, "article not rendered");
			return el;
		});
		const [ext, int] = Array.from(article.querySelectorAll("a"));
		assert(ext);
		assert(int);
		expect(ext.getAttribute("target")).toBe("_blank");
		expect(ext.getAttribute("rel")).toBe("noopener noreferrer");
		expect(int.getAttribute("target")).toBeNull();
		expect(int.getAttribute("rel")).toBeNull();
	});

	it("renders the disclaimer banner when resolver returns disclaimer source", async () => {
		using warn = vi.spyOn(console, "warn").mockImplementation(() => {});
		const resolver: typeof resolveLegalContent = async () => ({
			markdown: "# fallback",
			source: "disclaimer",
			profile: "",
		});
		const { findByRole } = render(
			<LegalPage page="imprint" title={LEGAL_PAGE_TITLES.imprint} resolver={resolver} />,
		);
		// Asserted on the copy: a bare "an alert rendered" passes just as happily on the "unable to
		// load" alert, which says the opposite about the deployment.
		const banner = await findByRole("alert");
		expect(banner.textContent).toMatch(/has not been configured with a legal profile/i);
		expect(warn).toHaveBeenCalledExactlyOnceWith(
			"[legal] Disclaimer fallback served for page=imprint. Configure LEGAL_PROFILE or mount /legal-overrides/. See docs/admin/legal-pages.",
		);
	});
});
