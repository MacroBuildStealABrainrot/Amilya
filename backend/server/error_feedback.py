"""Public failure descriptions: closed metadata only, never upstream bodies."""
from fastapi import HTTPException
MESSAGES = {
    'PROVIDER_QUOTA': 'The AI provider has insufficient funds or a spending limit. The administrator must check the provider account.',
    'PROVIDER_AUTH': 'The AI provider rejected authentication or account access. The administrator must check the API key and provider permissions.',
    'MODEL_UNAVAILABLE': 'No provider endpoint matches the configured model and privacy settings. The administrator must check routing.',
    'REQUEST_INCOMPATIBLE': 'The provider rejected the request format or model parameters. The integration needs correction.',
    'PROVIDER_TIMEOUT': 'The AI provider did not respond before the timeout. Check request status before retrying.',
    'INVALID_RESPONSE': 'The AI response failed structure or clinical-data validation. Its findings were not accepted.',
    'NETWORK_UNAVAILABLE': 'The server could not connect to the AI provider. Retry when the provider connection is restored.',
    'RATE_LIMITED': 'The AI provider rate-limited the request. Wait before retrying.',
    'PROVIDER_UNAVAILABLE': 'The provider returned an unsuccessful response. Check the request diagnostics before retrying.',
    'RESPONSE_PROCESSING_FAILED': 'The server could not process the provider response. Contact support with the request reference.',
}

# Exact application-owned messages, never arbitrary exception/provider text.
PREFLIGHT = {
    'Live AI is paused until the owner activates provider execution': ('AI_PAUSED', 'AI execution is paused in server settings. The owner must enable it.'),
    'AI is not configured; manual evidence and documentation are available': ('AI_NOT_CONFIGURED', 'The server has no configured AI API key. The owner must configure it.'),
    'This provider is awaiting clinical-data privacy approval': ('PROVIDER_APPROVAL_REQUIRED', 'This provider has not been approved for clinical-data processing.'),
    'Zero-retention routing must be enabled': ('PRIVACY_CONFIGURATION_REQUIRED', 'Required zero-retention routing is not enabled.'),
    'Secure application storage must be configured before AI processing': ('SECURE_STORAGE_REQUIRED', 'Secure server storage is not configured for AI processing.'),
    'Model configuration needs review': ('MODEL_CONFIGURATION_INVALID', 'The configured model is not in the reviewed model registry.'),
    'Model needs a reviewed price registry entry': ('MODEL_CONFIGURATION_INVALID', 'The model has no reviewed price registry entry.'),
    'This request contains instructions outside the clinical evidence workflow': ('SOURCE_SCOPE_REJECTED', 'The source was rejected by the clinical-workflow scope check.'),
}

def preflight_error(status, detail):
    code, _ = PREFLIGHT[detail]
    return HTTPException(status, detail, headers={'X-WCP-Error':code,'X-WCP-Retryable':'false'})

def extraction_warning(error):
    headers = error.headers or {}
    code = headers.get('X-WCP-Error')
    message = MESSAGES.get(code)
    if message is None:
        code, message = PREFLIGHT.get(str(error.detail), {
            402: ('APP_AI_ACCESS_REQUIRED', 'This app account lacks AI entitlement or app credits. Provider funding alone does not grant app access.'),
            422: ('EXTRACTION_INPUT_REJECTED', 'The extraction input was rejected. Review the source and request diagnostics.'),
            429: ('APP_RATE_LIMITED', 'The application request limit was reached. Wait before retrying.'),
            504: ('EXTRACTION_TIMEOUT', 'Extraction timed out. Check request status before retrying.'),
        }.get(error.status_code, ('EXTRACTION_UNCLASSIFIED', 'Extraction failed without a specific cause code. Contact support with the request reference.')))
    upstream = headers.get('X-WCP-Upstream-Status', '')
    technical = code + (f'; provider HTTP {upstream}' if upstream.isascii() and upstream.isdigit() and 100 <= int(upstream) <= 599 else '')
    return f'AI extraction failed: {message} [{technical}] The source was preserved; only explicit rule-based suggestions are shown.'
