from __future__ import annotations

import base64
import io
import pathlib
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PARTS = sorted((ROOT / "tools").glob("v24_payload.part*"))
READY = ROOT / "tools" / "v24_payload.ready"

if not READY.exists():
    raise SystemExit("payload marker is missing")
if len(PARTS) != 8:
    raise SystemExit(f"expected 8 payload parts, found {len(PARTS)}")

encoded = b"".join(part.read_bytes() for part in PARTS)
payload = base64.b64decode(encoded, validate=True)

with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
    members = archive.getmembers()
    for member in members:
        target = (ROOT / member.name).resolve()
        if ROOT not in target.parents and target != ROOT:
            raise SystemExit(f"unsafe archive path: {member.name}")
        if member.issym() or member.islnk():
            raise SystemExit(f"links are not allowed: {member.name}")
    archive.extractall(ROOT)

for part in PARTS:
    part.unlink()
READY.unlink()
pathlib.Path(__file__).unlink()
print("Trading Method Lab v2.4 payload applied")
