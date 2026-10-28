const std = @import("std");
const builtin = @import("builtin");
const ndk = @import("src/ndk.zig");

const default_build_targets: []const std.Target.Query = &.{
    .{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
    .{ .cpu_arch = .arm, .os_tag = .linux, .abi = .androideabi, .android_api_level = 24 },
    .{ .cpu_arch = .x86, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
    .{ .cpu_arch = .x86_64, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
};

fn resolveBuildTargets(b: *std.Build) []const std.Target.Query {
    const maybe_target = b.option(
        []const u8,
        "target",
        "Android target triple (default: all supported ABIs)",
    ) orelse return default_build_targets;

    var query = std.Target.Query.parse(.{ .arch_os_abi = maybe_target }) catch |err| {
        std.debug.panic("invalid -Dtarget '{s}': {s}", .{ maybe_target, @errorName(err) });
    };
    if (query.android_api_level == null) query.android_api_level = 24;

    const targets = b.allocator.alloc(std.Target.Query, 1) catch @panic("OOM");
    targets[0] = query;
    return targets;
}

fn ndkPrebuiltTag() []const u8 {
    const os_part = switch (builtin.os.tag) {
        .macos => "darwin",
        .linux => "linux",
        .windows => "windows",
        else => @panic("Unsupported host OS for Android NDK prebuilt toolchain"),
    };

    // NDK only provides x86_64 prebuilt tools for macOS (works on Apple Silicon via Rosetta 2)
    const arch_part = switch (builtin.cpu.arch) {
        .x86_64 => "x86_64",
        .aarch64 => if (builtin.os.tag == .macos) "x86_64" else "aarch64",
        else => @panic("Unsupported host architecture for Android NDK prebuilt toolchain"),
    };

    return std.fmt.comptimePrint("{s}-{s}", .{ os_part, arch_part });
}

/// Android NDK root from the environment. Both spellings are honored:
/// `ANDROID_NDK_HOME` points at a versioned NDK directory, while
/// `ANDROID_NDK_ROOT` is set by some toolchains to the parent `ndk/` directory
/// (`resolveNdkHome` normalizes that case).
fn ndkRootFromEnv(b: *std.Build) ?[]const u8 {
    return b.graph.env_map.get("ANDROID_NDK_HOME") orelse
        b.graph.env_map.get("ANDROID_NDK_ROOT");
}

/// Target for host-side Zig tests. Linux tests link musl instead of the host
/// glibc: recent glibc toolchains (e.g. GCC 16 crt1.o) carry `.sframe`
/// relocations that Zig 0.15.2's linker rejects, so a self-contained musl test
/// binary is both portable and linkable.
fn testTarget(b: *std.Build) std.Build.ResolvedTarget {
    if (builtin.os.tag == .linux) {
        return b.resolveTargetQuery(.{
            .cpu_arch = builtin.cpu.arch,
            .os_tag = .linux,
            .abi = .musl,
        });
    }
    return b.graph.host;
}

fn resolveNdkHome(b: *std.Build, ndk_root: []const u8) []const u8 {
    if (ndk_root.len == 0) return ndk_root;

    const toolchains_path = b.pathJoin(&.{ ndk_root, "toolchains", "llvm" });
    std.fs.cwd().access(toolchains_path, .{}) catch {
        var dir = std.fs.cwd().openDir(ndk_root, .{ .iterate = true }) catch return ndk_root;
        defer dir.close();

        var iter = dir.iterate();
        while (iter.next() catch null) |entry| {
            if (entry.kind != .directory) continue;
            return b.pathJoin(&.{ ndk_root, entry.name });
        }
        return ndk_root;
    };

    return ndk_root;
}

fn buildNativeLibrary(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    ndk_root: []const u8,
) *std.Build.Step.Compile {
    const ghostty_dep = b.dependency("ghostty", .{
        .target = target,
        .optimize = optimize,
        .simd = false,
    });
    const zigimg_dep = b.dependency("zigimg", .{
        .target = target,
        .optimize = optimize,
    });
    const mosh_dep = b.dependency("mosh", .{
        .target = target,
        .optimize = optimize,
    });

    // `ndk_root` is validated by the caller (see `build`); the NDK is required
    // for both the sysroot headers and the per-ABI libc configuration.
    const ndk_home = resolveNdkHome(b, ndk_root);

    const android_target = ndk.getAndroidTriple(target.result) catch {
        std.debug.panic("target must be Android", .{});
    };
    std.debug.assert(target.result.os.tag == .linux);
    const android_api_version: u32 = target.result.os.version_range.linux.android;

    const ndk_sysroot = b.pathJoin(&.{
        ndk_home,
        "toolchains",
        "llvm",
        "prebuilt",
        ndkPrebuiltTag(),
        "sysroot",
    });

    const libc_config = ndk.createLibC(
        b,
        android_target,
        android_api_version,
        ndk_sysroot,
    );

    const include_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "include" });
    const target_include_dir = b.pathJoin(&.{ include_dir, android_target });

    // Build libssh2 (configured to use openssl-zig)
    const libssh2_dep = b.dependency("libssh2", .{
        .target = target,
        .optimize = optimize,
        .libc_file = libc_config,
    });

    const libssh2 = libssh2_dep.artifact("ssh2");

    // Get upstream libssh2 for include paths
    const libssh2_upstream = libssh2_dep.builder.dependency("libssh2_upstream", .{
        .target = target,
        .optimize = optimize,
    });

    const root_module = b.createModule(.{
        .root_source_file = b.path("src/bridge/zignal_png.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = true,
        .unwind_tables = .none,
        .omit_frame_pointer = true,
    });
    root_module.addIncludePath(b.path("src/bridge"));
    root_module.addIncludePath(libssh2_upstream.path("include"));
    root_module.addImport("ghostty-vt", ghostty_dep.module("ghostty-vt"));
    root_module.addImport("zigimg", zigimg_dep.module("zigimg"));

    root_module.addImport("mosh", mosh_dep.module("mosh"));

    const lib = b.addLibrary(.{
        .linkage = .dynamic,
        .name = "chuchu_jni",
        .root_module = root_module,
    });
    lib.link_function_sections = true;
    lib.link_data_sections = true;
    lib.link_gc_sections = true;
    lib.link_eh_frame_hdr = false;
    lib.lto = .thin;
    root_module.strip = true;
    root_module.unwind_tables = .none;
    root_module.omit_frame_pointer = true;

    lib.addIncludePath(.{ .cwd_relative = include_dir });
    lib.addIncludePath(.{ .cwd_relative = target_include_dir });

    const api_dir = b.fmt("{d}", .{android_api_version});
    const lib_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "lib", android_target, api_dir });
    lib.addLibraryPath(.{ .cwd_relative = lib_dir });

    lib.setLibCFile(libc_config);
    lib.linkLibrary(libssh2);
    lib.linkSystemLibrary("log");
    lib.linkSystemLibrary("z");
    lib.linkLibC();
    lib.version_script = b.path("src/bridge/version-script.map");

    b.installArtifact(lib);
    return lib;
}

/// Bridge sources that carry host-runnable `test` blocks and compile without
/// Android headers. Modules that pull in `jni.h`, ghostty, mosh or libssh2 are
/// deliberately excluded: they only build for an Android target, and including
/// them here would make `zig build test` require an NDK.
///
/// The CHBK crypto vectors that used to live in `src/bridge/chuchu_backup.zig`
/// moved to `rust/chuchu-backup`; a new bridge source with test blocks has to be
/// added here explicitly.
const host_test_modules = [_][]const u8{
    "src/bridge/ipc.zig",
};

fn buildSourceExists(b: *std.Build, path: []const u8) bool {
    b.build_root.handle.access(path, .{}) catch return false;
    return true;
}

/// Generates a host-side test root importing every host-testable bridge module.
/// The sources are copied into the generated directory because Zig only executes
/// tests declared in the *root module's* own file tree: a file reached through a
/// named module import is a separate module whose tests never run.
fn addBridgeTests(b: *std.Build) *std.Build.Step {
    const generated = b.addWriteFiles();

    var root_source: std.ArrayListUnmanaged(u8) = .empty;
    const writer = root_source.writer(b.allocator);
    writer.writeAll("const std = @import(\"std\");\n\ntest {\n") catch @panic("OOM");

    for (host_test_modules) |path| {
        if (!buildSourceExists(b, path)) {
            return &b.addFail(b.fmt(
                "missing required bridge test source '{s}': update host_test_modules in build.zig",
                .{path},
            )).step;
        }
        appendTestImport(b, generated, writer, path);
    }
    writer.writeAll("}\n") catch @panic("OOM");

    const test_module = b.createModule(.{
        .root_source_file = generated.add("bridge_tests.zig", root_source.items),
        .target = testTarget(b),
        .optimize = .Debug,
    });

    const tests = b.addTest(.{ .root_module = test_module });
    return &b.addRunArtifact(tests).step;
}

fn appendTestImport(
    b: *std.Build,
    generated: *std.Build.Step.WriteFile,
    writer: std.ArrayListUnmanaged(u8).Writer,
    path: []const u8,
) void {
    const base_name = std.fs.path.basename(path);
    _ = generated.addCopyFile(b.path(path), base_name);
    writer.print("    std.testing.refAllDeclsRecursive(@import(\"{s}\"));\n", .{base_name}) catch @panic("OOM");
}

pub fn build(b: *std.Build) void {
    const optimize = b.standardOptimizeOption(.{});
    const build_targets = resolveBuildTargets(b);
    const ndk_root = ndkRootFromEnv(b);

    const native_step = b.step("native", "Build native JNI library");
    const jni_step = b.step("jni", "Build native library and copy to jniLibs");
    const openssl_test_step = b.step("test-openssl", "Test building OpenSSL for Android aarch64");

    // Failing step reused by every Android target: the NDK is mandatory, and a
    // silent no-op here would let `zig build jni` "succeed" while shipping no
    // libchuchu_jni.so.
    const missing_ndk = b.addFail(
        "Android NDK not found: set ANDROID_NDK_HOME (preferred) or ANDROID_NDK_ROOT to an NDK installation before building native targets",
    );

    if (ndk_root) |root| {
        for (build_targets) |target_query| {
            const resolved_target = b.resolveTargetQuery(target_query);
            const native_lib = buildNativeLibrary(b, resolved_target, optimize, root);
            native_step.dependOn(&native_lib.step);

            const abi_name = ndk.getOutputDir(resolved_target.result) catch unreachable;
            const jni_lib_dir = b.fmt("../android/app/src/main/jniLibs/{s}", .{abi_name});

            const mkdir_jni_libs = b.addSystemCommand(&.{ "mkdir", "-p", jni_lib_dir });
            mkdir_jni_libs.step.dependOn(&native_lib.step);

            const copy_to_jni_libs = b.addSystemCommand(&.{"cp"});
            copy_to_jni_libs.step.dependOn(&mkdir_jni_libs.step);
            copy_to_jni_libs.addFileArg(native_lib.getEmittedBin());
            _ = copy_to_jni_libs.addArg(b.fmt("{s}/libchuchu_jni.so", .{jni_lib_dir}));

            jni_step.dependOn(&copy_to_jni_libs.step);
        }

        // Test: try building openssl for aarch64 android
        const openssl_target = b.resolveTargetQuery(.{
            .cpu_arch = .aarch64,
            .os_tag = .linux,
            .abi = .android,
            .android_api_level = 24,
        });
        const openssl_dep = b.dependency("openssl", .{
            .target = openssl_target,
            .optimize = .ReleaseSmall,
        });
        const crypto = openssl_dep.artifact("crypto");
        const ssl = openssl_dep.artifact("ssl");
        openssl_test_step.dependOn(&crypto.step);
        openssl_test_step.dependOn(&ssl.step);
    } else {
        native_step.dependOn(&missing_ndk.step);
        jni_step.dependOn(&missing_ndk.step);
        openssl_test_step.dependOn(&missing_ndk.step);
    }

    // Host-side tests over the bridge modules listed in `host_test_modules`.
    // They build for the host, so no Android toolchain or NDK is required.
    const test_step = b.step("test", "Run host-side Zig tests for the JNI bridge");
    test_step.dependOn(addBridgeTests(b));

    // `zig build fmt` verifies formatting (CI gate); `zig build fmt-write`
    // rewrites files in place.
    const fmt_check = b.addFmt(.{
        .paths = &.{ "src", "build.zig", "build.zig.zon" },
        .check = true,
    });
    const fmt_step = b.step("fmt", "Verify Zig formatting");
    fmt_step.dependOn(&fmt_check.step);

    const fmt_write = b.addFmt(.{ .paths = &.{ "src", "build.zig", "build.zig.zon" } });
    const fmt_write_step = b.step("fmt-write", "Format Zig sources in place");
    fmt_write_step.dependOn(&fmt_write.step);
}
