//! Frozen-format parity checks for the CHBK container.
//!
//! The golden bytes below were produced by the same construction the previous Zig
//! implementation used (PBKDF2-HMAC-SHA1 over UTF-16BE passphrase bytes, AES-256-GCM
//! with the 56-byte metadata block as AAD), so any layout drift fails loudly.

use tafh_backup::container::{
    decrypt, encrypt_with_nonce, CIPHER_ID_AES_256_GCM, CONTAINER_MAGIC, FORMAT_VERSION,
    IV_SIZE, KDF_ID_PBKDF2_HMAC_SHA1, KDF_ITERATIONS, METADATA_LEN, SALT_SIZE, TAG_SIZE,
};

const GOLDEN_PLAINTEXT: &[u8] = b"chuchu-backup-parity";
const GOLDEN_PASSPHRASE_UTF16BE: [u8; 22] = [
    0x00, 0x70, 0x00, 0x61, 0x00, 0x72, 0x00, 0x69, 0x00, 0x74, 0x00, 0x79, 0x00, 0x2d, 0x00, 0x70,
    0x00, 0x61, 0x00, 0x73, 0x00, 0x73, // "parity-pass"
];
const GOLDEN_SALT: [u8; SALT_SIZE] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
const GOLDEN_IV: [u8; IV_SIZE] = [16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27];
const GOLDEN_CONTAINER_HEX: &str = concat!(
    "4348424b00000001000000010003345000000001000000100001020304050607",
    "08090a0b0c0d0e0f0000000c101112131415161718191a1b000000242f6065b8",
    "4480e3f78bf0592210cb6f8fa30f791d9bc6c7c1ca78e2eeade2f9baaceb2e2a",
);

fn golden_bytes() -> Vec<u8> {
    let digits = GOLDEN_CONTAINER_HEX.as_bytes();
    assert_eq!(digits.len() % 2, 0, "golden hex must have whole bytes");
    digits
        .chunks(2)
        .map(|pair| {
            let hi = (pair[0] as char).to_digit(16).expect("hex digit");
            let lo = (pair[1] as char).to_digit(16).expect("hex digit");
            ((hi as u8) << 4) | lo as u8
        })
        .collect()
}

fn read_u32_be(bytes: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

#[test]
fn golden_container_matches_hard_coded_bytes() {
    let produced = encrypt_with_nonce(
        GOLDEN_PLAINTEXT,
        &GOLDEN_PASSPHRASE_UTF16BE,
        GOLDEN_SALT,
        GOLDEN_IV,
    )
    .expect("encrypt");

    assert_eq!(produced, golden_bytes());
}

#[test]
fn golden_container_layout_positions() {
    let container = golden_bytes();

    assert_eq!(container.len(), METADATA_LEN + 4 + GOLDEN_PLAINTEXT.len() + TAG_SIZE);
    assert_eq!(METADATA_LEN, 56);

    // Fixed-width header fields, big-endian.
    assert_eq!(read_u32_be(&container, 0), CONTAINER_MAGIC);
    assert_eq!(&container[0..4], b"CHBK");
    assert_eq!(read_u32_be(&container, 4), FORMAT_VERSION);
    assert_eq!(read_u32_be(&container, 8), KDF_ID_PBKDF2_HMAC_SHA1);
    assert_eq!(read_u32_be(&container, 12), KDF_ITERATIONS);
    assert_eq!(read_u32_be(&container, 16), CIPHER_ID_AES_256_GCM);

    // Length-prefixed salt.
    assert_eq!(read_u32_be(&container, 20), SALT_SIZE as u32);
    assert_eq!(&container[24..40], &GOLDEN_SALT);

    // Length-prefixed iv, immediately after the salt.
    assert_eq!(read_u32_be(&container, 40), IV_SIZE as u32);
    assert_eq!(&container[44..56], &GOLDEN_IV);

    // Payload length covers ciphertext plus the 16-byte GCM tag.
    let declared = read_u32_be(&container, 56) as usize;
    assert_eq!(declared, GOLDEN_PLAINTEXT.len() + TAG_SIZE);
    assert_eq!(declared, container.len() - 60);

    // Ciphertext is not the plaintext, and the trailing tag is 16 bytes.
    let ciphertext = &container[60..60 + GOLDEN_PLAINTEXT.len()];
    let tag = &container[container.len() - TAG_SIZE..];
    assert_eq!(tag.len(), TAG_SIZE);
    assert_ne!(ciphertext, GOLDEN_PLAINTEXT);
    assert_eq!(
        ciphertext,
        &[
            0x2f, 0x60, 0x65, 0xb8, 0x44, 0x80, 0xe3, 0xf7, 0x8b, 0xf0, 0x59, 0x22, 0x10, 0xcb,
            0x6f, 0x8f, 0xa3, 0x0f, 0x79, 0x1d,
        ]
    );
    assert_eq!(
        tag,
        &[
            0x9b, 0xc6, 0xc7, 0xc1, 0xca, 0x78, 0xe2, 0xee, 0xad, 0xe2, 0xf9, 0xba, 0xac, 0xeb,
            0x2e, 0x2a,
        ]
    );
}

#[test]
fn golden_container_decrypts_with_primary_passphrase() {
    let plaintext = decrypt(
        &golden_bytes(),
        &GOLDEN_PASSPHRASE_UTF16BE,
        b"legacy-should-not-be-used",
    )
    .expect("decrypt");

    assert_eq!(plaintext, GOLDEN_PLAINTEXT);
}

#[test]
fn golden_container_rejects_wrong_passphrase() {
    let wrong = tafh_backup::container::utf16be_bytes(
        &"wrong-pass".encode_utf16().collect::<Vec<u16>>(),
    );

    let err = decrypt(&golden_bytes(), &wrong, b"also-wrong").unwrap_err();
    assert_eq!(err, tafh_backup::container::Error::InvalidPassphrase);
    assert_eq!(err.status_byte(), 2);
}

#[test]
fn golden_container_is_authenticated_over_metadata() {
    // Flipping a metadata byte changes the AAD, so decryption fails even though
    // the key and tag are untouched.
    let mut tampered = golden_bytes();
    tampered[24] ^= 0x01;

    let err = decrypt(&tampered, &GOLDEN_PASSPHRASE_UTF16BE, b"").unwrap_err();
    assert_eq!(err, tafh_backup::container::Error::InvalidPassphrase);
}
