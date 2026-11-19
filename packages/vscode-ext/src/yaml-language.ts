// SPDX-License-Identifier: Apache-2.0
/** Session policy only. The server owns YAML admission and header interpretation. */
export interface YamlLanguageDocument {
  uri: { toString(): string; scheme: string };
  languageId: string;
  isClosed: boolean;
  getText(): string;
}
export class YamlLanguageSelector {
  private readonly decisions = new Map<string, { language: string; manual: boolean }>();
  constructor(
    private readonly ask: (uri: string, text: string) => Promise<'ttr-yaml' | 'yaml'>,
    private readonly setLanguage: (
      document: YamlLanguageDocument,
      language: string
    ) => Promise<unknown>
  ) {}
  async select(document: YamlLanguageDocument, reconsider = false): Promise<void> {
    if (document.uri.scheme !== 'file') return;
    const uri = document.uri.toString();
    const previous = this.decisions.get(uri);
    if (previous && document.languageId !== previous.language) previous.manual = true;
    if (
      previous?.manual ||
      (previous && !reconsider) ||
      !['yaml', 'ttr-yaml'].includes(document.languageId)
    )
      return;
    // Record before awaiting the request: a language change reopens the document.
    const decision = { language: document.languageId, manual: false };
    this.decisions.set(uri, decision);
    const language = await this.ask(uri, document.getText());
    if (document.isClosed || document.languageId !== decision.language) {
      decision.manual = true;
      return;
    }
    decision.language = language;
    if (document.languageId !== language) await this.setLanguage(document, language);
  }
}
