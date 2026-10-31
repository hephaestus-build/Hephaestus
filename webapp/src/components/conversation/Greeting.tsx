import { HephaestusMark } from "@/components/brand/HephaestusMark";
import { Button } from "@/components/ui/button";
import { ITEM_KIND, type ItemProvider, itemKindNoun } from "@/lib/item-kinds";
import { useWorkProvider } from "@/lib/work-provider-context";

export interface GreetingProps {
	/**
	 * Puts an example question into the composer, so the developer sees what they can ask. It never
	 * sends. Absent where no example applies.
	 */
	onAsk?: (question: string) => void;
}

/**
 * Questions that the workspace's practice catalog answers before any review of the developer's work:
 * an example never presumes feedback that may not exist yet. None asks how a review works: no
 * document that the conversation reads describes it.
 */
function exampleQuestions(provider?: ItemProvider): string[] {
	return [
		"Which practices apply in this workspace?",
		`What does a ${itemKindNoun(ITEM_KIND.pullRequest, 1, provider)} that meets this workspace’s practices look like?`,
		"Why do this workspace’s practices matter?",
	];
}

/** Hephaestus's welcome on a conversation that has not started. */
export function Greeting({ onAsk }: GreetingProps) {
	const provider = useWorkProvider();
	return (
		<div className="mx-auto flex size-full max-w-3xl animate-in flex-col justify-center px-4 duration-300 fade-in slide-in-from-bottom-2 motion-reduce:animate-none sm:px-8 md:mt-20">
			<div className="mb-4 flex flex-col items-start gap-4 sm:flex-row">
				<HephaestusMark className="text-muted-foreground" size={64} />
				<div className="flex min-w-0 flex-col gap-2">
					<p className="text-2xl font-semibold">Ask Hephaestus about your work</p>
					<p className="max-w-prose text-sm text-muted-foreground">
						Ask why you got feedback, what a practice means, or what to try next. Answers come from
						an AI model and can be wrong.
					</p>
					{onAsk !== undefined && (
						<ul aria-label="Example questions" className="mt-2 flex flex-col items-start gap-2">
							{exampleQuestions(provider).map((question) => (
								<li key={question}>
									<Button
										type="button"
										variant="outline"
										size="sm"
										className="h-auto text-left whitespace-normal"
										onClick={() => onAsk(question)}
									>
										{question}
									</Button>
								</li>
							))}
						</ul>
					)}
				</div>
			</div>
		</div>
	);
}
