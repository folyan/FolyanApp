const std = @import("std");

// Zero-width steganography, same format as main.zig:
//   each secret byte -> 8 bits, MSB first
//   bit 0 -> U+200B (E2 80 8B), bit 1 -> U+200C (E2 80 8C)
//   the hidden bits are appended after the cover text.
//
// Return codes:
//   >= 0  number of bytes written
//   -1    arithmetic overflow (lengths too large)
//   -2    output buffer too small

const bytes_per_secret_byte = 24;
const zw0 = [3]u8{ 0xE2, 0x80, 0x8B };
const zw1 = [3]u8{ 0xE2, 0x80, 0x8C };

/// How many secret bytes fit after `cover_len` bytes of cover text in `out_cap` bytes.
export fn folyan_stego_max_secret(cover_len: usize, out_cap: usize) usize {
    if (cover_len >= out_cap) return 0;
    return (out_cap - cover_len) / bytes_per_secret_byte;
}

/// Writes cover text followed by the hidden secret into `out`.
export fn folyan_stego_encode(
    cover: [*]const u8,
    cover_len: usize,
    secret: [*]const u8,
    secret_len: usize,
    out: [*]u8,
    out_cap: usize,
) isize {
    const hidden = std.math.mul(usize, secret_len, bytes_per_secret_byte) catch return -1;
    const need = std.math.add(usize, cover_len, hidden) catch return -1;
    if (need > out_cap) return -2;

    @memcpy(out[0..cover_len], cover[0..cover_len]);
    var pos: usize = cover_len;

    for (secret[0..secret_len]) |byte| {
        var bit: u3 = 7;
        while (true) : (bit -= 1) {
            const z = if (((byte >> bit) & 1) == 1) zw1 else zw0;
            @memcpy(out[pos..][0..3], &z);
            pos += 3;
            if (bit == 0) break;
        }
    }
    return @intCast(pos);
}

/// Extracts the hidden bytes from `input` into `out`.
export fn folyan_stego_decode(
    input: [*]const u8,
    in_len: usize,
    out: [*]u8,
    out_cap: usize,
) isize {
    const s = input[0..in_len];
    var acc: u8 = 0;
    var bits: u4 = 0;
    var written: usize = 0;
    var i: usize = 0;

    while (i + 3 <= s.len) {
        if (s[i] == 0xE2 and s[i + 1] == 0x80 and (s[i + 2] == 0x8B or s[i + 2] == 0x8C)) {
            const bit: u8 = if (s[i + 2] == 0x8C) 1 else 0;
            acc = (acc << 1) | bit;
            bits += 1;
            if (bits == 8) {
                if (written >= out_cap) return -2;
                out[written] = acc;
                written += 1;
                bits = 0;
                acc = 0;
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    return @intCast(written);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test "encode then decode round-trips" {
    const cover = "hello world";
    const secret = "секрет\x00\xff";
    var buf: [512]u8 = undefined;
    const n = folyan_stego_encode(cover.ptr, cover.len, secret.ptr, secret.len, &buf, buf.len);
    try std.testing.expect(n > 0);

    var back: [64]u8 = undefined;
    const m = folyan_stego_decode(&buf, @intCast(n), &back, back.len);
    try std.testing.expectEqual(@as(isize, secret.len), m);
    try std.testing.expectEqualSlices(u8, secret, back[0..secret.len]);
}

test "cover text stays visible as a prefix" {
    const cover = "abc";
    const secret = "x";
    var buf: [64]u8 = undefined;
    const n = folyan_stego_encode(cover.ptr, cover.len, secret.ptr, secret.len, &buf, buf.len);
    try std.testing.expectEqual(@as(isize, 3 + 24), n);
    try std.testing.expectEqualStrings("abc", buf[0..3]);
}

test "encode refuses to overflow the output buffer" {
    const cover = "0123456789";
    const secret = "0123456789";
    var small: [100]u8 = undefined;
    try std.testing.expectEqual(@as(isize, -2), folyan_stego_encode(cover.ptr, cover.len, secret.ptr, secret.len, &small, small.len));
}

test "max_secret matches what fits" {
    try std.testing.expectEqual(@as(usize, 3), folyan_stego_max_secret(10, 10 + 3 * 24));
    try std.testing.expectEqual(@as(usize, 0), folyan_stego_max_secret(100, 50));
}

// Заглушка для старых версий Android Bionic libc (как в passwordmanager.zig)
export fn getauxval(id: c_ulong) c_ulong {
    _ = id;
    return 0;
}
