const std = @import("std");
const io_global = @import("io_global.zig");

// yt-dlp downloader, same arguments as main_1.zig.
//
// IMPORTANT: this runs `python3 yt-dlp`, which needs python3 and yt-dlp on the
// device. A normal Android app has neither by default. Either bundle them, or
// pass `python` and a yt-dlp path through the exe/script parameters.
//
// Return codes:
//    0  success; file path written to out_path (NUL-terminated)
//   -1  invalid arguments or output buffer too small
//   -2  process failed to start
//   -3  yt-dlp exited with an error
//   -4  yt-dlp printed no file path

const path_capacity = 4096;

export fn folyan_download(
    python: [*:0]const u8,
    yt_dlp_script: [*:0]const u8,
    url: [*:0]const u8,
    height: u32,
    codec: [*:0]const u8,
    ai: bool,
    dir: [*:0]const u8,
    out_path: [*]u8,
    out_cap: usize,
) c_int {
    const url_s = std.mem.span(url);
    const codec_s = std.mem.span(codec);
    const dir_s = std.mem.span(dir);
    if (url_s.len == 0 or codec_s.len == 0 or dir_s.len == 0) return -1;

    const sr: []const u8 = if (ai) "" else "[format_id!*=-sr]";
    var fmt_buf: [512]u8 = undefined;
    const fmt = std.fmt.bufPrint(
        &fmt_buf,
        "bestvideo[vcodec*={s}][height<={d}]{s}+bestaudio[format_id!*=-sr]/best[height<={d}]{s}",
        .{ codec_s, height, sr, height, sr },
    ) catch return -1;

    const argv = [_][]const u8{
        std.mem.span(python),
        std.mem.span(yt_dlp_script),
        "-f",
        fmt,
        "-P",
        dir_s,
        "-o",
        "%(title)s.%(ext)s",
        "--merge-output-format",
        "mkv",
        "--print",
        "after_move:filepath",
        url_s,
    };

    const gpa = std.heap.page_allocator;
    const io = io_global.get();
    const result = std.process.run(gpa, io, .{ .argv = &argv }) catch return -2;
    defer gpa.free(result.stdout);
    defer gpa.free(result.stderr);

    switch (result.term) {
        .exited => |code| if (code != 0) return -3,
        else => return -3,
    }

    // The last non-empty stdout line is the file path yt-dlp printed.
    var it = std.mem.splitScalar(u8, result.stdout, '\n');
    var last: []const u8 = "";
    while (it.next()) |line| {
        const t = std.mem.trim(u8, line, " \r\t");
        if (t.len > 0) last = t;
    }
    if (last.len == 0) return -4;
    if (last.len + 1 > out_cap or last.len + 1 > path_capacity) return -1;

    @memcpy(out_path[0..last.len], last);
    out_path[last.len] = 0;
    return 0;
}

export fn folyan_init() void {
    _ = io_global.get();
}

export fn folyan_deinit() void {
    io_global.deinit();
}
