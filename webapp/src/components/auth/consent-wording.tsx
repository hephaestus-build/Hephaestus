import {
	DatabaseIcon,
	EyeIcon,
	FlaskConicalIcon,
	ChevronDownIcon,
	LifeBuoyIcon,
	ScaleIcon,
	ShareIcon,
	ShieldCheckIcon,
	TriangleAlertIcon,
	UndoIcon,
	UsersIcon,
} from "lucide-react";

import { type Fact, FactList } from "@/components/auth/FactList";
import { LegalLink } from "@/components/auth/LegalLinks";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

/**
 * The version of every consent string in this file. This bundle is the archive: what an account
 * accepted is whichever release published these words, and `ConsentService.WORDING_VERSION` holds the
 * same string.
 *
 * The consent page refuses to render the form when the server reports a different one, and the
 * settings switch submits this one, because words on screen that the server does not know cannot
 * be truthfully accepted. Change any string below, or the strings of the consent page and the
 * research section in User settings, and this moves, in the same commit as the server's.
 */
export const WORDING_VERSION = "2026-10-04";

/**
 * What the reader needs before accepting, and nothing else. It names no operator: who runs this
 * instance is the privacy notice and the imprint, which every operator configures, so the same words
 * are true on every deployment.
 */
export const TERMS_FACTS: readonly Fact[] = [
	{
		icon: EyeIcon,
		term: "What it reads",
		detail:
			"Your work in the tools your project connects, such as pull requests, issues, reviews and chat.",
	},
	{
		icon: TriangleAlertIcon,
		term: "Feedback can be wrong",
		detail: "An AI model writes it. Check it against the work it links to before you act on it.",
	},
	{
		icon: ShieldCheckIcon,
		term: "Your data",
		detail: (
			<>
				The <LegalLink to="/privacy">privacy notice</LegalLink> says who runs this instance, what it
				stores and for how long.
			</>
		),
	},
];

export const TERMS_LABEL = "I accept the terms of use";

/** The obligations sit with the box that accepts them. The facts above are not things anyone agrees to. */
export const TERMS_OBLIGATIONS =
	"Use only work you are entitled to see. Treat feedback as guidance for the person it is for, not as an assessment to share.";

/**
 * Both answers are equal in weight, carry no icon and name what they do. The reason for asking
 * sits in the section description, not inside one answer: an answer with more reasons than its
 * opposite is the asymmetry EDPB 03/2022 calls deceptive.
 */
export function researchAnswers(organization: string) {
	return [
		{
			value: "yes",
			title: "Yes, allow research use",
			detail: `${organization} may use my data for this research.`,
		},
		{
			value: "no",
			title: "No, keep my data out of research",
			detail: `${organization} may not use my data for this research.`,
		},
	] as const;
}

/**
 * The layer that is always visible. It holds what every reader needs to decide: who asks, the area of
 * research, the data, who sees it, and what withdrawal does and cannot do. The safeguards are the
 * research organization's commitments, so each is worded with the team as its subject.
 */
/** `id` lets the control that records the answer name this text as its description. */
export function ResearchSummary({ organization, id }: { organization: string; id: string }) {
	return (
		<div id={id} className="space-y-2 text-sm break-words">
			<p>
				The research organization, {organization}, may use your data for research if you allow it.
				The research looks at how AI mentoring and practice feedback affect software engineering
				work and learning. It includes building and running benchmarks and evaluation datasets that
				measure and improve AI mentoring systems.
			</p>
			<p>Your data means:</p>
			<ul className="list-disc space-y-0.5 pl-5">
				<li>your work in the tools your project connects</li>
				<li>Hephaestus’s observations and feedback about that work</li>
				<li>your responses to that feedback</li>
				<li>how you use Hephaestus, including your conversations with Heph</li>
				<li>your answers to research surveys</li>
			</ul>
			<p>
				The research team replaces your name with a code before analysis. Only the team and its AI
				model providers work with data that could identify you. Nobody else gets a dataset unless it
				is anonymized.
			</p>
			<p>
				You can withdraw at any time, as easily as you said yes. Withdrawing stops future use. The
				team then removes your data from datasets that are not yet anonymized. It cannot remove
				anonymized data that is already in a published result.
			</p>
		</div>
	);
}

function researchDetails(organization: string): readonly Fact[] {
	return [
		{
			icon: DatabaseIcon,
			term: "What data",
			detail:
				"Your Slack message choices still apply. Sign-in credentials and access tokens are never used.",
		},
		{
			icon: UsersIcon,
			term: "Who uses it",
			detail: `The research organization is ${organization}. It and the researchers who work for it use the data, only for research in the area described here. AI model providers act for it under a data processing agreement and see only pseudonymized data.`,
		},
		{
			icon: ShieldCheckIcon,
			term: "How it is protected",
			detail:
				"The research team replaces your name, username and contact details with a code. This is called pseudonymization. The team stores the key apart from the data, and only the team can reach it. Text can still name people, so the team screens for names and removes what it finds. The team does not look for data about your health, beliefs or other special categories of personal data, and it removes any that it finds. These safeguards follow Article 89 of the GDPR.",
		},
		{
			icon: ShareIcon,
			term: "Datasets and benchmarks",
			detail:
				"A dataset or benchmark leaves the research team only if it is anonymized, so that nobody can identify you from it. If the team cannot anonymize a dataset, it keeps the dataset inside the team. Running a benchmark can mean running AI models on the data. Your AI choice in User settings applies to these runs too.",
		},
		{
			icon: UndoIcon,
			term: "If you change your mind",
			detail:
				"Turn research off in User settings at any time. Anonymized data that is already in a published result or dataset cannot be traced back to you, so it cannot be removed. Research done before you withdraw stays lawful.",
		},
		{
			icon: ScaleIcon,
			term: "Your choice has no cost",
			detail:
				"Saying no, or withdrawing later, has no disadvantage. You get the same access, features and feedback. Hephaestus does not require this consent.",
		},
		{
			icon: LifeBuoyIcon,
			term: "Your rights",
			detail: (
				<>
					The <LegalLink to="/privacy">privacy notice</LegalLink> gives the retention period, your
					rights over your data and the privacy contact.
				</>
			),
		},
	];
}

/** The layer behind “What this means”: closed by default, and found by the browser’s find-in-page. */
export function ResearchDetails({ organization }: { organization: string }) {
	return (
		<Collapsible>
			<CollapsibleTrigger
				render={
					<Button type="button" variant="link" size="inline" className="group w-fit text-sm" />
				}
			>
				<FlaskConicalIcon aria-hidden className="size-3.5" />
				What this means
				<ChevronDownIcon
					aria-hidden
					className="size-3.5 transition-transform group-aria-expanded:rotate-180"
				/>
			</CollapsibleTrigger>
			<CollapsibleContent hiddenUntilFound className="mt-3">
				<FactList facts={researchDetails(organization)} />
			</CollapsibleContent>
		</Collapsible>
	);
}
