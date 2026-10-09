/**
 * The guidance the bundled catalog ships for "Keep the change reviewable", read from the server's
 * own files so the stories show what a developer sees. Beside it, a picture that brings its own
 * colors.
 */
import type { PracticeGuidance, PracticeStanding } from "@/api/types.gen";

import splitOrderSvg from "@bundled-practice-guidance/scope-one-reviewable-change/figures/split-order.svg?raw";
import guideMarkdown from "@bundled-practice-guidance/scope-one-reviewable-change/guide.md?raw";
import visualSvg from "@bundled-practice-guidance/scope-one-reviewable-change/visual.svg?raw";
import bundledCatalog from "@bundled-practices";

const GUIDED_SLUG = "scope-one-reviewable-change";

function guidedPractice() {
	for (const group of bundledCatalog.groups) {
		for (const practice of group.practices) {
			if (practice.slug === GUIDED_SLUG && "visual" in practice && practice.visual !== undefined) {
				return { ...practice, visual: practice.visual };
			}
		}
	}
	throw new Error(`The bundled catalog has no visual for ${GUIDED_SLUG}`);
}

const guided = guidedPractice();

/** The practice's own words, as its standing carries them. */
export const bundledPractice = {
	whyItMatters: guided.whyItMatters,
	whatGoodLooksLike: guided.whatGoodLooksLike,
} satisfies Pick<PracticeStanding, "whyItMatters" | "whatGoodLooksLike">;

/** Its picture, guide and figure, as the guidance endpoint returns them. */
export const bundledGuidance = {
	practiceSlug: GUIDED_SLUG,
	visual: { svg: visualSvg, alt: guided.visual.alt },
	guide: { markdown: guideMarkdown, figures: { "split-order": splitOrderSvg } },
} satisfies PracticeGuidance;

/**
 * A picture an admin drew with its own colors and no `pv-*` class: dark ink that the dark theme
 * would swallow without the light ground under it.
 */
export const unthemedVisual = {
	svg: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 200">
  <rect x="24" y="40" width="176" height="120" rx="12" fill="#f1f5f9" stroke="#334155" stroke-width="2"/>
  <text x="40" y="108" font-size="20" font-weight="600" fill="#0f172a">Before</text>
  <path d="M212 100h200" stroke="#334155" stroke-width="3" fill="none"/>
  <path d="M424 100l-14-9v18z" fill="#334155"/>
  <rect x="440" y="40" width="176" height="120" rx="12" fill="#dcfce7" stroke="#166534" stroke-width="2"/>
  <text x="456" y="108" font-size="20" font-weight="600" fill="#14532d">After</text>
</svg>`,
	alt: "A box labelled Before with an arrow to a green box labelled After.",
};
