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


def test_one_step_mode_writes_the_report_without_a_second_ai_check(gateway, monkeypatch):
    """quick_ai_check off: exactly one AI request writes the report; no check, no rewrite."""
    import asyncio
    calls = []

    async def fake_routed(task, content, *args, **kwargs):
        calls.append(task)
        return {'text': 'Small drainage. Cleaned with NS.', 'status': 'ready', 'ui_alerts': [], 'usage': {}}
    monkeypatch.setattr(providers, 'routed', fake_routed)
    monkeypatch.setattr(providers.settings, 'quick_ai_check', False, raising=False)
    result = asyncio.run(providers._checked_quick_text({'untrusted_sources': []}, 'quick_draft', {'template': 'nursing'}, user=gateway.user, encounter_id='e1'))
    assert calls == ['quick_draft']
    assert result['qa']['status'] == 'ai_check_off' and result['text'].startswith('Small drainage')


def test_owner_effort_cap_lowers_thinking_but_not_for_photos(gateway, monkeypatch):
    monkeypatch.setattr(providers.settings, 'max_reasoning_effort', 'low', raising=False)
    monkeypatch.setattr(providers, 'select_route', lambda task, content: {'effort_level': 'high', 'reason': 'test'})
    assert providers.configured_route('quick_draft', json.dumps({'template': 'nursing'}))['effort_level'] == 'low'
    assert providers.configured_route('assistant', '{}')['effort_level'] == 'low'
    assert providers.configured_route('image', '{}')['effort_level'] == 'high'


def test_addon_buttons_can_use_a_cheaper_model(gateway, monkeypatch):
    monkeypatch.setattr(providers.settings, 'model_addon', 'anthropic/claude-haiku-5.5', raising=False)
    gateway.config.model_documentation_simple = 'anthropic/claude-sonnet-5.5'
    assert providers.configured_route('improve_report', json.dumps({'template': 'nursing'}))['model'] == 'anthropic/claude-haiku-5.5'
    assert providers.configured_route('improve_assessment', '{}')['model'] == 'anthropic/claude-haiku-5.5'
    assert providers.configured_route('quick_draft', json.dumps({'template': 'nursing'}))['model'] == 'anthropic/claude-sonnet-5.5'


def test_owner_can_turn_the_check_box_off(gateway, monkeypatch):
    import asyncio

    async def fake_inner(*args, **kwargs):
        return {'text': 'Small drainage.', 'status': 'ready', 'ui_alerts': [{'field': 'x', 'message': 'Confirm product'}], 'usage': {}}
    monkeypatch.setattr(providers, '_checked_quick_text', fake_inner)
    monkeypatch.setattr(providers.settings, 'show_report_alerts', False, raising=False)
    result = asyncio.run(providers.checked_quick_text({}, 'quick_draft', {'template': 'nursing'}, user=gateway.user, encounter_id='e1'))
    assert result['ui_alerts'] == [] and result['text'] == 'Small drainage.'
    monkeypatch.setattr(providers.settings, 'show_report_alerts', True, raising=False)
    result = asyncio.run(providers.checked_quick_text({}, 'quick_draft', {'template': 'nursing'}, user=gateway.user, encounter_id='e1'))
    assert result['ui_alerts']


def test_foreign_script_speech_is_flagged_as_background():
    from server import providers
    assert providers.foreign_speech_share('Wound bed with granulation tissue') == 0
    flagged = providers.flag_background_speech({'text': 'مرحبا كيف حالك اليوم', 'warnings': []})
    assert flagged['background_suspected'] and flagged['warnings']
    ok = providers.flag_background_speech({'text': 'Packed with Hydrofera Blue', 'warnings': []})
    assert 'background_suspected' not in ok


@pytest.mark.parametrize('task', ['quick_draft', 'quick_qa', 'image', 'consult', 'review', 'extract', 'challenge', 'escalation'])
def test_one_model_for_every_step_when_owner_sets_it(gateway, task):
    gateway.config.ai_model_all = 'minimax/minimax-m3'
    route = providers.configured_route(task, json.dumps({'template': 'nursing'}))
    assert route['model'] == 'minimax/minimax-m3'


def test_one_model_setting_never_falls_back_to_another_developer(gateway):
    gateway.config.ai_model_all = 'minimax/minimax-m3'
    assert providers.photo_backup_model() is None


def test_unknown_single_model_is_refused(gateway):
    gateway.config.ai_model_all = 'unknown/model'
    with pytest.raises(Exception):
        providers.configured_route('quick_draft', json.dumps({'template': 'nursing'}))


def test_accuracy_check_model_can_differ_from_the_single_model_and_only_for_that_step(gateway):
    gateway.config.ai_model_all = 'minimax/minimax-m3'
    gateway.config.ai_model_qa = 'anthropic/claude-haiku-5.5'
    content = json.dumps({'template': 'nursing'})
    assert providers.configured_route('quick_qa', content)['model'] == 'anthropic/claude-haiku-5.5'
    assert providers.configured_route('quick_draft', content)['model'] == 'minimax/minimax-m3'
    gateway.config.ai_model_qa = ''
    assert providers.configured_route('quick_qa', content)['model'] == 'minimax/minimax-m3'
