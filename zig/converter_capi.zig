const std = @import("std");
const conv = @import("converter.zig");

// C ABI for libfolyan_convert.so (dart:ffi / JNI).
//
// Callbacks come from the caller as C function pointers. Any may be null
// except `exists`, which is needed to pick a free output name.
//
// Return codes:
//    0  success; output path written to out_path
//   -1  invalid arguments (empty format, output buffer too small, ...)
//   -2  no free output name found
//   -3  cancelled by the caller
//   -4  ffmpeg failed in every attempt
//   -5  unexpected error

pub const CCallbacks = extern struct {
    ctx: ?*anyopaque,
    on_progress: ?*const fn (ctx: ?*anyopaque, percent: u8) callconv(.c) void,
    is_cancelled: ?*const fn (ctx: ?*anyopaque) callconv(.c) bool,
    remove_file: ?*const fn (ctx: ?*anyopaque, path: [*:0]const u8) callconv(.c) void,
    exists: *const fn (ctx: ?*anyopaque, path: [*:0]const u8) callconv(.c) bool,
};

const max_tries = 10000;
const path_capacity = 4096;

const io_global = @import("io_global.zig");

fn ioForLibrary() std.Io {
    return io_global.get();
}

/// Optional: call once from Dart at startup. Safe to skip.
export fn folyan_init() void {
    _ = io_global.get();
}

/// Optional teardown, call when the app is shutting down.
export fn folyan_deinit() void {
    io_global.deinit();
}

fn adapter(c: *const CCallbacks) conv.Hooks {
    return .{
        .ctx = @constCast(@ptrCast(c)),
        .onProgress = struct {
            fn f(ctx: *anyopaque, pct: u8) void {
                const cb: *const CCallbacks = @ptrCast(@alignCast(ctx));
                if (cb.on_progress) |p| p(cb.ctx, pct);
            }
        }.f,
        .isCancelled = struct {
            fn f(ctx: *anyopaque) bool {
                const cb: *const CCallbacks = @ptrCast(@alignCast(ctx));
                if (cb.is_cancelled) |p| return p(cb.ctx);
                return false;
            }
        }.f,
        .removeFile = struct {
            fn f(ctx: *anyopaque, path: []const u8) void {
                const cb: *const CCallbacks = @ptrCast(@alignCast(ctx));
                var z: [path_capacity:0]u8 = undefined;
                if (path.len >= z.len) return;
                @memcpy(z[0..path.len], path);
                z[path.len] = 0;
                if (cb.remove_file) |p| p(cb.ctx, &z);
            }
        }.f,
    };
}

/// Picks "<dir>/<base>.<ext>" or "<dir>/<base> (n).<ext>" that does not exist yet.
fn pickOutput(
    cb: *const CCallbacks,
    buf: []u8,
    dir: []const u8,
    base: []const u8,
    ext: []const u8,
) ?[]const u8 {
    var name_buf: [path_capacity]u8 = undefined;
    var i: usize = 0;
    while (i < max_tries) : (i += 1) {
        const name = conv.candidateName(&name_buf, base, ext, i) catch return null;
        const full = std.fmt.bufPrint(buf, "{s}/{s}", .{ dir, name }) catch return null;
        if (full.len >= buf.len) return null;
        buf[full.len] = 0;
        const z: [*:0]const u8 = @ptrCast(buf.ptr);
        if (!cb.exists(cb.ctx, z)) return buf[0..full.len];
    }
    return null;
}

fn mapError(err: anyerror) c_int {
    return switch (err) {
        error.Interrupted => -3,
        error.FfmpegFailed => -4,
        else => -5,
    };
}

fn copyOut(path: []const u8, out_path: [*]u8, out_cap: usize) c_int {
    if (path.len + 1 > out_cap) return -1;
    @memcpy(out_path[0..path.len], path);
    out_path[path.len] = 0;
    return 0;
}

/// Converts src into a new file in out_dir with extension `fmt`.
export fn folyan_convert(
    exe: [*:0]const u8,
    src: [*:0]const u8,
    out_dir: [*:0]const u8,
    fmt: [*:0]const u8,
    callbacks: *const CCallbacks,
    out_path: [*]u8,
    out_cap: usize,
) c_int {
    const src_s = std.mem.span(src);
    const dir_s = std.mem.span(out_dir);
    const fmt_s = std.mem.span(fmt);
    if (fmt_s.len == 0) return -1;

    const base = std.fs.path.stem(std.fs.path.basename(src_s));
    var buf: [path_capacity]u8 = undefined;
    const out = pickOutput(callbacks, &buf, dir_s, base, fmt_s) orelse return -2;

    conv.convertFile(ioForLibrary(), std.mem.span(exe), src_s, out, fmt_s, adapter(callbacks)) catch |err| {
        return mapError(err);
    };
    return copyOut(out, out_path, out_cap);
}

/// Merges the video `video` with the external audio `audio` into out_dir.
/// Output is "<video base>_merged.<fmt>".
export fn folyan_merge(
    exe: [*:0]const u8,
    video: [*:0]const u8,
    audio: [*:0]const u8,
    out_dir: [*:0]const u8,
    fmt: [*:0]const u8,
    callbacks: *const CCallbacks,
    out_path: [*]u8,
    out_cap: usize,
) c_int {
    const video_s = std.mem.span(video);
    const audio_s = std.mem.span(audio);
    const dir_s = std.mem.span(out_dir);
    const fmt_s = std.mem.span(fmt);
    if (fmt_s.len == 0) return -1;

    const base = std.fs.path.stem(std.fs.path.basename(video_s));
    var merged_base: [path_capacity]u8 = undefined;
    const merged = std.fmt.bufPrint(&merged_base, "{s}_merged", .{base}) catch return -1;

    var buf: [path_capacity]u8 = undefined;
    const out = pickOutput(callbacks, &buf, dir_s, merged, fmt_s) orelse return -2;

    conv.mergeFile(ioForLibrary(), std.mem.span(exe), video_s, audio_s, out, fmt_s, adapter(callbacks)) catch |err| {
        return mapError(err);
    };
    return copyOut(out, out_path, out_cap);
}
