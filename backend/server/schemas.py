"""Clinical request contracts. Unknown data stays unknown; chart values are typed."""
from __future__ import annotations

from datetime import date
import math
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

Name = Literal['wound_measurement', 'wound_location', 'pain', 'exudate', 'odour', 'undermining',
               'tunnelling', 'periwound', 'wound_edges', 'wound_tissue', 'perfusion_assessment',
               'offloading_assessment', 'compression', 'treatment_performed', 'patient_education',
               'narrative', 'vascular_test', 'lab', 'culture', 'imaging', 'history', 'allergy',
               'medication', 'nutrition', 'mobility', 'infection_findings', 'fever', 'chills',
               'diabetes', 'renal_disease', 'edema', 'smoking', 'pressure_exposure', 'glucose_control']
Mode = Literal['auto', 'fast', 'advanced', 'deep']
Template = Literal['nursing', 'soap', 'sbar', 'handoff', 'referral', 'physician_update', 'referral_letter', 'custom']
ShortText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False)


class Measurement(StrictModel):
    length_cm: float = Field(gt=0, le=200)
    width_cm: float = Field(gt=0, le=200)
    depth_cm: float | None = Field(default=None, ge=0, le=100)

    @model_validator(mode='before')
    @classmethod
    def numbers_only(cls, value):
        if isinstance(value, dict):
            for key, number in value.items():
                if number is not None and (isinstance(number, bool) or not isinstance(number, (int, float)) or not math.isfinite(number)):
                    raise ValueError('Measurements require finite numeric centimeter values')
        return value


class Location(StrictModel):
    site: ShortText
    laterality: Literal['left', 'right', 'bilateral', 'midline', 'not_applicable'] | None = None


class Observation(StrictModel):
    text: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=8000)] | None = None
    present: bool | None = Field(default=None, strict=True)
    observed_on: date | None = None

    @model_validator(mode='after')
    def information_required(self):
        if self.text is None and self.present is None:
            raise ValueError('An explicit observation or presence value is required')
        return self


class PhotoTissue(StrictModel):
    """Photo interpretation is qualitative; percentages belong to clinician input."""
    text: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]


class Tissue(StrictModel):
    text: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)] | None = None
    granulation: float | None = Field(default=None, ge=0, le=100, strict=True)
    slough: float | None = Field(default=None, ge=0, le=100, strict=True)
    eschar: float | None = Field(default=None, ge=0, le=100, strict=True)
    epithelial: float | None = Field(default=None, ge=0, le=100, strict=True)

    @model_validator(mode='after')
    def validate_tissue(self):
        percentages = [self.granulation, self.slough, self.eschar, self.epithelial]
        if not self.text and not any(x is not None for x in percentages):
            raise ValueError('Describe visible tissue or supply tissue percentages')
        if sum(x or 0 for x in percentages) > 100.001:
            raise ValueError('Tissue percentages cannot total more than 100')
        return self


class Pain(Observation):
    score_0_10: float | None = Field(default=None, ge=0, le=10, strict=True)

    @model_validator(mode='after')
    def information_required(self):
        if self.text is None and self.present is None and self.score_0_10 is None:
            raise ValueError('Describe pain or provide an explicit score')
        return self


def validated_value(name: str, value: dict) -> dict:
    model = {'wound_measurement': Measurement, 'wound_location': Location, 'wound_tissue': Tissue,
             'pain': Pain}.get(name, Observation)
    return model.model_validate(value).model_dump(mode='json', exclude_none=True)


class Finding(StrictModel):
    name: Name
    value: dict
    source_quote: str | None = Field(default=None, max_length=8000)

    @model_validator(mode='after')
    def validate_value(self):
        self.value = validated_value(self.name, self.value)
        return self


class PatientCreate(StrictModel):
    display_name: ShortText
    external_ref: ShortText | None = None


class WoundCreate(StrictModel):
    label: ShortText
    location: ShortText | None = None
    laterality: Literal['left', 'right', 'bilateral', 'midline', 'not_applicable'] | None = None
    wound_type: ShortText | None = None


class EncounterCreate(StrictModel):
    patient_id: str
    wound_id: str | None = None
    title: ShortText | None = None


class EncounterPatch(StrictModel):
    expected_revision: int = Field(ge=1)
    draft_data: dict | None = None
    title: ShortText | None = None


class EvidenceCreate(StrictModel):
    kind: Literal['typed', 'transcript', 'manual', 'measurement', 'result']
    text: str | None = Field(default=None, max_length=40000)
    findings: list[Finding] = Field(default_factory=list, max_length=100)
    consent: bool | None = None
    wound_id: str | None = None

    @model_validator(mode='after')
    def validate_source(self):
        if not (self.text and self.text.strip()) and not self.findings:
            raise ValueError('Supply source text or findings')
        if self.kind in {'manual', 'measurement'} and not self.findings:
            raise ValueError('Manual evidence requires explicit typed findings')
        if self.kind not in {'manual', 'measurement'} and self.findings:
            raise ValueError('Only clinician-attested manual evidence may directly supply findings')
        if self.kind == 'measurement' and any(f.name != 'wound_measurement' for f in self.findings):
            raise ValueError('Measurement evidence accepts only wound measurements')
        return self


class FactReview(StrictModel):
    expected_revision: int = Field(ge=1)
    action: Literal['confirm', 'edit', 'reject']
    value: dict | None = None

    @model_validator(mode='after')
    def edit_requires_value(self):
        if self.action == 'edit' and self.value is None:
            raise ValueError('An edit requires a typed value')
        if self.action != 'edit' and self.value is not None:
            raise ValueError('Use edit when changing a value')
        return self


class ReviewRequest(StrictModel):
    mode: Mode = 'auto'
    use_ai: bool = False


class AssistantRequest(StrictModel):
    question: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]


class CustomTemplate(StrictModel):
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=120)]
    instructions: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]


class TemplateSelection(StrictModel):
    template: Template = 'nursing'
    custom_template: CustomTemplate | None = None

    @model_validator(mode='after')
    def custom_layout_required(self):
        if self.template == 'custom' and self.custom_template is None:
            raise ValueError('Provide the custom report name and layout instructions')
        if self.template != 'custom' and self.custom_template is not None:
            raise ValueError('Custom layout instructions require the custom report template')
        return self


class DraftRequest(TemplateSelection):
    mode: Mode = 'auto'
    use_ai: bool = False


class DocumentCreate(TemplateSelection):
    text: str = Field(min_length=1, max_length=60000)
    expected_revision: int = Field(ge=1)
    amends_id: str | None = None
    reason: str | None = Field(default=None, max_length=2000)

    @model_validator(mode='after')
    def meaningful_text_and_amendment(self):
        if not self.text.strip():
            raise ValueError('Document text cannot be blank')
        if self.amends_id and not (self.reason and self.reason.strip()):
            raise ValueError('An amendment requires a reason')
        if self.reason and not self.amends_id:
            raise ValueError('An amendment reason requires the signed parent document')
        return self


class SignDocument(StrictModel):
    content_hash: str = Field(pattern=r'^[a-f0-9]{64}$')
    acknowledge_review: Literal[True]
    expected_case_revision: int = Field(ge=1)

