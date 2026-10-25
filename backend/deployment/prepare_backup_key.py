"""Create a separate local backup recovery key; never upload the private key/password."""
import argparse
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


def prepare(directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    paths = {name: directory / filename for name, filename in {
        'key': 'backup-private.pem', 'password': 'backup-key-password.txt',
        'certificate': 'backup-public.crt',
    }.items()}
    if any(path.exists() for path in paths.values()):
        raise ValueError('Refusing to overwrite existing recovery material')
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    password = secrets.token_urlsafe(48).encode()
    now = datetime.now(timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'WoundCoPilot private backup recovery')])
    certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
        .public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=3650))
        .add_extension(x509.KeyUsage(digital_signature=False, content_commitment=False,
            key_encipherment=True, data_encipherment=False, key_agreement=False,
            key_cert_sign=False, crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
        .sign(key, hashes.SHA256()))
    paths['key'].write_bytes(key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.BestAvailableEncryption(password)))
    paths['password'].write_bytes(password + b'\n')
    paths['certificate'].write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    for path in paths.values():
        path.chmod(0o600)
    print('Created separate recovery material in the protected directory; copy only backup-public.crt to the VM.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    prepare(parser.parse_args().directory)
