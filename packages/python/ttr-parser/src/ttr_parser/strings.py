# SPDX-License-Identifier: Apache-2.0
"""STRING_LITERAL decoding and physical source positions; triple and tagged strings stay raw."""
from collections.abc import Callable


def decode_string(raw: str, on_escape: Callable[[int], None] | None = None) -> str:
    """One pass; unknown backslash escapes retain both characters."""
    escapes = {"\\": "\\", '"': '"', "n": "\n", "t": "\t", "r": "\r"}
    inner = raw[1:-1]
    out: list[str] = []
    i = 0
    while i < len(inner):
        if inner[i] == "\\" and i + 1 < len(inner) and inner[i + 1] in escapes:
            if on_escape is not None:
                on_escape(len(out))
            out.append(escapes[inner[i + 1]])
            i += 2
        else:
            out.append(inner[i])
            i += 1
    return "".join(out)



def string_source_position(text: str, escapes: tuple[int, ...], offset: int, line: int, column: int) -> tuple[int, int]:
    """Physical position before a decoded character (encoded newlines occupy two columns)."""
    escaped = set(escapes)
    for i, ch in enumerate(text[:offset]):
        if i in escaped:
            column += 2
        elif ch == "\n":
            line += 1
            column = 0
        else:
            column += 1
    return line, column
