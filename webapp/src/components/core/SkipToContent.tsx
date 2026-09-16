export function SkipToContent() {
	return (
		<a
			href="#main-content"
			className="sr-only fixed top-4 left-4 z-[100] rounded-md bg-background px-4 py-2 text-sm font-medium text-foreground shadow-lg ring-2 ring-ring focus:not-sr-only"
			onClick={(event) => {
				const target = event.currentTarget.ownerDocument.getElementById(
					event.currentTarget.hash.slice(1),
				);
				if (!target) {
					return;
				}
				event.preventDefault();
				target.focus();
			}}
		>
			Skip to main content
		</a>
	);
}
