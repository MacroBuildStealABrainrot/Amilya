from logging.config import fileConfig
from alembic import context
from server.core import settings
from server.db import Base, engine
from server import identity, audit, clinical_models, billing_models, contact, provider_models, transfer_models, diagnostics

config = context.config
if config.config_file_name: fileConfig(config.config_file_name)
target_metadata = Base.metadata

if context.is_offline_mode():
    context.configure(url=settings.database_url, target_metadata=target_metadata, literal_binds=True, dialect_opts={'paramstyle': 'named'})
    with context.begin_transaction(): context.run_migrations()
else:
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata, compare_type=True, render_as_batch=engine.dialect.name == 'sqlite')
        with context.begin_transaction(): context.run_migrations()
