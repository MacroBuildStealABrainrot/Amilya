"""Everyday nursing notes use the cheaper approved model; checks and doctor notes do not."""
import json

import pytest
from server import providers
from test_providers import gateway


@pytest.mark.parametrize('template,task,expected', [
    ('nursing', 'quick_draft', 'anthropic/claude-haiku-5.5'),
    ('sbar', 'quick_draft', 'anthropic/claude-haiku-5.5'),
    ('handoff', 'quick_refine', 'anthropic/claude-haiku-5.5'),
    ('soap', 'quick_draft', 'openai/gpt-6.1-sol'),
    ('referral_letter', 'quick_draft', 'openai/gpt-6.1-sol'),
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
