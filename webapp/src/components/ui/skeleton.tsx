import { cn } from "cn";

// Diverges from the shadcn registry: `motion-reduce:animate-none` stills the pulse for readers who
// ask for reduced motion, as `Spinner` does.
function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
	return (
		<div
			data-slot="skeleton"
			className={cn("animate-pulse rounded-md bg-muted motion-reduce:animate-none", className)}
			{...props}
		/>
	);
}

export { Skeleton };
