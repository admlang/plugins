// The language server: `adm lsp` over stdio.

import * as vscode from "vscode";
import { LanguageClient, LanguageClientOptions, ServerOptions } from "vscode-languageclient/node";
import { admEnv, admPath, reportMissing } from "./adm";

export class Server implements vscode.Disposable {
	private client: LanguageClient | undefined;
	readonly output = vscode.window.createOutputChannel("ADM Language Server");

	async start(): Promise<void> {
		const adm = admPath();
		if (!adm) {
			await reportMissing();
			return;
		}
		const args = ["lsp"];
		if (vscode.workspace.getConfiguration("adm").get<boolean>("lsp.logProtocol", false)) {
			args.push("--log-protocol");
		}
		const serverOptions: ServerOptions = { command: adm, args, options: { env: admEnv() } };
		const clientOptions: LanguageClientOptions = {
			documentSelector: [{ scheme: "file", language: "adm" }],
			outputChannel: this.output,
			middleware: {
				// The server's usage lenses carry a title only: clicking one
				// shows the references it counted.
				provideCodeLenses: async (document, token, next) => {
					const lenses = await next(document, token);
					for (const lens of lenses ?? []) {
						if (lens.command && lens.command.command === "" && /\busages?\b/.test(lens.command.title)) {
							lens.command = {
								title: lens.command.title,
								command: "adm.showReferences",
								arguments: [document.uri, lens.range.start],
							};
						}
					}
					return lenses;
				},
			},
		};
		this.client = new LanguageClient("adm", "ADM Language Server", serverOptions, clientOptions);
		try {
			await this.client.start();
		} catch (err) {
			this.client = undefined;
			void vscode.window.showErrorMessage(`The ADM language server did not start: ${String(err)}`);
		}
	}

	async stop(): Promise<void> {
		const running = this.client;
		this.client = undefined;
		if (running) {
			try {
				await running.stop();
			} catch {
				// A server that already went away is stopped.
			}
		}
	}

	async restart(): Promise<void> {
		await this.stop();
		await this.start();
	}

	dispose(): void {
		void this.stop();
		this.output.dispose();
	}
}

// Shows the references of the symbol at a place, in the peek view.
export async function showReferences(uri: vscode.Uri, position: vscode.Position): Promise<void> {
	const found = await vscode.commands.executeCommand<vscode.Location[]>("vscode.executeReferenceProvider", uri, position);
	await vscode.commands.executeCommand("editor.action.showReferences", uri, position, found ?? []);
}
