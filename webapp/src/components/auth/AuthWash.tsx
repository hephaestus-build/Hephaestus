/**
 * The brand wash the landing page uses, so the screens before the app look like it. Decorative and
 * behind everything: the caller is `relative isolate overflow-hidden`, which keeps it out of the
 * content's stacking context and clips it to the surface.
 */
export function AuthWash() {
	return (
		<div
			aria-hidden="true"
			className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(70rem_40rem_at_50%_-10rem,color-mix(in_oklab,var(--color-mentor)_14%,transparent),transparent_65%)]"
		/>
	);
}
