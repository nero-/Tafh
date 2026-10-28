# chuchu-backup (Rust)

CHBK encrypted backup container + JNI bridge, replacing the previous Zig bridge
module. Exports `nativeEncrypt`/`nativeDecrypt` for `NativeBackupBridge`, loaded
as `libchuchu_backup.so`.

## Build
```sh
cargo install cargo-ndk   # once
cd rust && rustup target add aarch64-linux-android x86_64-linux-android
ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/29.0.13113456 cargo ndk \
  -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build --release -p chuchu-backup
cargo test   # host tests
```

## Frozen format

Layout, PBKDF2-HMAC-SHA1 (210000), AES-256-GCM over the 56-byte metadata prefix
as AAD, and the UTF-16BE passphrase encoding are **frozen**: existing backups must
keep decrypting; `tests/parity.rs` pins golden bytes.
