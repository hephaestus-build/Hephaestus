import Link from "@docusaurus/Link";
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

export default function NotFoundContent({ className }: Props): ReactNode {
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
