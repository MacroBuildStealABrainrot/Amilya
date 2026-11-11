import json

import pytest
from fastapi import HTTPException

from server.media_review import parse_clinician_findings


@pytest.mark.parametrize('value,words', [('positive', 'probe to bone: positive'), ('negative', 'probe to bone: negative'),
                                         ('not_done', 'probe to bone: not done')])
def test_probe_to_bone_is_accepted_and_reported(value, words):
    found = parse_clinician_findings(json.dumps({'probe_to_bone': value}), 'photo')
    assert found is not None
    assert words in found.as_text()


def test_probe_to_bone_alone_counts_as_an_entry():
    assert parse_clinician_findings(json.dumps({'probe_to_bone': 'negative'}), 'photo') is not None


def test_unknown_probe_to_bone_value_is_refused():
    with pytest.raises(HTTPException):
        parse_clinician_findings(json.dumps({'probe_to_bone': 'maybe'}), 'photo')
