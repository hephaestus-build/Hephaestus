import { HephaestusMark } from "@/components/brand/HephaestusMark";

/** Hephaestus's welcome on a conversation that has not started. */
export function Greeting() {
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
				</div>
			</div>
		</div>
	);
}
