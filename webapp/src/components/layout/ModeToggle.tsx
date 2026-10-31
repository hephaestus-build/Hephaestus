import { Moon, Sun } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuItem,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useTheme } from "@/runtime/theme/ThemeContext";

export function ModeToggle() {
	const { setTheme } = useTheme();

	return (
		<DropdownMenu>
			<DropdownMenuTrigger render={<Button variant="outline" size="icon" />}>
				<Sun className="h-[1.2rem] w-[1.2rem] scale-100 rotate-0 transition-transform duration-280 ease-enter motion-reduce:transition-none dark:scale-0 dark:-rotate-90 dark:duration-200 dark:ease-exit" />
				<Moon className="absolute h-[1.2rem] w-[1.2rem] scale-0 rotate-90 transition-transform duration-200 ease-exit motion-reduce:transition-none dark:scale-100 dark:rotate-0 dark:duration-280 dark:ease-enter" />
				<span className="sr-only">Change theme</span>
			</DropdownMenuTrigger>
			<DropdownMenuContent align="end">
				<DropdownMenuItem onClick={() => setTheme("light")}>Light</DropdownMenuItem>
				<DropdownMenuItem onClick={() => setTheme("dark")}>Dark</DropdownMenuItem>
				<DropdownMenuItem onClick={() => setTheme("system")}>System</DropdownMenuItem>
			</DropdownMenuContent>
		</DropdownMenu>
	);
}
