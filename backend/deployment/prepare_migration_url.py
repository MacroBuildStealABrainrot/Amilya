"""Generate operator-only URL files from existing task-private database material."""
import argparse
from pathlib import Path
from sqlalchemy.engine import URL


def write_urls(material: Path):
    material = material.resolve(strict=True)
    ca = (material / 'ca.crt').resolve(strict=True).as_posix()
    for user, password_file, output in [
        ('wcp_owner', 'owner-password.txt', 'migration-url.txt'),
        ('wcp_app', 'app-password.txt', 'runtime-tunnel-url.txt'),
    ]:
        password = (material / password_file).read_text(encoding='utf-8').strip()
        if len(password) < 32: raise ValueError('Generated database material is incomplete')
        url = URL.create('postgresql+psycopg', username=user, password=password, host='10.30.0.10', port=15432,
                         database='woundcopilot', query={'hostaddr':'127.0.0.1', 'sslmode':'verify-full',
                                                      'sslrootcert':ca, 'connect_timeout':'8'})
        target = material / output
        target.write_text(url.render_as_string(hide_password=False) + '\n', encoding='utf-8')
        print(f'Protected operator URL written to {target}; credential content omitted')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--material-dir', type=Path, required=True)
    write_urls(parser.parse_args().material_dir)
