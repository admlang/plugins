// ADM for Visual Studio Code: the language server, commands, tasks and tests.

import * as vscode from "vscode";
import { Server, showReferences } from "./client";
import { runApplication, runCommand } from "./commands";
import { AdmTaskProvider } from "./tasks";
import { Tests } from "./tests";

let server: Server | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
	const started = new Server();
	server = started;
	context.subscriptions.push(
		started,
		new Tests(),
		vscode.tasks.registerTaskProvider("adm", new AdmTaskProvider()),
		vscode.commands.registerCommand("adm.restartServer", () => started.restart()),
		vscode.commands.registerCommand("adm.showOutput", () => started.output.show()),
		vscode.commands.registerCommand("adm.showReferences", showReferences),
		vscode.commands.registerCommand("adm.run", runApplication),
		vscode.commands.registerCommand("adm.build", () => runCommand("build")),
		vscode.commands.registerCommand("adm.check", () => runCommand("check")),
		vscode.commands.registerCommand("adm.test", () => runCommand("test")),
		vscode.commands.registerCommand("adm.lint", () => runCommand("lint")),
		// The server reads its program, library and flags at start.
		vscode.workspace.onDidChangeConfiguration((change) => {
			if (
				change.affectsConfiguration("adm.path") ||
				change.affectsConfiguration("adm.lib") ||
				change.affectsConfiguration("adm.env") ||
				change.affectsConfiguration("adm.lsp.logProtocol")
			) {
				void started.restart();
			}
		}),
	);
	await started.start();
}

export async function deactivate(): Promise<void> {
	await server?.stop();
	server = undefined;
}
