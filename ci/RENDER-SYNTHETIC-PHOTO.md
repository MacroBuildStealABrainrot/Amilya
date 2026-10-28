# Positive and negative synthetic photo checks

The earlier geometric-shapes test proved that the photo upload, provider and
validation path could safely request a retake. It did not prove that a visible
wound-like image could return supported, unconfirmed observations. This test
adds that missing positive case without using any person's wound photograph.

The built-in ImageGen tool generated a completely fictional training image on
October 28, 2026. No user image, real patient photograph or reference image was
provided. The original is in the generated-image output for this conversation:
`generated_images/exec-a2d007a1-5b52-43c1-8361-4e0427baf893.png`.
For the app fixture, the unchanged production `image_payload` preparation
function encoded it as JPEG and omitted metadata. No visual retouching was used.

Project fixture: `backend/scripts/fixtures/synthetic-wound-image.jpg.b64`.
Decoded JPEG: 256,564 bytes, 1254 x 1254 pixels. SHA-256:
`6115d07dca6d07e7a268dc77641e821a28996fb26313fe96b988085c390d9d55`.
The decoder checks this hash before use. This file is synthetic training data,
not a clinical record, a real patient example or medical ground truth.

The private once-only `wound-http-v6` test uses the existing normal native HTTP
test harness and requires all of the following:

- Unrelated geometric shapes: retake required, zero accepted findings.
- Generated wound picture: sufficient quality, no retake, nonempty observations
  restricted to wound_tissue, wound_edges, periwound and exudate. No automatic
  clinician confirmation. The normal strict schema, percentage and evidence
  validation remain enabled.
- Fictional typed extraction and a genuinely checked report also pass.

The same dedicated temporary fixture database, privacy-required routing, US$1
total provider cap, existing expiry and separate durable marker apply. No prior
claim is reset or deleted. No real patient image, owner password, clinical-data
setting, production traffic or APK signing is involved. Passing means the real
API can return a valid assessment for this generated input. It does not establish
clinical accuracy, phone camera behavior or a successful Samsung assessment.

Exact generation prompt (built-in ImageGen, no CLI/API key):

> Use case: scientific-educational. Asset type: entirely artificial medical software test image, not a real patient. Generate one photorealistic fictional clinical training photograph showing only an adult lower leg and ankle with a small shallow open skin ulcer. Close-up, wound fills about a quarter of the frame, fully visible sharp focus, even natural clinical lighting, normal perspective, no identifying features. Show a visibly moist red wound bed with a small patch of pale yellow surface material and mildly pink surrounding skin, realistic skin texture, intact visible wound margins. No fresh bleeding, no exposed bone or tendon, no severe injury, no instruments, no dressing covering the wound, no face, no labels, no text, no logo, no watermark, no ruler. Square composition. This is synthetic fictional test data for testing a photo-upload validation workflow; it is not a diagnostic illustration or a real medical record.
