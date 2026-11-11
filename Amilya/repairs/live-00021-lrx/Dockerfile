FROM us-central1-docker.pkg.dev/woundcopilot-20260905/wcp/app@sha256:1b05b36af5db9f1a53426101faef035d6940f6dabc6897301e4dd7c67fee39e4
COPY providers.py /app/server/providers.py
COPY quick_workflows.py /app/server/quick_workflows.py
COPY 40_wound_image_assessment.system.md /app/server/prompts/40_wound_image_assessment.system.md
COPY 81_quick_documentation.system.md /app/server/prompts/81_quick_documentation.system.md
COPY 86_quick_documentation_qa.system.md /app/server/prompts/86_quick_documentation_qa.system.md
COPY 91_wound_consultant.system.md /app/server/prompts/91_wound_consultant.system.md
