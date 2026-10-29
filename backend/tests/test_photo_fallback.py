"""Synthetic photo fallback: one second-developer attempt after an upstream refusal or outage."""
import logging

import pytest
from fastapi import HTTPException

from test_providers import gateway, completion
from test_photo_recovery import assess


def refused(error_type, provider='Azure', flagged=False):
    metadata = {'error_type': error_type, 'provider_name': provider}
    if flagged:
        metadata['flagged_input'] = 'SYNTHETIC_PRIVATE_TEXT'
    return (200, {'error': {'code': 502, 'message': 'SYNTHETIC_PRIVATE_MESSAGE', 'metadata': metadata}})


def wound_result():
    return completion({'facts': [{'name': 'wound_edges', 'value': {'text': 'Edges attached.'}, 'quote': ''}],
                       'warnings': [], 'quality': {'status': 'sufficient', 'reason': 'none'}})


@pytest.mark.parametrize('error_type', ['provider_unavailable', 'content_policy_violation', 'refusal'])
def test_refused_photo_gets_one_attempt_from_the_other_developer(gateway, error_type):
    gateway.config.model_vision = 'openai/gpt-6.1-sol'
    gateway.config.model_challenger = 'anthropic/claude-sonnet-5.5'
    gateway.http.responses = [refused(error_type), wound_result()]
    result = assess(gateway)
    assert [f['value']['text'] for f in result['facts']] == ['Edges attached.']
    models = [call['json']['model'] for call in gateway.http.calls]
    assert models == ['openai/gpt-6.1-sol', 'anthropic/claude-sonnet-5.5']


def test_backup_failure_reports_the_original_error_and_stops(gateway):
    gateway.config.model_vision = 'openai/gpt-6.1-sol'
    gateway.config.model_challenger = 'anthropic/claude-sonnet-5.5'
    gateway.http.responses = [refused('provider_unavailable'), refused('provider_unavailable', 'Anthropic')]
    with pytest.raises(HTTPException) as caught:
        assess(gateway)
    assert caught.value.headers['X-WCP-Provider-Error'] == 'provider_unavailable'
    assert len(gateway.http.calls) == 2


@pytest.mark.parametrize('status', [401, 402])
def test_no_backup_for_account_or_budget_problems(gateway, status):
    gateway.config.model_challenger = 'anthropic/claude-sonnet-5.5'
    gateway.http.responses = [(status, {'error': {'code': status}})]
    with pytest.raises(HTTPException):
        assess(gateway)
    assert len(gateway.http.calls) == 1


def test_no_backup_when_both_settings_use_the_same_developer(gateway):
    gateway.config.model_vision = 'openai/gpt-6.1-sol'
    gateway.config.model_challenger = 'openai/gpt-6-astra'
    gateway.http.responses = [refused('provider_unavailable')]
    with pytest.raises(HTTPException):
        assess(gateway)
    assert len(gateway.http.calls) == 1


def test_refusal_log_has_safe_category_only(gateway, caplog):
    gateway.config.model_challenger = 'anthropic/claude-sonnet-5.5'
    gateway.http.responses = [refused('content_policy_violation', flagged=True), wound_result()]
    with caplog.at_level(logging.WARNING, logger='wcp.providers'):
        assess(gateway)
    text = caplog.text
    assert 'category=content_policy_violation' in text
    assert 'upstream=Azure' in text and 'safety_flagged=True' in text
    assert 'SYNTHETIC_PRIVATE' not in text
