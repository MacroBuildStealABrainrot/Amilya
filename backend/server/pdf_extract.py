"""Isolated, time- and memory-bounded PDF text worker. No network or active content."""
import sys

def main():
    if sys.platform != 'win32':
        import resource
        resource.setrlimit(resource.RLIMIT_AS, (384 * 1024**2, 384 * 1024**2))
        resource.setrlimit(resource.RLIMIT_CPU, (10, 10))
        resource.setrlimit(resource.RLIMIT_FSIZE, (1024**2, 1024**2))
    from pypdf import PdfReader
    reader = PdfReader(sys.argv[1], strict=True)
    if reader.is_encrypted or len(reader.pages) > 25: raise ValueError('PDF limits')
    parts = []
    for page in reader.pages:
        contents = page.get_contents()
        if contents is not None and len(contents.get_data()) > 4 * 1024**2: raise ValueError('Page content limit')
        parts.append(page.extract_text() or '')
        if sum(len(x) for x in parts) > 40000: raise ValueError('Text limit')
    sys.stdout.buffer.write('\n\n'.join(parts).encode('utf-8'))

if __name__ == '__main__':
    try: main()
    except Exception: raise SystemExit(2)
