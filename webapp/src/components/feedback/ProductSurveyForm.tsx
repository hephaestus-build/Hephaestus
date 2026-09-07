import { useEffect, useId, useRef } from "react";

import type { Survey } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Textarea } from "@/components/ui/textarea";

interface ProductSurveyFormProps {
	survey: Survey;
	answers: Record<string, string>;
	onAnswersChange: (answers: Record<string, string>) => void;
	isSubmitting: boolean;
	error?: string;
	onSubmit: (answers: Record<string, string>) => void | Promise<void>;
	onDismiss: () => void | Promise<void>;
	onBack: () => void;
}

export function ProductSurveyForm({
	survey,
	answers,
	onAnswersChange,
	isSubmitting,
	error,
	onSubmit,
	onDismiss,
	onBack,
}: ProductSurveyFormProps) {
	const id = useId();
	const title = useRef<HTMLHeadingElement>(null);
	useEffect(() => {
		title.current?.focus();
	}, []);
	const complete = survey.questions.every(
		(question) => !question.required || answers[question.id]?.trim(),
	);
	return (
		<>
			<DialogHeader>
				<DialogTitle ref={title} tabIndex={-1}>
					{survey.title}
				</DialogTitle>
				<DialogDescription>{survey.description}</DialogDescription>
			</DialogHeader>
			<p className="text-xs text-muted-foreground">
				Your response is linked to your account and visible only to this instance's administrators.
				It is not anonymous and is not used for research. Contact your instance administrator to
				object or request deletion. Please leave out secrets and sensitive personal data.
			</p>
			<form
				className="space-y-5"
				onSubmit={(event) => {
					event.preventDefault();
					if (complete && !isSubmitting)
						void onSubmit(
							Object.fromEntries(Object.entries(answers).filter(([, value]) => value.trim())),
						);
				}}
			>
				<fieldset disabled={isSubmitting} className="space-y-5">
					<legend className="sr-only">Survey questions</legend>
					{survey.questions.map((question, index) => (
						<fieldset className="space-y-2" key={question.id}>
							<legend className="text-sm font-medium break-words" id={`${id}-${index}-legend`}>
								{index + 1}. {question.prompt} {question.required ? "(required)" : "(optional)"}
							</legend>
							{question.type === "TEXT" ? (
								<Textarea
									aria-labelledby={`${id}-${index}-legend`}
									name={question.id}
									required={question.required}
									maxLength={4000}
									rows={3}
									value={answers[question.id] ?? ""}
									onChange={(event) =>
										onAnswersChange({ ...answers, [question.id]: event.target.value })
									}
								/>
							) : (
								<>
									<RadioGroup
										disabled={isSubmitting}
										aria-required={question.required}
										aria-labelledby={`${id}-${index}-legend`}
										className={question.type === "RATING" ? "flex flex-wrap gap-3" : "gap-2"}
										name={question.id}
										value={answers[question.id] ?? ""}
										onValueChange={(value) => onAnswersChange({ ...answers, [question.id]: value })}
									>
										{(question.type === "RATING"
											? ["1", "2", "3", "4", "5"]
											: question.options
										).map((option, optionIndex) => (
											<div className="flex items-center gap-2 rounded-md border p-2" key={option}>
												<RadioGroupItem id={`${id}-${index}-${optionIndex}`} value={option} />
												<Label
													className="min-w-0 break-words"
													htmlFor={`${id}-${index}-${optionIndex}`}
												>
													{option}
												</Label>
											</div>
										))}
									</RadioGroup>
									{!question.required && answers[question.id] && (
										<Button
											type="button"
											variant="ghost"
											size="sm"
											onClick={() => {
												const next = { ...answers };
												delete next[question.id];
												onAnswersChange(next);
											}}
										>
											Clear answer
										</Button>
									)}
								</>
							)}
						</fieldset>
					))}
				</fieldset>
				<p role="alert" className="text-sm text-destructive">
					{error}
				</p>
				<div className="flex flex-wrap justify-between gap-2">
					<Button type="button" variant="ghost" disabled={isSubmitting} onClick={onBack}>
						Back to surveys
					</Button>
					<Button type="submit" disabled={!complete || isSubmitting}>
						{isSubmitting ? "Sending…" : "Submit response"}
					</Button>
				</div>
				<details className="text-sm text-muted-foreground">
					<summary className="cursor-pointer">Don't want to answer this survey?</summary>
					<p className="my-2">
						Closing keeps your draft until you reload, leave this workspace, or sign out. Declining
						removes this survey for your account on all devices.
					</p>
					<Button
						type="button"
						variant="outline"
						disabled={isSubmitting}
						onClick={() => void onDismiss()}
					>
						Decline this survey
					</Button>
				</details>
			</form>
		</>
	);
}
