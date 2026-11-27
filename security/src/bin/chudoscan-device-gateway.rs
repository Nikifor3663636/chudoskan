use std::{
    env,
    net::IpAddr,
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use chudoscan_device_proof::{
    gateway::{self, Enrollment, GatewayError},
    Challenge, DeviceUse,
};
use jsonwebtoken::{decode, decode_header, jwk::JwkSet, Algorithm, DecodingKey, Validation};
use native_tls::TlsConnector;
use octet_attest_verify::keyattest::{
    verify_key_attestation_with, ExpectedAppIdentity, KeyAttestOptions,
};
use postgres::{config::SslMode, Client, Config};
use postgres_native_tls::MakeTlsConnector;
use reqwest::blocking::Client as HttpClient;
use serde::Deserialize;
use serde_json::{json, Value};
use tiny_http::{Header, Request, Response, Server, StatusCode};

const APP: &str = "com.chudoostriv.app";
const PROJECT: &str = "chudoostriv";
const FIREBASE_KEYS: &str =
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const ANDROID_REVOCATIONS: &str = "https://android.googleapis.com/attestation/status";
const MAX_BODY: usize = 32 * 1024;

type Cached<T> = Mutex<Option<(Instant, T)>>;

struct Gateway {
    database: Config,
    tls: MakeTlsConnector,
    http: HttpClient,
    firebase_keys: Cached<JwkSet>,
    revocations: Cached<Vec<Vec<u8>>>,
    app: ExpectedAppIdentity,
    render: bool,
}

#[derive(Deserialize)]
struct FirebaseClaims {
    sub: String,
    iat: u64,
    auth_time: u64,
}

#[derive(Clone, Copy, Debug)]
enum Failure {
    BadRequest,
    Unauthorized,
    Denied,
    Upstream,
}

impl From<postgres::Error> for Failure {
    fn from(error: postgres::Error) -> Self {
        eprintln!("Database request failed (SQLSTATE {:?})", error.code());
        Self::Upstream
    }
}

impl From<GatewayError> for Failure {
    fn from(error: GatewayError) -> Self {
        match error {
            GatewayError::Database(error) => error.into(),
            GatewayError::Proof(_) | GatewayError::Unavailable => Self::Denied,
        }
    }
}

fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut database: Config = env::var("DEVICE_DATABASE_URL")?.parse()?;
    if database.get_ssl_mode() == SslMode::Disable {
        return Err("DEVICE_DATABASE_URL must support TLS".into());
    }
    database
        .ssl_mode(SslMode::Require)
        .connect_timeout(Duration::from_secs(8));
    let app_digest: [u8; 32] = hex::decode(env::var("ANDROID_SIGNING_SHA256")?)?
        .try_into()
        .map_err(|_| "ANDROID_SIGNING_SHA256 must be a 32-byte digest")?;
    let state = Arc::new(Gateway {
        database,
        tls: MakeTlsConnector::new(TlsConnector::new()?),
        http: HttpClient::builder()
            .timeout(Duration::from_secs(8))
            .redirect(reqwest::redirect::Policy::none())
            .build()?,
        firebase_keys: Mutex::new(None),
        revocations: Mutex::new(None),
        app: ExpectedAppIdentity {
            package_name: APP.into(),
            signing_cert_sha256: app_digest,
        },
        render: env::var("RENDER").as_deref() == Ok("true"),
    });
    let port = env::var("PORT")
        .unwrap_or_else(|_| "8080".into())
        .parse::<u16>()?;
    let server = Arc::new(Server::http(("0.0.0.0", port))?);
    std::thread::scope(|scope| {
        for _ in 0..8 {
            let (server, state) = (Arc::clone(&server), Arc::clone(&state));
            scope.spawn(move || {
                for mut request in server.incoming_requests() {
                    let (code, body) = match state.handle(&mut request) {
                        Ok(body) => (200, body),
                        Err(error) => match error {
                            Failure::BadRequest => (400, json!({"error": "invalid_request"})),
                            Failure::Unauthorized => (401, json!({"error": "invalid_identity"})),
                            Failure::Denied => (403, json!({"error": "access_denied"})),
                            Failure::Upstream => (503, json!({"error": "unavailable"})),
                        },
                    };
                    let response = Response::from_string(body.to_string())
                        .with_status_code(StatusCode(code))
                        .with_header(
                            Header::from_bytes("Content-Type", "application/json").unwrap(),
                        )
                        .with_header(Header::from_bytes("Cache-Control", "no-store").unwrap());
                    let _ = request.respond(response);
                }
            });
        }
    });
    Ok(())
}

impl Gateway {
    fn handle(&self, request: &mut Request) -> Result<Value, Failure> {
        if request.method() == &tiny_http::Method::Get && request.url() == "/healthz" {
            return Ok(json!({"status": "ok"}));
        }
        if request.method() != &tiny_http::Method::Post {
            return Err(Failure::BadRequest);
        }
        let route = match request.url() {
            "/v1/android/begin" => Self::begin,
            "/v1/android/enroll" => Self::enroll,
            "/v1/android/prove" => Self::prove,
            _ => return Err(Failure::BadRequest),
        };
        let bearer = header(request, "Authorization")
            .and_then(|value| value.strip_prefix("Bearer "))
            .filter(|token| token.len() <= 8192)
            .ok_or(Failure::Unauthorized)?;
        let uid = self.firebase_uid(bearer)?;
        let ip = self.observed_ip(request)?;
        let mut body = Vec::new();
        use std::io::Read;
        request
            .as_reader()
            .take((MAX_BODY + 1) as u64)
            .read_to_end(&mut body)
            .map_err(|_| Failure::BadRequest)?;
        if body.len() > MAX_BODY {
            return Err(Failure::BadRequest);
        }
        let data = serde_json::from_slice(&body).map_err(|_| Failure::BadRequest)?;
        let mut db = self.database.connect(self.tls.clone())?;
        route(self, &mut db, &uid, ip, data)
    }

    fn firebase_uid(&self, bearer: &str) -> Result<String, Failure> {
        let header = decode_header(bearer).map_err(|_| Failure::Unauthorized)?;
        let kid = header
            .kid
            .filter(|key| key.len() <= 128)
            .ok_or(Failure::Unauthorized)?;
        if header.alg != Algorithm::RS256 {
            return Err(Failure::Unauthorized);
        }
        let key = {
            let mut cache = self.firebase_keys.lock().map_err(|_| Failure::Upstream)?;
            if cache
                .as_ref()
                .is_none_or(|(expires, _)| *expires <= Instant::now())
            {
                let keys = self.fetch(FIREBASE_KEYS)?;
                *cache = Some((Instant::now() + Duration::from_secs(300), keys));
            }
            let jwk = cache
                .as_ref()
                .and_then(|(_, set)| set.find(&kid))
                .ok_or(Failure::Unauthorized)?;
            DecodingKey::from_jwk(jwk).map_err(|_| Failure::Unauthorized)?
        };
        let mut validation = Validation::new(Algorithm::RS256);
        validation.leeway = 0;
        validation.set_required_spec_claims(&["exp", "iss", "aud", "sub"]);
        validation.set_issuer(&[format!("https://securetoken.google.com/{PROJECT}")]);
        validation.set_audience(&[PROJECT]);
        let claims = decode::<FirebaseClaims>(bearer, &key, &validation)
            .map_err(|_| Failure::Unauthorized)?
            .claims;
        let now = unix_now()?;
        if claims.sub.is_empty()
            || claims.sub.len() > 128
            || claims.iat > now
            || claims.auth_time > now
        {
            return Err(Failure::Unauthorized);
        }
        Ok(claims.sub)
    }

    fn observed_ip(&self, request: &Request) -> Result<IpAddr, Failure> {
        if !self.render {
            return request
                .remote_addr()
                .map(|address| address.ip())
                .ok_or(Failure::Denied);
        }
        // Render's public ingress appends the visitor IP to X-Forwarded-For.
        header(request, "X-Forwarded-For")
            .and_then(|value| value.rsplit(',').next())
            .and_then(|value| value.trim().parse().ok())
            .ok_or(Failure::Denied)
    }

    fn begin(&self, db: &mut Client, uid: &str, _: IpAddr, data: Value) -> Result<Value, Failure> {
        let key = data
            .get("public_key")
            .map(|key| decode_field(Some(key), 65))
            .transpose()?;
        let status: String = db
            .query_one("select app.android_enrollment_status($1,$2)", &[&uid, &key])?
            .get(0);
        match status.as_str() {
            "pending" | "blocked" | "another_pending" => Ok(json!({"status": status})),
            "approved" => {
                let (nonce, expires_at) =
                    gateway::issue_with_expiry(db, uid, DeviceUse::AndroidSignIn)?;
                Ok(
                    json!({"status": "prove", "nonce": URL_SAFE_NO_PAD.encode(nonce), "expires_at": expires_at}),
                )
            }
            "enroll" => {
                let nonce = Challenge::random_nonce().map_err(|_| Failure::Upstream)?;
                let allowed: bool = db
                    .query_one(
                        "select app.issue_android_enrollment($1,$2)",
                        &[&uid, &&nonce[..]],
                    )?
                    .get(0);
                if !allowed {
                    return Err(Failure::Denied);
                }
                Ok(json!({"status": "enroll", "nonce": URL_SAFE_NO_PAD.encode(nonce)}))
            }
            _ => Err(Failure::Denied),
        }
    }

    fn enroll(
        &self,
        db: &mut Client,
        uid: &str,
        ip: IpAddr,
        data: Value,
    ) -> Result<Value, Failure> {
        let nonce = decode_field(data.get("nonce"), 32)?;
        let chain = data
            .get("certificates")
            .and_then(Value::as_array)
            .filter(|values| (1..=8).contains(&values.len()))
            .ok_or(Failure::BadRequest)?
            .iter()
            .map(|cert| decode_field(Some(cert), 8192))
            .collect::<Result<Vec<_>, _>>()?;
        let exists: bool = db
            .query_one(
                "select app.android_enrollment_challenge_valid($1,$2)",
                &[&uid, &nonce],
            )?
            .get(0);
        if !exists {
            return Err(Failure::Denied);
        }
        let revoked = self.revoked_serials()?;
        let attested = verify_key_attestation_with(
            &chain,
            unix_now()?,
            &KeyAttestOptions {
                expected_challenge: Some(&nonce),
                expected_apps: std::slice::from_ref(&self.app),
                revoked_serials: &revoked,
                require_verified_boot: true,
                allow_software: false,
            },
        )
        .map_err(|_| Failure::Denied)?;
        let mut transaction = db.transaction()?;
        let consumed: bool = transaction
            .query_one(
                "select app.consume_android_enrollment($1,$2)",
                &[&uid, &nonce],
            )?
            .get(0);
        if !consumed {
            return Err(Failure::Denied);
        }
        let status = gateway::request_enrollment(
            &mut transaction,
            uid,
            DeviceUse::AndroidSignIn,
            &attested.leaf_pubkey_sec1,
            ip,
        )?;
        transaction.commit()?;
        Ok(json!({"status": match status {
            Enrollment::Pending | Enrollment::Approved => "pending",
            Enrollment::Blocked => "blocked",
            Enrollment::AnotherPending => "another_pending",
        }}))
    }

    fn prove(&self, db: &mut Client, uid: &str, ip: IpAddr, data: Value) -> Result<Value, Failure> {
        let nonce = decode_field(data.get("nonce"), 32)?
            .try_into()
            .map_err(|_| Failure::BadRequest)?;
        let signature = decode_field(data.get("signature"), 80)?;
        let session = gateway::open_session(
            db,
            uid,
            DeviceUse::AndroidSignIn,
            nonce,
            &signature,
            Some(ip),
        )?;
        if !gateway::session_valid(db, uid, DeviceUse::AndroidSignIn, &session)? {
            return Err(Failure::Denied);
        }
        Ok(json!({"status": "approved", "session": session}))
    }

    fn fetch<T: serde::de::DeserializeOwned>(&self, url: &str) -> Result<T, Failure> {
        self.http
            .get(url)
            .send()
            .and_then(|response| response.error_for_status())
            .and_then(|response| response.json())
            .map_err(|_| Failure::Upstream)
    }

    fn revoked_serials(&self) -> Result<Vec<Vec<u8>>, Failure> {
        let mut cache = self.revocations.lock().map_err(|_| Failure::Upstream)?;
        if cache
            .as_ref()
            .is_none_or(|(expires, _)| *expires <= Instant::now())
        {
            let list: Value = self.fetch(ANDROID_REVOCATIONS)?;
            let entries = list
                .get("entries")
                .and_then(Value::as_object)
                .ok_or(Failure::Upstream)?;
            let serials = entries
                .keys()
                .map(|key| {
                    let hex = if key.len() % 2 == 0 {
                        key.clone()
                    } else {
                        format!("0{key}")
                    };
                    hex::decode(hex).map_err(|_| Failure::Upstream)
                })
                .collect::<Result<Vec<_>, _>>()?;
            *cache = Some((Instant::now() + Duration::from_secs(300), serials));
        }
        cache
            .as_ref()
            .map(|(_, serials)| serials.clone())
            .ok_or(Failure::Upstream)
    }
}

fn header<'a>(request: &'a Request, name: &'static str) -> Option<&'a str> {
    let mut matches = request
        .headers()
        .iter()
        .filter(|header| header.field.equiv(name));
    let value = matches.next()?.value.as_str();
    if matches.next().is_some() {
        return None;
    }
    Some(value)
}

fn decode_field(value: Option<&Value>, max: usize) -> Result<Vec<u8>, Failure> {
    let value = value.and_then(Value::as_str).ok_or(Failure::BadRequest)?;
    let bytes = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Failure::BadRequest)?;
    if bytes.is_empty() || bytes.len() > max {
        return Err(Failure::BadRequest);
    }
    Ok(bytes)
}

fn unix_now() -> Result<u64, Failure> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_secs())
        .map_err(|_| Failure::Upstream)
}
