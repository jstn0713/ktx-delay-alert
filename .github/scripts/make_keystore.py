#!/usr/bin/env python3
"""
앱 서명 키를 저장소 Secret `SIGNING_SEED`(비밀 문구)에서 항상 똑같이 만들어 낸다.

같은 문구 → 같은 RSA 키 → 같은 인증서(바이트 단위로 동일) 이므로
매번 빌드해도 서명이 같아 휴대폰에서 업데이트 설치가 된다.
키 파일 자체는 어디에도 저장하지 않는다.

사용: SIGNING_SEED=... python make_keystore.py <출력.p12>
GitHub Actions 안에서는 KEYSTORE_FILE, KEYSTORE_PASSWORD 를 $GITHUB_ENV 에 써 준다.
"""
import datetime
import hashlib
import hmac
import os
import sys

from Crypto.PublicKey import RSA
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

ALIAS = b"ktxdelay"
SALT = b"ktx-delay-alert/signing/v1"


class Drbg:
    """HMAC-SHA256 카운터 방식의 결정적 난수 (같은 seed → 같은 바이트열)"""

    def __init__(self, key: bytes):
        self.key = key
        self.counter = 0
        self.buf = b""

    def __call__(self, n: int) -> bytes:
        while len(self.buf) < n:
            self.buf += hmac.new(self.key, self.counter.to_bytes(8, "big"), hashlib.sha256).digest()
            self.counter += 1
        out, self.buf = self.buf[:n], self.buf[n:]
        return out


def main() -> None:
    seed = os.environ.get("SIGNING_SEED", "").strip()
    if len(seed) < 24:
        sys.exit("SIGNING_SEED 는 24자 이상이어야 합니다.")
    out_path = sys.argv[1]

    master = hashlib.pbkdf2_hmac("sha256", seed.encode("utf-8"), SALT, 600_000, 64)
    rsa = RSA.generate(2048, randfunc=Drbg(master))
    key = serialization.load_pem_private_key(rsa.export_key("PEM"), password=None)

    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "KTX Delay Alert"),
        x509.NameAttribute(NameOID.COUNTRY_NAME, "KR"),
    ])
    utc = datetime.timezone.utc
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(int.from_bytes(hmac.new(master, b"serial", hashlib.sha256).digest()[:16], "big") >> 1)
        .not_valid_before(datetime.datetime(2026, 1, 1, tzinfo=utc))
        .not_valid_after(datetime.datetime(2076, 1, 1, tzinfo=utc))
        .sign(key, hashes.SHA256())
    )

    password = hmac.new(master, b"keystore-password", hashlib.sha256).hexdigest()
    encryption = (
        serialization.PrivateFormat.PKCS12.encryption_builder()
        .kdf_rounds(50_000)
        .key_cert_algorithm(pkcs12.PBES.PBESv2SHA256AndAES256CBC)
        .hmac_hash(hashes.SHA256())
        .build(password.encode())
    )
    with open(out_path, "wb") as f:
        f.write(pkcs12.serialize_key_and_certificates(ALIAS, key, cert, None, encryption))

    fingerprint = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    print(f"서명 인증서 SHA-256: {fingerprint}")

    env = os.environ.get("GITHUB_ENV")
    if env:
        print(f"::add-mask::{password}")
        with open(env, "a") as f:
            f.write(f"KEYSTORE_FILE={out_path}\nKEYSTORE_PASSWORD={password}\n")


if __name__ == "__main__":
    main()
