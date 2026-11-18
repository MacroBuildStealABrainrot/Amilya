"""Internal report outcome, distinct from a temporary HTTP/provider failure."""
from fastapi import HTTPException


class TerminalReportError(HTTPException):
    """No remaining attempt can finish this report's unchanged request.

    A stage can raise this marker only after a durable terminal outcome. The
    outer report wrapper returns app credits before exposing the legacy ended
    signal. This makes no assertion about the AI provider's bill or refund.
    """
