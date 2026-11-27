//! Device proof verification for a trusted Rust request handler.
//! The handler verifies identity and obtains IP from its trusted transport.

use std::net::IpAddr;

use p256::ecdsa::VerifyingKey;
use postgres::{Client, GenericClient};

use crate::{Challenge, DeviceUse, InvalidProof};

#[derive(Debug)]
pub enum GatewayError {
    Proof(InvalidProof),
    Unavailable,
    Database(postgres::Error),
}

impl From<postgres::Error> for GatewayError {
    fn from(error: postgres::Error) -> Self {
        Self::Database(error)
    }
}

impl From<InvalidProof> for GatewayError {
    fn from(error: InvalidProof) -> Self {
        Self::Proof(error)
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Enrollment {
    Pending,
    Approved,
    Blocked,
    AnotherPending,
}

/// Call after verifying the Firebase identity and hardware attestation for this exact key.
pub fn request_enrollment(
    db: &mut impl GenericClient,
    account: &str,
    purpose: DeviceUse,
    attested_key: &[u8],
    observed_ip: IpAddr,
) -> Result<Enrollment, GatewayError> {
    VerifyingKey::from_sec1_bytes(attested_key).map_err(|_| InvalidProof::Key)?;
    if attested_key.len() != 65 || attested_key.first() != Some(&4) {
        return Err(InvalidProof::Key.into());
    }
    let ip = observed_ip.to_string();
    let status: String = db
        .query_one(
            "select app.request_device_enrollment($1,$2,$3,$4::text::inet)",
            &[&account, &(purpose as i32), &attested_key, &ip],
        )?
        .get(0);
    match status.as_str() {
        "pending" => Ok(Enrollment::Pending),
        "approved" => Ok(Enrollment::Approved),
        "blocked" => Ok(Enrollment::Blocked),
        "another_pending" => Ok(Enrollment::AnotherPending),
        _ => Err(GatewayError::Unavailable),
    }
}

/// Check every protected request against the verified account and device purpose.
pub fn session_valid(
    db: &mut Client,
    account: &str,
    purpose: DeviceUse,
    session: &str,
) -> Result<bool, GatewayError> {
    Ok(db
        .query_one(
            "select app.device_session_valid($1,$2,$3::text::uuid)",
            &[&account, &(purpose as i32), &session],
        )?
        .get(0))
}

/// The account is the UID extracted from a verified Firebase token.
pub fn issue(
    db: &mut Client,
    account: &str,
    purpose: DeviceUse,
) -> Result<([u8; 32], Vec<u8>), GatewayError> {
    let (nonce, expires_at) = issue_with_expiry(db, account, purpose)?;
    let challenge = Challenge::from_storage(account, purpose, nonce, expires_at)?;
    Ok((nonce, challenge.payload()))
}

pub fn issue_with_expiry(
    db: &mut Client,
    account: &str,
    purpose: DeviceUse,
) -> Result<([u8; 32], u64), GatewayError> {
    let nonce = Challenge::random_nonce()?;
    let expires_at: Option<i64> = db
        .query_one(
            "select app.issue_device_challenge($1,$2,$3)",
            &[&account, &(purpose as i32), &&nonce[..]],
        )?
        .get(0);
    Ok((
        nonce,
        deadline(expires_at.ok_or(GatewayError::Unavailable)?)?,
    ))
}

/// Return a session only after a valid signature and atomic consumption.
pub fn open_session(
    db: &mut Client,
    account: &str,
    purpose: DeviceUse,
    nonce: [u8; 32],
    signature_der: &[u8],
    observed_ip: Option<IpAddr>,
) -> Result<String, GatewayError> {
    let kind = purpose as i32;
    let nonce_bytes = &nonce[..];
    let row = db
        .query_opt(
            "select public_key, expires_at, floor(extract(epoch from statement_timestamp()))::bigint
             from app.device_challenge($1,$2,$3)",
            &[&account, &kind, &nonce_bytes],
        )?
        .ok_or(GatewayError::Unavailable)?;
    let public_key: &[u8] = row.get(0);
    let expires_at = deadline(row.get(1))?;
    let now = deadline(row.get(2))?;
    let challenge = Challenge::from_storage(account, purpose, nonce, expires_at)?;
    challenge.verify(now, account, purpose, public_key, signature_der)?;

    let ip = observed_ip.map(|addr| addr.to_string());
    let session: Option<String> = db
        .query_one(
            "select app.finish_device_challenge($1,$2,$3,$4::text::inet)::text",
            &[&account, &kind, &nonce_bytes, &ip],
        )?
        .get(0);
    session.ok_or(GatewayError::Unavailable)
}

fn deadline(value: i64) -> Result<u64, GatewayError> {
    u64::try_from(value).map_err(|_| GatewayError::Unavailable)
}
