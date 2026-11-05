const std = @import("std");
const Io = std.Io;
const process = std.process;

// Port of the converter from nativebins.py / core.py:
//   - format classification and ffmpeg argument lists (convert + merge)
//   - parsing "Duration:" / "time=" from ffmpeg stderr for progress
//   - running ffmpeg through std.process with cancel and fallback attempts
// Encoding itself stays in ffmpeg (libx264, libmp3lame, aac, ...).

// ---------------------------------------------------------------------------
// Formats and codecs
// ---------------------------------------------------------------------------

pub const audio_formats = [_][]const u8{ "mp3", "wav", "m4a", "opus", "ogg", "flac", "aac" };
pub const video_formats = [_][]const u8{ "mp4", "mkv" };

pub fn isAudio(fmt: []const u8) bool {
    for (audio_formats) |f| {
        if (std.mem.eql(u8, f, fmt)) return true;
    }
    return false;
}

const CodecEntry = struct {
    fmt: []const u8,
    args: []const []const u8,
};

const audio_codecs = [_]CodecEntry{
    .{ .fmt = "mp3", .args = &.{ "-c:a", "libmp3lame", "-b:a", "192k" } },
    .{ .fmt = "wav", .args = &.{ "-c:a", "pcm_s16le" } },
    .{ .fmt = "m4a", .args = &.{ "-c:a", "aac", "-b:a", "192k" } },
    .{ .fmt = "opus", .args = &.{ "-c:a", "libopus", "-b:a", "128k" } },
    .{ .fmt = "ogg", .args = &.{ "-c:a", "libvorbis", "-q:a", "5" } },
    .{ .fmt = "flac", .args = &.{ "-c:a", "flac" } },
    .{ .fmt = "aac", .args = &.{ "-c:a", "aac", "-b:a", "192k" } },
};

pub fn audioCodecArgs(fmt: []const u8) ?[]const []const u8 {
    for (audio_codecs) |e| {
        if (std.mem.eql(u8, e.fmt, fmt)) return e.args;
    }
    return null;
}

// ---------------------------------------------------------------------------
// Fixed-capacity argv builder (no allocator needed)
// ---------------------------------------------------------------------------

pub const max_args = 64;

pub const Args = struct {
    items: [max_args][]const u8 = undefined,
    len: usize = 0,

    pub fn reset(self: *Args) void {
        self.len = 0;
    }

    pub fn push(self: *Args, s: []const u8) void {
        self.items[self.len] = s;
        self.len += 1;
    }

    pub fn add(self: *Args, list: []const []const u8) void {
        for (list) |s| self.push(s);
    }

    pub fn slice(self: *const Args) []const []const u8 {
        return self.items[0..self.len];
    }
};

// ---------------------------------------------------------------------------
// Argument builders (one per fallback attempt)
// ---------------------------------------------------------------------------

/// Mirrors convert_media() in nativebins.py. Attempts 0 and 1.
/// The caller prepends "-hide_banner -nostdin -y" as run_ffmpeg() does.
pub fn convertArgs(args: *Args, src: []const u8, out: []const u8, fmt: []const u8, attempt: usize) bool {
    args.reset();

    if (isAudio(fmt)) {
        switch (attempt) {
            0 => {
                args.add(&.{ "-i", src, "-vn", "-map", "0:a:0" });
                args.add(audioCodecArgs(fmt) orelse return false);
                args.push(out);
            },
            1 => {
                args.add(&.{ "-i", src, "-vn" });
                args.push(out);
            },
            else => return false,
        }
        return true;
    }

    const flags: []const []const u8 = if (std.mem.eql(u8, fmt, "mp4"))
        &.{ "-movflags", "+faststart" }
    else
        &.{};
    const maps = [_][]const u8{ "-map", "0:v:0", "-map", "0:a?", "-sn" };

    switch (attempt) {
        0 => {
            args.add(&.{ "-i", src });
            args.add(&maps);
            args.add(&.{ "-c", "copy" });
            args.add(flags);
            args.push(out);
        },
        1 => {
            args.add(&.{ "-i", src });
            args.add(&maps);
            args.add(&.{ "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-b:a", "192k" });
            args.add(flags);
            args.push(out);
        },
        else => return false,
    }
    return true;
}

/// Mirrors merge_media() in nativebins.py. Attempts 0, 1 and 2.
pub fn mergeArgs(args: *Args, video: []const u8, audio: []const u8, out: []const u8, fmt: []const u8, attempt: usize) bool {
    args.reset();

    const flags: []const []const u8 = if (std.mem.eql(u8, fmt, "mp4"))
        &.{ "-movflags", "+faststart" }
    else
        &.{};

    args.add(&.{ "-i", video, "-i", audio, "-map", "0:v:0", "-map", "1:a:0", "-shortest" });
    switch (attempt) {
        0 => args.add(&.{ "-c", "copy" }),
        1 => args.add(&.{ "-c:v", "copy", "-c:a", "aac", "-b:a", "192k" }),
        2 => args.add(&.{ "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-b:a", "192k" }),
        else => return false,
    }
    args.add(flags);
    args.push(out);
    return true;
}

// ---------------------------------------------------------------------------
// Progress parsing
// ---------------------------------------------------------------------------

/// Parses "H:MM:SS(.ff)" into seconds.
pub fn parseHms(s: []const u8) ?f64 {
    var it = std.mem.splitScalar(u8, s, ':');
    const h = it.next() orelse return null;
    const m = it.next() orelse return null;
    const sec = it.next() orelse return null;
    if (it.next() != null) return null;

    const hh = std.fmt.parseInt(u32, h, 10) catch return null;
    const mm = std.fmt.parseInt(u32, m, 10) catch return null;
    const ss = std.fmt.parseFloat(f64, sec) catch return null;
    return @as(f64, @floatFromInt(hh)) * 3600.0 + @as(f64, @floatFromInt(mm)) * 60.0 + ss;
}

/// Finds `key` (e.g. "Duration:" or "time=") in an ffmpeg line and parses the time after it.
pub fn findTime(line: []const u8, key: []const u8) ?f64 {
    const start = std.mem.indexOf(u8, line, key) orelse return null;
    const rest = std.mem.trimStart(u8, line[start + key.len ..], " ");
    var end: usize = 0;
    while (end < rest.len and (std.ascii.isDigit(rest[end]) or rest[end] == ':' or rest[end] == '.')) {
        end += 1;
    }
    return parseHms(rest[0..end]);
}

/// Progress percent as in run_ffmpeg(): capped at 99 while running.
pub fn percent(current: f64, total: f64) u8 {
    if (total <= 0) return 0;
    const p = @floor(current * 100.0 / total);
    if (p >= 99) return 99;
    if (p < 0) return 0;
    return @intFromFloat(p);
}

// ---------------------------------------------------------------------------
// Output names (mirrors unique_path() in nativebins.py)
// ---------------------------------------------------------------------------

/// Candidate name for attempt `index`: index 0 gives "base.ext",
/// index n gives "base (n).ext". Walk indices from 0 and use the first
/// name whose file does not exist yet. Merge output uses base "<name>_merged".
pub fn candidateName(buf: []u8, base: []const u8, ext: []const u8, index: usize) std.fmt.BufPrintError![]u8 {
    if (index == 0) return std.fmt.bufPrint(buf, "{s}.{s}", .{ base, ext });
    return std.fmt.bufPrint(buf, "{s} ({d}).{s}", .{ base, index, ext });
}

// ---------------------------------------------------------------------------
// Running ffmpeg (std.process, same calls as process.zig's run())
// ---------------------------------------------------------------------------

pub const Error = error{ Interrupted, FfmpegFailed, ArgsTooLong } || process.SpawnError || Io.Cancelable || Io.UnexpectedError;

/// Callbacks supplied by the caller (UI, JNI, dart:ffi, ...).
pub const Hooks = struct {
    ctx: *anyopaque,
    /// Called with 0..99 while encoding.
    onProgress: *const fn (ctx: *anyopaque, percent: u8) void,
    /// Polled between reads. Return true to stop the conversion.
    isCancelled: *const fn (ctx: *anyopaque) bool,
    /// Removes a partial output file after a failed attempt or cancel.
    removeFile: *const fn (ctx: *anyopaque, path: []const u8) void,
};

const line_capacity = 2048;

const LineState = struct {
    total: ?f64 = null,
    hooks: Hooks,

    fn handle(self: *LineState, line: []const u8) void {
        if (self.total == null) {
            self.total = findTime(line, "Duration:");
        }
        const cur = findTime(line, "time=") orelse return;
        const total = self.total orelse return;
        self.hooks.onProgress(self.hooks.ctx, percent(cur, total));
    }
};

/// Spawns `ffmpeg -hide_banner -nostdin -y <args>` and waits for it.
fn runOnce(io: Io, exe: []const u8, args: []const []const u8, hooks: Hooks) Error!void {
    var argv: Args = .{};
    argv.push(exe);
    argv.push("-hide_banner");
    argv.push("-nostdin");
    argv.push("-y");
    argv.add(args);

    // argv[0] is the absolute path to ffmpeg; the default exe = .detect uses it as-is.
    var child = try process.spawn(io, .{
        .argv = argv.slice(),
        .stdin = .ignore,
        .stdout = .ignore,
        .stderr = .pipe,
    });
    defer child.kill(io);

    var state = LineState{ .hooks = hooks };
    var line: [line_capacity]u8 = undefined;
    var line_len: usize = 0;

    var rbuf: [4096]u8 = undefined;
    var file_reader = child.stderr.?.reader(io, &rbuf);
    var chunk: [1024]u8 = undefined;

    while (true) {
        if (hooks.isCancelled(hooks.ctx)) {
            child.kill(io);
            return error.Interrupted;
        }
        // The real cause is in file_reader.err; the caller only needs "failed".
        const n = file_reader.interface.readSliceShort(&chunk) catch return error.FfmpegFailed;
        if (n == 0) break;

        // ffmpeg separates progress updates with '\r', messages with '\n'.
        for (chunk[0..n]) |c| {
            if (c == '\r' or c == '\n') {
                state.handle(line[0..line_len]);
                line_len = 0;
            } else if (line_len < line.len) {
                line[line_len] = c;
                line_len += 1;
            }
        }
    }
    if (line_len > 0) state.handle(line[0..line_len]);

    const term = try child.wait(io);
    switch (term) {
        .exited => |code| if (code != 0) return error.FfmpegFailed,
        else => return error.FfmpegFailed,
    }
}

/// Runs every fallback attempt from `build` until one succeeds.
/// On failure the partial output is removed; on cancel the error is Interrupted.
fn runAttempts(
    io: Io,
    exe: []const u8,
    out: []const u8,
    hooks: Hooks,
    comptime build: anytype,
    build_ctx: anytype,
) Error!void {
    var args: Args = .{};
    var last_err: Error = error.FfmpegFailed;
    var attempt: usize = 0;

    while (build(&args, build_ctx, attempt)) : (attempt += 1) {
        runOnce(io, exe, args.slice(), hooks) catch |err| switch (err) {
            error.Interrupted => {
                hooks.removeFile(hooks.ctx, out);
                return error.Interrupted;
            },
            else => {
                last_err = err;
                hooks.removeFile(hooks.ctx, out);
                continue;
            },
        };
        return;
    }
    return last_err;
}

const ConvertCtx = struct { src: []const u8, out: []const u8, fmt: []const u8 };

fn buildConvert(args: *Args, ctx: ConvertCtx, attempt: usize) bool {
    return convertArgs(args, ctx.src, ctx.out, ctx.fmt, attempt);
}

const MergeCtx = struct { video: []const u8, audio: []const u8, out: []const u8, fmt: []const u8 };

fn buildMerge(args: *Args, ctx: MergeCtx, attempt: usize) bool {
    return mergeArgs(args, ctx.video, ctx.audio, ctx.out, ctx.fmt, attempt);
}

/// Converts `src` to `out` (a unique path chosen by the caller).
/// Mirrors convert_media() + run_attempts().
pub fn convertFile(io: Io, exe: []const u8, src: []const u8, out: []const u8, fmt: []const u8, hooks: Hooks) Error!void {
    return runAttempts(io, exe, out, hooks, buildConvert, ConvertCtx{ .src = src, .out = out, .fmt = fmt });
}

/// Merges `video` with the external audio `audio` into `out` (unique path from caller).
/// Mirrors merge_media() + run_attempts().
pub fn mergeFile(io: Io, exe: []const u8, video: []const u8, audio: []const u8, out: []const u8, fmt: []const u8, hooks: Hooks) Error!void {
    return runAttempts(io, exe, out, hooks, buildMerge, MergeCtx{ .video = video, .audio = audio, .out = out, .fmt = fmt });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test "audio attempt 0 matches python" {
    var args: Args = .{};
    try std.testing.expect(convertArgs(&args, "in.wav", "out.mp3", "mp3", 0));
    const expected = [_][]const u8{
        "-i", "in.wav", "-vn", "-map", "0:a:0", "-c:a", "libmp3lame", "-b:a", "192k", "out.mp3",
    };
    try std.testing.expectEqual(expected.len, args.slice().len);
    for (expected, args.slice()) |e, a| try std.testing.expectEqualStrings(e, a);
}

test "mp4 attempt 0 is stream copy with faststart" {
    var args: Args = .{};
    try std.testing.expect(convertArgs(&args, "in.mkv", "out.mp4", "mp4", 0));
    const s = args.slice();
    try std.testing.expectEqualStrings("-c", s[7]);
    try std.testing.expectEqualStrings("copy", s[8]);
    try std.testing.expectEqualStrings("-movflags", s[9]);
    try std.testing.expectEqualStrings("out.mp4", s[s.len - 1]);
}

test "mkv has no faststart flag" {
    var args: Args = .{};
    try std.testing.expect(convertArgs(&args, "in.mp4", "out.mkv", "mkv", 0));
    for (args.slice()) |a| try std.testing.expect(!std.mem.eql(u8, a, "-movflags"));
}

test "no convert attempt beyond the second" {
    var args: Args = .{};
    try std.testing.expect(!convertArgs(&args, "a", "b", "mp3", 2));
}

test "merge attempt 0 maps video and audio and copies streams" {
    var args: Args = .{};
    try std.testing.expect(mergeArgs(&args, "v.mp4", "a.m4a", "out_merged.mp4", "mp4", 0));
    const s = args.slice();
    try std.testing.expectEqualStrings("-i", s[0]);
    try std.testing.expectEqualStrings("v.mp4", s[1]);
    try std.testing.expectEqualStrings("a.m4a", s[3]);
    try std.testing.expectEqualStrings("1:a:0", s[7]);
    try std.testing.expectEqualStrings("-shortest", s[8]);
    try std.testing.expectEqualStrings("copy", s[10]);
    try std.testing.expectEqualStrings("out_merged.mp4", s[s.len - 1]);
}

test "merge has exactly three attempts" {
    var args: Args = .{};
    try std.testing.expect(mergeArgs(&args, "v", "a", "o", "mkv", 2));
    try std.testing.expect(!mergeArgs(&args, "v", "a", "o", "mkv", 3));
}

test "parseHms and findTime" {
    try std.testing.expectApproxEqAbs(@as(f64, 90.5), parseHms("0:01:30.5").?, 1e-9);
    const dur = findTime("  Duration: 00:01:02.50, start: 0.0", "Duration:").?;
    try std.testing.expectApproxEqAbs(@as(f64, 62.5), dur, 1e-9);
    const cur = findTime("size=  100kB time=00:00:03.12 bitrate=", "time=").?;
    try std.testing.expectApproxEqAbs(@as(f64, 3.12), cur, 1e-9);
}

test "candidateName matches unique_path numbering" {
    var buf: [64]u8 = undefined;
    try std.testing.expectEqualStrings("clip.mp4", try candidateName(&buf, "clip", "mp4", 0));
    try std.testing.expectEqualStrings("clip (2).mp4", try candidateName(&buf, "clip", "mp4", 2));
    try std.testing.expectEqualStrings("clip_merged.mkv", try candidateName(&buf, "clip_merged", "mkv", 0));
}

test "percent is capped at 99" {
    try std.testing.expectEqual(@as(u8, 50), percent(5, 10));
    try std.testing.expectEqual(@as(u8, 99), percent(10, 10));
    try std.testing.expectEqual(@as(u8, 0), percent(1, 0));
}

const Recorder = struct {
    last: u8 = 0,
    cancel: bool = false,
    removed: usize = 0,

    fn onProgress(ctx: *anyopaque, pct: u8) void {
        const self: *Recorder = @ptrCast(@alignCast(ctx));
        self.last = pct;
    }
    fn isCancelled(ctx: *anyopaque) bool {
        const self: *Recorder = @ptrCast(@alignCast(ctx));
        return self.cancel;
    }
    fn removeFile(ctx: *anyopaque, path: []const u8) void {
        _ = path;
        const self: *Recorder = @ptrCast(@alignCast(ctx));
        self.removed += 1;
    }
};

test "LineState reports percent from Duration and time lines" {
    var rec = Recorder{};
    var state = LineState{ .hooks = .{
        .ctx = &rec,
        .onProgress = Recorder.onProgress,
        .isCancelled = Recorder.isCancelled,
        .removeFile = Recorder.removeFile,
    } };

    state.handle("  Duration: 00:00:10.00, start: 0.0");
    state.handle("size=10kB time=00:00:05.00 bitrate=");
    try std.testing.expectEqual(@as(u8, 50), rec.last);

    state.handle("size=20kB time=00:00:12.00 bitrate=");
    try std.testing.expectEqual(@as(u8, 99), rec.last);
}
