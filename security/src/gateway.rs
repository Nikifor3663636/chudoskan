//! Device proof verification for a trusted Rust request handler.
//! The handler verifies identity and obtains IP from its trusted transport.

use std::net::IpAddr;

use postgres::Client;

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

/// The account is the UID extracted from a verified Firebase token.
pub fn issue(
    db: &mut Client,
    account: &str,
    purpose: DeviceUse,
) -> Result<([u8; 32], Vec<u8>), GatewayError> {
    let nonce = Challenge::random_nonce()?;
    let expires_at: Option<i64> = db
        .query_one(
            "select app.issue_device_challenge($1,$2,$3)",
            &[&account, &(purpose as i32), &&nonce[..]],
        )?
        .get(0);
    let expires_at = deadline(expires_at.ok_or(GatewayError::Unavailable)?)?;
    let challenge = Challenge::from_storage(account, purpose, nonce, expires_at)?;
    Ok((nonce, challenge.payload()))
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
