import pytest
from backup_capacity import check


def test_allows_final_object_below_both_caps():
    assert check({'items': [{'size': '10'}] * 29}, 5000) == {
        'existing_objects': 29, 'existing_bytes': 290, 'projected_bytes': 5290}


@pytest.mark.parametrize('inventory,size', [
    ({'items': [{'size': '1'}] * 30}, 1),
    ({'items': [{'size': '99999999'}]}, 1),
    ({'items': [], 'nextPageToken': 'unseen-objects'}, 1),
    ({'items': [{'size': '-1'}]}, 1),
    ({'items': [{}]}, 1),
])
def test_caps_fail_closed(inventory, size):
    with pytest.raises((ValueError, KeyError)):
        check(inventory, size)
