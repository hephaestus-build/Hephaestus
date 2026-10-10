import { toast } from "sonner";

/** Says what a person's choice does, and when other people see it: on their next load, which a shared cache can delay by a minute. */
export function announcePublicActivityChoice(visible: boolean) {
	toast.success(
		visible ? "You show on public activity pages" : "You are hidden on public activity pages",
		{
			description:
				"Other people see the change when they load the page again, at most about a minute later.",
		},
	);
}
