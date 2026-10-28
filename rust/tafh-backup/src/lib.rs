//! JNI bridge for the CHBK encrypted backup container.
//!
//! Exposes exactly two native methods on
//! `dev.nero.tafh.service.backup.NativeBackupBridge`:
//!
//! * `nativeEncrypt(byte[] plaintext, char[] passphrase): byte[]`
//! * `nativeDecrypt(byte[] ciphertext, char[] passphrase): byte[]`
//!
//! Both return a status byte followed by the payload (or a UTF-8 message when the
//! status is non-zero): 0 OK, 1 format error, 2 invalid passphrase, 3 internal
//! error. A `null` return means the JVM refused the response allocation.

pub mod container;

use jni::objects::{JByteArray, JCharArray, JObject};
use jni::sys::{jbyteArray, jchar};
use jni::JNIEnv;
use zeroize::{Zeroize, Zeroizing};

use container::{Error, MAX_BACKUP_SIZE};

const STATUS_OK: u8 = 0;
const STATUS_FORMAT_ERROR: u8 = 1;

/// Why a JVM array argument could not be read.
enum ReadError {
    /// Null array, or the JVM rejected the copy.
    Invalid,
    /// The array alone exceeds the container size cap.
    TooLarge,
}

/// `NativeBackupBridge.nativeEncrypt(byte[], char[])`.
#[no_mangle]
pub extern "system" fn Java_dev_nero_tafh_service_backup_NativeBackupBridge_nativeEncrypt<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    plaintext: JByteArray<'local>,
    passphrase: JCharArray<'local>,
) -> jbyteArray {
    let plaintext = match read_bytes(&env, &plaintext) {
        Ok(bytes) => Zeroizing::new(bytes),
        Err(err) => return reply_read_error(&env, err, b"Invalid plaintext"),
    };

    let units = match read_char_units(&env, &passphrase) {
        Ok(units) => Zeroizing::new(units),
        Err(()) => return reply(&env, STATUS_FORMAT_ERROR, b"Invalid passphrase"),
    };

    let passphrase = Zeroizing::new(container::utf16be_bytes(&units));

    match container::encrypt(&plaintext, &passphrase) {
        Ok(mut container_bytes) => {
            let response = reply(&env, STATUS_OK, &container_bytes);
            container_bytes.zeroize();
            response
        }
        Err(err) => reply_error(&env, err),
    }
}

/// `NativeBackupBridge.nativeDecrypt(byte[], char[])`.
#[no_mangle]
pub extern "system" fn Java_dev_nero_tafh_service_backup_NativeBackupBridge_nativeDecrypt<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    ciphertext: JByteArray<'local>,
    passphrase: JCharArray<'local>,
) -> jbyteArray {
    let ciphertext = match read_bytes(&env, &ciphertext) {
        Ok(bytes) => Zeroizing::new(bytes),
        Err(err) => return reply_read_error(&env, err, b"Invalid ciphertext"),
    };

    let units = match read_char_units(&env, &passphrase) {
        Ok(units) => Zeroizing::new(units),
        Err(()) => return reply(&env, STATUS_FORMAT_ERROR, b"Invalid passphrase"),
    };

    // Primary encoding is UTF-16BE; the legacy fallback is the UTF-8 bytes of the
    // same char[]. As in the previous bridge, a passphrase containing unpaired
    // surrogates has no UTF-8 form and is rejected before decryption is tried.
    let legacy = match container::utf8_bytes(&units) {
        Some(bytes) => Zeroizing::new(bytes),
        None => return reply(&env, STATUS_FORMAT_ERROR, b"Invalid passphrase"),
    };
    let primary = Zeroizing::new(container::utf16be_bytes(&units));

    match container::decrypt(&ciphertext, &primary, &legacy) {
        Ok(mut plaintext) => {
            let response = reply(&env, STATUS_OK, &plaintext);
            plaintext.zeroize();
            response
        }
        Err(err) => reply_error(&env, err),
    }
}

/// Copies a Java `byte[]`, mirroring the previous `readJByteArray` helper while
/// refusing oversized input before allocating.
fn read_bytes(env: &JNIEnv<'_>, array: &JByteArray<'_>) -> Result<Vec<u8>, ReadError> {
    if array.as_raw().is_null() {
        return Err(ReadError::Invalid);
    }
    let length = env.get_array_length(array).map_err(|_| ReadError::Invalid)?;
    if length < 0 {
        return Err(ReadError::Invalid);
    }
    if length as usize > MAX_BACKUP_SIZE {
        return Err(ReadError::TooLarge);
    }
    env.convert_byte_array(array).map_err(|_| ReadError::Invalid)
}

/// Copies a Java `char[]` as raw UTF-16 code units (`jchar` is `u16`).
fn read_char_units(env: &JNIEnv<'_>, array: &JCharArray<'_>) -> Result<Vec<jchar>, ()> {
    if array.as_raw().is_null() {
        return Err(());
    }
    let length = env.get_array_length(array).map_err(|_| ())?;
    if length < 0 {
        return Err(());
    }
    let mut units = vec![0u16; length as usize];
    if !units.is_empty() {
        env.get_char_array_region(array, 0, &mut units).map_err(|_| ())?;
    }
    Ok(units)
}

fn reply_read_error(env: &JNIEnv<'_>, err: ReadError, invalid_message: &[u8]) -> jbyteArray {
    match err {
        ReadError::Invalid => reply(env, STATUS_FORMAT_ERROR, invalid_message),
        ReadError::TooLarge => reply_error(env, Error::BackupTooLarge),
    }
}

fn reply_error(env: &JNIEnv<'_>, err: Error) -> jbyteArray {
    reply(env, err.status_byte(), err.message().as_bytes())
}

/// Builds `[status, payload...]` as a fresh JVM byte array.
fn reply(env: &JNIEnv<'_>, status: u8, payload: &[u8]) -> jbyteArray {
    let mut response = Vec::with_capacity(1 + payload.len());
    response.push(status);
    response.extend_from_slice(payload);
    match env.byte_array_from_slice(&response) {
        Ok(array) => array.into_raw(),
        Err(_) => core::ptr::null_mut(),
    }
}
