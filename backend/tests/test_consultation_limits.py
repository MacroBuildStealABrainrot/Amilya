"""The phone checks the Ask Consultant conversation against these limits BEFORE sending, and never shortens or drops it.
These tests pin the server's real limits, so the phone's copy (ConsultationLimits.kt) can be checked against them."""
import pytest
from pydantic import ValidationError

from server.quick_workflows import QuickReport


def report(turns):
    return QuickReport(template='nursing', consultation=[{'role': 'user' if i % 2 == 0 else 'assistant', 'content': text} for i, text in enumerate(turns)])


def test_forty_messages_of_one_thousand_letters_are_accepted():
    assert len(report(['x' * 1000] * 40).consultation) == 40


def test_forty_messages_of_eleven_hundred_letters_are_refused_as_over_40000_in_all():
    with pytest.raises(ValidationError, match='40,000'):
        report(['x' * 1100] * 40)


def test_forty_one_messages_are_refused():
    with pytest.raises(ValidationError):
        report(['x'] * 41)


def test_one_message_over_ten_thousand_letters_is_refused():
    with pytest.raises(ValidationError):
        report(['y' * 10001])
    assert report(['y' * 10000]).consultation[0].content == 'y' * 10000


def test_spaces_around_a_message_are_trimmed_before_counting():
    assert len(report(['  ' + 'x' * 1000 + '  '] * 40).consultation) == 40
