import { useEffect, useRef, useState } from "react";

type ScrollBehavior = "auto" | "smooth";

export function useScrollToBottom() {
	const containerRef = useRef<HTMLDivElement>(null);
	const endRef = useRef<HTMLDivElement>(null);

	// Simple local state for UI concerns
	const [isAtBottom, setIsAtBottom] = useState(false);

	// Auto-scroll to bottom with specified behavior
	const scrollToBottom = (behavior: ScrollBehavior = "smooth") => {
		endRef.current?.scrollIntoView({ behavior });
	};

	// Add scroll detection
	useEffect(() => {
		const container = containerRef.current;
		if (!container) {
			return;
		}

		const handleScroll = () => {
			const { scrollTop, scrollHeight, clientHeight } = container;
			const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
			const atBottom = distanceFromBottom <= 5;

			setIsAtBottom(atBottom);
		};

		// Initial check
		handleScroll();

		// Add scroll listener
		container.addEventListener("scroll", handleScroll, { passive: true });

		return () => {
			container.removeEventListener("scroll", handleScroll);
		};
	}, []);

	return {
		containerRef,
		endRef,
		isAtBottom,
		scrollToBottom,
	};
}
