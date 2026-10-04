import { HephIcon } from "@/components/brand/HephIcon";

/** Heph's welcome on a conversation that has not started. */
export function Greeting() {
	return (
		<div className="mx-auto flex size-full max-w-3xl animate-in flex-col justify-center px-4 duration-300 fade-in slide-in-from-bottom-2 motion-reduce:animate-none sm:px-8 md:mt-20">
			<div className="mb-4 flex items-center gap-4">
				<HephIcon className="text-muted-foreground" size={80} />
				<div className="flex flex-col text-2xl">
					<p className="font-semibold">Hi, I’m Heph</p>
					<p className="text-muted-foreground">What would you like to talk through?</p>
				</div>
			</div>
		</div>
	);
}
