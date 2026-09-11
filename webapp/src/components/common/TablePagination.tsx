import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";
import type { ComponentProps, ReactElement, ReactNode } from "react";

import { cn } from "cn";
import { Button, buttonVariants } from "@/components/ui/button";
import {
	Pagination,
	PaginationContent,
	PaginationEllipsis,
	PaginationItem,
} from "@/components/ui/pagination";

interface TablePaginationCommonProps {
	page: number;
	totalPages: number;
	className?: string;
}

export type TablePaginationProps = TablePaginationCommonProps &
	(
		| {
				onPageChange: (page: number) => void;
				renderPageLink?: never;
		  }
		| {
				renderPageLink: (page: number, props: ComponentProps<"a">) => ReactElement;
				onPageChange?: never;
		  }
	);

const WINDOW_THRESHOLD = 7;

function paginationItems(current: number, total: number): (number | "ellipsis")[] {
	if (total <= WINDOW_THRESHOLD) {
		return Array.from({ length: total }, (_, i) => i);
	}
	const pages = new Set<number>([0, total - 1, current, current - 1, current + 1]);
	const sorted = [...pages].filter((p) => p >= 0 && p < total).sort((a, b) => a - b);
	const items: (number | "ellipsis")[] = [];
	let previous: number | undefined;
	for (const page of sorted) {
		if (previous !== undefined) {
			const missingPages = page - previous - 1;
			if (missingPages === 1) {
				items.push(previous + 1);
			} else if (missingPages > 1) {
				items.push("ellipsis");
			}
		}
		items.push(page);
		previous = page;
	}
	return items;
}

interface PageControlProps {
	page: number;
	label: string;
	current?: boolean;
	size?: "default" | "icon";
	className?: string;
	children: ReactNode;
	onPageChange?: (page: number) => void;
	renderPageLink?: (page: number, props: ComponentProps<"a">) => ReactElement;
}

function PageControl({
	page,
	label,
	current,
	size = "icon",
	className,
	children,
	onPageChange,
	renderPageLink,
}: PageControlProps) {
	if (renderPageLink) {
		return renderPageLink(page, {
			"aria-current": current ? "page" : undefined,
			"aria-label": label,
			className: cn(buttonVariants({ variant: current ? "outline" : "ghost", size }), className),
			children,
		});
	}
	return (
		<Button
			variant={current ? "outline" : "ghost"}
			size={size}
			aria-label={label}
			aria-current={current ? "page" : undefined}
			className={className}
			onClick={() => onPageChange?.(page)}
		>
			{children}
		</Button>
	);
}

export function TablePagination({
	page,
	totalPages,
	onPageChange,
	renderPageLink,
	className,
}: TablePaginationProps) {
	if (totalPages <= 1) return null;

	return (
		<Pagination className={className}>
			<PaginationContent className="flex-wrap justify-center gap-y-1">
				<PaginationItem>
					{page <= 0 ? (
						<Button variant="ghost" className="pl-1.5!" aria-label="Go to previous page" disabled>
							<ChevronLeftIcon data-icon="inline-start" />
							<span className="hidden sm:block">Previous</span>
						</Button>
					) : (
						<PageControl
							page={page - 1}
							label="Go to previous page"
							size="default"
							className="pl-1.5!"
							onPageChange={onPageChange}
							renderPageLink={renderPageLink}
						>
							<ChevronLeftIcon data-icon="inline-start" />
							<span className="hidden sm:block">Previous</span>
						</PageControl>
					)}
				</PaginationItem>
				{paginationItems(page, totalPages).map((item, index) =>
					item === "ellipsis" ? (
						<PaginationItem key={`ellipsis-${index}`}>
							<PaginationEllipsis />
						</PaginationItem>
					) : (
						<PaginationItem key={item}>
							<PageControl
								page={item}
								label={`Go to page ${item + 1}`}
								current={item === page}
								onPageChange={onPageChange}
								renderPageLink={renderPageLink}
							>
								{item + 1}
							</PageControl>
						</PaginationItem>
					),
				)}
				<PaginationItem>
					{page >= totalPages - 1 ? (
						<Button variant="ghost" className="pr-1.5!" aria-label="Go to next page" disabled>
							<span className="hidden sm:block">Next</span>
							<ChevronRightIcon data-icon="inline-end" />
						</Button>
					) : (
						<PageControl
							page={page + 1}
							label="Go to next page"
							size="default"
							className="pr-1.5!"
							onPageChange={onPageChange}
							renderPageLink={renderPageLink}
						>
							<span className="hidden sm:block">Next</span>
							<ChevronRightIcon data-icon="inline-end" />
						</PageControl>
					)}
				</PaginationItem>
			</PaginationContent>
		</Pagination>
	);
}
