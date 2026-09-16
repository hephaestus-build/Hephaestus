import { Globe } from "lucide-react";

import { Github } from "@/components/icons/brand";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { buttonVariants } from "@/components/ui/button";

export interface ProjectManager {
	id: number;
	login: string;
	avatarUrl: string;
	htmlUrl: string;
	name: string;
	title: string;
	description: string;
	websiteUrl: string;
}

interface ProjectManagerCardProps {
	projectManager: ProjectManager;
}

export function ProjectManagerCard({ projectManager }: ProjectManagerCardProps) {
	return (
		<div className="mb-16 rounded-lg border border-muted bg-gradient-to-br from-background to-muted/30 p-6 sm:p-8">
			<div className="flex flex-col items-center gap-8 md:flex-row md:items-start">
				<Avatar className="h-32 w-32 border-4 border-background">
					<AvatarImage src={projectManager.avatarUrl} alt={projectManager.name} />
					<AvatarFallback className="text-2xl">
						{projectManager.name
							.split(" ")
							.map((n) => n[0])
							.join("")}
					</AvatarFallback>
				</Avatar>
				<div className="space-y-4 text-center md:text-left">
					<div>
						<h3 className="text-2xl font-bold">{projectManager.name}</h3>
						<p className="text-primary">{projectManager.title}</p>
					</div>
					<p className="text-muted-foreground">{projectManager.description}</p>
					<div className="flex items-center justify-center gap-2 pt-2 md:justify-start">
						<a
							href={projectManager.htmlUrl}
							target="_blank"
							rel="noopener noreferrer"
							className={buttonVariants({ variant: "outline", size: "sm" })}
						>
							<Github className="h-5 w-5" /> GitHub
						</a>
						<a
							href={projectManager.websiteUrl}
							target="_blank"
							rel="noopener noreferrer"
							className={buttonVariants({ variant: "outline", size: "sm" })}
						>
							<Globe className="h-5 w-5" />
							Website
						</a>
					</div>
				</div>
			</div>
		</div>
	);
}
