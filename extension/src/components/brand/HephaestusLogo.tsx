import { cn } from "cn";
import markUrl from "@/brand/hephaestus-mark.svg";

/**
 * The static Heph mark from `webapp/brand/hephaestus-mark.svg`, signal blue in both colour modes.
 * Decorative: whatever it sits beside names the product.
 */
export function HephMark({ className }: { className?: string }) {
	return <img src={markUrl} alt="" aria-hidden className={cn("size-6 shrink-0", className)} />;
}

/** The product name as every Hephaestus surface writes it: "Heph" in the mode-aware brand accent. */
export function HephaestusWordmark({ className }: { className?: string }) {
	return (
		<span className={cn("font-semibold tracking-tight", className)}>
			<span className="text-brand-accent">Heph</span>aestus
		</span>
	);
}

/** The settings page's lockup: mark and wordmark. */
export function HephaestusLogo({ className }: { className?: string }) {
	return (
		<span className={cn("inline-flex items-center gap-2", className)}>
			<HephMark className="size-7" />
			<HephaestusWordmark className="text-lg" />
		</span>
	);
}
