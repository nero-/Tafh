//! Cross-language regression: a container produced by the retired Zig
//! implementation (`zig-src/src/bridge/tafh_backup.zig`) must still decrypt.
//!
//! The fixture was generated once from the Zig code with a fixed salt/IV and
//! the passphrase "correct horse" (UTF-16BE), so it pins wire compatibility
//! independent of this crate's own encryptor.

fn hex_to_bytes(hex: &str) -> Vec<u8> {
    (0..hex.len() / 2)
        .map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).expect("valid hex"))
        .collect()
}

fn utf16be(text: &str) -> Vec<u8> {
    text.encode_utf16().flat_map(|unit| unit.to_be_bytes()).collect()
}

const ZIG_CONTAINER_HEX: &str = "4348424b0000000100000001000334500000000100000010000102030405060708090a0b0c0d0e0f0000000c101112131415161718191a1b00000023033f6b37501e1f16a9254d6e137b003c5ab6813b1c587b66dbe20c5950ac41ae32edf4";

#[test]
fn decrypts_container_written_by_the_zig_implementation() {
    let container = hex_to_bytes(ZIG_CONTAINER_HEX);
    let plaintext = tafh_backup::container::decrypt(&container, &utf16be("correct horse"), b"correct horse")
        .expect("zig container must decrypt");
    assert_eq!(plaintext, b"hello chuchu backup");
}

#[test]
fn rejects_the_zig_container_with_a_wrong_passphrase() {
    let container = hex_to_bytes(ZIG_CONTAINER_HEX);
    assert!(tafh_backup::container::decrypt(&container, &utf16be("wrong horse"), b"wrong horse").is_err());
}

/// The reciprocal direction: a container produced by this crate with the same
/// fixed salt/IV must decrypt under the retired Zig implementation. The
/// ciphertext below was confirmed by running that Zig code (recovered from
/// `git show <parent>:zig-src/src/bridge/tafh_backup.zig`) against it.
#[test]
fn rust_container_matches_the_zig_ciphertext_for_fixed_nonce() {
    let salt = [0u8, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
    let iv = [16u8, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27];
    let container =
        tafh_backup::container::encrypt_with_nonce(b"hello chuchu backup", &utf16be("correct horse"), salt, iv)
            .expect("encrypt");
    let hex: String = container.iter().map(|byte| format!("{byte:02x}")).collect();
    assert_eq!(
        hex,
        "4348424b0000000100000001000334500000000100000010000102030405060708090a0b0c0d0e0f\
         0000000c101112131415161718191a1b000000230cf1d22c90c16f0d22d1f3efe383345b03daab8cd87806f2cf\
         721393bbb32645bf2978"
            .replace(['\n', ' '], "")
    );
    // And it round-trips through this crate's own decryptor.
    let plaintext = tafh_backup::container::decrypt(&container, &utf16be("correct horse"), b"correct horse")
        .expect("decrypt");
    assert_eq!(plaintext, b"hello chuchu backup");
}
