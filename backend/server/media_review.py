"""Client-reported image capture checks; never clinical facts or image diagnoses."""
from __future__ import annotations

import json
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
