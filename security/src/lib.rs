//! Server-side proof of possession for an enrolled P-256 key.
//! Enrollment verifies hardware attestation separately. The gateway must
//! atomically consume stored challenges before granting sessions.

pub mod gateway;

use p256::ecdsa::{signature::Verifier, Signature, VerifyingKey};
use p256::elliptic_curve::rand_core::{OsRng, RngCore};

const DOMAIN: &[u8] = b"chudoscan/device-proof/v1\0";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum DeviceUse {
    AndroidSignIn = 1,
    AdminSignIn = 2,
    PrinterAgent = 3,
}

#[derive(Debug, PartialEq, Eq)]
pub enum InvalidProof {
    Account,
    Purpose,
    Expired,
    Key,
    Signature,
    Randomness,
}

pub struct Challenge {
    nonce: [u8; 32],
    account: String,
    purpose: DeviceUse,
    expires_at: u64,
}

impl Challenge {
    /// Generate the nonce before creating a challenge in the database.
    pub fn random_nonce() -> Result<[u8; 32], InvalidProof> {
        let mut nonce = [0; 32];
        OsRng
            .try_fill_bytes(&mut nonce)
            .map_err(|_| InvalidProof::Randomness)?;
        Ok(nonce)
    }

    /// These values come from verified identity and trusted challenge storage,
    /// including the expiry returned by the database's issuance function.
    pub fn from_storage(
        account: &str,
        purpose: DeviceUse,
        nonce: [u8; 32],
        expires_at: u64,
    ) -> Result<Self, InvalidProof> {
        if account.is_empty() || account.len() > 128 {
            return Err(InvalidProof::Account);
        }
        Ok(Self {
            nonce,
            account: account.to_owned(),
            purpose,
            expires_at,
        })
    }

    /// Protocol bytes to sign with ECDSA P-256/SHA-256.
    pub fn payload(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(DOMAIN.len() + 42 + self.account.len());
        bytes.extend_from_slice(DOMAIN);
        bytes.push(self.purpose as u8);
        bytes.push(self.account.len() as u8);
        bytes.extend_from_slice(self.account.as_bytes());
        bytes.extend_from_slice(&self.expires_at.to_be_bytes());
        bytes.extend_from_slice(&self.nonce);
        bytes
    }

    pub fn expires_at(&self) -> u64 {
        self.expires_at
    }

    /// The public key must come from the server's enrolled-device record.
    pub fn verify(
        self,
        now: u64,
        authenticated_account: &str,
        expected_purpose: DeviceUse,
        enrolled_public_key_sec1: &[u8],
        signature_der: &[u8],
    ) -> Result<(), InvalidProof> {
        if authenticated_account != self.account {
            return Err(InvalidProof::Account);
        }
        if self.purpose != expected_purpose {
            return Err(InvalidProof::Purpose);
        }
        if now >= self.expires_at {
            return Err(InvalidProof::Expired);
        }
        let key = VerifyingKey::from_sec1_bytes(enrolled_public_key_sec1)
            .map_err(|_| InvalidProof::Key)?;
        let signature = Signature::from_der(signature_der).map_err(|_| InvalidProof::Signature)?;
        key.verify(&self.payload(), &signature)
            .map_err(|_| InvalidProof::Signature)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::{signature::Signer, SigningKey};

    fn issue(account: &str, purpose: DeviceUse, now: u64) -> Result<Challenge, InvalidProof> {
        Challenge::from_storage(account, purpose, Challenge::random_nonce()?, now + 120)
    }

    fn verify(
        challenge: Challenge,
        key: &SigningKey,
        actual_signer: &SigningKey,
    ) -> Result<(), InvalidProof> {
        let signature: Signature = actual_signer.sign(&challenge.payload());
        challenge.verify(
            1_001,
            "employee-1",
            DeviceUse::AndroidSignIn,
            key.verifying_key().to_encoded_point(false).as_bytes(),
            signature.to_der().as_bytes(),
        )
    }

    #[test]
    fn accepts_enrolled_device_and_rejects_another_device_for_the_same_account() {
        let enrolled = SigningKey::random(&mut OsRng);
        let stranger = SigningKey::random(&mut OsRng);
        let issue = || issue("employee-1", DeviceUse::AndroidSignIn, 1_000).unwrap();
        assert_eq!(verify(issue(), &enrolled, &enrolled), Ok(()));
        assert_eq!(
            verify(issue(), &enrolled, &stranger),
            Err(InvalidProof::Signature)
        );
    }

    #[test]
    fn rejects_a_signature_from_a_different_challenge() {
        let first = issue("employee-1", DeviceUse::AdminSignIn, 1_000).unwrap();
        let second = issue("employee-1", DeviceUse::AdminSignIn, 1_000).unwrap();
        let enrolled = SigningKey::random(&mut OsRng);
        let signature: Signature = enrolled.sign(&first.payload());
        assert_eq!(
            second.verify(
                1_001,
                "employee-1",
                DeviceUse::AdminSignIn,
                enrolled.verifying_key().to_encoded_point(false).as_bytes(),
                signature.to_der().as_bytes()
            ),
            Err(InvalidProof::Signature)
        );
    }

    #[test]
    fn rejects_expired_proofs_and_account_mismatches() {
        let wrong_purpose = issue("employee-1", DeviceUse::AndroidSignIn, 1_000).unwrap();
        assert_eq!(
            wrong_purpose.verify(1_001, "employee-1", DeviceUse::AdminSignIn, &[], &[]),
            Err(InvalidProof::Purpose)
        );
        let expired = issue("employee-1", DeviceUse::PrinterAgent, 1_000).unwrap();
        assert_eq!(
            expired.verify(1_120, "employee-1", DeviceUse::PrinterAgent, &[], &[]),
            Err(InvalidProof::Expired)
        );
        let wrong_user = issue("employee-1", DeviceUse::PrinterAgent, 1_000).unwrap();
        assert_eq!(
            wrong_user.verify(1_001, "employee-2", DeviceUse::PrinterAgent, &[], &[]),
            Err(InvalidProof::Account)
        );
    }
}
