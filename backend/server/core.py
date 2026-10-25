from __future__ import annotations

import os
from pathlib import Path
from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=os.environ.get('WOUNDCOPILOT_ENV_FILE', '.env'), extra='ignore')
    environment: str = 'development'
    app_secret: str = ''
    database_url: str = 'sqlite:///./work/woundcopilot.db'
    public_base_url: str = 'http://127.0.0.1:8766'
    frontend_path: str = 'frontend/dist'
    secure_cookies: bool = False
    trusted_hosts: str = 'localhost,127.0.0.1,testserver'
    session_hours: int = 12
    allow_signup: bool = True
    clinical_data_enabled: bool = False
    legal_version: str = '2026-09-05'
    legal_business_name: str = ''
    legal_business_address: str = ''
    privacy_email: str = ''
    admin_email: str = ''
    admin_password: str = ''
    unlimited_credit_user_ids: str = ''
    test_provider_mode: bool = False
    auto_create_schema: bool = False
    openrouter_api_key: str = ''
    openrouter_base_url: str = 'https://openrouter.ai/api/v1'
    openrouter_phi_approved: bool = False
    openrouter_require_zdr: bool = True
    cloudflare_api_token: str = ''
    cloudflare_account_id: str = ''
    cloudflare_phi_approved: bool = False
    groq_api_key: str = ''
    groq_phi_approved: bool = False
    groq_zdr_enabled: bool = False
    deepgram_api_key: str = ''
    deepgram_phi_approved: bool = False
    deepgram_enabled: bool = False
    speech_provider: str = 'auto'
    model_extraction: str = 'openai/gpt-6-luna'
    model_documentation: str = 'openai/gpt-6.1-sol'
    model_vision: str = 'openai/gpt-6.1-sol'
    model_review: str = 'openai/gpt-6.1-sol'
    model_frontier: str = 'openai/gpt-6-astra'
    model_challenger: str = 'anthropic/claude-sonnet-5.5'
    provider_timeout_seconds: int = 60
    provider_execution_enabled: bool = False
    # Stuck-credit recovery (1.8.5-test10, development only; OFF by default). See STUCK-REQUEST-LEASE-PLAN.md.
    ai_leases_enabled: bool = False
    ai_lease_seconds: int = 120
    ai_lease_takeover_grace_seconds: int = 60
    ai_stale_release_quiet_seconds: int = 600
    max_audio_mb: int = 25
    max_image_mb: int = 12
    max_document_mb: int = 20
    max_audio_seconds: int = 3600
    max_text_chars: int = 40000
    stripe_secret_key: str = ''
    stripe_webhook_secret: str = ''
    stripe_price_essential: str = ''
    stripe_price_plus: str = ''
    stripe_price_pro: str = ''
    stripe_price_power: str = ''
    stripe_price_topup: str = ''
    stripe_live_mode: bool = False
    stripe_api_version: str = '2026-08-26.dahlia'
    billing_enabled: bool = False

    @field_validator('model_extraction','model_documentation','model_vision','model_review','model_frontier','model_challenger')
    @classmethod
    def valid_model(cls, v: str) -> str:
        if 'contributor' in v.lower():
            raise ValueError('Training/contributor routes are not permitted')
        return v

    def validate_runtime(self):
        if self.environment == 'production':
            errors=[]
            if len(self.app_secret)<32: errors.append('APP_SECRET must contain at least 32 characters')
            if not self.secure_cookies: errors.append('SECURE_COOKIES must be true')
            if not self.public_base_url.startswith('https://'): errors.append('PUBLIC_BASE_URL must use HTTPS')
            if '*' in self.trusted_hosts: errors.append('TRUSTED_HOSTS must be explicit')
            if self.test_provider_mode: errors.append('TEST_PROVIDER_MODE cannot run in production')
            if self.auto_create_schema: errors.append('Use migrations in production')
            if self.database_url.startswith('sqlite'): errors.append('Production requires durable PostgreSQL')
            if self.clinical_data_enabled:
                if self.openrouter_api_key and not self.openrouter_phi_approved: errors.append('OpenRouter privacy approval required')
                if self.cloudflare_api_token and not self.cloudflare_phi_approved: errors.append('Cloudflare privacy approval required')
                if self.groq_api_key and not (self.groq_phi_approved and self.groq_zdr_enabled): errors.append('Groq privacy and ZDR approval required')
                if self.deepgram_enabled and self.deepgram_api_key and not self.deepgram_phi_approved: errors.append('Deepgram privacy approval required')
            if errors: raise RuntimeError('; '.join(errors))


settings = Settings()
