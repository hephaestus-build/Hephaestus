import Link from "@docusaurus/Link";
import { useLocation } from "@docusaurus/router";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import Heading from "@theme/Heading";
import type { Props } from "@theme/NotFound/Content";
import SearchBar from "@theme/SearchBar";
import type { ReactNode } from "react";

import styles from "./styles.module.css";

const guides = [
	{
		title: "User guide",
		description: "Feedback on your work, and how to talk it through.",
		to: "/user/overview",
	},
	{ title: "Admin guide", description: "Install and run a deployment.", to: "/admin/overview" },
	{
		title: "Contributor guide",
		description: "Change the code and ship it.",
		to: "/contributor/overview",
	},
];

/** The same path in the other version: a page can exist in the release docs and not in the next ones, or the other way round. */
function useOtherVersion(): { label: string; href: string } | undefined {
	const { siteConfig } = useDocusaurusContext();
	const { pathname } = useLocation();
	const { docsChannel, siteUrl } = siteConfig.customFields ?? {};
	if (typeof siteUrl !== "string") {
		return undefined;
	}
	// GitHub Pages serves 404.html for every missing path. Before hydration, the path is that file.
	const rest = pathname.startsWith(siteConfig.baseUrl)
		? pathname.slice(siteConfig.baseUrl.length)
		: "";
	const path = rest === "404.html" ? "" : rest;
	return docsChannel === "latest"
		? { label: "Look for it in the docs of the next release", href: `${siteUrl}/next/${path}` }
		: { label: "Look for it in the docs of the latest release", href: `${siteUrl}/${path}` };
}

export default function NotFoundContent({ className }: Props): ReactNode {
	const otherVersion = useOtherVersion();
	return (
		<main className={["container margin-vert--xl", className].filter(Boolean).join(" ")}>
			<div className="row">
				<div className="col col--6 col--offset-3">
					<Heading as="h1" className="hero__title">
						This page does not exist
					</Heading>
					<p>
						The page may have moved when the guides changed. Search for it, or start from a guide.
					</p>
					<div className={styles.search}>
						<SearchBar />
					</div>
					{otherVersion === undefined ? null : (
						<p>
							<Link href={otherVersion.href}>{otherVersion.label}</Link>.
						</p>
					)}
					<ul className={styles.guides}>
						{guides.map((guide) => (
							<li key={guide.to}>
								<Link to={guide.to}>{guide.title}</Link>
								<span> — {guide.description}</span>
							</li>
						))}
					</ul>
				</div>
			</div>
		</main>
	);
}
