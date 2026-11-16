import type { BaseLayoutProps } from "fumadocs-ui/layouts/shared";

import { BASE, PRODUCT_URL, REPO_URL } from "@/lib/site";
import { versions } from "@/lib/version";

const base = BASE;

export function baseOptions(): BaseLayoutProps {
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
			{ text: "User guide", url: `${base}user/overview`, active: "nested-url" },
			{ text: "Admin guide", url: `${base}admin/overview`, active: "nested-url" },
			{ text: "Contributor guide", url: `${base}contributor/overview`, active: "nested-url" },
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
