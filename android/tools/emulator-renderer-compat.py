#!/usr/bin/env python3
"""Use the non-DMA readback path with Android 17's goldfish mapper.

The emulator advertises ANDROID_EMU_read_color_buffer_dma, but the guest mapper
asserts that it is absent when SurfaceFlinger samples a buffer. Feature flags
did not suppress the advertisement in the reviewed emulator builds. Emptying
this one capability string makes the unmodified guest use rcReadColorBuffer,
which the renderer also implements. Android, SystemUI and app APKs stay intact.

Only the reviewed Linux Emulator 37.1.11 renderer is accepted. The original is
preserved next to it; SDK upgrades must be reviewed rather than blindly patched.
"""

import hashlib
import os
from pathlib import Path
import shutil
import sys

ORIGINAL_SHA256 = "fc9386749b288a6875c1da775071fd7b7ccd6c5af6180b2029215a6ca953f339"
PATCHED_SHA256 = "fcdf88917b2a808d4840ea97a56502ec3eaf3517f1a1b8c07358f8dc56477a81"
CAPABILITY = b"ANDROID_EMU_read_color_buffer_dma\0"


def main():
    emulator = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(os.environ["ANDROID_HOME"]) / "emulator"
    library = emulator / "lib64/libgfxstream_backend.so"
    original = library.with_suffix(".so.original")
    data = library.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest == PATCHED_SHA256:
        print(f"Renderer compatibility already applied: {digest}")
        return
    if digest != ORIGINAL_SHA256 or data.count(CAPABILITY) != 1:
        sys.exit("Unreviewed emulator renderer; refusing to modify it. Review the SDK update first.")
    if original.exists() and hashlib.sha256(original.read_bytes()).hexdigest() != ORIGINAL_SHA256:
        sys.exit("Unexpected renderer backup; refusing to overwrite it.")
    patched = data.replace(CAPABILITY, b"\0" + CAPABILITY[1:])
    assert hashlib.sha256(patched).hexdigest() == PATCHED_SHA256
    if not original.exists():
        shutil.copy2(library, original)
    temporary = library.with_suffix(".so.tmp")
    temporary.write_bytes(patched)
    shutil.copymode(library, temporary)
    temporary.replace(library)
    print(f"Renderer non-DMA compatibility applied: {ORIGINAL_SHA256} -> {PATCHED_SHA256}")


if __name__ == "__main__":
    main()
