# SPDX-License-Identifier: Apache-2.0
"""One fixture per subprocess; each request has its own evidence or error."""
import sys
from importlib import import_module
from .runtime import dumps, loads


def run(clients, request):
    try:
        module = request.get("module", __package__)
        runtime = import_module(module + ".runtime")
        if module not in clients:
            clients[module] = import_module(module + ".client").Client()
        return runtime.execute(clients[module], request["port"], request.get("as_of"),
                               dry_run=request.get("dry_run", False), parameters=request.get("parameters"))
    except (RuntimeError, ValueError) as error:
        message = str(error) if isinstance(error, RuntimeError) else "invalid input or replay parameter"
        return {"error": message}
    except Exception:
        return {"error": "generated service failed"}


def main() -> int:
    try:
        envelope = loads(sys.stdin.read())
        clients = {}
        print(dumps({"results": [run(clients, request) for request in envelope["requests"]]}))
        return 0
    except Exception:
        print(dumps({"error": "invalid service input or configuration"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
