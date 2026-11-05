const std = @import("std");

// One Io instance per library, created on first use. It must outlive every
// call that uses it, so it lives in a global.
// Not thread-safe on the very first call: call folyan_init() from Dart before
// concurrent use (each library exports it).

var threaded: std.Io.Threaded = undefined;
var threaded_ready = false;

pub fn get() std.Io {
    if (!threaded_ready) {
        threaded = std.Io.Threaded.init(std.heap.page_allocator, .{});
        threaded_ready = true;
    }
    return threaded.io();
}

pub fn deinit() void {
    if (threaded_ready) {
        threaded.deinit();
        threaded_ready = false;
    }
}
