import { BanIcon, CloudIcon, ServerIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";

import type { WorkspaceAiModel } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { AI_VENDOR_LOGOS, vendorMarks } from "@/components/icons/ai-vendor-logos";
import {
	CHOICE_FACT_SLOTS,
	CHOICE_TONE_CLASS,
	type ChoiceTone,
	MEMBER_AI_CHOICE_DEFS,
	type MemberAiChoice,
} from "@/components/practice-vocabulary/data-handling-defs";
import { Badge } from "@/components/ui/badge";
import {
	QuestionnaireChoice,
	QuestionnaireChoiceDescription,
	QuestionnaireChoices,
} from "@/components/ui/questionnaire";

export const AI_CHOICES = statusValues(MEMBER_AI_CHOICE_DEFS);

export interface AiChoiceCardsProps {
	/** The checked card. `undefined` checks nothing, so a first answer is never pre-selected. */
	choice: MemberAiChoice | undefined;
	/** The answer already saved, if any. Its card wears a Current badge and nothing else changes. */
	saved?: MemberAiChoice;
	/**
	 * The models each answer would use in this workspace. When given, the hero shows their vendors'
	 * marks and the models row names them. Without it (User settings has no workspace) each card
	 * shows its generic mark.
	 */
	models?: Partial<Record<MemberAiChoice, readonly WorkspaceAiModel[]>>;
	onChoice: (choice: MemberAiChoice) => void;
}

/**
 * The three answers side by side, modelled on Artemis's AI choice dialog. Every card has the same
 * anatomy: a hero of large white discs with the marks of the companies behind the models (or the
 * red prohibition sign for No AI), a centred title and tagline, then the same five facts in the same
 * rows, each drawn as a check, a triangle, a cross or an "i" in its colour. Fixed hero and header
 * heights keep the rows level, so the reader compares by scanning across.
 */
export function AiChoiceCards({ choice, saved, models, onChoice }: AiChoiceCardsProps) {
	return (
		<QuestionnaireChoices className="gap-3 md:grid-cols-3">
			{AI_CHOICES.map((value) => {
				const { label, description, facts } = MEMBER_AI_CHOICE_DEFS[value];
				const known = models?.[value] ?? [];
				const names = known.map((model) => model.name);
				return (
					<QuestionnaireChoice
						key={value}
						value={value}
						checked={choice === value}
						onChange={(event) => {
							if (event.target.checked) {
								onChoice(value);
							}
						}}
					>
						<AiChoiceHero choice={value} models={known} />{" "}
						<span className="mt-4 flex min-h-[4.5rem] flex-col items-center gap-1 text-center">
							<span className="flex items-center gap-2 text-lg font-semibold">
								{label} {saved === value && <Badge variant="secondary">Current</Badge>}
							</span>{" "}
							<span className="text-sm text-muted-foreground">{description}</span>
						</span>{" "}
						<QuestionnaireChoiceDescription>
							<span className="mt-3 flex flex-col gap-2.5 text-sm text-foreground">
								{CHOICE_FACT_SLOTS.map((slot) => {
									const fact =
										slot === "models" && names.length > 0
											? { tone: "neutral" as const, text: `Runs ${names.join(" and ")}` }
											: facts[slot];
									return (
										<span key={slot} className="flex items-start gap-2.5">
											<ToneMark tone={fact.tone} />
											{fact.text}{" "}
										</span>
									);
								})}
							</span>
						</QuestionnaireChoiceDescription>
					</QuestionnaireChoice>
				);
			})}
		</QuestionnaireChoices>
	);
}

/** Large white discs, overlapping when there are two, as in Artemis. Decoration: the title names the answer. */
function AiChoiceHero({
	choice,
	models,
}: {
	choice: MemberAiChoice;
	models: readonly WorkspaceAiModel[];
}) {
	const marks = choice === "NO_AI" ? [] : vendorMarks(models);
	return (
		<span aria-hidden="true" className="group/hero flex h-24 items-center justify-center">
			{marks.length > 0 ? (
				marks.map((vendor, index) => (
					<Disc
						key={vendor}
						className={cn(
							"motion-safe:transition-transform",
							index === 0
								? "z-10 group-hover/questionnaire-choice:-translate-x-1.5"
								: "-ml-6 group-hover/questionnaire-choice:translate-x-1.5",
						)}
					>
						<img src={AI_VENDOR_LOGOS[vendor].src} alt="" className="size-12" />
					</Disc>
				))
			) : (
				<Disc>
					<FallbackMark choice={choice} />
				</Disc>
			)}
		</span>
	);
}

function Disc({ className, children }: { className?: string; children: ReactNode }) {
	return (
		<span
			className={cn(
				"inline-flex size-20 items-center justify-center rounded-full bg-white shadow-md ring-1 ring-black/5",
				className,
			)}
		>
			{children}
		</span>
	);
}

/** What a card shows when no model's vendor is known: a server, a cloud, or a prohibition sign. */
function FallbackMark({ choice }: { choice: MemberAiChoice }) {
	if (choice === "NO_AI") {
		return <BanIcon className="size-12 text-destructive" strokeWidth={2} />;
	}
	const Icon = choice === "CLOUD" ? CloudIcon : ServerIcon;
	return <Icon className="size-10 text-mentor" strokeWidth={1.75} />;
}

/** Artemis's marks: a check in a soft disc, a warning triangle, a plain cross, an outlined "i". */
function ToneMark({ tone }: { tone: ChoiceTone }) {
	const shape = {
		pro: (
			<>
				<circle cx="8" cy="8" r="7" fill="currentColor" opacity="0.15" />
				<path
					d="M11.5 5.5L7 10.5L4.5 8"
					stroke="currentColor"
					strokeWidth="2.5"
					strokeLinecap="round"
					strokeLinejoin="round"
				/>
			</>
		),
		caveat: (
			<>
				<path d="M8 2.2L14.3 13.3H1.7L8 2.2Z" fill="currentColor" opacity="0.15" />
				<path
					d="M8 2.2L14.3 13.3H1.7L8 2.2Z"
					stroke="currentColor"
					strokeWidth="1.5"
					strokeLinejoin="round"
				/>
				<path
					d="M8 6.5V9.2M8 11.2V11.4"
					stroke="currentColor"
					strokeWidth="1.6"
					strokeLinecap="round"
				/>
			</>
		),
		con: (
			<path d="M4 4L12 12M12 4L4 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
		),
		neutral: (
			<>
				<circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.5" />
				<path d="M8 7V11M8 5V5.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
			</>
		),
	}[tone];
	return (
		<svg
			viewBox="0 0 16 16"
			fill="none"
			aria-hidden="true"
			className={cn("mt-0.5 size-4.5 shrink-0", CHOICE_TONE_CLASS[tone])}
		>
			{shape}
		</svg>
	);
}
