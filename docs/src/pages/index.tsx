import Link from "@docusaurus/Link";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import feedbackSceneDark from "@site/images/readme/feedback-scene-dark.png";
import feedbackSceneLight from "@site/images/readme/feedback-scene-light.png";
import HomepageFeatures from "@site/src/components/HomepageFeatures";
import Heading from "@theme/Heading";
import Layout from "@theme/Layout";
import ThemedImage from "@theme/ThemedImage";
import type { ReactNode } from "react";

import styles from "./index.module.css";

const guides = [
	{
		title: "User guide",
		audience: "You use Hephaestus",
		description: "What the feedback is, where it reaches you, and how to talk it through.",
		to: "/user/overview",
		links: [
			{ label: "Getting started", to: "/user/getting-started" },
			{ label: "Practice profile", to: "/user/practice-profile" },
			{ label: "Activity", to: "/user/activity" },
			{ label: "Practice feedback", to: "/user/ai-code-review" },
			{ label: "Talk with Heph", to: "/user/ai-mentor" },
			{ label: "Chrome extension", to: "/user/browser-extension" },
		],
	},
	{
		title: "Admin guide",
		audience: "You run a deployment",
		description: "Install it, connect a source and an AI provider, and keep it healthy.",
		to: "/admin/overview",
		links: [
			{ label: "Install with Helm", to: "/admin/kubernetes" },
			{ label: "Connect an AI provider", to: "/admin/ai-providers" },
			{ label: "Practice catalog", to: "/admin/practice-catalog" },
		],
	},
	{
		title: "Contributor guide",
		audience: "You change the code",
		description: "Set up the services, follow the standards, and ship a change.",
		to: "/contributor/overview",
		links: [
			{ label: "Local development", to: "/contributor/local-development" },
			{ label: "Coding guidelines", to: "/contributor/coding-guidelines" },
			{ label: "Release management", to: "/contributor/release-management" },
		],
	},
];

function HomepageHeader() {
	return (
		<header className={styles.heroSection}>
			<div className="container">
				<div className={styles.heroGrid}>
					<div className={styles.heroCopy}>
						<span className={styles.heroKicker}>
							Open-source practice feedback for software teams
						</span>
						<Heading as="h1" className={styles.heroTitle}>
							Learn from the work you’re already doing
						</Heading>
						<p className={styles.heroSubtitle}>
							Hephaestus reviews the pull requests, issues, and code reviews your team already
							writes against the engineering practices you choose. It gives each developer feedback
							that cites their work and names a next step.
						</p>
						<p className={styles.heroSubtitle}>
							Feedback appears on the work itself, on each developer’s private practice page, and in
							a conversation. There, developers can ask why, ask for an example, or disagree.
							Workspace admins can approve feedback before it appears on the work.
						</p>
						<div className={styles.heroActions}>
							<Link className="button button--primary button--lg" to="/user/overview">
								Read the user guide
							</Link>
							<Link className="button button--secondary button--lg" to="/admin/kubernetes">
								Install it yourself
							</Link>
						</div>
					</div>
					<figure className={styles.heroVisual}>
						<ThemedImage
							sources={{ light: feedbackSceneLight, dark: feedbackSceneDark }}
							width={1792}
							height={1426}
							alt="Four cards from one change: an issue, a pull request, a review and the merge. Each card has the practice feedback that Hephaestus attached to it."
						/>
					</figure>
				</div>
			</div>
		</header>
	);
}

function GuideRouter(): ReactNode {
	return (
		<section className={styles.quickstartSection}>
			<div className="container">
				<div className={styles.quickstartHeader}>
					<Heading as="h2">Pick the guide that matches your role</Heading>
					<p>Every page says who it is for in its first line.</p>
				</div>
				<div className={styles.quickstartGrid}>
					{guides.map((guide) => (
						<div key={guide.title} className={styles.quickstartCard}>
							<p className={styles.quickstartAudience}>{guide.audience}</p>
							<Heading as="h3" className={styles.quickstartTitle}>
								<Link to={guide.to}>{guide.title}</Link>
							</Heading>
							<p className={styles.quickstartBody}>{guide.description}</p>
							<ul className={styles.quickstartLinks}>
								{guide.links.map((link) => (
									<li key={link.to}>
										<Link to={link.to}>{link.label}</Link>
									</li>
								))}
							</ul>
						</div>
					))}
				</div>
			</div>
		</section>
	);
}

export default function Home(): ReactNode {
	const { siteConfig } = useDocusaurusContext();
	return (
		<Layout title={siteConfig.title}>
			<HomepageHeader />
			<main>
				<GuideRouter />
				<HomepageFeatures />
			</main>
		</Layout>
	);
}
