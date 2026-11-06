"""Client-reported image capture checks; never clinical facts or image diagnoses."""
from __future__ import annotations

import json
import re
from typing import Annotated, Literal

from fastapi import HTTPException
from pydantic import Field, StringConstraints, ValidationError, model_validator

from server.schemas import StrictModel


class PhotoReview(StrictModel):
    algorithm_version: Literal['capture-quality-v1', 'manual-review-v1']
    focus_variance: float | None = Field(default=None, ge=0, le=10_000_000, strict=True)
    mean_luminance: float | None = Field(default=None, ge=0, le=255, strict=True)
    clipped_fraction: float | None = Field(default=None, ge=0, le=1, strict=True)
    obstruction: Literal['clear', 'partial', 'uncertain']
    limitation_note: Annotated[str, StringConstraints(strip_whitespace=True, max_length=500)] = ''
    rotation_degrees: Literal[0, 90, 180, 270] = 0
    crop_percent: float = Field(default=0, ge=0, le=30, strict=True)

    @model_validator(mode='before')
    @classmethod
    def rotation_is_integer(cls, value):
        if isinstance(value, dict) and 'rotation_degrees' in value and type(value['rotation_degrees']) is not int:
            raise ValueError('Rotation must be an explicit integer angle')
        return value

    @model_validator(mode='after')
    def algorithm_controls_measurement_presence(self):
        metrics = (self.focus_variance, self.mean_luminance, self.clipped_fraction)
        if self.algorithm_version == 'capture-quality-v1' and any(value is None for value in metrics):
            raise ValueError('Automatic capture review requires measured quality values')
        if self.algorithm_version == 'manual-review-v1' and any(value is not None for value in metrics):
            raise ValueError('Manual review must not fabricate automatic quality measurements')
        return self


def parse_photo_review(raw: str | None, kind: str):
    if raw is None:
        return None
    if kind != 'photo':
        raise HTTPException(422, 'Photo review metadata applies only to a photo upload')
    if len(raw.encode('utf-8')) > 4000:
        raise HTTPException(413, 'Photo review metadata is too large')
    try:
        review = PhotoReview.model_validate(json.loads(raw)).model_dump(mode='json')
    except (ValueError, TypeError, ValidationError) as error:
        raise HTTPException(422, 'Photo review metadata has an invalid structure') from error
    return {**review, 'client_reported': True, 'clinical_assessment': False}


# ---------- Clinician-recorded wound details sent with a photo (owner request 2026-11-05) ----------
WOUND_BED = Literal['granulation', 'slough', 'eschar', 'epithelial', 'hypergranulation', 'necrotic', 'bone', 'tendon']
WOUND_EDGES = Literal['attached', 'unattached', 'rolled', 'undermining', 'tunnelling']
INFECTION_SIGNS = Literal['none', 'warmth', 'increased_pain', 'odour', 'spreading_erythema', 'friable_tissue', 'increased_drainage', 'wound_breakdown', 'delayed_healing']
SURROUNDING_SKIN = Literal['intact', 'erythema', 'maceration', 'edema', 'induration', 'dry_flaky', 'callus', 'fragile']
DRAINAGE_TYPE = Literal['none', 'serous', 'serosanguineous', 'sanguineous', 'purulent']
DRAINAGE_AMOUNT = Literal['none', 'scant', 'small', 'moderate', 'large']
Centimetres = Annotated[float, Field(gt=0, le=100, strict=False)]


class ClinicianFindings(StrictModel):
    """Optional quick choices the clinician picked. Absent or empty means unknown; nothing is ever assumed or zero."""
    wound_bed: list[WOUND_BED] = Field(default_factory=list, max_length=8)
    wound_edges: list[WOUND_EDGES] = Field(default_factory=list, max_length=5)
    surrounding_skin: list[SURROUNDING_SKIN] = Field(default_factory=list, max_length=8)
    drainage_type: DRAINAGE_TYPE | None = None
    drainage_amount: DRAINAGE_AMOUNT | None = None
    length_cm: Centimetres | None = None
    width_cm: Centimetres | None = None
    depth_cm: Centimetres | None = None
    infection_signs: list[INFECTION_SIGNS] = Field(default_factory=list, max_length=9)
    # Optional healing progress: an earlier length x width (and how many weeks ago) to compare with today's size.
    pain_score: Annotated[int, Field(ge=0, le=10, strict=False)] | None = None
    previous_length_cm: Centimetres | None = None
    previous_width_cm: Centimetres | None = None
    previous_weeks_ago: Annotated[int, Field(ge=1, le=104, strict=False)] | None = None

    @model_validator(mode='after')
    def _none_stands_alone(self):
        if 'none' in self.infection_signs and len(self.infection_signs) > 1:
            raise ValueError('No local signs of infection cannot be combined with a sign')
        earlier = (self.previous_length_cm, self.previous_width_cm)
        if any(v is not None for v in earlier) or self.previous_weeks_ago is not None:
            if None in earlier or self.length_cm is None or self.width_cm is None:
                raise ValueError('Healing progress needs today\'s length and width and the earlier length and width')
        if {'attached', 'unattached'} <= set(self.wound_edges):
            raise ValueError('Edges cannot be both attached and unattached')
        return self

    def area_change_percent(self) -> int | None:
        """Percent area reduction (length x width) from the earlier measurement: positive is smaller, negative is larger.
        Calculated here, never by the AI."""
        if None in (self.length_cm, self.width_cm, self.previous_length_cm, self.previous_width_cm):
            return None
        before, now = self.previous_length_cm * self.previous_width_cm, self.length_cm * self.width_cm
        return round((before - now) / before * 100)

    def healing_progress(self) -> str:
        change = self.area_change_percent()
        if change is None:
            return ''
        before = f'{self.previous_length_cm:g} x {self.previous_width_cm:g} cm ({round(self.previous_length_cm * self.previous_width_cm, 2):g} cm2)'
        when = f', {self.previous_weeks_ago} week{"s" if self.previous_weeks_ago != 1 else ""} ago' if self.previous_weeks_ago else ''
        now = f'{round(self.length_cm * self.width_cm, 2):g} cm2'
        if change > 0:
            trend = f'{change}% area reduction'
        elif change < 0:
            trend = f'area increased by {-change}%'
        else:
            trend = 'no change in area'
        return f'healing progress: {trend} (area {now}, earlier {before}{when})'

    def is_empty(self) -> bool:
        return not any([self.wound_bed, self.wound_edges, self.surrounding_skin, self.drainage_type, self.drainage_amount, self.infection_signs, self.pain_score is not None,
                        self.length_cm, self.width_cm, self.depth_cm])

    def as_text(self) -> str:
        """Plain clinical wording of exactly what was entered; unknown items are simply left out."""
        parts = []
        if self.wound_bed: parts.append('wound bed: ' + ', '.join(self.wound_bed))
        if self.wound_edges:
            names = {'rolled': 'rolled (epibole)'}
            parts.append('wound edges: ' + ', '.join(names.get(item, item) for item in self.wound_edges))
        if self.surrounding_skin: parts.append('periwound: ' + ', '.join(item.replace('_', '/') for item in self.surrounding_skin))
        drainage = ' '.join(item for item in (self.drainage_amount, self.drainage_type) if item)
        if drainage: parts.append('drainage: ' + drainage)
        if self.infection_signs == ['none']: parts.append('no local signs of infection')
        elif self.infection_signs: parts.append('local signs of infection: ' + ', '.join(item.replace('_', ' ') for item in self.infection_signs))
        if self.pain_score is not None: parts.append(f'pain {self.pain_score}/10')
        size = [f'{label} {value:g} cm' for label, value in (('length', self.length_cm), ('width', self.width_cm), ('depth', self.depth_cm)) if value is not None]
        if size: parts.append('measured ' + ', '.join(size))
        progress = self.healing_progress()
        if progress: parts.append(progress)
        return '; '.join(parts)


BED_DIFFERENCE = 'Please confirm wound-bed tissue. Your selection and the image assessment may differ.'
DRAINAGE_DIFFERENCE = 'Please confirm drainage. Your selection and the image assessment may differ.'
PERIWOUND_DIFFERENCE = 'Please confirm periwound. Your selection and the image assessment may differ.'
_PHOTO_TISSUE = {'granulation': r'granulat', 'slough': r'slough', 'eschar': r'eschar', 'necrotic': r'necro',
                 'epithelial': r'epitheli', 'hypergranulation': r'hypergranulat|overgranulat'}
_VISIBLE_FIELDS = {'wound_tissue', 'wound_edges', 'periwound', 'exudate', 'visible_finding'}


def selection_differences(findings: 'ClinicianFindings | None', facts) -> list[str]:
    """Free, fixed check (no AI): where the clinician's picks and the photo's own words may disagree. Never decides who
    is right and never changes either; it only asks the clinician to confirm. Tissue types can coexist, so a photo
    tissue is a possible difference only when the clinician picked wound-bed tissue and did not pick that one."""
    if findings is None:
        return []
    seen = ' '.join(str((f.get('value') or {}).get('text', '')) for f in facts or []
                    if isinstance(f, dict) and f.get('name') in _VISIBLE_FIELDS)
    if not seen.strip():
        return []
    out = []
    if findings.wound_bed:
        photo_tissue = set()
        for name, pattern in _PHOTO_TISSUE.items():
            for match in re.finditer(pattern, seen, re.I):
                before = seen[max(0, match.start() - 25):match.start()].lower()
                if not re.search(r'\b(no|without|absent|nil)\b[^.;]*$', before):
                    photo_tissue.add(name)
        if 'hypergranulation' in photo_tissue:
            photo_tissue.discard('granulation')
        if photo_tissue - set(findings.wound_bed):
            out.append(BED_DIFFERENCE)
    if 'intact' in findings.surrounding_skin:
        skin = ' '.join(str((f.get('value') or {}).get('text', '')) for f in facts or []
                        if isinstance(f, dict) and f.get('name') in {'periwound', 'wound_edges', 'visible_finding'})
        if re.search(r'(?<!no )(?<!without )\b(erythem\w*|macerat\w*|o?edema\w*|swell\w*|indurat\w*|redness)', skin, re.I):
            out.append(PERIWOUND_DIFFERENCE)
    if findings.drainage_type or findings.drainage_amount:
        picked_none = findings.drainage_type == 'none' or findings.drainage_amount == 'none'
        photo_none = re.search(r'\bno\b[^.;]{0,25}\b(drainage|exudate|discharge)\b|\bdry wound (bed|surface)\b', seen, re.I) is not None
        photo_some = re.search(r'\b(purulent|serous|serosanguin\w*|sanguineous|moist|wet|exudat\w*|drainage)\b', seen, re.I) is not None and not photo_none
        if (photo_none and not picked_none) or (picked_none and photo_some):
            out.append(DRAINAGE_DIFFERENCE)
    return out


def parse_clinician_findings(raw: str | None, kind: str):
    if raw in (None, ''):
        return None
    if kind != 'photo':
        raise HTTPException(422, 'Wound details are accepted only with a photo')
    if len(raw) > 2000:
        raise HTTPException(422, 'Wound details are too long')
    try:
        findings = ClinicianFindings.model_validate(json.loads(raw))
    except (ValueError, ValidationError):
        raise HTTPException(422, 'Wound details are not in the expected format')
    return None if findings.is_empty() else findings
