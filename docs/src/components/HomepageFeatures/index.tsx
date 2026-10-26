import Link from "@docusaurus/Link";
import Heading from "@theme/Heading";
import type { ReactNode } from "react";

import styles from "./styles.module.css";

interface FeatureItem {
	title: string;
	description: string;
	cta: { label: string; to: string };
}

/** The six product features, named the same way in the README, the webapp and these docs. */
const FeatureList: FeatureItem[] = [
	{
		title: "Practices",
		description:
			"Start from a curated catalog or write your own. Each states what good work looks like.",
		cta: { label: "Practice catalog", to: "/admin/practice-catalog" },
	},
	{
		title: "Feedback on the Work",
		description: "A comment on the pull request, merge request, or issue.",
		cta: { label: "Practice feedback", to: "/user/ai-code-review" },
	},
	{
		title: "Practice Page",
		description:
			"Each developer’s private view of feedback and progress per practice. Workspace admins see the observations and delivery status behind it.",
		cta: { label: "Practice profile", to: "/user/practice-profile" },
	},
	{
		title: "Conversations",
		description:
			"A weekly digest you can turn on arrives in the conversation. Developers ask why, ask for an example, or disagree.",
		cta: { label: "Talk with Heph", to: "/user/ai-mentor" },
	},
	{
		title: "Feedback Mode",
		description:
			"Off, Approve first, or Automatic, per workspace, practice group, or practice. Approve first holds feedback on the work until a workspace admin approves it.",
		cta: { label: "Practice review", to: "/admin/practice-review" },
	},
	{
		title: "Your Stack",
		description:
			"Works with GitHub, GitLab, Slack, and Outline. Your own model provider: OpenAI, Azure OpenAI, or any OpenAI-compatible endpoint. Helm on your Kubernetes.",
		cta: { label: "Install with Helm", to: "/admin/kubernetes" },
	},
];

function Feature({ title, description, cta }: FeatureItem) {
	return (
		<div className={styles.featureColumn}>
			<div className={styles.featureCard}>
				<Heading as="h3">{title}</Heading>
				<p className={styles.description}>{description}</p>
				<Link className={styles.cta} to={cta.to}>
					{cta.label}
				</Link>
			</div>
		</div>
	);
}

export default function HomepageFeatures(): ReactNode {
	return (
		<section className={styles.features} aria-labelledby="homepage-features-heading">
			<div className="container">
				<Heading as="h2" id="homepage-features-heading" className={styles.featuresHeading}>
					Features
				</Heading>
				<div className={styles.featureRow}>
					{FeatureList.map((feature) => (
						<Feature key={feature.title} {...feature} />
					))}
				</div>
			</div>
		</section>
	);
}
