import type { BaseLayoutProps, LinkItemType } from "fumadocs-ui/layouts/shared";

import { BASE, PRODUCT_URL, REPO_URL } from "@/lib/site";
import { versions } from "@/lib/version";

const base = BASE;

const GUIDE_LINKS = [
	{ text: "User guide", url: `${base}user/overview`, active: "nested-url" },
	{ text: "Admin guide", url: `${base}admin/overview`, active: "nested-url" },
	{ text: "Contributor guide", url: `${base}contributor/overview`, active: "nested-url" },
] satisfies LinkItemType[];

/**
 * A docs page leaves the guide links out (`guides: false`): its sidebar switches between the guides, which are the root
 * folders of the page tree. The home page has no sidebar, so its navigation bar links them.
 */
export function baseOptions({ guides = true }: { guides?: boolean } = {}): BaseLayoutProps {
	return {
		nav: {
			title: (
				<>
					<img
						className="h-7 w-auto dark:hidden"
						src={`${base}img/brand/hephaestus-lockup-light.png`}
						alt="Hephaestus"
						width={155}
						height={32}
					/>
					<img
						className="hidden h-7 w-auto dark:block"
						src={`${base}img/brand/hephaestus-lockup-dark.png`}
						alt="Hephaestus"
						width={155}
						height={32}
					/>
				</>
			),
			url: base,
		},
		githubUrl: REPO_URL,
		links: [
			...(guides ? GUIDE_LINKS : []),
			{
				type: "menu",
				text: versions.find((version) => version.current)?.label ?? versions[1].label,
				// Each version is a separate build, so the switcher links whole sites, not pages.
				items: versions.map((version) => ({
					text: version.label,
					url: version.href,
					external: true,
				})),
			},
			{ text: "Open Hephaestus", url: PRODUCT_URL, external: true },
		],
	};
}
