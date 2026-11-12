# SPDX-License-Identifier: Apache-2.0
"""One fixture per subprocess; each request has its own evidence or error."""
import sys
from importlib import import_module
from .runtime import dumps, loads


def run(clients, request):
    runtime = None
    try:
        module = request.get("module", __package__)
        runtime = import_module(module + ".runtime")
        if module not in clients:
            clients[module] = import_module(module + ".client").Client()
        return runtime.execute(clients[module], request["port"], request.get("as_of"),
                               dry_run=request.get("dry_run", False), parameters=request.get("parameters"))
    except ValueError:
        return {"error": "invalid input or replay parameter"}
    except Exception as error:
        # Only our API errors have messages that exclude values and response bodies.
        return {"error": str(error) if runtime is not None and isinstance(error, runtime.ApiError) else "generated service failed"}


def main() -> int:
    try:
        envelope = loads(sys.stdin.read())
        clients = {}
        print(dumps({"results": [run(clients, request) for request in envelope["requests"]]}))
        return 0
    except Exception:
        message = "invalid service input or configuration"
        print(dumps({"error": message}))
        print(message, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
