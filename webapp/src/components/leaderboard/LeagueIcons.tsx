import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "cn";

const leagueVariants = cva("size-8", {
	variants: {
		size: {
			default: "",
			sm: "size-6",
			lg: "size-12",
			max: "size-28",
			full: "size-full",
		},
	},
	defaultVariants: {
		size: "default",
	},
});

// Base props for all league icons
type LeagueIconBaseProps = React.ComponentProps<"svg"> & VariantProps<typeof leagueVariants>;

// Bronze League Icon
export function LeagueBronzeIcon({ className, size, ...props }: LeagueIconBaseProps) {
	return (
		<svg
			className={cn(leagueVariants({ size }), "text-league-bronze", className)}
			width="24"
			height="24"
			viewBox="0 0 24 24"
			fill="none"
			xmlns="http://www.w3.org/2000/svg"
			role="img"
			aria-label="Bronze League"
			{...props}
		>
			<title>Bronze League</title>
			<path
				fillRule="evenodd"
				clipRule="evenodd"
				d="M12 4.3094L5.33975 8.1547V15.8453L12 19.6906L18.6603 15.8453V8.1547L12 4.3094ZM20.6603 7L12 2L3.33975 7V17L12 22L20.6603 17V7Z"
				fill="currentColor"
			/>
			<path
				d="M6.80385 12.7991V10.4897L12 13.4897L17.1962 10.4897V12.7991L12 15.7991L6.80385 12.7991Z"
				fill="currentColor"
			/>
		</svg>
	);
}

// Silver League Icon
export function LeagueSilverIcon({ className, size, ...props }: LeagueIconBaseProps) {
	return (
		<svg
			className={cn(leagueVariants({ size }), "text-league-silver", className)}
			width="24"
			height="24"
			viewBox="0 0 24 24"
			fill="none"
			xmlns="http://www.w3.org/2000/svg"
			role="img"
			aria-label="Silver League"
			{...props}
		>
			<title>Silver League</title>
			<path
				fillRule="evenodd"
				clipRule="evenodd"
				d="M12 4.3094L5.33975 8.1547V15.8453L12 19.6906L18.6603 15.8453V8.1547L12 4.3094ZM20.6603 7L12 2L3.33975 7V17L12 22L20.6603 17V7Z"
				fill="currentColor"
			/>
			<path
				d="M6.80385 11.3133V9.00391L12 12.0039L17.1962 9.00391V11.3133L12 14.3133L6.80385 11.3133Z"
				fill="currentColor"
			/>
			<path
				d="M6.80385 14.8133V12.5039L12 15.5039L17.1962 12.5039V14.8133L12 17.8133L6.80385 14.8133Z"
				fill="currentColor"
			/>
		</svg>
	);
}

// Gold League Icon
export function LeagueGoldIcon({ className, size, ...props }: LeagueIconBaseProps) {
	return (
		<svg
			className={cn(leagueVariants({ size }), "text-league-gold", className)}
			width="24"
			height="24"
			viewBox="0 0 24 24"
			fill="none"
			xmlns="http://www.w3.org/2000/svg"
			role="img"
			aria-label="Gold League"
			{...props}
		>
			<title>Gold League</title>
			<path
				fillRule="evenodd"
				clipRule="evenodd"
				d="M12 4.3094L5.33975 8.1547V15.8453L12 19.6906L18.6603 15.8453V8.1547L12 4.3094ZM20.6603 7L12 2L3.33975 7V17L12 22L20.6603 17V7Z"
				fill="currentColor"
			/>
			<path
				d="M9.5371 7.42196L12 8.84392L14.4629 7.42196L16.4629 8.57666L12 11.1533L7.5371 8.57666L9.5371 7.42196Z"
				fill="currentColor"
			/>
			<path
				d="M6.80385 11.6533V9.34392L12 12.3439L17.1962 9.34392V11.6533L12 14.6533L6.80385 11.6533Z"
				fill="currentColor"
			/>
			<path
				d="M17.1962 12.8439V15L12 18L6.80385 15V12.8439L12 15.8439L17.1962 12.8439Z"
				fill="currentColor"
			/>
		</svg>
	);
}

// Diamond League Icon
export function LeagueDiamondIcon({ className, size, ...props }: LeagueIconBaseProps) {
	return (
		<svg
			className={cn(leagueVariants({ size }), "text-league-diamond", className)}
			width="26"
			height="26"
			viewBox="0 0 26 26"
			fill="none"
			xmlns="http://www.w3.org/2000/svg"
			role="img"
			aria-label="Diamond League"
			{...props}
		>
			<title>Diamond League</title>
			<path
				fillRule="evenodd"
				clipRule="evenodd"
				d="M13 4.3094L6.33975 8.1547V15.8453L13 19.6906L19.6603 15.8453V8.1547L13 4.3094ZM21.6603 7L13 2L4.33975 7V17L13 22L21.6603 17V7Z"
				fill="currentColor"
			/>
			<path
				d="M6.87564 3.8453L4.87564 2.6906L0.875641 5V9.6188L2.87564 10.7735V6.1547L6.87564 3.8453Z"
				fill="currentColor"
			/>
			<path
				d="M9 21.3812L9 23.6906L13 26L17 23.6906V21.3812L13 23.6906L9 21.3812Z"
				fill="currentColor"
			/>
			<path
				d="M23.1244 10.7735V6.1547L19.1244 3.8453L21.1244 2.6906L25.1244 5V9.61881L23.1244 10.7735Z"
				fill="currentColor"
			/>
			<path
				d="M10.5371 7.42196L13 8.84392L15.4629 7.42196L17.4629 8.57666L13 11.1533L8.53708 8.57666L10.5371 7.42196Z"
				fill="currentColor"
			/>
			<path
				d="M7.80383 11.6533V9.34392L13 12.3439L18.1961 9.34392V11.6533L13 14.6533L7.80383 11.6533Z"
				fill="currentColor"
			/>
			<path
				d="M18.1961 12.8439V15L13 18L7.80383 15V12.8439L13 15.8439L18.1961 12.8439Z"
				fill="currentColor"
			/>
		</svg>
	);
}

// Master League Icon
export function LeagueMasterIcon({ className, size, ...props }: LeagueIconBaseProps) {
	return (
		<svg
			className={cn(leagueVariants({ size }), "text-league-master", className)}
			width="26"
			height="28"
			viewBox="0 0 26 28"
			fill="none"
			xmlns="http://www.w3.org/2000/svg"
			role="img"
			aria-label="Master League"
			{...props}
		>
			<title>Master League</title>
			<path
				fillRule="evenodd"
				clipRule="evenodd"
				d="M13 6.3094L6.33975 10.1547V17.8453L13 21.6906L19.6603 17.8453V10.1547L13 6.3094ZM21.6603 9L13 4L4.33975 9V19L13 24L21.6603 19V9Z"
				fill="currentColor"
			/>
			<path d="M18.19 17.0036L15.69 18.4469V9.55307L18.19 10.9965V17.0036Z" fill="currentColor" />
			<path
				d="M7.81001 10.9964L10.31 9.55307V18.4469L7.81001 17.0036V10.9964Z"
				fill="currentColor"
			/>
			<path
				d="M13 11.1155L11.5 10.4894V15.5913L13 16.2174L14.5 15.5913V10.4894L13 11.1155Z"
				fill="currentColor"
			/>
			<path
				d="M19.1244 5.8453L23.1244 8.1547V12.7735L25.1244 11.6188V7L21.1244 4.6906L19.1244 5.8453Z"
				fill="currentColor"
			/>
			<path d="M9 2.3094L13 0L17 2.3094V4.6188L13 2.3094L9 4.6188V2.3094Z" fill="currentColor" />
			<path
				d="M0.875641 11.6188V7L4.87564 4.6906L6.87564 5.8453L2.87564 8.1547V12.7735L0.875641 11.6188Z"
				fill="currentColor"
			/>
			<path
				d="M4.87565 23.3094L0.875641 21V16.3812L2.87564 15.2265V19.8453L6.87565 22.1547L4.87565 23.3094Z"
				fill="currentColor"
			/>
			<path
				d="M17 25.6906L13 28L9 25.6906V23.3812L13 25.6906L17 23.3812V25.6906Z"
				fill="currentColor"
			/>
			<path
				d="M25.1244 16.3812V21L21.1244 23.3094L19.1244 22.1547L23.1244 19.8453V15.2265L25.1244 16.3812Z"
				fill="currentColor"
			/>
		</svg>
	);
}

// None/Default League Icon
export function LeagueNoneIcon({ className, size, ...props }: LeagueIconBaseProps) {
	return (
		<svg
			className={cn(leagueVariants({ size }), "text-league-none", className)}
			width="24"
			height="24"
			viewBox="0 0 24 24"
			fill="none"
			xmlns="http://www.w3.org/2000/svg"
			role="img"
			aria-label="No League"
			{...props}
		>
			<title>No League</title>
			<path
				fillRule="evenodd"
				clipRule="evenodd"
				d="M15.4641 20L17.1962 19L16.1962 17.268L14.4641 18.268L15.4641 20ZM9.5359 18.2679L7.80385 17.2679L6.80385 19L8.5359 20L9.5359 18.2679ZM3.33976 13H5.33976V11H3.33976V13ZM6.80386 5L7.80386 6.73205L9.53591 5.73205L8.53591 4L6.80386 5ZM20.6603 11H18.6603V13H20.6603V11ZM17.1962 5L15.4641 4L14.4641 5.73205L16.1962 6.73205L17.1962 5Z"
				fill="currentColor"
			/>
			<path
				fillRule="evenodd"
				clipRule="evenodd"
				d="M14.1651 3.25L12 2L9.83494 3.25L10.8349 4.98205L12 4.3094L13.1651 4.98205L14.1651 3.25ZM3.33975 14.5H5.33975V15.8453L6.50482 16.5179L5.50482 18.25L3.33975 17V14.5ZM9.83494 20.75L12 22L14.1651 20.75L13.1651 19.018L12 19.6906L10.8349 19.018L9.83494 20.75ZM20.6603 9.5H18.6603V8.1547L17.4952 7.48205L18.4952 5.75L20.6603 7V9.5ZM20.6603 14.5H18.6603V15.8453L17.4952 16.5179L18.4952 18.25L20.6603 17V14.5ZM3.33975 9.5H5.33975V8.1547L6.50482 7.48205L5.50482 5.75L3.33975 7V9.5Z"
				fill="currentColor"
			/>
		</svg>
	);
}
