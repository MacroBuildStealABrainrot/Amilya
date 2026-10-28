"""Static regression checks for PHI-free exception classification."""
from pathlib import Path

def test_boundary_logs_exception_class_without_payload():
    source = (Path(__file__).resolve().parents[1] / "server" / "main.py").read_text()
    assert "error_class = type(exc).__name__" in source
    assert "error_class=%s" in source
    assert "str(exc)" not in source.split("logger.error('request_failed")[1].split("response=JSONResponse")[0]
