import { cn } from "cn";

import markUrl from "@/brand/hephaestus-mark.svg";

export function HephMark({ className }: { className?: string }) {
	return (
		<span className={cn("inline-flex size-8 shrink-0", className)} aria-hidden="true">
			<img className="size-full" src={markUrl} alt="" />
		</span>
	);
}

export function HephaestusWordmark({ className }: { className?: string }) {
	return (
		<span className={className}>
			<span className="text-brand-accent">Heph</span>aestus
		</span>
	);
}

interface HephaestusLogoProps {
	className?: string;
	markClassName?: string;
	wordmarkClassName?: string;
}

export function HephaestusLogo({
	className,
	markClassName,
	wordmarkClassName,
}: HephaestusLogoProps) {
	return (
		<span className={cn("inline-flex items-center gap-2", className)}>
			<HephMark className={markClassName} />
			<HephaestusWordmark className={cn("font-semibold tracking-tight", wordmarkClassName)} />
		</span>
	);
}
