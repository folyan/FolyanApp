const std = @import("std");

const Hmac = std.crypto.auth.hmac.sha2.HmacSha256;
const b64 = std.base64.standard.Encoder;

pub const default_salt = "FolyanApp";
pub const default_iterations: u32 = 500_000;

/// Derived key length (SHA-256 output).
const dk_len = 32;
/// base64 of 32 bytes, with one '=' pad.
const b64_len = b64.calcSize(dk_len);
/// Output: first 30 chars of base64 (with '/' -> '_') plus "_!".
pub const password_len = 30 + 2;

pub const Error = error{ WeakParameters, OutOfMemory, OutputTooLong };

/// Mirrors passwordmanager.generate_password:
///   pbkdf2_hmac(sha256, master, salt ++ site, iterations, 32)
///   -> base64 -> '/' replaced by '_' -> first 30 chars + "_!"
pub fn generatePassword(
    allocator: std.mem.Allocator,
    out: *[password_len]u8,
    master: []const u8,
    salt: []const u8,
    site: []const u8,
    iterations: u32,
) Error!void {
    if (iterations < 1) return error.WeakParameters;

    const pbkdf2_salt = try allocator.alloc(u8, salt.len + site.len);
    defer allocator.free(pbkdf2_salt);
    @memcpy(pbkdf2_salt[0..salt.len], salt);
    @memcpy(pbkdf2_salt[salt.len..], site);

    var key: [dk_len]u8 = undefined;
    std.crypto.pwhash.pbkdf2(&key, master, pbkdf2_salt, iterations, Hmac) catch |err| switch (err) {
        error.WeakParameters => return error.WeakParameters,
        error.OutputTooLong => return error.OutputTooLong,
    };

    var encoded: [b64_len]u8 = undefined;
    _ = b64.encode(&encoded, &key);

    for (encoded[0..30], 0..) |c, i| {
        out[i] = if (c == '/') '_' else c;
    }
    out[30] = '_';
    out[31] = '!';
}

// ---------------------------------------------------------------------------
// C ABI for dart:ffi / JNI.
//
// Returns 0 on success, negative on error. `out` must point to 32 writable bytes.
// `iterations` = 0 is rejected (same as Python's "Итерации: целое число от 1").
// ---------------------------------------------------------------------------
export fn folyan_generate_password(
    master_ptr: [*]const u8,
    master_len: usize,
    salt_ptr: [*]const u8,
    salt_len: usize,
    site_ptr: [*]const u8,
    site_len: usize,
    iterations: u32,
    out: [*]u8,
) c_int {
    var result: [password_len]u8 = undefined;
    generatePassword(
        std.heap.page_allocator,
        &result,
        master_ptr[0..master_len],
        salt_ptr[0..salt_len],
        site_ptr[0..site_len],
        iterations,
    ) catch |err| return switch (err) {
        error.WeakParameters => -1,
        error.OutputTooLong => -2,
        error.OutOfMemory => -3,
    };
    @memcpy(out[0..password_len], &result);
    return 0;
}

// ---------------------------------------------------------------------------
// Tests: reference values produced by passwordmanager.generate_password (Python).
// ---------------------------------------------------------------------------

fn expectPassword(master: []const u8, salt: []const u8, site: []const u8, iterations: u32, expected: []const u8) !void {
    var out: [password_len]u8 = undefined;
    try generatePassword(std.testing.allocator, &out, master, salt, site, iterations);
    try std.testing.expectEqualStrings(expected, &out);
}

test "matches python: default iterations" {
    try expectPassword("master", default_salt, "example.com", default_iterations, "Zj7EPoI4w4Ualwym3jWk8XinpWprlc_!");
}

test "matches python: low iterations" {
    try expectPassword("secret", default_salt, "github.com", 1000, "EvKhN7biMolkWbnZFNtZgP4imP5ZE2_!");
}

test "matches python: unicode input and '+' kept in output" {
    try expectPassword("pässwörd", "salt", "сайт.рф", 3, "WSngAhkQizEfEhR8Zl+Y3GG5A9Uilr_!");
}

test "rejects zero iterations" {
    var out: [password_len]u8 = undefined;
    try std.testing.expectError(error.WeakParameters, generatePassword(std.testing.allocator, &out, "a", "b", "c", 0));
}
