"""Copy a stopped Compose project's named volumes without deleting the originals."""
import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-project", required=True)
    parser.add_argument("--target-project", default="citymate")
    args = parser.parse_args()
    source, target = args.source_project, args.target_project
    if source == target or not all(re.fullmatch(r"[a-z0-9][a-z0-9_-]*", p) for p in [source, target]):
        raise SystemExit("Invalid or identical project names")
    if docker("ps", "-q", "--filter", "label=com.docker.compose.project=" + source):
        raise SystemExit("Stop the source project before copying database files")
    names = docker("volume", "ls", "--format", "{{.Name}}").splitlines()
    sources = [name for name in names if name.startswith(source + "_")]
    if not sources:
        raise SystemExit("No source volumes found; refusing an empty migration")
    records = []
    for old in sources:
        suffix = old[len(source) + 1:]
        new = target + "_" + suffix
        if new not in names:
            docker("volume", "create", "--label", "com.docker.compose.project=" + target,
                   "--label", "com.docker.compose.volume=" + suffix, new)
            docker("run", "--rm", "--network", "none", "-v", old + ":/from:ro", "-v", new + ":/to",
                   "nginx:alpine", "sh", "-ec", 'test -z "$(ls -A /to)" && cp -a /from/. /to/ && sync')
        # Existing targets are never overwritten. A retry is allowed only if hashes match.
        hashes = docker("run", "--rm", "--network", "none", "-v", old + ":/from:ro", "-v", new + ":/to:ro",
                        "nginx:alpine", "sh", "-ec",
                        'cd /from; find . -type f -exec sha256sum {} + | LC_ALL=C sort | sha256sum; '
                        'cd /to; find . -type f -exec sha256sum {} + | LC_ALL=C sort | sha256sum').splitlines()
        if len(hashes) != 2 or hashes[0] != hashes[1]:
            raise SystemExit("Volume verification failed; both originals and copies retained: " + suffix)
        records.append({"source": old, "target": new, "fileTreeSha256": hashes[0].split()[0]})
        print("Verified volume: " + suffix, flush=True)
    out = Path(__file__).resolve().parents[1] / "target/citymate-storage-migration.json"
    out.write_text(json.dumps({"checkedAt": datetime.now(timezone.utc).isoformat(),
                               "originalsRetained": True, "volumes": records}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
