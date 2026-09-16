export function InstitutionalAttribution() {
	return (
		<div className="inline-flex w-fit max-w-full items-center justify-center gap-2">
			<a
				href="https://aet.cit.tum.de/"
				target="_blank"
				rel="noopener noreferrer"
				className="inline-flex h-14 items-center gap-2.5 rounded-lg px-2 text-foreground transition-colors hover:bg-muted/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
				aria-label="Applied Education Technologies (opens in a new tab)"
			>
				<img
					src="/brand/aet-mark.svg"
					alt=""
					width="40"
					height="40"
					loading="lazy"
					className="size-10 shrink-0 object-contain dark:invert"
				/>
				<span className="text-left text-xs leading-tight font-semibold tracking-wide">
					Applied
					<br />
					Education
					<br />
					Technologies
				</span>
			</a>
			<span className="h-8 w-px shrink-0 bg-border" aria-hidden="true" />
			<a
				href="https://www.tum.de/en/"
				target="_blank"
				rel="noopener noreferrer"
				aria-label="Technical University of Munich (opens in a new tab)"
				className="inline-flex h-14 items-center rounded-lg px-2 transition-colors hover:bg-muted/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
			>
				<img
					src="/brand/tum-logo.svg"
					alt=""
					width="61"
					height="32"
					loading="lazy"
					className="h-7 w-auto dark:brightness-0 dark:invert"
				/>
			</a>
		</div>
	);
}
