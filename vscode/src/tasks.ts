// Tasks of type "adm": one adm command run in a folder.

import * as path from "path";
import * as vscode from "vscode";
import { admEnv, admPath, runAdm } from "./adm";
import { parseUnits, Unit } from "./units";

export interface AdmTaskDefinition extends vscode.TaskDefinition {
	command: string;
	dir?: string;
	args?: string[];
}

const groups: Record<string, vscode.TaskGroup | undefined> = {
	build: vscode.TaskGroup.Build,
	check: vscode.TaskGroup.Build,
	test: vscode.TaskGroup.Test,
};

// The task that runs `adm <command> <args>` in a folder of the workspace.
export function admTask(folder: vscode.WorkspaceFolder, definition: AdmTaskDefinition, adm: string): vscode.Task {
	const cwd = definition.dir ? path.resolve(folder.uri.fsPath, definition.dir) : folder.uri.fsPath;
	const args = [definition.command, ...(definition.args ?? [])];
	const where = definition.dir ? ` (${definition.dir})` : "";
	const task = new vscode.Task(
		definition,
		folder,
		`${args.join(" ")}${where}`,
		"adm",
		new vscode.ProcessExecution(adm, args, { cwd, env: stringEnv(admEnv()) }),
		// No problem matcher: adm draws each diagnostic as a box of several
		// lines, and the language server already reports them as problems.
		[],
	);
	task.group = groups[definition.command];
	task.presentationOptions = { clear: true, panel: vscode.TaskPanelKind.Dedicated };
	return task;
}

function stringEnv(env: NodeJS.ProcessEnv): Record<string, string> {
	const out: Record<string, string> = {};
	for (const [name, value] of Object.entries(env)) {
		if (value !== undefined) {
			out[name] = value;
		}
	}
	return out;
}

// The units under a workspace folder.
export async function unitsOf(folder: vscode.WorkspaceFolder, adm: string): Promise<Unit[]> {
	const ran = await runAdm(adm, ["list", ".", "--json"], folder.uri.fsPath);
	return parseUnits(ran.stdout);
}

// What a unit's folder is called in a task: its path from the workspace
// folder, nothing for the workspace folder itself.
export function dirOf(folder: vscode.WorkspaceFolder, unit: Unit): string | undefined {
	const relative = path.relative(folder.uri.fsPath, unit.dir);
	return relative === "" ? undefined : relative;
}

export class AdmTaskProvider implements vscode.TaskProvider {
	async provideTasks(): Promise<vscode.Task[]> {
		const adm = admPath();
		if (!adm) {
			return [];
		}
		const tasks: vscode.Task[] = [];
		for (const folder of vscode.workspace.workspaceFolders ?? []) {
			for (const command of ["build", "check", "test", "lint", "fmt"]) {
				tasks.push(admTask(folder, { type: "adm", command }, adm));
			}
			for (const unit of await unitsOf(folder, adm)) {
				if (unit.kind === "application") {
					tasks.push(admTask(folder, { type: "adm", command: "run", dir: dirOf(folder, unit), args: [unit.name] }, adm));
				}
			}
		}
		return tasks;
	}

	resolveTask(task: vscode.Task): vscode.Task | undefined {
		const adm = admPath();
		const definition = task.definition as AdmTaskDefinition;
		if (!adm || typeof definition.command !== "string" || !isFolder(task.scope)) {
			return undefined;
		}
		return admTask(task.scope, definition, adm);
	}
}

function isFolder(scope: vscode.Task["scope"]): scope is vscode.WorkspaceFolder {
	return typeof scope === "object" && scope !== null && "uri" in scope;
}
