"""Explicit one-time owner bootstrap. Never derive privileges from signup email."""
import getpass
from server.identity import bootstrap_owner

if __name__ == '__main__':
    email = input('Owner email: ').strip()
    password = getpass.getpass('New owner password (at least 16 characters): ')
    if password != getpass.getpass('Repeat password: '): raise SystemExit('Passwords did not match')
    bootstrap_owner(email, password)
    print('Owner account created. Accept the current terms and privacy policy on first login.')
