//! Sign in with Apple / Google: verify the provider's ID token (RS256 JWT)
//! against its published keys, then issue our own opaque bearer token.
//! Only a SHA-256 of each token is stored.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::Duration;
use mangavault_core::http::get_ok;
use rsa::{BigUint, Pkcs1v15Sign, RsaPublicKey};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use worker::Request;

use crate::api::{ApiError, ApiResult, json, no_content};
use crate::{App, args, time};

const SESSION_DAYS: i64 = 180;

pub struct Provider {
    pub name: &'static str,
    pub jwks_url: &'static str,
    pub issuers: &'static [&'static str],
}

pub const APPLE: Provider = Provider {
    name: "apple",
    jwks_url: "https://appleid.apple.com/auth/keys",
    issuers: &["https://appleid.apple.com"],
};

pub const GOOGLE: Provider = Provider {
    name: "google",
    jwks_url: "https://www.googleapis.com/oauth2/v3/certs",
    issuers: &["https://accounts.google.com", "accounts.google.com"],
};

#[derive(Debug, Deserialize)]
pub struct Claims {
    pub sub: String,
    pub iss: String,
    pub aud: Value,
    pub exp: i64,
    pub email: Option<String>,
    pub name: Option<String>,
}

#[derive(Debug, PartialEq)]
pub enum JwtError {
    Malformed,
    UnknownKey,
    BadSignature,
    Expired,
    WrongIssuer,
    WrongAudience,
}

/// Verify an RS256 JWT against a JWKS document and the expected issuers and
/// audiences. `now` is unix seconds.
pub fn verify_jwt(
    token: &str,
    jwks: &Value,
    issuers: &[&str],
    audiences: &[String],
    now: i64,
) -> Result<Claims, JwtError> {
    let mut parts = token.split('.');
    let (Some(h), Some(p), Some(s), None) =
        (parts.next(), parts.next(), parts.next(), parts.next())
    else {
        return Err(JwtError::Malformed);
    };
    let header: Value = decode_json(h)?;
    if header["alg"] != "RS256" {
        return Err(JwtError::Malformed);
    }
    let kid = header["kid"].as_str().ok_or(JwtError::Malformed)?;
    let jwk = jwks["keys"]
        .as_array()
        .and_then(|keys| keys.iter().find(|k| k["kid"] == kid))
        .ok_or(JwtError::UnknownKey)?;
    let n = b64(jwk["n"].as_str().ok_or(JwtError::UnknownKey)?)?;
    let e = b64(jwk["e"].as_str().ok_or(JwtError::UnknownKey)?)?;
    let key = RsaPublicKey::new(BigUint::from_bytes_be(&n), BigUint::from_bytes_be(&e))
        .map_err(|_| JwtError::UnknownKey)?;
    let digest = Sha256::digest(format!("{h}.{p}").as_bytes());
    key.verify(Pkcs1v15Sign::new::<Sha256>(), &digest, &b64(s)?)
        .map_err(|_| JwtError::BadSignature)?;

    let claims: Claims = decode_json(p)?;
    if claims.exp < now {
        return Err(JwtError::Expired);
    }
    if !issuers.contains(&claims.iss.as_str()) {
        return Err(JwtError::WrongIssuer);
    }
    let aud_ok = match &claims.aud {
        Value::String(a) => audiences.contains(a),
        Value::Array(list) => list
            .iter()
            .any(|a| a.as_str().is_some_and(|a| audiences.iter().any(|x| x == a))),
        _ => false,
    };
    if !aud_ok {
        return Err(JwtError::WrongAudience);
    }
    Ok(claims)
}

fn b64(s: &str) -> Result<Vec<u8>, JwtError> {
    URL_SAFE_NO_PAD
        .decode(s.trim_end_matches('='))
        .map_err(|_| JwtError::Malformed)
}

fn decode_json<T: for<'a> Deserialize<'a>>(s: &str) -> Result<T, JwtError> {
    serde_json::from_slice(&b64(s)?).map_err(|_| JwtError::Malformed)
}

pub fn hash_token(token: &str) -> String {
    Sha256::digest(token.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn new_token() -> Result<String, ApiError> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes)
        .map_err(|e| ApiError::from(worker::Error::RustError(e.to_string())))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

#[derive(Serialize, Deserialize, Clone)]
pub struct User {
    pub id: i64,
    pub name: Option<String>,
    pub email: Option<String>,
}

#[derive(Deserialize)]
struct SignInBody {
    #[serde(alias = "identityToken", alias = "idToken")]
    token: Option<String>,
    name: Option<String>,
}

pub async fn sign_in(app: &App, provider: &str, req: &mut Request) -> ApiResult {
    let body: SignInBody = req
        .json()
        .await
        .map_err(|_| ApiError::bad_request("expected a JSON body"))?;
    let (subject, email, name) = match provider {
        "apple" | "google" => {
            let (p, audiences) = if provider == "apple" {
                (&APPLE, &app.config.apple_audiences)
            } else {
                (&GOOGLE, &app.config.google_audiences)
            };
            if audiences.is_empty() {
                return Err(ApiError::bad_request(format!(
                    "{provider} sign-in is not configured"
                )));
            }
            let token = body
                .token
                .as_deref()
                .ok_or_else(|| ApiError::bad_request("missing token"))?;
            let jwks_text = get_ok(&app.http, p.jwks_url, &[])
                .await
                .map_err(|e| ApiError::upstream(e.to_string()))?;
            let jwks: Value =
                serde_json::from_str(&jwks_text).map_err(|e| ApiError::upstream(e.to_string()))?;
            let claims = verify_jwt(token, &jwks, p.issuers, audiences, time::now().timestamp())
                .map_err(|e| ApiError::unauthorized(format!("invalid {provider} token: {e:?}")))?;
            (claims.sub, claims.email, body.name.or(claims.name))
        }
        // Local development only: sign in as any name without a provider.
        "dev" if app.config.dev_auth => {
            let name = body
                .name
                .ok_or_else(|| ApiError::bad_request("missing name"))?;
            (name.clone(), None, Some(name))
        }
        _ => {
            return Err(ApiError::not_found(format!(
                "unknown sign-in provider {provider:?}"
            )));
        }
    };

    let now = time::now_iso();
    // Apple sends the name only on first sign-in, so never overwrite it with null.
    app.db
        .run(
            "INSERT INTO user (provider, subject, email, name, created_at) VALUES (?, ?, ?, ?, ?) \
             ON CONFLICT(provider, subject) DO UPDATE SET email = COALESCE(excluded.email, email), \
             name = COALESCE(excluded.name, name)",
            args![provider, &subject, email, name, &now],
        )
        .await?;
    let user: User = app
        .db
        .first(
            "SELECT id, name, email FROM user WHERE provider = ? AND subject = ?",
            args![provider, &subject],
        )
        .await?
        .ok_or_else(|| ApiError::from(worker::Error::RustError("user vanished".into())))?;

    let token = new_token()?;
    app.db
        .run(
            "INSERT INTO session (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
            args![
                hash_token(&token),
                user.id,
                &now,
                time::iso(time::now() + Duration::days(SESSION_DAYS))
            ],
        )
        .await?;
    json(&json!({ "token": token, "user": user }))
}

fn bearer(req: &Request) -> Option<String> {
    let h = req.headers().get("Authorization").ok().flatten()?;
    h.strip_prefix("Bearer ")
        .map(|t| t.trim().to_string())
        .filter(|t| !t.is_empty())
}

pub async fn require_user(app: &App, req: &Request) -> Result<User, ApiError> {
    let token = bearer(req).ok_or_else(|| ApiError::unauthorized("missing bearer token"))?;
    app.db
        .first(
            "SELECT u.id, u.name, u.email FROM session s JOIN user u ON u.id = s.user_id \
             WHERE s.token_hash = ? AND s.expires_at > ?",
            args![hash_token(&token), time::now_iso()],
        )
        .await?
        .ok_or_else(|| ApiError::unauthorized("invalid or expired token"))
}

pub async fn me(app: &App, req: &Request) -> ApiResult {
    let user = require_user(app, req).await?;
    json(&json!({ "user": user }))
}

pub async fn logout(app: &App, req: &Request) -> ApiResult {
    if let Some(token) = bearer(req) {
        app.db
            .run(
                "DELETE FROM session WHERE token_hash = ?",
                args![hash_token(&token)],
            )
            .await?;
    }
    no_content()
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsa::RsaPrivateKey;
    use rsa::traits::PublicKeyParts;

    fn sign(key: &RsaPrivateKey, header: Value, claims: Value) -> String {
        let h = URL_SAFE_NO_PAD.encode(header.to_string());
        let p = URL_SAFE_NO_PAD.encode(claims.to_string());
        let digest = Sha256::digest(format!("{h}.{p}").as_bytes());
        let sig = key.sign(Pkcs1v15Sign::new::<Sha256>(), &digest).unwrap();
        format!("{h}.{p}.{}", URL_SAFE_NO_PAD.encode(sig))
    }

    fn setup() -> (RsaPrivateKey, Value) {
        let key = RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap();
        let pubkey = key.to_public_key();
        let jwks = json!({ "keys": [{
            "kid": "k1", "kty": "RSA", "alg": "RS256",
            "n": URL_SAFE_NO_PAD.encode(pubkey.n().to_bytes_be()),
            "e": URL_SAFE_NO_PAD.encode(pubkey.e().to_bytes_be()),
        }]});
        (key, jwks)
    }

    fn claims(aud: &str, exp: i64) -> Value {
        json!({ "sub": "001", "iss": "https://appleid.apple.com", "aud": aud, "exp": exp, "email": "a@b.c" })
    }

    #[test]
    fn accepts_valid_token_and_rejects_tampering() {
        let (key, jwks) = setup();
        let aud = vec!["com.example.app".to_string()];
        let header = json!({ "alg": "RS256", "kid": "k1" });
        let token = sign(&key, header.clone(), claims("com.example.app", 2_000));
        let c = verify_jwt(&token, &jwks, APPLE.issuers, &aud, 1_000).unwrap();
        assert_eq!(c.sub, "001");
        assert_eq!(c.email.as_deref(), Some("a@b.c"));

        assert_eq!(
            verify_jwt(&token, &jwks, APPLE.issuers, &aud, 3_000).unwrap_err(),
            JwtError::Expired
        );
        assert_eq!(
            verify_jwt(&token, &jwks, GOOGLE.issuers, &aud, 1_000).unwrap_err(),
            JwtError::WrongIssuer
        );
        let other = sign(&key, header.clone(), claims("someone.else", 2_000));
        assert_eq!(
            verify_jwt(&other, &jwks, APPLE.issuers, &aud, 1_000).unwrap_err(),
            JwtError::WrongAudience
        );

        let mut parts: Vec<&str> = token.split('.').collect();
        let forged = URL_SAFE_NO_PAD.encode(claims("com.example.app", 9_999).to_string());
        parts[1] = &forged;
        assert_eq!(
            verify_jwt(&parts.join("."), &jwks, APPLE.issuers, &aud, 1_000).unwrap_err(),
            JwtError::BadSignature
        );

        let unknown = sign(
            &key,
            json!({ "alg": "RS256", "kid": "k2" }),
            claims("com.example.app", 2_000),
        );
        assert_eq!(
            verify_jwt(&unknown, &jwks, APPLE.issuers, &aud, 1_000).unwrap_err(),
            JwtError::UnknownKey
        );
        assert_eq!(
            verify_jwt("a.b", &jwks, APPLE.issuers, &aud, 1_000).unwrap_err(),
            JwtError::Malformed
        );
    }

    #[test]
    fn token_hash_is_hex_sha256() {
        assert_eq!(
            hash_token("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
