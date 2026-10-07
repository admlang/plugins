// The commands of the palette: run, build, check, test and lint as tasks.

import * as path from "path";
import * as vscode from "vscode";
import { admPath, reportMissing } from "./adm";
import { admTask, dirOf, unitsOf } from "./tasks";
import { Unit, unitFor } from "./units";

// The workspace folder a command is about: the active file's, else the only
// one, else the one the user picks.
async function pickFolder(): Promise<vscode.WorkspaceFolder | undefined> {
	const active = vscode.window.activeTextEditor?.document.uri;
	const mine = active ? vscode.workspace.getWorkspaceFolder(active) : undefined;
	if (mine) {
		return mine;
	}
	const folders = vscode.workspace.workspaceFolders ?? [];
	if (folders.length <= 1) {
		return folders[0];
	}
	return vscode.window.showWorkspaceFolderPick({ placeHolder: "The folder to run adm in" });
}

// The folder of the unit the active file belongs to, as a task names it;
// nothing when the file is in no unit.
async function activeDir(folder: vscode.WorkspaceFolder, adm: string): Promise<string | undefined> {
	const active = vscode.window.activeTextEditor?.document;
	if (!active || active.languageId !== "adm" || active.uri.scheme !== "file") {
		return undefined;
	}
	const unit = unitFor(await unitsOf(folder, adm), active.uri.fsPath, path.sep);
	return unit ? dirOf(folder, unit) : undefined;
}

// Runs one adm command on the unit of the active file, or on the whole
// workspace folder when there is none.
export async function runCommand(command: "build" | "check" | "test" | "lint"): Promise<void> {
	const adm = admPath();
	if (!adm) {
		await reportMissing();
		return;
	}
	const folder = await pickFolder();
	if (!folder) {
		void vscode.window.showInformationMessage("Open a folder with ADM sources first.");
		return;
	}
	const args = command === "check" ? ["--all"] : [];
	await vscode.tasks.executeTask(admTask(folder, { type: "adm", command, dir: await activeDir(folder, adm), args }, adm));
}

// Builds and runs an application: the one the active file belongs to, the
// only one of the workspace folder, or the one the user picks.
export async function runApplication(): Promise<void> {
	const adm = admPath();
	if (!adm) {
		await reportMissing();
		return;
	}
	const folder = await pickFolder();
	if (!folder) {
		void vscode.window.showInformationMessage("Open a folder with ADM sources first.");
		return;
	}
	const units = await unitsOf(folder, adm);
	const applications = units.filter((unit) => unit.kind === "application");
	if (applications.length === 0) {
		void vscode.window.showInformationMessage(`No application under ${folder.name}.`);
		return;
	}
	const active = vscode.window.activeTextEditor?.document.uri;
	const mine = active?.scheme === "file" ? unitFor(units, active.fsPath, path.sep) : undefined;
	let target: Unit | undefined = mine?.kind === "application" ? mine : undefined;
	if (!target && applications.length === 1) {
		target = applications[0];
	}
	if (!target) {
		const picked = await vscode.window.showQuickPick(
			applications.map((unit) => ({ label: unit.name, description: dirOf(folder, unit) ?? ".", unit })),
			{ placeHolder: "The application to run" },
		);
		target = picked?.unit;
	}
	if (!target) {
		return;
	}
	await vscode.workspace.saveAll(false);
	await vscode.tasks.executeTask(admTask(folder, { type: "adm", command: "run", dir: dirOf(folder, target), args: [target.name] }, adm));
}
