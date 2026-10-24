# Literární recepty

Zdrojem je dokument Markdown. Příkaz `ttrp build recept.md` odvodí TTR-P, mapu `.ttrd`, balíček a vysvětlení README.
`--check` ověří odvozené soubory bez zápisu; při odchylce vrací kód 3 (`TTRP-DOC-003`).
`--lang cs` vytvoří české vysvětlení. Identifikátory a kanonický program se nepřekládají.

Kostra `recipe { … }` předchází kontejnerům. Záhlaví bloku určuje kontejner; podporované značky jsou `ttrp`, `sql`,
`ttrb`, `ttrb-cs` a `python`. Více receptů v dokumentu vyžaduje jednoznačné `recipe: <jméno>` pro každou kostru.
Podrobnosti a příklady najdete v [anglickém návodu](../en/literate-recipes.md).
