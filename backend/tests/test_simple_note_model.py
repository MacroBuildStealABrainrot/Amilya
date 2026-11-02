"""Everyday nursing notes use the cheaper approved model; checks and doctor notes do not."""
import json

import pytest
from server import providers
from test_providers import gateway


@pytest.mark.parametrize('template,task,expected', [
    ('nursing', 'quick_draft', 'anthropic/claude-haiku-5.5'),
    ('sbar', 'quick_draft', 'anthropic/claude-haiku-5.5'),
    ('handoff', 'quick_refine', 'anthropic/claude-haiku-5.5'),
    ('soap', 'quick_draft', 'anthropic/claude-haiku-5.5'),
    ('referral_letter', 'quick_draft', 'anthropic/claude-haiku-5.5'),
    ('physician_update', 'quick_draft', 'anthropic/claude-haiku-5.5'),
    ('custom', 'quick_draft', 'openai/gpt-6.1-sol'),
    ('nursing', 'quick_qa', 'openai/gpt-6.1-sol'),
])
def test_note_type_picks_the_model(gateway, template, task, expected):
    gateway.config.model_documentation = 'openai/gpt-6.1-sol'
    gateway.config.model_documentation_simple = 'anthropic/claude-haiku-5.5'
    route = providers.configured_route(task, json.dumps({'template': template}))
    assert route['model'] == expected


def test_custom_layout_keeps_the_main_model(gateway):
    gateway.config.model_documentation = 'openai/gpt-6.1-sol'
    gateway.config.model_documentation_simple = 'anthropic/claude-haiku-5.5'
    content = json.dumps({'template': 'nursing', 'untrusted_layout_preferences': {'sections': ['A']}})
    assert providers.configured_route('quick_draft', content)['model'] == 'openai/gpt-6.1-sol'


def test_hard_looking_recording_still_uses_the_note_model_for_standard_notes(gateway, monkeypatch):
    """A long or noisy recording must not send a standard note to the most expensive model."""
    gateway.config.model_documentation = 'anthropic/claude-sonnet-5.5'
    gateway.config.model_documentation_simple = 'anthropic/claude-haiku-5.5'
    gateway.config.model_frontier = 'anthropic/claude-sonnet-5.5'
    monkeypatch.setattr(providers, 'select_route', lambda task, content: {'effort_level': 'high', 'reason': 'test'})
    content = json.dumps({'template': 'nursing'})
    assert providers.configured_route('quick_draft', content)['model'] == 'anthropic/claude-haiku-5.5'
    assert providers.configured_route('quick_qa', content)['model'] == 'anthropic/claude-sonnet-5.5'
