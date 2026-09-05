"""Minimal dependency-free PNG reader/writer for RigStudio's dev tools.

RigStudio's engine is Kotlin; these tools exist so a human can inspect, render and validate
character sheets without building the app. They deliberately avoid Pillow/numpy: a plain CPython
install with `zlib` and `struct` is enough, which means the tools run anywhere the repo runs
(including CI containers with no network access).

Supported for reading: 8-bit greyscale (0), greyscale+alpha (4), RGB (2) and RGBA (6),
non-interlaced. Palette PNGs and Adam7 interlacing are rejected with a clear message rather than
guessed at — a character sheet must be RGBA anyway.
"""

from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass

SIGNATURE = b"\x89PNG\r\n\x1a\n"

COLOR_TYPE_CHANNELS = {0: 1, 2: 3, 4: 2, 6: 4}


class PngError(Exception):
    """Raised for a PNG this tool refuses to interpret (rather than mis-read silently)."""


@dataclass
class Image:
    """An 8-bit image stored as one RGBA byte per channel, row-major, top-left origin."""

    width: int
    height: int
    pixels: bytearray  # len == width * height * 4
    has_alpha: bool = True

    def get(self, x: int, y: int) -> tuple[int, int, int, int]:
        offset = (y * self.width + x) * 4
        return self.pixels[offset], self.pixels[offset + 1], self.pixels[offset + 2], self.pixels[offset + 3]

    def set(self, x: int, y: int, rgba: tuple[int, int, int, int]) -> None:
        if 0 <= x < self.width and 0 <= y < self.height:
            offset = (y * self.width + x) * 4
            self.pixels[offset] = rgba[0]
            self.pixels[offset + 1] = rgba[1]
            self.pixels[offset + 2] = rgba[2]
            self.pixels[offset + 3] = rgba[3]

    @staticmethod
    def blank(width: int, height: int, rgba: tuple[int, int, int, int] = (0, 0, 0, 0)) -> "Image":
        pixels = bytearray(width * height * 4)
        if rgba != (0, 0, 0, 0):
            for index in range(width * height):
                offset = index * 4
                pixels[offset] = rgba[0]
                pixels[offset + 1] = rgba[1]
                pixels[offset + 2] = rgba[2]
                pixels[offset + 3] = rgba[3]
        return Image(width, height, pixels, rgba[3] != 0)


def _chunks(data: bytes):
    position = len(SIGNATURE)
    while position + 8 <= len(data):
        (length,) = struct.unpack(">I", data[position:position + 4])
        kind = data[position + 4:position + 8]
        payload = data[position + 8:position + 8 + length]
        if len(payload) != length:
            raise PngError("truncated PNG chunk")
        yield kind, payload
        position += 12 + length  # length + kind + payload + crc
        if kind == b"IEND":
            return


def _unfilter(raw: bytes, width: int, height: int, channels: int) -> bytearray:
    """Reverses PNG's per-scanline predictors. This is the only clever bit in the reader."""
    stride = width * channels
    out = bytearray(stride * height)
    previous = bytearray(stride)
    position = 0
    for y in range(height):
        filter_type = raw[position]
        position += 1
        line = bytearray(raw[position:position + stride])
        position += stride
        if len(line) != stride:
            raise PngError("truncated PNG scanline")

        if filter_type == 0:  # None
            pass
        elif filter_type == 1:  # Sub
            for x in range(channels, stride):
                line[x] = (line[x] + line[x - channels]) & 0xFF
        elif filter_type == 2:  # Up
            for x in range(stride):
                line[x] = (line[x] + previous[x]) & 0xFF
        elif filter_type == 3:  # Average
            for x in range(stride):
                left = line[x - channels] if x >= channels else 0
                line[x] = (line[x] + ((left + previous[x]) >> 1)) & 0xFF
        elif filter_type == 4:  # Paeth
            for x in range(stride):
                a = line[x - channels] if x >= channels else 0
                b = previous[x]
                c = previous[x - channels] if x >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                predictor = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + predictor) & 0xFF
        else:
            raise PngError(f"unknown PNG filter type {filter_type}")

        out[y * stride:(y + 1) * stride] = line
        previous = line
    return out


def read(path: str) -> Image:
    """Reads a PNG into an RGBA [Image]."""
    with open(path, "rb") as handle:
        data = handle.read()
    if not data.startswith(SIGNATURE):
        raise PngError(f"{path}: not a PNG (missing signature)")

    idat = bytearray()
    width = height = bit_depth = color_type = interlace = None
    for kind, payload in _chunks(data):
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _compression, _filter, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
        elif kind == b"IDAT":
            idat += payload
        elif kind == b"IEND":
            break

    if width is None:
        raise PngError(f"{path}: no IHDR chunk")
    if bit_depth != 8:
        raise PngError(f"{path}: bit depth {bit_depth} is not supported (need 8)")
    if color_type not in COLOR_TYPE_CHANNELS:
        raise PngError(f"{path}: colour type {color_type} is not supported (need 0, 2, 4 or 6)")
    if interlace != 0:
        raise PngError(f"{path}: interlaced PNGs are not supported")

    channels = COLOR_TYPE_CHANNELS[color_type]
    raw = zlib.decompress(bytes(idat))
    decoded = _unfilter(raw, width, height, channels)

    pixels = bytearray(width * height * 4)
    has_alpha = channels in (2, 4)
    for index in range(width * height):
        source = index * channels
        target = index * 4
        if channels == 4:
            pixels[target:target + 4] = decoded[source:source + 4]
        elif channels == 3:
            pixels[target] = decoded[source]
            pixels[target + 1] = decoded[source + 1]
            pixels[target + 2] = decoded[source + 2]
            pixels[target + 3] = 255
        elif channels == 2:
            grey = decoded[source]
            alpha = decoded[source + 1]
            pixels[target] = pixels[target + 1] = pixels[target + 2] = grey
            pixels[target + 3] = alpha
        else:
            grey = decoded[source]
            pixels[target] = pixels[target + 1] = pixels[target + 2] = grey
            pixels[target + 3] = 255

    return Image(width, height, pixels, has_alpha)


def write(image: Image, path: str) -> int:
    """Writes an RGBA PNG. Returns the number of bytes written."""
    stride = image.width * 4
    raw = bytearray()
    for y in range(image.height):
        raw.append(0)  # filter: None — the compressor does the real work
        start = y * stride
        raw += image.pixels[start:start + stride]

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0)
    data = (
        SIGNATURE
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as handle:
        handle.write(data)
    return len(data)
