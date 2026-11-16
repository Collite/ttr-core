"""Extract the decide_return way's slice of the ttr-demo model at a commit into a fixture world dir."""
import re, subprocess, sys, pathlib
repo, commit, out = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])

def show(path):
    return subprocess.run(["git", "-C", repo, "show", f"{commit}:{path}"], check=True, capture_output=True, text=True).stdout

def block(text, kind, name):
    m = re.search(rf"^def {kind} {name} \{{", text, re.M)
    assert m, (kind, name)
    i, depth, in_str = m.end() - 1, 0, None
    j = i
    while True:
        c = text[j]
        if text.startswith('"""', j):
            k = text.index('"""', j + 3); j = k + 3; continue
        if c == '"':
            k = j + 1
            while text[k] != '"':
                k += 2 if text[k] == "\\" else 1
            j = k + 1; continue
        if text.startswith("//", j):
            j = text.index("\n", j); continue
        if c == "{": depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[m.start(): j + 1]
        j += 1

ER = [("model/er/returns-flow.ttrm", "return_request"), ("model/er/returns-flow.ttrm", "return_request_line"),
      ("model/er/parties.ttrm", "customer"), ("model/er/orders.ttrm", "order"), ("model/er/orders.ttrm", "sales_line"),
      ("model/er/inventory.ttrm", "inventory")]
DB = [("model/db/returns-flow.ttrm", "table", "return_request_hdr"), ("model/db/returns-flow.ttrm", "table", "return_request_line"),
      ("model/db/returns-flow.ttrm", "view", "return_request"), ("model/db/dims.ttrm", "table", "customer"),
      ("model/db/orders.ttrm", "table", "sales_order"), ("model/db/orders.ttrm", "table", "sales_line"),
      ("model/db/facts.ttrm", "table", "inventory")]
head = f"// Extracted from Collite/ttr-demo {commit}, model/ — the entities the decide_return way reads (ttr-core#407).\n\n"
out.mkdir(parents=True, exist_ok=True)
(out / "er.ttrm").write_text("package hartland\n\nmodel er\n\n" + head + "\n\n".join(block(show(p), "entity", n) for p, n in ER) + "\n")
# The one substitution: the `return_request` view becomes a table with the view's columns (its definitionSql dropped),
# because ttr-concept's ErModelHandle exposes db tables only; the translator sees the same columns either way.
VIEW_NOTE = ("// Substituted: ttr-demo declares `return_request` as a VIEW over return_request_hdr + return_request_line;\n"
             "// ErModelHandle exposes db tables only, so the fixture declares its columns as a table.\n")
def db_block(p, k, n):
    b = block(show(p), k, n)
    if k == "view":
        b = re.sub(r'\n    definitionSql: """.*?"""\n', "\n", b, flags=re.S)
        b = VIEW_NOTE + b.replace(f"def view {n} {{", f"def table {n} {{", 1)
    return b
(out / "db.ttrm").write_text("package hartland\n\nmodel db schema dbo\n\n" + head + "\n\n".join(db_block(p, k, n) for p, k, n in DB) + "\n")
er2db = show("model/binding/er2db.ttrm")
names = [n for _, n in ER]
lines = [l.replace("target: { view: db.dbo.return_request }", "target: { table: db.dbo.return_request }") for l in er2db.splitlines()
         if re.match(r"def er2db_(entity|attribute) ", l) and re.search(r"er\.entity\.(" + "|".join(names) + r")[ .},]", l)]
(out / "er2db.ttrm").write_text("package hartland\n\nmodel binding\n\n" + head + "\n".join(lines) + "\n")
print(len(lines), "er2db lines")
