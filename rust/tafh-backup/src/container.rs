//! CHBK encrypted backup container.
//!
//! The byte layout is **frozen**: containers written by the previous Zig
//! implementation must decrypt here, and vice versa. Nothing in this module may
//! change the wire format.
//!
//! ```text
//! offset  size  field
//! 0       4     magic            0x4348424B ("CHBK")
//! 4       4     version          1
//! 8       4     kdf id           1 (PBKDF2-HMAC-SHA1)
//! 12      4     iterations       210000
//! 16      4     cipher id        1 (AES-256-GCM)
//! 20      4     salt length      16
//! 24      16    salt
//! 40      4     iv length        12
//! 44      12    iv
//! 56      4     ciphertext len   plaintext len + 16 (GCM tag)
//! 60      n     ciphertext
//! 60+n    16    GCM tag
//! ```
//!
//! All integers are big-endian. The 56 metadata bytes from `magic` through the
//! iv are the GCM additional authenticated data.

use aes_gcm::aead::rand_core::RngCore;
use aes_gcm::aead::{AeadInPlace, KeyInit, OsRng};
use aes_gcm::{Aes256Gcm, Nonce, Tag};
use hmac::Hmac;
use pbkdf2::pbkdf2;
use sha1::Sha1;
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

pub const CONTAINER_MAGIC: u32 = 0x4348_424B;
pub const FORMAT_VERSION: u32 = 1;
pub const KDF_ID_PBKDF2_HMAC_SHA1: u32 = 1;
pub const KDF_ITERATIONS: u32 = 210_000;
pub const CIPHER_ID_AES_256_GCM: u32 = 1;
pub const SALT_SIZE: usize = 16;
pub const IV_SIZE: usize = 12;
pub const TAG_SIZE: usize = 16;
pub const KEY_SIZE: usize = 32;
pub const MAX_BACKUP_SIZE: usize = 32 * 1024 * 1024;
/// Size of the authenticated metadata prefix (magic through iv).
pub const METADATA_LEN: usize = 5 * 4 + 4 + SALT_SIZE + 4 + IV_SIZE;

/// Container failure, mapped onto the status byte handed back to Java.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    /// Malformed container: bad magic/ids/lengths, truncation, trailing bytes.
    FormatError,
    /// Plaintext or container exceeds [`MAX_BACKUP_SIZE`].
    BackupTooLarge,
    /// The derived key did not authenticate the payload.
    InvalidPassphrase,
    /// A primitive failed unexpectedly.
    InternalError,
}

impl Error {
    /// Status byte expected by `NativeBackupBridge`.
    pub fn status_byte(self) -> u8 {
        match self {
            Error::FormatError | Error::BackupTooLarge => 1,
            Error::InvalidPassphrase => 2,
            Error::InternalError => 3,
        }
    }

    /// UTF-8 message returned to Java alongside a non-zero status.
    pub fn message(self) -> &'static str {
        match self {
            Error::FormatError => "Invalid backup file",
            Error::BackupTooLarge => "Backup file is too large",
            Error::InvalidPassphrase => "Invalid backup passphrase",
            Error::InternalError => "Native backup failure",
        }
    }
}

/// Encrypts `plaintext` with a freshly generated salt and iv.
///
/// `passphrase` must already be encoded the way Java supplies it: UTF-16BE code
/// units (see [`utf16be_bytes`]).
pub fn encrypt(plaintext: &[u8], passphrase: &[u8]) -> Result<Vec<u8>, Error> {
    let mut salt = [0u8; SALT_SIZE];
    let mut iv = [0u8; IV_SIZE];
    OsRng.fill_bytes(&mut salt);
    OsRng.fill_bytes(&mut iv);
    encrypt_with_nonce(plaintext, passphrase, salt, iv)
}

/// Encryption with caller-provided salt and iv.
///
/// Exists so tests can reproduce a container byte for byte; production callers
/// use [`encrypt`].
#[doc(hidden)]
pub fn encrypt_with_nonce(
    plaintext: &[u8],
    passphrase: &[u8],
    salt: [u8; SALT_SIZE],
    iv: [u8; IV_SIZE],
) -> Result<Vec<u8>, Error> {
    if plaintext.len() > MAX_BACKUP_SIZE {
        return Err(Error::BackupTooLarge);
    }
    // An empty passphrase cannot be keyed; the previous bridge reported a format
    // error for it and callers depend on that.
    if passphrase.is_empty() {
        return Err(Error::FormatError);
    }

    let ciphertext_size = plaintext.len() + TAG_SIZE;
    if ciphertext_size > MAX_BACKUP_SIZE {
        return Err(Error::BackupTooLarge);
    }
    let total_size = METADATA_LEN + 4 + ciphertext_size;
    if total_size > MAX_BACKUP_SIZE {
        return Err(Error::BackupTooLarge);
    }

    let metadata = build_metadata(salt, iv);
    let key = derive_key(passphrase, &salt)?;
    let cipher = Aes256Gcm::new_from_slice(&*key).map_err(|_| Error::InternalError)?;

    let mut buffer = Zeroizing::new(plaintext.to_vec());
    let tag = cipher
        .encrypt_in_place_detached(Nonce::from_slice(&iv), &metadata, &mut buffer[..])
        .map_err(|_| Error::InternalError)?;

    let mut out = Vec::with_capacity(total_size);
    out.extend_from_slice(&metadata);
    out.extend_from_slice(&(ciphertext_size as u32).to_be_bytes());
    out.extend_from_slice(&buffer[..]);
    out.extend_from_slice(&tag);
    Ok(out)
}

/// Decrypts `input`, retrying with `passphrase_legacy` when the primary key fails.
///
/// Containers written before the passphrase encoding was fixed to UTF-16BE used
/// the UTF-8 bytes of the same `char[]`; the retry keeps those readable.
pub fn decrypt(
    input: &[u8],
    passphrase_primary: &[u8],
    passphrase_legacy: &[u8],
) -> Result<Vec<u8>, Error> {
    match decrypt_with(input, passphrase_primary) {
        // An empty legacy encoding carries no key material, so retrying with it
        // would only replace the useful error with a format error.
        Err(Error::InvalidPassphrase) if !passphrase_legacy.is_empty() => {
            decrypt_with(input, passphrase_legacy)
        }
        other => other,
    }
}

fn decrypt_with(input: &[u8], passphrase: &[u8]) -> Result<Vec<u8>, Error> {
    if input.len() > MAX_BACKUP_SIZE {
        return Err(Error::BackupTooLarge);
    }
    if passphrase.is_empty() {
        return Err(Error::FormatError);
    }

    let mut reader = Reader::new(input);
    let metadata = read_metadata(&mut reader)?;

    let declared_len = reader.read_u32()?;
    if declared_len > i32::MAX as u32 {
        return Err(Error::FormatError);
    }
    let ciphertext_size = declared_len as usize;
    if ciphertext_size > MAX_BACKUP_SIZE {
        return Err(Error::BackupTooLarge);
    }

    let ciphertext_and_tag = reader.read_bytes(ciphertext_size)?;
    if !reader.is_eof() {
        return Err(Error::FormatError);
    }
    if ciphertext_and_tag.len() < TAG_SIZE {
        return Err(Error::FormatError);
    }

    let (ciphertext, tag) = ciphertext_and_tag.split_at(ciphertext_and_tag.len() - TAG_SIZE);

    let key = derive_key(passphrase, &metadata.salt)?;
    let cipher = Aes256Gcm::new_from_slice(&*key).map_err(|_| Error::InternalError)?;

    let mut buffer = Zeroizing::new(ciphertext.to_vec());
    let result = cipher.decrypt_in_place_detached(
        Nonce::from_slice(&metadata.iv),
        &metadata.encoded,
        &mut buffer[..],
        Tag::from_slice(tag),
    );
    match result {
        Ok(()) => Ok(std::mem::take(&mut *buffer)),
        Err(_) => Err(Error::InvalidPassphrase),
    }
}

/// PBKDF2-HMAC-SHA1 key derivation used by the container.
fn derive_key(passphrase: &[u8], salt: &[u8; SALT_SIZE]) -> Result<Zeroizing<[u8; KEY_SIZE]>, Error> {
    let mut key = Zeroizing::new([0u8; KEY_SIZE]);
    pbkdf2::<Hmac<Sha1>>(passphrase, salt, KDF_ITERATIONS, &mut *key)
        .map_err(|_| Error::InternalError)?;
    Ok(key)
}

fn build_metadata(salt: [u8; SALT_SIZE], iv: [u8; IV_SIZE]) -> [u8; METADATA_LEN] {
    let mut metadata = [0u8; METADATA_LEN];
    let mut offset = 0;

    for value in [
        CONTAINER_MAGIC,
        FORMAT_VERSION,
        KDF_ID_PBKDF2_HMAC_SHA1,
        KDF_ITERATIONS,
        CIPHER_ID_AES_256_GCM,
    ] {
        metadata[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
        offset += 4;
    }

    metadata[offset..offset + 4].copy_from_slice(&(SALT_SIZE as u32).to_be_bytes());
    offset += 4;
    metadata[offset..offset + SALT_SIZE].copy_from_slice(&salt);
    offset += SALT_SIZE;

    metadata[offset..offset + 4].copy_from_slice(&(IV_SIZE as u32).to_be_bytes());
    offset += 4;
    metadata[offset..offset + IV_SIZE].copy_from_slice(&iv);

    metadata
}

struct Metadata {
    salt: [u8; SALT_SIZE],
    iv: [u8; IV_SIZE],
    /// The raw prefix bytes, reused verbatim as GCM additional data.
    encoded: [u8; METADATA_LEN],
}

fn read_metadata(reader: &mut Reader<'_>) -> Result<Metadata, Error> {
    let mut encoded = [0u8; METADATA_LEN];
    encoded.copy_from_slice(reader.read_bytes(METADATA_LEN)?);

    let mut field = Reader::new(&encoded);
    ensure_field(field.read_u32()?, CONTAINER_MAGIC)?;
    ensure_field(field.read_u32()?, FORMAT_VERSION)?;
    ensure_field(field.read_u32()?, KDF_ID_PBKDF2_HMAC_SHA1)?;
    ensure_field(field.read_u32()?, KDF_ITERATIONS)?;
    ensure_field(field.read_u32()?, CIPHER_ID_AES_256_GCM)?;
    ensure_field(field.read_u32()?, SALT_SIZE as u32)?;

    let mut salt = [0u8; SALT_SIZE];
    salt.copy_from_slice(field.read_bytes(SALT_SIZE)?);

    ensure_field(field.read_u32()?, IV_SIZE as u32)?;

    let mut iv = [0u8; IV_SIZE];
    iv.copy_from_slice(field.read_bytes(IV_SIZE)?);

    Ok(Metadata { salt, iv, encoded })
}

/// Compares a header field against its required value in constant time.
fn ensure_field(actual: u32, expected: u32) -> Result<(), Error> {
    if bool::from(actual.ct_eq(&expected)) {
        Ok(())
    } else {
        Err(Error::FormatError)
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn read_u32(&mut self) -> Result<u32, Error> {
        let bytes = self.read_bytes(4)?;
        Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn read_bytes(&mut self, size: usize) -> Result<&'a [u8], Error> {
        if size > self.bytes.len() - self.offset {
            return Err(Error::FormatError);
        }
        let out = &self.bytes[self.offset..self.offset + size];
        self.offset += size;
        Ok(out)
    }

    fn is_eof(&self) -> bool {
        self.offset == self.bytes.len()
    }
}

/// Encodes Java `char[]` code units as UTF-16BE bytes, like the previous Zig
/// `readJCharArrayAsBigEndianBytes` helper.
pub fn utf16be_bytes(units: &[u16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(units.len() * 2);
    for unit in units {
        out.extend_from_slice(&unit.to_be_bytes());
    }
    out
}

/// Re-encodes Java `char[]` code units as UTF-8, like the legacy
/// `readJCharArrayAsUtf8` helper. Unpaired surrogates are rejected.
pub fn utf8_bytes(units: &[u16]) -> Option<Vec<u8>> {
    String::from_utf16(units).ok().map(String::into_bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SALT: [u8; SALT_SIZE] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
    const IV: [u8; IV_SIZE] = [16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27];

    fn utf16be(text: &str) -> Vec<u8> {
        utf16be_bytes(&text.encode_utf16().collect::<Vec<u16>>())
    }

    fn fixed_container(plaintext: &[u8], passphrase: &[u8]) -> Vec<u8> {
        encrypt_with_nonce(plaintext, passphrase, SALT, IV).expect("encrypt")
    }

    fn expect_format_error(result: Result<Vec<u8>, Error>) {
        assert_eq!(result.err(), Some(Error::FormatError));
    }

    #[test]
    fn round_trip_utf16be_passphrase() {
        let plaintext = b"compatibility-check";
        let passphrase = utf16be("awdsefrr");

        let container = fixed_container(plaintext, &passphrase);
        let decrypted = decrypt(&container, &passphrase, b"unused").expect("decrypt");
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn round_trip_empty_plaintext() {
        let passphrase = utf16be("secret");

        let container = fixed_container(b"", &passphrase);
        assert_eq!(container.len(), METADATA_LEN + 4 + TAG_SIZE);
        assert_eq!(decrypt(&container, &passphrase, b"").expect("decrypt"), b"");
    }

    #[test]
    fn random_salt_and_iv_differ_between_encryptions() {
        let passphrase = utf16be("same passphrase");
        let first = encrypt(b"payload", &passphrase).expect("encrypt");
        let second = encrypt(b"payload", &passphrase).expect("encrypt");

        assert_ne!(first, second);
        assert_eq!(decrypt(&first, &passphrase, b"").expect("decrypt"), b"payload");
        assert_eq!(decrypt(&second, &passphrase, b"").expect("decrypt"), b"payload");
    }

    #[test]
    fn wrong_passphrase_is_invalid_passphrase() {
        let container = fixed_container(b"payload", &utf16be("correct"));

        let err = decrypt(&container, &utf16be("wrong"), &utf16be("also wrong")).unwrap_err();
        assert_eq!(err, Error::InvalidPassphrase);
        assert_eq!(err.status_byte(), 2);
    }

    #[test]
    fn legacy_utf8_container_decrypts_via_fallback() {
        let plaintext = b"compatibility-check";
        let passphrase_utf8 = b"awdsefrr".to_vec();
        let passphrase_utf16be = utf16be("awdsefrr");

        let legacy = fixed_container(plaintext, &passphrase_utf8);
        let modern = fixed_container(plaintext, &passphrase_utf16be);

        assert_eq!(
            decrypt(&legacy, &passphrase_utf16be, &passphrase_utf8).expect("legacy decrypt"),
            plaintext
        );
        assert_eq!(
            decrypt(&modern, &passphrase_utf16be, &passphrase_utf8).expect("modern decrypt"),
            plaintext
        );
    }

    #[test]
    fn empty_passphrase_is_format_error() {
        let err = encrypt(b"payload", b"").unwrap_err();
        assert_eq!(err, Error::FormatError);
        assert_eq!(err.status_byte(), 1);

        let container = fixed_container(b"payload", &utf16be("secret"));
        let err = decrypt(&container, b"", utf16be("secret").as_slice()).unwrap_err();
        assert_eq!(err, Error::FormatError);
    }

    #[test]
    fn truncated_containers_are_format_errors() {
        let container = fixed_container(b"payload", &utf16be("secret"));
        let passphrase = utf16be("secret");

        for len in 0..container.len() {
            let truncated = &container[..len];
            assert_eq!(
                decrypt(truncated, &passphrase, b"").err(),
                Some(Error::FormatError),
                "length {len} should be a format error"
            );
        }
    }

    #[test]
    fn trailing_bytes_are_format_error() {
        let mut container = fixed_container(b"payload", &utf16be("secret"));
        let passphrase = utf16be("secret");

        container.push(0);
        expect_format_error(decrypt(&container, &passphrase, b""));
    }

    #[test]
    fn bad_header_fields_are_format_errors() {
        let passphrase = utf16be("secret");
        let cases: [(usize, [u8; 4], &str); 7] = [
            (0, [0x43, 0x48, 0x42, 0x4A], "magic"),
            (4, 2u32.to_be_bytes(), "version"),
            (8, 2u32.to_be_bytes(), "kdf id"),
            (12, 100u32.to_be_bytes(), "iterations"),
            (16, 2u32.to_be_bytes(), "cipher id"),
            (20, 15u32.to_be_bytes(), "salt length"),
            (40, 11u32.to_be_bytes(), "iv length"),
        ];

        for (offset, value, label) in cases {
            let mut container = fixed_container(b"payload", &passphrase);
            container[offset..offset + 4].copy_from_slice(&value);
            assert_eq!(
                decrypt(&container, &passphrase, b"").err(),
                Some(Error::FormatError),
                "{label} should be a format error"
            );
        }
    }

    #[test]
    fn declared_overlong_salt_and_iv_are_format_errors() {
        // A header that claims a longer salt/iv makes the metadata prefix
        // unreadable within METADATA_LEN bytes.
        let passphrase = utf16be("secret");

        let mut salt_len = fixed_container(b"payload", &passphrase);
        salt_len[20..24].copy_from_slice(&17u32.to_be_bytes());
        expect_format_error(decrypt(&salt_len, &passphrase, b""));

        let mut iv_len = fixed_container(b"payload", &passphrase);
        iv_len[40..44].copy_from_slice(&13u32.to_be_bytes());
        expect_format_error(decrypt(&iv_len, &passphrase, b""));
    }

    #[test]
    fn short_ciphertext_length_field_is_format_error() {
        let passphrase = utf16be("secret");
        let mut container = fixed_container(b"payload", &passphrase);

        // Declaring fewer bytes than the tag allows leaves no ciphertext.
        container[56..60].copy_from_slice(&8u32.to_be_bytes());
        expect_format_error(decrypt(&container, &passphrase, b""));
    }

    #[test]
    fn negative_ciphertext_length_is_format_error() {
        let passphrase = utf16be("secret");
        let mut container = fixed_container(b"payload", &passphrase);

        container[56..60].copy_from_slice(&0xFFFF_FFFFu32.to_be_bytes());
        expect_format_error(decrypt(&container, &passphrase, b""));
    }

    #[test]
    fn oversized_plaintext_is_rejected() {
        let passphrase = utf16be("secret");
        let too_large = vec![0u8; MAX_BACKUP_SIZE + 1];

        assert_eq!(encrypt(&too_large, &passphrase).err(), Some(Error::BackupTooLarge));

        // Exactly at the cap the ciphertext (plaintext + tag) overflows.
        let at_cap = vec![0u8; MAX_BACKUP_SIZE];
        assert_eq!(encrypt(&at_cap, &passphrase).err(), Some(Error::BackupTooLarge));
    }

    #[test]
    fn oversized_container_is_rejected() {
        let passphrase = utf16be("secret");
        let oversized = vec![0u8; MAX_BACKUP_SIZE + 1];

        let err = decrypt(&oversized, &passphrase, b"").unwrap_err();
        assert_eq!(err, Error::BackupTooLarge);
        assert_eq!(err.status_byte(), 1);
    }

    #[test]
    fn status_and_message_mapping_is_stable() {
        assert_eq!(Error::FormatError.status_byte(), 1);
        assert_eq!(Error::BackupTooLarge.status_byte(), 1);
        assert_eq!(Error::InvalidPassphrase.status_byte(), 2);
        assert_eq!(Error::InternalError.status_byte(), 3);
        assert_eq!(Error::FormatError.message(), "Invalid backup file");
        assert_eq!(Error::BackupTooLarge.message(), "Backup file is too large");
        assert_eq!(Error::InvalidPassphrase.message(), "Invalid backup passphrase");
        assert_eq!(Error::InternalError.message(), "Native backup failure");
    }

    #[test]
    fn utf16be_bytes_matches_java_char_encoding() {
        let units: Vec<u16> = "p\u{e1}ssw\u{1F510}rd".encode_utf16().collect();
        let expected = [
            0x00, 0x70, 0x00, 0xe1, 0x00, 0x73, 0x00, 0x73, 0x00, 0x77, 0xd8, 0x3d, 0xdd, 0x10,
            0x00, 0x72, 0x00, 0x64,
        ];

        assert_eq!(utf16be_bytes(&units), expected);
        assert_eq!(utf8_bytes(&units).as_deref(), Some("p\u{e1}ssw\u{1F510}rd".as_bytes()));
    }

    #[test]
    fn utf8_bytes_rejects_unpaired_surrogates() {
        // A lone high surrogate is not a character; the legacy UTF-8 encoding
        // cannot be produced for it.
        assert_eq!(utf8_bytes(&[0x0070, 0xd83d]), None);
        assert_eq!(utf8_bytes(&[0x0070, 0xdc00]), None);
        // A properly paired surrogate encodes to the surrogate-pair character.
        assert_eq!(
            utf8_bytes(&[0xd83d, 0xdc00]).as_deref(),
            Some("\u{1F400}".as_bytes())
        );
    }

    #[test]
    fn derive_key_matches_jvm_utf16be_vector_ascii() {
        let passphrase = utf16be("password");
        assert_eq!(passphrase.len(), 16);

        let key = derive_key(&passphrase, &SALT).expect("derive");
        let expected = [
            0xde, 0x7c, 0x12, 0x36, 0x4e, 0xea, 0xd1, 0x9a, 0xc7, 0x06, 0xb0, 0xa4, 0xb9, 0x88,
            0xf0, 0x60, 0x11, 0xa4, 0x82, 0x7d, 0x23, 0x66, 0x98, 0xfc, 0xd6, 0x5e, 0x57, 0x89,
            0xdb, 0x5a, 0x74, 0xc7,
        ];

        assert_eq!(&key[..], &expected[..]);
    }

    #[test]
    fn derive_key_matches_jvm_utf16be_vector_unicode() {
        let passphrase = utf16be("p\u{e1}ssw\u{1F510}rd");
        assert_eq!(
            passphrase,
            [0x00, 0x70, 0x00, 0xe1, 0x00, 0x73, 0x00, 0x73, 0x00, 0x77, 0xd8, 0x3d, 0xdd, 0x10, 0x00, 0x72, 0x00, 0x64]
        );

        let key = derive_key(&passphrase, &SALT).expect("derive");
        let expected = [
            0x7e, 0x67, 0xf2, 0xf5, 0x11, 0x6a, 0xba, 0x42, 0xe3, 0x47, 0xcf, 0x7e, 0x08, 0x95,
            0x21, 0x46, 0x86, 0xb4, 0xe2, 0x12, 0xc0, 0xe8, 0xf1, 0x02, 0x16, 0xc1, 0x5c, 0x81,
            0xcf, 0x97, 0x32, 0x22,
        ];

        assert_eq!(&key[..], &expected[..]);
    }
}
