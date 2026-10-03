import Link from "@docusaurus/Link";
import Heading from "@theme/Heading";
import type { ReactNode } from "react";

import styles from "./styles.module.css";

interface FeatureItem {
	title: string;
	kicker: string;
	description: string;
	bullets: string[];
	cta: { label: string; to: string };
}

const FeatureList: FeatureItem[] = [
	{
		title: "Practice feedback",
		kicker: "Specific feedback on how the work was done",
		description:
			"Hephaestus can review pull requests, merge requests, and issues against the engineering practices chosen for a workspace. Feedback links back to evidence in the work.",
		bullets: [
			"Points to evidence in the work",
			"Names the missing decision or working-practice gap",
			"Suggests a concrete next step",
		],
		cta: { label: "How practice feedback works", to: "/user/ai-code-review" },
	},
	{
		title: "Chat with Heph",
		kicker: "Talk through feedback and recent work",
		description: "Heph can use recent project activity and feedback you have received as context.",
		bullets: [
			"Ask a question about a recent change",
			"Question a suggestion or compare possible next steps",
			"Use the web app or a Slack direct message",
		],
		cta: { label: "How to chat with Heph", to: "/user/ai-mentor" },
	},
	{
		title: "Practice profile",
		kicker: "Your workspace home when it reviews practices",
		description: "See how your own work stands and read your private feedback.",
		bullets: [
			"Open the evidence behind a practice",
			"Respond to feedback",
			"Read reviews of your work",
		],
		cta: { label: "Read your Practice profile", to: "/user/practice-profile" },
	},
	{
		title: "Activity",
		kicker: "What needs you and what you worked on",
		description: "Activity counts and lists work. It never scores work or ranks members.",
		bullets: ["Find review requests", "See your work over a time range", "Read workspace activity"],
		cta: { label: "Use Activity", to: "/user/activity" },
	},
];

function Feature({ title, kicker, description, bullets, cta }: FeatureItem) {
	return (
		<div className={styles.featureColumn}>
			<div className={styles.featureCard}>
				<p className={styles.kicker}>{kicker}</p>
				<Heading as="h3">{title}</Heading>
				<p className={styles.description}>{description}</p>
				<ul>
					{bullets.map((bullet) => (
						<li key={bullet}>{bullet}</li>
					))}
				</ul>
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
					What Hephaestus does
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
