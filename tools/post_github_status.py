#!/usr/bin/env python3
"""Post a safe GitHub commit status for OwnerGuard Actions observability."""
from __future__ import annotations

import json
import os
import sys
import urllib.request


def main() -> int:
    if len(sys.argv) != 4:
        raise SystemExit("usage: post_github_status.py <context> <state> <description>")
    context, state, description = sys.argv[1:]
    if state not in {"pending", "success", "failure", "error"}:
        raise SystemExit(f"invalid status state: {state}")
    token = os.environ.get("GITHUB_TOKEN", "")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    sha = os.environ.get("GITHUB_SHA", "")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    if not token or not repository or not sha or not run_id:
        raise SystemExit("GitHub Actions status environment is incomplete")
    payload = json.dumps({
        "state": state,
        "context": context,
        "description": description[:140],
        "target_url": f"https://github.com/{repository}/actions/runs/{run_id}",
    }).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/statuses/{sha}",
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status not in (200, 201):
            raise RuntimeError(f"GitHub status API returned HTTP {response.status}")
    print(f"Posted {context}: {state}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
